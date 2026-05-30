package com.hjh_database.weapon

import com.google.common.collect.ArrayListMultimap // 【新增】用于清除属性
import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class WeaponManager(private val plugin: Hjh_database) {
    // 【修复点说明】：
    // Kotlin 的 val 属性会自动生成 getLoadedWeapons() 方法。
    // 所以不需要手动写 getLoadedWeapons()，外部 Java 代码依然可以正常调用它。
    val loadedWeapons: MutableMap<String, WeaponData> = HashMap()

    private val keyId: NamespacedKey
    private val weaponKey: NamespacedKey
    private var file: File? = null
    private var config: FileConfiguration? = null

    init {
        this.keyId = NamespacedKey(plugin, "resource_id")
        this.weaponKey = NamespacedKey(plugin, "weapon_id")
        reload()
    }

    // 这里保留这个辅助方法，因为它通过 ID 获取单个数据，不会产生冲突
    fun getWeaponData(id: String): WeaponData? {
        return loadedWeapons[id]
    }

    fun getWeaponDataFromItem(item: ItemStack?): WeaponData? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        var id = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
        if (id == null) {
            id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
        }
        return id?.let { loadedWeapons[it] }
    }

    fun reload() {
        loadedWeapons.clear()
        file = File(plugin.dataFolder, "weapons.yml")
        if (!file!!.exists()) {
            plugin.saveResource("weapons.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file!!)

        val sec = config!!.getConfigurationSection("weapons")
        if (sec != null) {
            for (key in sec.getKeys(false)) {
                val itemSec = sec.getConfigurationSection(key)
                if (itemSec != null) {
                    loadedWeapons[key] = WeaponData(key, itemSec)
                }
            }
        }
        plugin.logger.info("WeaponManager 加载了 " + loadedWeapons.size + " 把武器。")
    }

    fun checkActiveWeapon(player: Player, item: ItemStack?, checkSlot: Int): WeaponData? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) {
            return null
        }
        val meta = item.itemMeta ?: return null

        // 1. 获取 ID
        var id = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
        if (id == null) {
            id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
        }
        if (id == null) return null

        val wData = loadedWeapons[id] ?: return null

        val data = plugin.playerManager.getData(player.uniqueId) ?: return null

        // 2. 检查槽位要求
        // -1 代表任意位置
        if (wData.activateSlot != -1 && wData.activateSlot != checkSlot) {
            return null
        }
        // 3. 检查职业
        if (wData.reqJob != -1) {
            if (data.job == null || data.job != wData.reqJob) return null
        }
        // 4. 检查等级 (修改点：如果 status 为 4，则绕过等级检查)
        if (data.status != 4 && (data.lv ?: 0) < wData.reqLv) {
            return null
        }

        return wData
    }

    /**
     * 获取副手当前处于“激活状态”的武器 ID (供 SpellListener 使用)
     */
    fun getActiveOffHandWeaponId(player: Player): String? {
        // 40 是副手槽位的 ID
        val wd = checkActiveWeapon(player, player.inventory.itemInOffHand, 40)
        return wd?.id
    }

    /**
     * 刷新玩家背包中【所有位置】武器的 Lore 状态
     */
    fun refreshPlayerWeapons(player: Player) {
        // 获取玩家数据
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 遍历整个背包
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (item == null || !item.hasItemMeta()) continue

            val meta = item.itemMeta ?: continue

            // 检查是否是武器 (优先检查 weapon_id，兼容 resource_id)
            var id = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
            if (id == null) id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)

            if (id == null) continue // 不是武器，跳过

            val wData = loadedWeapons[id] ?: continue // 配置文件里已经删除了这个武器

            // === 判定激活状态逻辑 (保留你原本的逻辑) ===
            var isActive = true
            val statusLore: MutableList<String> = ArrayList()

            // 1. 检查槽位要求
            if (wData.activateSlot != -1 && wData.activateSlot != slot) {
                isActive = false
                statusLore.add(ChatColor.RED.toString() + "⚠ " + ChatColor.translateAlternateColorCodes('&', wData.activeLoreLine))
            }
            // 2. 检查职业
            if (wData.reqJob != -1) {
                if (data.job == null || data.job != wData.reqJob) {
                    isActive = false
                    statusLore.add(ChatColor.RED.toString() + "⚠ 职业不符")
                }
            }
            // 3. 检查等级 (修改点：status 为 4 时跳过此判定)
            if (data.status != 4 && (data.lv ?: 0) < wData.reqLv) {
                isActive = false
                statusLore.add(ChatColor.RED.toString() + "⚠ 等级不足 (" + data.lv + "/" + wData.reqLv + ")")
            }

            // === 重新构建 Meta ===
            // meta 已经在上面获取了
            if (wData.display != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', wData.display!!))
            }
            meta.persistentDataContainer.set(NamespacedKey(plugin, "rarity"), PersistentDataType.INTEGER, wData.rarity)

            // 构建新的 Lore 列表
            val newLore: MutableList<String> = ArrayList()

            // 【修改点】 1. 第一行插入稀有度星星 (紧跟名字下方)
            // 获取颜色代码
            var colorCode = "§7" // 默认为灰
            when (wData.rarity) {
                1 -> colorCode = "§f" // 白
                2 -> colorCode = "§a" // 绿
                3 -> colorCode = "§9" // 蓝
                4 -> colorCode = "§d" // 粉
                5 -> colorCode = "§e" // 黄
                6 -> colorCode = "§c" // 红
            }

            // 拼接：颜色 + 文字 + 星星
            newLore.add(colorCode + "稀有度: " + getRarityStars(wData.rarity))

            // 2. 插入原有 Lore (配置文件的描述)
            if (wData.lore != null) {
                for (line in wData.lore!!) {
                    newLore.add(ChatColor.translateAlternateColorCodes('&', line))
                }
            }

            // 3. 插入激活状态提示
            if (isActive) {
                newLore.add(" ")
                newLore.add(ChatColor.GREEN.toString() + "✔ 已激活 - 属性生效中")
            } else {
                newLore.add(" ")
                newLore.addAll(statusLore)
            }

            // =======================================================
            // 【新增】弩型武器专属判定：激活时给附魔，未激活时移除，且始终隐藏附魔显示
            // =======================================================
            if (item.type == Material.CROSSBOW) {
                if (isActive) {
                    // 【修改点】判断 ID 是否为 tingchao，赋予不同的快速装填等级
                    val quickChargeLevel = if (id == "tingchao") 3 else 2

                    meta.addEnchant(org.bukkit.enchantments.Enchantment.MULTISHOT, 1, true)
                    meta.addEnchant(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, quickChargeLevel, true)
                } else {
                    // 失效时移除，防止玩家放回背包依然能射出多重箭
                    meta.removeEnchant(org.bukkit.enchantments.Enchantment.MULTISHOT)
                    meta.removeEnchant(org.bukkit.enchantments.Enchantment.QUICK_CHARGE)
                }
                // 隐藏附魔文字描述
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }

            // =======================================================
            // 【关键修复】 4. 检查并保留医术信息 (防止刷新丢失)
            // =======================================================
            // 检查是否有医术ID的 NBT
            val medKey = NamespacedKey(plugin, "med_skill_id")
            if (meta.persistentDataContainer.has(medKey, PersistentDataType.STRING)) {
                val medSkillId = meta.persistentDataContainer.get(medKey, PersistentDataType.STRING)

                // 检查是否有制作者 NBT
                val crafterKey = NamespacedKey(plugin, "med_crafter")
                val crafterName = meta.persistentDataContainer.get(crafterKey, PersistentDataType.STRING)

                newLore.add("§8§m------------------")

                // 尝试获取技能中文名
                // Kotlin 调用 Java getter 简化为属性访问
                if (plugin.medicalManager != null) {
                    val skillName = plugin.medicalManager!!.getSkillName(medSkillId!!)
                    newLore.add("§6[医术] §e" + if (skillName != null) skillName else medSkillId)

                    // 【核心逻辑补充】从 MedicalManager 读取原始技能书的详细Lore
                    val originalBook = plugin.medicalManager!!.getSkillBook(medSkillId)
                    if (originalBook != null && originalBook.hasItemMeta() && originalBook.itemMeta!!.hasLore()) {
                        for (line in originalBook.itemMeta!!.lore!!) {
                            // 过滤掉那句 "放入绘制台" 的提示，其他都加上
                            if (line.contains("放入绘制台")) continue
                            newLore.add(line)
                        }
                    }
                } else {
                    newLore.add("§6[医术] §e$medSkillId")
                }

                if (crafterName != null) {
                    newLore.add("§7绘旗者: $crafterName")
                }
            }
            // =======================================================

            // 4. 应用更改
            meta.lore = newLore
            if (wData.customModelData != 0) {
                meta.setCustomModelData(wData.customModelData) // 顺便刷新材质
            }

            // 【此处可复用 1.21 属性清除逻辑，如果你希望 refresh 也清除属性的话】
            // meta.setAttributeModifiers(ArrayListMultimap.create())
            // meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)

            item.itemMeta = meta
        }
    }

    /**
     * 计算所有属性
     * 规则：只要位置对、等级够、职业对，所有属性（含攻击力）全部加上
     */
    fun calculateWeaponStats(player: Player, data: PlayerData): Map<String, Double> {
        val totalStats: MutableMap<String, Double> = HashMap()
        // 1. 定义稀有度累加变量
        var totalRarity = 0.0

        // 遍历全背包
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (item == null || !item.hasItemMeta()) continue

            // 获取ID (优先 weapon_id，其次 resource_id)
            val meta = item.itemMeta ?: continue
            var id = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
            if (id == null) id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
            if (id == null) continue

            val wData = loadedWeapons[id] ?: continue

            // === 校验激活条件 ===
            // 1. 槽位不对，跳过
            if (wData.activateSlot != -1 && wData.activateSlot != slot) continue
            // 2. 职业不符，跳过
            if (wData.reqJob != -1 && (data.job == null || data.job != wData.reqJob)) continue
            // 3. 等级不够 (修改点：如果 status 是 4，即便等级不够也不跳过，继续执行)
            if (data.status != 4 && (data.lv ?: 0) < wData.reqLv) continue

            // === 激活成功 ===
            // ★【新增】这里是激活成功的地方，把稀有度记入 List
            data.rarityDetails.add(wData.rarity)
            // ★ 修改点1：累加稀有度
            totalRarity += wData.rarity.toDouble()

            // ★ 修改点2：累加所有属性
            for ((key, value) in wData.stats) {
                totalStats.merge(key, value) { a: Double, b: Double -> a + b }
            }
        }

        // ★ 修改点3：把计算好的总稀有度放入 Map 返回
        // 这样 PlayerManager 就能通过 get("total_rarity") 拿到了
        totalStats["total_rarity"] = totalRarity

        return totalStats
    }

    /**
     * 【新增】获取纯净版武器 (用于配方保存和指令获取)
     */
    fun getItemStack(id: String): ItemStack? {
        val data = loadedWeapons[id] ?: return null

        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item

        // 1. 基础信息
        if (data.display != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', data.display!!))
        }
        if (data.lore != null) {
            val coloredLore: MutableList<String> = ArrayList()
            for (line in data.lore!!) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line))
            }
            meta.lore = coloredLore
        }
        if (data.customModelData != 0) {
            meta.setCustomModelData(data.customModelData)
        }

        // 2. 写入 NBT
        meta.persistentDataContainer.set(keyId, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(weaponKey, PersistentDataType.STRING, id)

        // 3. 属性标记 (1.21.3 修复 +4 攻击力显示问题)
        meta.isUnbreakable = true

        // 【新增】如果拿出来的是弩，默认给它附魔
        if (item.type == Material.CROSSBOW) {
            // 【修改点】针对 tingchao 给予 3 级快速装填
            val quickChargeLevel = if (id == "tingchao") 3 else 2
            meta.addEnchant(org.bukkit.enchantments.Enchantment.MULTISHOT, 1, true)
            meta.addEnchant(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, quickChargeLevel, true)
        }

        // 【关键修复】显式设置空属性修改器，清除原版属性
        meta.setAttributeModifiers(ArrayListMultimap.create())

        // 隐藏常规属性和 1.21+ 的额外提示
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_ENCHANTS)

        item.itemMeta = meta
        return item
    }

    val allIds: Set<String>
        get() = loadedWeapons.keys

    fun getNameById(id: String): String? {
        val data = loadedWeapons[id]
        return if (data != null && data.display != null) {
            ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', data.display!!))
        } else {
            null
        }
    }

    // 【删除点】
    // 原有的 fun getLoadedWeapons(): Map<String, WeaponData> 已被删除。
    // 因为 val loadedWeapons 属性已经自动生成了该方法。

    companion object {
        // 获取稀有度显示的星星
        fun getRarityStars(rarity: Int): String {
            val sb = StringBuilder()
            val color: String = when (rarity) {
                1 -> "§f" // 白
                2 -> "§a" // 绿
                3 -> "§9" // 蓝
                4 -> "§d" // 粉
                5 -> "§e" // 黄
                6 -> "§c" // 红
                else -> "§7"
            }
            sb.append(color)
            for (i in 0 until rarity) {
                sb.append("★")
            }
            return sb.toString()
        }
    }

    // 嵌套类 (默认是 static 的)
    class WeaponData(var id: String, sec: ConfigurationSection) {
        var display: String? = sec.getString("display", "Weapon")
        var material: Material = Material.matchMaterial(sec.getString("material", "STONE")!!) ?: Material.STONE
        var customModelData: Int = sec.getInt("custom_model_data", 0)
        var lore: List<String>? = sec.getStringList("lore")
        @JvmField var reqJob: Int = sec.getInt("req_job", -1)
        @JvmField var reqLv: Int = sec.getInt("req_lv", 1)
        @JvmField var activateSlot: Int = sec.getInt("activate_slot", 0)
        @JvmField var activeLoreLine: String = sec.getString("active_lore_line", "请放在快捷栏第一格激活")!!

        // 稀有度
        @JvmField var rarity: Int = sec.getInt("rarity", 1)

        // 【新增】灵力回复数值 (默认 0.0 代表不回蓝)
        @JvmField var manaRegen: Double = 0.0

        @JvmField var stats: MutableMap<String, Double> = HashMap()

        init {
            val statSec = sec.getConfigurationSection("stats")
            if (statSec != null) {
                for (key in statSec.getKeys(false)) {
                    stats[key] = statSec.getDouble(key)
                }
                // 【新增】提取灵力回复属性
                this.manaRegen = stats.getOrDefault("mana_regen", 0.0)
            }
        }
    }
}
