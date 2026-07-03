package com.hjh_database.farming.config

import com.hjh_database.Hjh_database
import com.hjh_database.farming.data.AcceleratorDef
import com.hjh_database.farming.data.BoosterDef
import com.hjh_database.farming.data.FarmingEventDef
import com.hjh_database.farming.data.PlantType
import com.hjh_database.farming.data.ProtectionDef
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class FarmingConfig(private val plugin: Hjh_database) {
    lateinit var farming: FileConfiguration
        private set
    lateinit var plantsFile: FileConfiguration
        private set

    var guiTitle: String = "&2&l✦ 灵田药圃 ✦"
        private set
    var guiRows: Int = 5
        private set
    var fieldSlots: List<Int> = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        private set
    var defaultMaxFields: Int = 1
        private set
    var maxTotalFields: Int = 9
        private set
    var unlockItemId: String = "lingtiankaituoling"
        private set
    var growthTickInterval: Int = 60
        private set
    var eventCheckInterval: Int = 900
        private set
    var eventMinOnlineTime: Int = 300
        private set
    var eventNotification: Boolean = true
        private set

    private val unlockCosts = linkedMapOf<Int, Int>()
    private val plants = linkedMapOf<String, PlantType>()
    private val accelerators = linkedMapOf<String, AcceleratorDef>()
    private val boosters = linkedMapOf<String, BoosterDef>()
    private val protections = linkedMapOf<String, ProtectionDef>()
    private val events = linkedMapOf<String, FarmingEventDef>()

    fun load() {
        saveResourceIfMissing("farming/config.yml")
        saveResourceIfMissing("farming/plants.yml")

        farming = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "farming/config.yml"))
        plantsFile = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "farming/plants.yml"))

        guiTitle = farming.getString("gui.title", guiTitle) ?: guiTitle
        guiRows = farming.getInt("gui.rows", 5)
        fieldSlots = farming.getIntegerList("gui.field_slots").ifEmpty { fieldSlots }
        defaultMaxFields = farming.getInt("unlock.default_max_fields", 1)
        maxTotalFields = farming.getInt("unlock.max_total_fields", fieldSlots.size).coerceAtMost(fieldSlots.size)
        unlockItemId = farming.getString("unlock.item_id", "lingtiankaituoling") ?: "lingtiankaituoling"
        growthTickInterval = farming.getInt("growth.tick_interval", 60)
        eventCheckInterval = farming.getInt("events.check_interval", 900)
        eventMinOnlineTime = farming.getInt("events.min_online_time", 300)
        eventNotification = farming.getBoolean("events.notification", true)

        loadUnlockCosts()
        loadPlants()
        loadAccelerators()
        loadBoosters()
        loadProtections()
        loadEvents()

        plugin.logger.info("灵田配置已加载：${plants.size} 种灵植，${protections.size} 种防护符。")
    }

    private fun saveResourceIfMissing(name: String) {
        val file = File(plugin.dataFolder, name)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            plugin.saveResource(name, false)
        }
    }

    private fun loadUnlockCosts() {
        unlockCosts.clear()
        farming.getConfigurationSection("unlock.costs")?.getKeys(false)?.forEach { key ->
            unlockCosts[key.toIntOrNull() ?: return@forEach] = farming.getInt("unlock.costs.$key")
        }
    }

    private fun loadPlants() {
        plants.clear()
        val root = plantsFile.getConfigurationSection("plants") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            plants[id] = PlantType(
                id = id,
                name = sec.getString("name", id) ?: id,
                description = sec.getString("description", "") ?: "",
                seedItemId = sec.getString("seed_item_id", "${id}_seed") ?: "${id}_seed",
                harvestItemId = sec.getString("harvest_item_id", "${id}_harvest") ?: "${id}_harvest",
                growthTimeSeconds = sec.getInt("growth_time", 300),
                growthStages = sec.getInt("growth_stages", 3),
                harvestMin = sec.getInt("harvest_min", 1),
                harvestMax = sec.getInt("harvest_max", 3),
                expReward = sec.getInt("exp_reward", 10),
                levelRequired = sec.getInt("level_required", 1)
            )
        }
    }

    private fun loadAccelerators() {
        accelerators.clear()
        val root = plantsFile.getConfigurationSection("accelerators") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            accelerators[id] = AcceleratorDef(
                id = id,
                itemId = sec.getString("item_id", id) ?: id,
                speedMultiplier = sec.getDouble("speed_multiplier", 2.0).coerceAtLeast(1.0),
                durationSeconds = sec.getInt("duration", 600)
            )
        }
    }

    private fun loadBoosters() {
        boosters.clear()
        val root = plantsFile.getConfigurationSection("boosters") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            boosters[id] = BoosterDef(
                id = id,
                itemId = sec.getString("item_id", id) ?: id,
                yieldMultiplier = sec.getDouble("yield_multiplier", 2.0).coerceAtLeast(1.0)
            )
        }
    }

    private fun loadProtections() {
        protections.clear()
        val root = farming.getConfigurationSection("protections") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            protections[id] = ProtectionDef(
                id = id,
                itemId = sec.getString("item_id", id) ?: id,
                price = sec.getInt("price", 1).coerceAtLeast(0),
                against = sec.getStringList("against"),
                durationSeconds = sec.getInt("duration", 86400)
            )
        }
    }

    private fun loadEvents() {
        events.clear()
        val root = farming.getConfigurationSection("events.events") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            events[id] = FarmingEventDef(
                id = id,
                chance = sec.getDouble("chance", 0.0),
                damageType = sec.getString("damage_type", "yield") ?: "yield",
                damagePercent = sec.getDouble("damage_percent", 0.0),
                message = sec.getString("message", "&c药圃遇劫！") ?: "&c药圃遇劫！",
                affectedPlants = sec.getStringList("affected_plants")
            )
        }
    }

    fun unlockCost(fieldIndex: Int): Int = unlockCosts[fieldIndex + 1] ?: 1
    fun message(key: String): String = farming.getString("messages.$key", "&c消息未配置: $key") ?: "&c消息未配置: $key"
    fun message(key: String, values: Map<String, String>): String {
        var msg = message(key)
        values.forEach { (k, v) -> msg = msg.replace("{$k}", v) }
        return msg
    }

    fun plant(id: String?): PlantType? = id?.let { plants[it] }
    fun allPlants(): Collection<PlantType> = plants.values
    fun allAccelerators(): Collection<AcceleratorDef> = accelerators.values
    fun accelerator(id: String?): AcceleratorDef? = id?.let { accelerators[it] }
    fun allBoosters(): Collection<BoosterDef> = boosters.values
    fun booster(id: String?): BoosterDef? = id?.let { boosters[it] }
    fun allProtections(): Collection<ProtectionDef> = protections.values
    fun protection(id: String?): ProtectionDef? = id?.let { protections[it] }
    fun allEvents(): Collection<FarmingEventDef> = events.values
}
