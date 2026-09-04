package com.hjh_database.qixiazhen.farming.config

import com.hjh_database.Hjh_database
import com.hjh_database.qixiazhen.farming.data.FarmCropDef
import com.hjh_database.qixiazhen.farming.data.FarmEventPresentation
import com.hjh_database.qixiazhen.farming.data.FarmSeedDef
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class FarmingConfig(private val plugin: Hjh_database) {
    private val seeds = linkedMapOf<String, FarmSeedDef>()
    private val crops = linkedMapOf<String, FarmCropDef>()
    private val events = linkedMapOf<String, FarmEventPresentation>()
    private lateinit var config: YamlConfiguration

    var displayDurationSeconds: Int = 10
        private set
    var displayUpdateTicks: Long = 20L
        private set
    var cropDisplayDurationSeconds: Int = 300
        private set
    var controllerRadius: Double = 16.0
        private set
    var textHeight: Double = 1.65
        private set
    var cropHeight: Double = 1.02
        private set
    var eventCheckIntervalSeconds: Int = 300
        private set

    fun load() {
        saveIfMissing("farming/farm_config.yml")
        saveIfMissing("farming/farm_events.yml")

        config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "farming/farm_config.yml"))
        displayDurationSeconds = config.getInt("display.duration_seconds", 10).coerceAtLeast(1)
        displayUpdateTicks = config.getLong("display.update_interval_ticks", 20L).coerceAtLeast(20L)
        cropDisplayDurationSeconds = config.getInt("display.crop_duration_seconds", 300).coerceAtLeast(10)
        controllerRadius = config.getDouble("display.controller_radius", 16.0).coerceAtLeast(1.0)
        textHeight = config.getDouble("display.text_height", 1.65)
        cropHeight = config.getDouble("display.crop_height", 1.02)
        eventCheckIntervalSeconds = config.getInt("events.check_interval_seconds", 300).coerceAtLeast(10)

        loadSeeds(File(plugin.dataFolder, "resources/items/farm_seeds.yml"))
        crops.clear()
        loadCrops(File(plugin.dataFolder, "resources/items/farm_crops.yml"))
        loadCrops(File(plugin.dataFolder, "resources/items/farm_food.yml"))
        loadCrops(File(plugin.dataFolder, "resources/items/alchemy.yml"))
        loadEvents(File(plugin.dataFolder, "farming/farm_events.yml"))
        validateLinks()
        plugin.logger.info("灵田配置已加载：${seeds.size} 种种子、${crops.size} 种作物、${events.size} 种天灾。")
    }

    private fun saveIfMissing(path: String) {
        val target = File(plugin.dataFolder, path)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            plugin.saveResource(path, false)
        }
    }

    private fun loadSeeds(file: File) {
        seeds.clear()
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (resourceId in yaml.getKeys(false)) {
            val section = yaml.getConfigurationSection(resourceId) ?: continue
            val cropId = section.getString("crop_id")?.trim().orEmpty()
            if (cropId.isEmpty()) {
                plugin.logger.warning("灵田种子 $resourceId 缺少 crop_id，已跳过。")
                continue
            }
            val amount = section.getIntegerList("harvest_amount")
            val fixed = section.getInt("harvest_amount", 1)
            val min = (amount.getOrNull(0) ?: fixed).coerceAtLeast(1)
            val max = (amount.getOrNull(1) ?: min).coerceAtLeast(min)
            seeds[resourceId] = FarmSeedDef(
                resourceId,
                cropId,
                section.getLong("mature_seconds", 60L).coerceAtLeast(1L),
                min,
                max
            )
        }
    }

    private fun loadCrops(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (resourceId in yaml.getKeys(false)) {
            val section = yaml.getConfigurationSection(resourceId) ?: continue
            val cropId = section.getString("crop_id")?.trim().orEmpty()
            if (cropId.isEmpty()) continue
            val materialName = section.getString("material", "WHEAT") ?: "WHEAT"
            val material = Material.matchMaterial(materialName)
            if (material == null) {
                plugin.logger.warning("灵田作物 $resourceId 的 material 无效：$materialName")
                continue
            }
            crops[cropId] = FarmCropDef(
                cropId,
                resourceId,
                color(section.getString("name", cropId) ?: cropId),
                material
            )
        }
    }

    private fun loadEvents(file: File) {
        events.clear()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("events") ?: return
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            events[id] = FarmEventPresentation(
                id,
                color(section.getString("name", id) ?: id),
                section.getStringList("lore").map(::color),
                color(section.getString("message", "&c灵田遭遇灾害。") ?: "&c灵田遭遇灾害。")
            )
        }
    }

    private fun validateLinks() {
        seeds.values.filter { it.cropId !in crops }.forEach {
            plugin.logger.warning("灵田种子 ${it.resourceId} 指向不存在的作物 ${it.cropId}。")
        }
    }

    fun seed(resourceId: String?): FarmSeedDef? = resourceId?.let(seeds::get)
    fun crop(cropId: String?): FarmCropDef? = cropId?.let(crops::get)
    fun event(id: String): FarmEventPresentation? = events[id]
    fun allSeedIds(): Set<String> = seeds.keys
    fun allCropResourceIds(): Set<String> = crops.values.mapTo(linkedSetOf()) { it.resourceId }
    fun message(key: String): String = color(config.getString("messages.$key", "&c消息未配置：$key") ?: "&c消息未配置：$key")
    private fun color(value: String): String = ChatColor.translateAlternateColorCodes('&', value)
}
