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
    // 【新增】读取职业限制，0 代表无限制
    val reqJob: Int = sec.getInt("req_job", 0)
    // 【修复1】新增稀有度读取
    val rarity: Int = sec.getInt("rarity", 1)
    // 【新增】读取结晶激活位置，默认是 0 (第一格)
    val activateSlot: Int = sec.getInt("activate_slot", 0)
    // 【新增】读取 CustomModelData，如果没有配置则默认为 0
    val customModelData: Int = sec.getInt("custom_model_data", 0)
    val lore: List<String> = sec.getStringList("lore")

    // 【新增】读取技能ID与箭袋专属数值 (默认值防空)
    val skillId: String? = sec.getString("skill_id")
    val maxArrows: Int = sec.getInt("quiver_data.max_arrows", 1024)
    val replenishThreshold: Int = sec.getInt("quiver_data.replenish_threshold", 16)
    val replenishAmount: Int = sec.getInt("quiver_data.replenish_amount", 32)

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

                            // 判断当前格子的索引是否等于这个结晶专属的激活槽位
                            val isEquipped = (i == crystalData.activateSlot)
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

        // 【新增】应用自定义模型数据
        // 如果 custom_model_data 为 0，通常代表使用默认模型
        if (crystalData.customModelData != 0) {
            meta.setCustomModelData(crystalData.customModelData)
        } else {
            // 如果是 0，则清除 CustomModelData（防止物品之前的模型残留）
            meta.setCustomModelData(null)
        }

        // 状态判定
        if (isEquipped) {
            if (crystalData.reqJob > 0 && playerData.job != crystalData.reqJob) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 职业不符")
            } else if (playerData.lv < crystalData.reqLv) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 等级不足 (" + playerData.lv + "/" + crystalData.reqLv + ")")
            }
        }

        if (!isEquipped) {
            // 把 0~8 的索引转换为中文的 一~九
            val chineseNums = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
            val slotName = if (crystalData.activateSlot in 0..8) {
                chineseNums[crystalData.activateSlot]
            } else {
                (crystalData.activateSlot + 1).toString() // 兜底防越界
            }
            statusLore.add(org.bukkit.ChatColor.GRAY.toString() + "○ 未激活 (请放入饰品栏第${slotName}格)")
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

        // 【修复】不再写死 ID，只要最大箭矢数量大于 0，就自动识别为箭袋饰品
        val isQuiver = crystalData.maxArrows > 0
        var currentArrows = 0

        if (isQuiver) {
            val arrowKey = NamespacedKey(plugin, "quiver_arrows")
            currentArrows = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        }

        for (line in crystalData.lore) {
            var finalLine = line
            // 只要是箭袋，就把【所有】的占位符都替换掉
            if (isQuiver) {
                finalLine = finalLine.replace("{arrows}", currentArrows.toString())
                    .replace("{max_arrows}", crystalData.maxArrows.toString())
                    .replace("{threshold}", crystalData.replenishThreshold.toString())
                    .replace("{amount}", crystalData.replenishAmount.toString())
            }
            // 经过这行代码转换，你的 &a 就会变成正确的颜色代码，同时拼接进最终的 Lore
            newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', finalLine))
        }

        newLore.add(" ")
        if (isActive) {
            newLore.add(org.bukkit.ChatColor.GREEN.toString() + "✔ 已激活 - 饰品生效中")
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

                // 判断当前格子的索引是否等于这个结晶专属的激活槽位
                val isEquipped = (i == crystalData.activateSlot)
                updateCrystalLore(item, crystalData, data, isEquipped)
            }
        }
    }

    fun calculateCrystalStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = HashMap<String, Double>()
        var totalRarity = 0.0

        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return stats

        try {
            BukkitObjectInputStream(ByteArrayInputStream(savedBytes)).use { ois ->
                val size = ois.readInt()
                for (i in 0 until size) {
                    val item = ois.readObject() as? ItemStack ?: continue
                    val meta = item.itemMeta ?: continue

                    if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                        val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                        val crystalData = loadedCrystals[crystalId] ?: continue

                        // 【核心修改】不仅要校验等级和位置，还要校验职业 (reqJob == 0 即为无限制)
                        val jobMatch = crystalData.reqJob == 0 || data.job == crystalData.reqJob
                        if (i == crystalData.activateSlot && data.lv >= crystalData.reqLv && jobMatch) {

                            data.rarityDetails.add(crystalData.rarity)
                            totalRarity += crystalData.rarity.toDouble()

                            crystalData.stats.forEach { (k, v) ->
                                val actualKey = if (k == "power") {
                                    when (data.job) {
                                        1 -> "archerDamage"
                                        2, 3 -> "zfStr"
                                        else -> "attack"
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