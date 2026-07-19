package com.hjh_database.qixiazhen.farming.data

import com.hjh_database.Hjh_database
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** YML-backed storage for placed farm plots and controller bells. */
class FarmLocationStore(private val plugin: Hjh_database) {
    private data class StoredLocation(
        val key: FarmBlockKey,
        val worldName: String,
        val createdBy: UUID?,
        val createdAt: Long
    )

    private val file = File(plugin.dataFolder, "farming/farm_locations.yml")
    private val plots = linkedMapOf<Long, StoredLocation>()
    private val controllers = linkedMapOf<Long, StoredLocation>()
    private var nextPlotId = 1L
    private var nextControllerId = 1L

    @Synchronized
    fun loadAndMigrate(repository: FarmingRepository): Pair<List<FarmPlot>, List<FarmController>> {
        loadYaml()
        if (repository.hasLegacyLocationTables()) {
            val legacyPlots = repository.loadLegacyPlots()
            val legacyControllers = repository.loadLegacyControllers()
            mergeLegacyPlots(legacyPlots)
            mergeLegacyControllers(legacyControllers)
            saveYaml()
            repository.removeLegacyLocationTables()
            plugin.logger.info("灵田设施位置已从数据库迁移至 ${file.absolutePath}，旧位置表已删除。")
        } else if (!file.exists()) {
            saveYaml()
        }
        return snapshots()
    }

    @Synchronized
    fun createPlot(key: FarmBlockKey, worldName: String, creator: UUID): FarmPlot? {
        if (plots.values.any { it.key == key }) return null
        val id = nextPlotId++
        plots[id] = StoredLocation(key, worldName, creator, System.currentTimeMillis())
        saveYaml()
        return FarmPlot(id, key, worldName)
    }

    @Synchronized
    fun createController(key: FarmBlockKey, worldName: String, creator: UUID): FarmController? {
        if (controllers.values.any { it.key == key }) return null
        val id = nextControllerId++
        controllers[id] = StoredLocation(key, worldName, creator, System.currentTimeMillis())
        saveYaml()
        return FarmController(id, key, worldName)
    }

    @Synchronized
    fun deletePlot(id: Long): Boolean {
        if (plots.remove(id) == null) return false
        saveYaml()
        return true
    }

    @Synchronized
    fun deleteController(id: Long): Boolean {
        if (controllers.remove(id) == null) return false
        saveYaml()
        return true
    }

    private fun loadYaml() {
        plots.clear()
        controllers.clear()
        if (!file.exists()) {
            nextPlotId = 1L
            nextControllerId = 1L
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        loadSection(yaml, "plots", plots)
        loadSection(yaml, "controllers", controllers)
        nextPlotId = yaml.getLong("next_plot_id", 1L).coerceAtLeast((plots.keys.maxOrNull() ?: 0L) + 1L)
        nextControllerId = yaml.getLong("next_controller_id", 1L).coerceAtLeast((controllers.keys.maxOrNull() ?: 0L) + 1L)
    }

    private fun loadSection(yaml: YamlConfiguration, path: String, target: MutableMap<Long, StoredLocation>) {
        val root = yaml.getConfigurationSection(path) ?: return
        for (idText in root.getKeys(false)) {
            val id = idText.toLongOrNull() ?: continue
            val section = root.getConfigurationSection(idText) ?: continue
            try {
                val worldId = UUID.fromString(section.getString("world_uuid").orEmpty())
                val worldName = section.getString("world_name").orEmpty()
                target[id] = StoredLocation(
                    FarmBlockKey(worldId, section.getInt("x"), section.getInt("y"), section.getInt("z")),
                    worldName,
                    section.getString("created_by")?.takeIf(String::isNotBlank)?.let(UUID::fromString),
                    section.getLong("created_at", 0L)
                )
            } catch (ex: IllegalArgumentException) {
                plugin.logger.warning("灵田位置配置 $path.$idText 无效，已跳过：${ex.message}")
            }
        }
    }

    private fun mergeLegacyPlots(legacy: List<FarmPlot>) {
        for (plot in legacy) {
            val existing = plots[plot.id]
            if (existing != null && existing.key != plot.key) {
                error("灵田迁移失败：plot_id ${plot.id} 在 YML 与数据库中指向不同位置")
            }
            val sameLocation = plots.entries.firstOrNull { it.value.key == plot.key }
            if (sameLocation != null && sameLocation.key != plot.id) {
                error("灵田迁移失败：位置 ${plot.key} 在 YML 与数据库中使用了不同 ID")
            }
            plots.putIfAbsent(plot.id, StoredLocation(plot.key, plot.worldName, null, 0L))
        }
        nextPlotId = nextPlotId.coerceAtLeast((plots.keys.maxOrNull() ?: 0L) + 1L)
    }

    private fun mergeLegacyControllers(legacy: List<FarmController>) {
        for (controller in legacy) {
            val existing = controllers[controller.id]
            if (existing != null && existing.key != controller.key) {
                error("灵田迁移失败：controller_id ${controller.id} 在 YML 与数据库中指向不同位置")
            }
            val sameLocation = controllers.entries.firstOrNull { it.value.key == controller.key }
            if (sameLocation != null && sameLocation.key != controller.id) {
                error("灵田迁移失败：观测钟位置 ${controller.key} 在 YML 与数据库中使用了不同 ID")
            }
            controllers.putIfAbsent(controller.id, StoredLocation(controller.key, controller.worldName, null, 0L))
        }
        nextControllerId = nextControllerId.coerceAtLeast((controllers.keys.maxOrNull() ?: 0L) + 1L)
    }

    private fun saveYaml() {
        file.parentFile?.mkdirs()
        val yaml = YamlConfiguration()
        yaml.set("next_plot_id", nextPlotId)
        yaml.set("next_controller_id", nextControllerId)
        writeSection(yaml, "plots", plots)
        writeSection(yaml, "controllers", controllers)

        val temporary = File(file.parentFile, "${file.name}.tmp")
        yaml.save(temporary)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeSection(yaml: YamlConfiguration, path: String, values: Map<Long, StoredLocation>) {
        for ((id, stored) in values) {
            val base = "$path.$id"
            yaml.set("$base.world_uuid", stored.key.worldId.toString())
            yaml.set("$base.world_name", stored.worldName)
            yaml.set("$base.x", stored.key.x)
            yaml.set("$base.y", stored.key.y)
            yaml.set("$base.z", stored.key.z)
            yaml.set("$base.created_by", stored.createdBy?.toString())
            yaml.set("$base.created_at", stored.createdAt)
        }
    }

    private fun snapshots(): Pair<List<FarmPlot>, List<FarmController>> =
        plots.map { (id, stored) -> FarmPlot(id, stored.key, stored.worldName) } to
            controllers.map { (id, stored) -> FarmController(id, stored.key, stored.worldName) }
}
