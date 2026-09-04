package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.equipment.activation.ActivatableEquipment
import com.hjh_database.equipment.activation.ActivationFailure
import com.hjh_database.equipment.activation.ActivationSpec
import com.hjh_database.weapon.WeaponManager
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.Locale

/** 普通常规法宝：只受槽位、职业与等级限制，不使用虎瘴或耐久。 */
class ArtifactManager(private val plugin: Hjh_database) {
    private val artifactKey = NamespacedKey(plugin, "artifact_id")
    private val artifacts = mutableMapOf<String, ArtifactData>()
    val allIds: Set<String>
        get() = artifacts.keys.toSet()

    init {
        reload()
    }

    fun reload() {
        val file = File(plugin.dataFolder, "artifacts.yml")
        if (!file.exists()) plugin.saveResource("artifacts.yml", false)

        artifacts.clear()
        val config = YamlConfiguration.loadConfiguration(file)
        val root = config.getConfigurationSection("artifacts")
        root?.getKeys(false)?.forEach { rawId ->
            val section = root.getConfigurationSection(rawId) ?: return@forEach
            val id = rawId.lowercase(Locale.ROOT)
            artifacts[id] = ArtifactData(id, section)
        }
        plugin.logger.info("普通法宝加载完成：${artifacts.size} 件。")
    }

    fun getItem(id: String): ItemStack? {
        val data = artifacts[id.lowercase(Locale.ROOT)] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(color(data.display))
        meta.lore = baseLore(data)
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData)
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
        meta.persistentDataContainer.set(artifactKey, PersistentDataType.STRING, data.id)
        meta.persistentDataContainer.set(NamespacedKey(plugin, "rarity"), PersistentDataType.INTEGER, data.rarity)
        item.itemMeta = meta

        if (data.id == TianheyiSkill.ARTIFACT_ID) {
            TianheyiSkill.initializeNewItem(plugin, item)
        }
        if (data.id == QueqiaoyinSkill.ARTIFACT_ID) {
            QueqiaoyinSkill.initializeNewItem(plugin, item)
        }
        if (data.id == QueshuanglingSkill.ARTIFACT_ID) {
            QueshuanglingSkill.initializeNewItem(plugin, item)
        }
        if (data.id == LingyunsuoSkill.ARTIFACT_ID) {
            LingyunsuoSkill.initializeNewItem(plugin, item)
        }
        return item
    }

    fun getDataFromItem(item: ItemStack?): ArtifactData? {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null
        val id = item.itemMeta?.persistentDataContainer
            ?.get(artifactKey, PersistentDataType.STRING)
            ?.lowercase(Locale.ROOT)
            ?: return null
        return artifacts[id]
    }

    fun isActive(player: Player, item: ItemStack?, slot: Int): Boolean {
        val data = getDataFromItem(item) ?: return false
        val playerData = plugin.playerManager.getData(player.uniqueId) ?: return false
        return data.activationSpec.isActive(playerData, slot, player = player, item = item)
    }

    fun calculateStats(player: Player, playerData: PlayerData): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            val data = getDataFromItem(item) ?: continue
            if (!data.activationSpec.isActive(playerData, slot, player = player, item = item)) continue

            data.stats.forEach { (key, value) -> result.merge(key, value, Double::plus) }
            result.merge("total_rarity", data.rarity.toDouble(), Double::plus)
            playerData.rarityDetails.add(data.rarity)
        }
        return result
    }

    fun refreshPlayerArtifacts(player: Player) {
        val playerData = plugin.playerManager.getData(player.uniqueId) ?: return
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            val data = getDataFromItem(item) ?: continue
            val meta = item.itemMeta ?: continue
            val lore = baseLore(data).toMutableList()
            lore.add("")
            val failure = data.activationSpec.firstFailure(playerData, slot, player = player, item = item)
            when (failure) {
                null -> lore.add("§a✓ 法宝已激活")
                ActivationFailure.SLOT_MISMATCH -> lore.add(color(data.activeLoreLine))
                ActivationFailure.JOB_MISMATCH -> lore.add("§c⚠ 职业不符")
                ActivationFailure.LEVEL_MISMATCH -> lore.add("§c⚠ 等级不足 (${playerData.lv}/${data.reqLv})")
                else -> lore.add("§7◆ 法宝未激活")
            }
            meta.lore = lore
            if (data.customModelData > 0) meta.setCustomModelData(data.customModelData)
            item.itemMeta = meta
        }
    }

    private fun baseLore(data: ArtifactData): List<String> = buildList {
        add(WeaponManager.getRarityLore(data.rarity))
        data.lore.forEach { add(color(it)) }
    }

    private fun color(value: String): String = ChatColor.translateAlternateColorCodes('&', value)
}

class ArtifactData(val id: String, section: ConfigurationSection) : ActivatableEquipment {
    val display: String = section.getString("display", id)!!
    val rarity: Int = section.getInt("rarity", 1)
    val material: Material = Material.matchMaterial(section.getString("material", "STONE")!!) ?: Material.STONE
    val customModelData: Int = section.getInt("custom_model_data", 0)
    val skillId: String = section.getString("skill_id", id)!!.lowercase(Locale.ROOT)
    val reqJob: Int = section.getInt("req_job", -1)
    val reqLv: Int = section.getInt("req_lv", 1)
    val activateSlot: Int = section.getInt("activate_slot", 0)
    val activeLoreLine: String = section.getString("active_lore_line", "&c请放入指定快捷栏激活")!!
    val manaCost: Double = section.getDouble("mana_cost", 7.0).coerceAtLeast(0.0)
    val lore: List<String> = section.getStringList("lore")
    val stats: Map<String, Double> = section.getConfigurationSection("stats")
        ?.getKeys(false)
        ?.associateWith { section.getDouble("stats.$it") }
        ?: emptyMap()

    override val activationSpec = ActivationSpec(
        requiredJob = reqJob,
        requiredLevel = reqLv,
        acceptedInventorySlots = if (activateSlot == -1) null else intArrayOf(activateSlot)
    )
}
