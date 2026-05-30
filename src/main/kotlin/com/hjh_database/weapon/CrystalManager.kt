package com.hjh_database.weapon

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.io.File

// 新增类：用于存放每个独立槽位的属性配置
class ActivationConfig(val stats: Map<String, Double>)

class CrystalData(val id: String, sec: ConfigurationSection) {

    val display: String = sec.getString("display", "&f未知结晶")!!
    val material: Material = Material.valueOf(sec.getString("material", "SHULKER_SHELL")!!)
    val reqLv: Int = sec.getInt("req_lv", 1)
    val reqJob: Int = sec.getInt("req_job", 0)
    val rarity: Int = sec.getInt("rarity", 1)
    val customModelData: Int = sec.getInt("custom_model_data", 0)
    val lore: List<String> = sec.getStringList("lore")

    val skillId: String? = sec.getString("skill_id")
    val maxArrows: Int = if (sec.isConfigurationSection("quiver_data")) sec.getInt("quiver_data.max_arrows", 1024) else 0
    val replenishThreshold: Int = sec.getInt("quiver_data.replenish_threshold", 16)
    val replenishAmount: Int = sec.getInt("quiver_data.replenish_amount", 32)

    val medicalOverflowMaxStorage: Double = sec.getDouble("taolizhi_data.max_storage", 100.0)
    val medicalOverflowTriggerStorage: Double = sec.getDouble("taolizhi_data.trigger_storage", 20.0)
    val medicalOverflowCooldownSeconds: Double = sec.getDouble("taolizhi_data.cooldown", 3.0)
    val medicalOverflowRange: Double = sec.getDouble("taolizhi_data.range", 10.0)
    val medicalOverflowZfMultiplier: Double = sec.getDouble("taolizhi_data.zf_multiplier", 1.5)
    val medicalOverflowStorageMultiplier: Double = sec.getDouble("taolizhi_data.storage_multiplier", 0.5)

    // 【核心改动】支持多槽位与多属性映射表
    val activations = mutableMapOf<String, ActivationConfig>()

    init {
        val actSec = sec.getConfigurationSection("activation")
        if (actSec != null) {
            // 新版解析 logic
            for (slotKey in actSec.getKeys(false)) {
                val statSec = actSec.getConfigurationSection("$slotKey.stats")
                val map = mutableMapOf<String, Double>()
                if (statSec != null) {
                    for (k in statSec.getKeys(false)) {
                        map[k] = statSec.getDouble(k)
                    }
                }
                activations[slotKey] = ActivationConfig(map)
            }
        } else {
            // 兼容旧版的 activate_slot 与 stats 写法
            val oldSlot = sec.getInt("activate_slot", 0)
            val statSec = sec.getConfigurationSection("stats")
            val map = mutableMapOf<String, Double>()
            if (statSec != null) {
                for (k in statSec.getKeys(false)) {
                    map[k] = statSec.getDouble(k)
                }
            }
            activations["accessory_$oldSlot"] = ActivationConfig(map)
        }
    }

    fun isActivated(playerData: PlayerData): Boolean {
        val jobMatch = this.reqJob == -1 || playerData.job == this.reqJob
        return playerData.lv >= this.reqLv && jobMatch
    }
}

class CrystalManager(private val plugin: Hjh_database) {
    val loadedCrystals: MutableMap<String, CrystalData> = HashMap()
    val crystalKey: NamespacedKey = NamespacedKey(plugin, "crystal_id")
    private val resourceKey: NamespacedKey = NamespacedKey(plugin, "resource_id")
    private val accessoryInvKey = NamespacedKey(plugin, "player_accessory_inv")

    init { reload() }

    val allIds: List<String> get() = ArrayList(loadedCrystals.keys)

    fun reload() {
        loadedCrystals.clear()
        val file = File(plugin.dataFolder, "crystals.yml")
        if (!file.exists()) plugin.saveResource("crystals.yml", false)
        val config = YamlConfiguration.loadConfiguration(file)

        val sec = config.getConfigurationSection("crystals") ?: return
        for (key in sec.getKeys(false)) {
            sec.getConfigurationSection(key)?.let {
                loadedCrystals[key] = CrystalData(key, it)
            }
        }
    }

    fun buildItem(id: String): ItemStack? {
        val data = loadedCrystals[id] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', data.display))

        val newLore = mutableListOf<String>()
        var colorCode = "§7"
        when (data.rarity) {
            1 -> colorCode = "§f"
            2 -> colorCode = "§a"
            3 -> colorCode = "§9"
            4 -> colorCode = "§d"
            5 -> colorCode = "§e"
            6 -> colorCode = "§c"
        }
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(data.rarity)}")

