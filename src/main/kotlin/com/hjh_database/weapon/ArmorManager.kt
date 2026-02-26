package com.hjh_database.weapon

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
import java.util.ArrayList
import java.util.HashMap

class ArmorManager(private val plugin: Hjh_database) {
    // 保持 public final Map 的可见性
    val loadedArmors: MutableMap<String, ArmorData> = HashMap()

    private val keyId: NamespacedKey
    private val armorKey: NamespacedKey
    private var file: File? = null
    private var config: FileConfiguration? = null

    init {
        this.keyId = NamespacedKey(plugin, "resource_id")
        this.armorKey = NamespacedKey(plugin, "armor_id")
        reload()
    }

    fun reload() {
        loadedArmors.clear()
        file = File(plugin.dataFolder, "armors.yml")
        if (!file!!.exists()) {
            plugin.saveResource("armors.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file!!)

        val sec = config!!.getConfigurationSection("armors")
        if (sec != null) {
            for (key in sec.getKeys(false)) {
                val itemSec = sec.getConfigurationSection(key)
                if (itemSec != null) {
                    loadedArmors[key] = ArmorData(key, itemSec)
                }
            }
        }
        plugin.logger.info("ArmorManager 加载了 " + loadedArmors.size + " 件防具。")
    }

    /**
     * 【新增】刷新玩家身上防具的 Lore 状态
     * 显示是否已激活或条件不符
     */
    fun refreshPlayerArmors(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 获取玩家身上的装备内容
        val armorContents = player.inventory.armorContents
        var changed = false

        for (i in armorContents.indices) {
            val item = armorContents[i]
            if (item == null || !item.hasItemMeta()) continue

            val meta = item.itemMeta ?: continue

            // 识别防具 ID
            var id = meta.persistentDataContainer.get(armorKey, PersistentDataType.STRING)
            if (id == null) {
                // 兼容旧 ID key
                id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
            }
            if (id == null) continue // 不是本系统的防具

            val aData = loadedArmors[id] ?: continue

            // === 逻辑判断区域 (完全保持原样) ===
            var isActive = true
            val statusLore: MutableList<String> = ArrayList()

            // 1. 检查职业
            if (aData.reqJob != -1) {
                if (data.job == null || data.job != aData.reqJob) {
                    isActive = false
                    statusLore.add(ChatColor.RED.toString() + "⚠ 职业不符")
                }
            }
            // 2. 检查等级
            if ((data.lv ?: 0) < aData.reqLv) {
                isActive = false
                statusLore.add(ChatColor.RED.toString() + "⚠ 等级不足 (" + data.lv + "/" + aData.reqLv + ")")
            }

            // === Lore 构建区域 (仅在此处修改) ===
            // 顺便刷新一下名字，防止配置改了名字不生效
            if (aData.display != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', aData.display!!))
            }

            val newLore: MutableList<String> = ArrayList()

            // 【新增】在第一行插入稀有度星星
            // 获取颜色代码
            var colorCode = "§7" // 默认为灰
            when (aData.rarity) {
                1 -> colorCode = "§f" // 白
                2 -> colorCode = "§a" // 绿
                3 -> colorCode = "§9" // 蓝
                4 -> colorCode = "§d" // 粉
                5 -> colorCode = "§e" // 黄
                6 -> colorCode = "§c" // 红
            }

            // 拼接：颜色 + 文字 + 星星 (WeaponManager.getRarityStars自带颜色，所以这里前面拼一次颜色即可)
            // 注意：这里调用了 WeaponManager 的伴生对象方法
            newLore.add(colorCode + "稀有度: " + WeaponManager.getRarityStars(aData.rarity))

            // 【保留】插入配置文件里的 Lore
            if (aData.lore != null) {
                for (line in aData.lore!!) {
                    newLore.add(ChatColor.translateAlternateColorCodes('&', line))
                }
            }

            // 【保留】插入状态提示
            if (isActive) {
                newLore.add(" ")
                newLore.add(ChatColor.GREEN.toString() + "✔ 已激活 - 防御生效中")
            } else {
                newLore.add(" ")
                newLore.addAll(statusLore)
            }

            // 【新增】动态刷新时也检查并应用皮革颜色
            if (meta is org.bukkit.inventory.meta.LeatherArmorMeta && aData.color != null) {
                meta.setColor(aData.color)
            }

            // 【修复】强行给旧装备补上最全的隐藏标签
            meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_ARMOR_TRIM,
                ItemFlag.HIDE_ENCHANTS
            )

            // 【修复】清洗旧装备的原版默认属性残留
            meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()

            meta.lore = newLore
            if (aData.customModelData != 0) {
                meta.setCustomModelData(aData.customModelData) // 顺手刷新一下模型数据
            }

            item.itemMeta = meta
            changed = true
        }

        // 如果修改了物品 Meta，需要重新设置回去
        if (changed) {
            player.inventory.setArmorContents(armorContents)
        }
    }

    /**
     * 计算所有防具属性
     */
    fun calculateArmorStats(player: Player, data: PlayerData): Map<String, Double> {
        val totalStats: MutableMap<String, Double> = HashMap()
        // 1. 定义稀有度累加变量
        var totalRarity = 0.0

        // 遍历身上 4 件装备
        val armorContents = player.inventory.armorContents ?: return totalStats

        for (item in armorContents) {
            if (item == null || !item.hasItemMeta()) continue

            // 获取ID (优先 armor_id，其次 resource_id)
            val meta = item.itemMeta ?: continue
            var id = meta.persistentDataContainer.get(armorKey, PersistentDataType.STRING)
            if (id == null) id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
            if (id == null) continue

            val aData = loadedArmors[id] ?: continue

            // === 校验激活条件 ===
            // 1. 等级不够，跳过
            if ((data.lv ?: 0) < aData.reqLv) continue
            // 2. 职业不符，跳过
            if (aData.reqJob != -1 && (data.job == null || data.job != aData.reqJob)) continue

            // ★【新增】这里是激活成功的地方，把稀有度记入 List
            data.rarityDetails.add(aData.rarity)
            // ★ 修改点1：累加稀有度
            totalRarity += aData.rarity.toDouble()
            // ★ 修改点2：累加所有属性
            for ((key, value) in aData.stats) {
                totalStats.merge(key, value) { a: Double, b: Double -> a + b }
            }
        }

        // ★ 修改点3：把计算好的总稀有度放入 Map 返回
        totalStats["total_rarity"] = totalRarity

        return totalStats
    }

    /**
     * 【新增】获取纯净版护甲 (用于配方保存和指令获取)
     */
    fun getItemStack(id: String): ItemStack? {
        val data = loadedArmors[id] ?: return null

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

        // 2. 写入 NBT (注意：护甲用的是 armorKey)
        meta.persistentDataContainer.set(keyId, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(armorKey, PersistentDataType.STRING, id)

        // 3. 属性标记 (1.21.3 推荐加入 HIDE_ADDITIONAL_TOOLTIP)
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_DYE,           // 【关键】隐藏“颜色: #FFFFFF”
            ItemFlag.HIDE_ARMOR_TRIM,    // 隐藏盔甲纹饰
            ItemFlag.HIDE_ENCHANTS       // 隐藏附魔
        )

        // 直接塞入一个空的属性表，彻底干掉原版默认的“穿在XX上: +X 护甲”
        meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()

        // 【新增】如果是皮革材质，且配置了颜色，强转 Meta 并染色
        if (meta is org.bukkit.inventory.meta.LeatherArmorMeta && data.color != null) {
            meta.setColor(data.color)
        }
        item.itemMeta = meta
        return item
    }

    private fun checkRequirements(player: Player, data: PlayerData, armor: ArmorData): Boolean {
        if (armor.reqJob != -1) {
            if (data.job == null || data.job != armor.reqJob) return false
        }
        if ((data.lv ?: 0) < armor.reqLv) return false
        return true
    }

    val allIds: Set<String>
        get() = loadedArmors.keys

    fun getNameById(id: String): String? {
        val data = loadedArmors[id]
        return if (data != null && data.display != null) {
            ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', data.display!!))
        } else {
            null
        }
    }

    class ArmorData(var id: String, sec: ConfigurationSection) {
        var display: String? = sec.getString("display", "Armor")
        var material: Material = Material.matchMaterial(sec.getString("material", "LEATHER_CHESTPLATE")!!) ?: Material.LEATHER_CHESTPLATE
        var customModelData: Int = sec.getInt("custom_model_data", 0)
        var lore: List<String>? = sec.getStringList("lore")
        @JvmField var reqJob: Int = sec.getInt("req_job", -1)
        @JvmField var reqLv: Int = sec.getInt("req_lv", 1)
        @JvmField var activeLoreLine: String = sec.getString("active_lore_line", "条件不符")!!
        @JvmField var rarity: Int = sec.getInt("rarity", 1) // <--- 【1】新增字段, 【2】读取配置，默认为1
        @JvmField var stats: MutableMap<String, Double> = HashMap()

        // 【新增】护甲颜色字段
        var color: org.bukkit.Color? = null

        init {
            val statSec = sec.getConfigurationSection("stats")
            if (statSec != null) {
                for (key in statSec.getKeys(false)) {
                    stats[key] = statSec.getDouble(key)
                }
            }
            // 【新增】解析 yaml 中的颜色配置
            val colorStr = sec.getString("color")
            if (colorStr != null) {
                try {
                    if (colorStr.startsWith("#") && colorStr.length == 7) {
                        // 解析十六进制 (如 #FF0000)
                        val r = colorStr.substring(1, 3).toInt(16)
                        val g = colorStr.substring(3, 5).toInt(16)
                        val b = colorStr.substring(5, 7).toInt(16)
                        color = org.bukkit.Color.fromRGB(r, g, b)
                    } else if (colorStr.contains(",")) {
                        // 解析 RGB (如 255,0,0)
                        val rgb = colorStr.split(",")
                        if (rgb.size >= 3) {
                            color = org.bukkit.Color.fromRGB(rgb[0].trim().toInt(), rgb[1].trim().toInt(), rgb[2].trim().toInt())
                        }
                    }
                } catch (e: Exception) {
                    // 解析失败时优雅降级
                    println("防具 $id 的颜色格式错误: $colorStr，请使用 #RRGGBB 或 R,G,B")
                }
            }
        }
    }
}