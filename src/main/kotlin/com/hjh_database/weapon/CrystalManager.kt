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
import java.util.ArrayList
import java.util.HashMap

class CrystalData(val id: String, sec: ConfigurationSection) {

    val display: String = sec.getString("display", "&f未知结晶")!!
    val material: Material = Material.valueOf(sec.getString("material", "SHULKER_SHELL")!!)
    val reqLv: Int = sec.getInt("req_lv", 1)
    // 【修复1】新增稀有度读取
    val rarity: Int = sec.getInt("rarity", 1)
    val lore: List<String> = sec.getStringList("lore")
    val stats: MutableMap<String, Double> = HashMap()

    init {
        val statSec = sec.getConfigurationSection("stats")
        if (statSec != null) {
            for (key in statSec.getKeys(false)) {
                stats[key] = statSec.getDouble(key)
            }
        }
    }
}

class CrystalManager(private val plugin: Hjh_database) {
    val loadedCrystals: MutableMap<String, CrystalData> = HashMap()
    val crystalKey: NamespacedKey = NamespacedKey(plugin, "crystal_id")
    // 【修复2】加上通用资源 Key，保持和护甲/武器的统一步伐
    private val resourceKey: NamespacedKey = NamespacedKey(plugin, "resource_id")
    private val accessoryInvKey = NamespacedKey(plugin, "player_accessory_inv")

    init { reload() }

    val allIds: List<String>
        get() = ArrayList(loadedCrystals.keys)

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

    // 生成物品
    fun buildItem(id: String): ItemStack? {
        val data = loadedCrystals[id] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item

        // 【修复3】使用标准的 ChatColor 转换
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', data.display))

        val newLore = mutableListOf<String>()

        // ===============================================
        // 【新增】稀有度 Lore 拼接 (完全对齐 ArmorManager)
        var colorCode = "§7"
        when (data.rarity) {
            1 -> colorCode = "§f"
            2 -> colorCode = "§a"
            3 -> colorCode = "§9"
            4 -> colorCode = "§d"
            5 -> colorCode = "§e"
            6 -> colorCode = "§c"
        }
        // 调用 WeaponManager 的静态/伴生方法获取星星
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(data.rarity)}")
        // ===============================================

        // 拼接配置中原有的 Lore
        for (line in data.lore) {
            newLore.add(ChatColor.translateAlternateColorCodes('&', line))
        }
        meta.lore = newLore

        // 写入 NBT (同时写入 crystal_id 和 resource_id)
        meta.persistentDataContainer.set(crystalKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(resourceKey, PersistentDataType.STRING, id)

        // 【修复4】彻底干掉原版的多余属性提示，保持装备纯净
        meta.addItemFlags(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_ENCHANTS
        )
        meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()
        meta.isUnbreakable = true // 建议加上不可破坏

        item.itemMeta = meta
        return item
    }

    fun refreshPlayerCrystals(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 1. 清理玩家随身背包里的游离结晶
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, isEquipped = false)
            }
        }

        // 2. 处理饰品栏内的结晶
        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return
        try {
            var changed = false
            java.io.ByteArrayInputStream(savedBytes).use { bais ->
                org.bukkit.util.io.BukkitObjectInputStream(bais).use { ois ->
                    val size = ois.readInt()
                    if (size <= 0) return

                    val items = arrayOfNulls<ItemStack>(size)
                    for (i in 0 until size) {
                        items[i] = ois.readObject() as? ItemStack
                    }

                    // 【修复点2】遍历饰品栏里的*所有*物品，而不再仅仅是第一格！
                    for (i in 0 until size) {
                        val item = items[i] ?: continue
                        val meta = item.itemMeta ?: continue

                        // 如果该物品是结晶
                        if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                            val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                            val crystalData = loadedCrystals[crystalId] ?: continue

                            // 只有在饰品栏第 0 格 (也就是第一格) 才是已装备状态
                            val isEquipped = (i == 0)
                            updateCrystalLore(item, crystalData, data, isEquipped)

                            items[i] = item
                            changed = true
                        }
                    }

                    // 如果发生了修改，重新保存到 PDC
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

    private fun updateCrystalLore(item: ItemStack, crystalData: CrystalData, playerData: PlayerData, isEquipped: Boolean) {
        val meta = item.itemMeta ?: return
        var isActive = isEquipped
        val statusLore = mutableListOf<String>()

        // 状态判定
        if (isEquipped && playerData.lv < crystalData.reqLv) {
            isActive = false
            statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 等级不足 (" + playerData.lv + "/" + crystalData.reqLv + ")")
        } else if (!isEquipped) {
            statusLore.add(org.bukkit.ChatColor.GRAY.toString() + "○ 未装备 (请放入饰品栏第一格)")
        }

        val newLore = mutableListOf<String>()
        var colorCode = "§7"
        when (crystalData.rarity) {
            1 -> colorCode = "§f"
            2 -> colorCode = "§a"
            3 -> colorCode = "§9"
            4 -> colorCode = "§d"
            5 -> colorCode = "§e"
            6 -> colorCode = "§c"
        }
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(crystalData.rarity)}")

        for (line in crystalData.lore) {
            newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line))
        }

        newLore.add(" ")
        if (isActive) {
            newLore.add(org.bukkit.ChatColor.GREEN.toString() + "✔ 已激活 - 结晶生效中")
        } else {
            newLore.addAll(statusLore)
        }

        meta.lore = newLore
        item.itemMeta = meta
    }

    // ==========================================
    // 【新增】专用于动态刷新打开中的饰品栏的方法
    // ==========================================
    fun refreshOpenAccessoryMenu(player: Player, topInv: org.bukkit.inventory.Inventory) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 1. 扫描并刷新玩家随身背包里的结晶 (全部标记为未激活)
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, false)
            }
        }

        // 2. 扫描并刷新饰品栏上方的结晶
        for (i in 0 until topInv.size) {
            val item = topInv.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue

                // 只有放在第一格 (索引 0) 才是真正的激活状态
                val isEquipped = (i == 0)
                updateCrystalLore(item, crystalData, data, isEquipped)
            }
        }
    }

    fun calculateCrystalStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = HashMap<String, Double>()
        var totalRarity = 0.0

        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return stats

        try {
            org.bukkit.util.io.BukkitObjectInputStream(java.io.ByteArrayInputStream(savedBytes)).use { ois ->
                val size = ois.readInt()
                if (size > 0) {
                    val firstSlotItem = ois.readObject() as? ItemStack ?: return stats
                    val meta = firstSlotItem.itemMeta ?: return stats

                    if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                        val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                        val crystalData = loadedCrystals[crystalId] ?: return stats

                        if (data.lv >= crystalData.reqLv) {
                            data.rarityDetails.add(crystalData.rarity)
                            totalRarity += crystalData.rarity.toDouble()

                            // 【优化：自适应属性转化】
                            crystalData.stats.forEach { (k, v) ->
                                val actualKey = if (k == "power") {
                                    // 根据职业动态分配进攻属性 (0:战士, 1:弓箭, 2:术士, 3:医师)
                                    when (data.job) {
                                        1 -> "archer_damage"
                                        2, 3 -> "zf_str"
                                        else -> "attack" // 默认（无职业或战士）给近战强度
                                    }
                                } else {
                                    k
                                }
                                stats.merge(actualKey, v) { a, b -> a + b }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("读取玩家 ${player.name} 的结晶数据失败。")
        }

        if (totalRarity > 0) {
            stats["total_rarity"] = totalRarity
        }
        return stats
    }
}