        for (line in data.lore) {
            val finalLine = line
                .replace("{arrows}", "0")
                .replace("{max_arrows}", data.maxArrows.toString())
                .replace("{threshold}", data.replenishThreshold.toString())
                .replace("{amount}", data.replenishAmount.toString())
                .replace("{stored}", "0")
                .replace("{max_storage}", formatNumber(data.medicalOverflowMaxStorage))
                .replace("{trigger_storage}", formatNumber(data.medicalOverflowTriggerStorage))
            newLore.add(ChatColor.translateAlternateColorCodes('&', finalLine))
        }
        meta.lore = newLore

        meta.persistentDataContainer.set(crystalKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(resourceKey, PersistentDataType.STRING, id)

        meta.addItemFlags(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_ENCHANTS
        )
        meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()
        meta.isUnbreakable = true

        item.itemMeta = meta
        return item
    }

    // 获取某个物品在玩家背包中所处的“激活槽位标识符”
    private fun getInventorySlotKey(slot: Int): String {
        return when (slot) {
            in 0..8 -> "hotbar_$slot"
            40 -> "offhand"
            else -> "none"
        }
    }

    fun refreshPlayerCrystals(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 1. 刷新随身背包里的物品 (包含热键栏、副手等)
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue

                val currentSlotKey = getInventorySlotKey(i)
                updateCrystalLore(item, crystalData, data, currentSlotKey)
            }
        }

        // 2. 刷新饰品栏内的结晶
        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return
        try {
            var changed = false
            ByteArrayInputStream(savedBytes).use { bais ->
                BukkitObjectInputStream(bais).use { ois ->
                    val size = ois.readInt()
                    if (size <= 0) return

                    val items = arrayOfNulls<ItemStack>(size)
                    for (i in 0 until size) {
                        items[i] = ois.readObject() as? ItemStack
                    }

                    for (i in 0 until size) {
                        val item = items[i] ?: continue
                        val meta = item.itemMeta ?: continue

                        if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                            val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                            val crystalData = loadedCrystals[crystalId] ?: continue

                            val currentSlotKey = "accessory_$i"
                            updateCrystalLore(item, crystalData, data, currentSlotKey)
                            items[i] = item
                            changed = true
                        }
                    }

                    if (changed) {
                        java.io.ByteArrayOutputStream().use { baos ->
                            org.bukkit.util.io.BukkitObjectOutputStream(baos).use { oos ->
                                oos.writeInt(items.size)
                                for (item in items) {
                                    oos.writeObject(item)
                                }
                            }
                            player.persistentDataContainer.set(accessoryInvKey, PersistentDataType.BYTE_ARRAY, baos.toByteArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("刷新玩家 ${player.name} 的结晶 Lore 失败。")
        }
    }

    fun updateCrystalLore(item: ItemStack, crystalData: CrystalData, playerData: PlayerData, currentSlotKey: String) {
        val meta = item.itemMeta ?: return

        // 1. 首先判断当前槽位是否在配置的激活范围内
        var isActive = crystalData.activations.containsKey(currentSlotKey)
        val statusLore = mutableListOf<String>()

        if (crystalData.customModelData != 0) {
            meta.setCustomModelData(crystalData.customModelData)
        } else {
            meta.setCustomModelData(null)
        }

        // 2. 【核心修复点】正确处理无职业限制 (-1) 的情况
        if (isActive) {
            // 如果要求职业不是-1（有特定职业要求），并且玩家职业不符合
            if (crystalData.reqJob != -1 && playerData.job != crystalData.reqJob) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 职业不符")
            } else if (playerData.lv < crystalData.reqLv) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 等级不足 (${playerData.lv}/${crystalData.reqLv})")
            }
        }

        // 3. 如果槽位也不对（且不是因为职业等级报错），生成位置提示
        if (!isActive && statusLore.isEmpty()) {
            val reqs = crystalData.activations.keys.joinToString(", ") { key ->
                when {
                    key.startsWith("accessory_") -> "饰品栏[${key.removePrefix("accessory_").toInt() + 1}]"
                    key.startsWith("hotbar_") -> "快捷栏[${key.removePrefix("hotbar_").toInt() + 1}]"
                    key == "offhand" -> "副手"
                    else -> key
                }
            }
            statusLore.add(org.bukkit.ChatColor.GRAY.toString() + "○ 未激活 (支持放入: $reqs)")
        }

        // --- 以下是生成最终 Lore 的逻辑 ---
        val newLore = mutableListOf<String>()

        // 稀有度颜色处理
        var colorCode = "§7"
        when (crystalData.rarity) {
            1 -> colorCode = "§f"; 2 -> colorCode = "§a"; 3 -> colorCode = "§9"
            4 -> colorCode = "§d"; 5 -> colorCode = "§e"; 6 -> colorCode = "§c"
        }
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(crystalData.rarity)}")

        // 箭袋占位符处理
        val isQuiver = crystalData.maxArrows > 0
        var currentArrows = 0
        if (isQuiver) {
            val arrowKey = NamespacedKey(plugin, "quiver_arrows")
            currentArrows = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        }

        val medicalOverflowKey = NamespacedKey(plugin, "medical_overflow_stored")
        val currentMedicalOverflow = meta.persistentDataContainer.get(medicalOverflowKey, PersistentDataType.DOUBLE) ?: 0.0

        for (line in crystalData.lore) {
            var finalLine = line
            if (isQuiver) {
                finalLine = finalLine.replace("{arrows}", currentArrows.toString())
                    .replace("{max_arrows}", crystalData.maxArrows.toString())
                    .replace("{threshold}", crystalData.replenishThreshold.toString())
                    .replace("{amount}", crystalData.replenishAmount.toString())
            }
            finalLine = finalLine
                .replace("{stored}", formatNumber(currentMedicalOverflow))
                .replace("{max_storage}", formatNumber(crystalData.medicalOverflowMaxStorage))
                .replace("{trigger_storage}", formatNumber(crystalData.medicalOverflowTriggerStorage))
            newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', finalLine))
        }

        newLore.add(" ")

        // 4. 【最终显示】只有真正的 isActive 才会显示绿色对勾
        if (isActive) {
            val activeLabel = when {
                currentSlotKey.startsWith("accessory_") -> "饰品栏"
                currentSlotKey.startsWith("hotbar_") -> "快捷栏"
                currentSlotKey == "offhand" -> "副手"
                else -> currentSlotKey
            }
            newLore.add(org.bukkit.ChatColor.GREEN.toString() + "✔ 已在 [$activeLabel] 激活")
        } else {
            // 这里会显示“职业不符”或“等级不足”或“未激活”
            newLore.addAll(statusLore)
        }

        meta.lore = newLore
        item.itemMeta = meta
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }

    fun refreshOpenAccessoryMenu(player: Player, topInv: org.bukkit.inventory.Inventory) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, getInventorySlotKey(i))
            }
        }

        for (i in 0 until topInv.size) {
            val item = topInv.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, "accessory_$i")
            }
        }
    }

    fun calculateCrystalStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = HashMap<String, Double>()
        var totalRarity = 0.0

        // 内部复用处理逻辑
        fun processItem(item: ItemStack?, slotKey: String) {
            if (item == null) return
            val meta = item.itemMeta ?: return
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: return

// 【核心修复点】直接调用已经封装好的 isActivated 方法，彻底杜绝硬编码遗漏
                // 校验：等级足够、职业匹配，且该槽位在配置文件的激活列表里
                if (crystalData.isActivated(data) && crystalData.activations.containsKey(slotKey)) {

                    data.rarityDetails.add(crystalData.rarity)
                    totalRarity += crystalData.rarity.toDouble()

                    // 读取该特定槽位赋予的属性
                    val slotStats = crystalData.activations[slotKey]?.stats ?: return
                    slotStats.forEach { (k, v) ->
                        val actualKey = if (k == "power") {
                            when (data.job) {
                                1 -> "archerDamage"
                                2, 3 -> "zfStr"
                                else -> "attack"
                            }
                        } else k
                        stats.merge(actualKey, v) { a, b -> a + b }
                    }
                }
            }
        }

        // 1. 扫描饰品栏
        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY)
        if (savedBytes != null) {
            try {
                BukkitObjectInputStream(ByteArrayInputStream(savedBytes)).use { ois ->
                    val size = ois.readInt()
                    for (i in 0 until size) {
                        processItem(ois.readObject() as? ItemStack, "accessory_$i")
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("读取玩家 ${player.name} 的结晶数据失败。")
            }
        }

        // 2. 扫描副手
        processItem(player.inventory.itemInOffHand, "offhand")

        // 3. 扫描快捷栏 (0-8)
        for (i in 0..8) {
            processItem(player.inventory.getItem(i), "hotbar_$i")
        }

        if (totalRarity > 0) {
            stats["total_rarity"] = totalRarity
        }
        return stats
    }
}
