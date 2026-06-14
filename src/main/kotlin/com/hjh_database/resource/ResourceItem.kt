package com.hjh_database.resource

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import java.util.ArrayList

/**
 * 资源物品数据类
 * 已优化为 Kotlin 风格，逻辑与原版保持一致
 */
class ResourceItem(
    val id: String?,
    rawMaterial: Material?, // 构造参数：原始材质（可能为空）
    rawName: String,        // 构造参数：原始名称（未处理颜色）
    rawLore: List<String>?, // 构造参数：原始Lore（未处理颜色）
    private val _customModelData: Int?, // 私有属性：用于内部存储（区分 null 和 0）
    val isUnbreakable: Boolean,
    val rarity: Int = 0, // 默认为 0，表示没有稀有度
    val maxStackSize: Int? = null, // 【新增】可选的最大堆叠数
    val colorHex: String? = null, // 【新增】用于存储药水的 Hex 颜色

    val onlyDoctor: Boolean = false,
    val reqLevel: Int = 1,
    val baseExp: Int = 5,
    val sicknessTime: Int = 10,
    val hasSicknessTime: Boolean = false,
    val jianghuXindeValue: Int = 0
) {

    // === 属性初始化逻辑 (自动处理颜色和默认值) ===

    // 如果传入的材质为空，自动默认为 STONE
    val material: Material = rawMaterial ?: Material.STONE

    // 自动处理名称颜色
    val name: String = ChatColor.translateAlternateColorCodes('&', rawName)

    // 自动处理 Lore 颜色，并根据稀有度在最上方插入星星
    val lore: MutableList<String>

    init {
        // 1. 处理原始 Lore 颜色
        val tempLore = rawLore?.map {
            ChatColor.translateAlternateColorCodes('&', it)
        }?.toMutableList() ?: ArrayList()

        // 2. 如果有稀有度，插入到 Lore 的第一行 (索引 0)
        // 这样就会显示在 Name 下面，其他 Lore 之上
        if (rarity > 0) {
            tempLore.add(0, getRarityDisplay(rarity))
        }

        this.lore = tempLore
    }

    /**
     * 【属性】customModelData
     * 如果是 null 则返回 0
     */
    val customModelData: Int
        get() = _customModelData ?: 0

    // === 次要构造函数：从 Config 读取 ===
    constructor(id: String?, sec: ConfigurationSection) : this(
        id = id,
        rawMaterial = Material.matchMaterial(sec.getString("material", "STONE") ?: "STONE"),
        rawName = sec.getString("name", "&f未知物品") ?: "&f未知物品",
        rawLore = sec.getStringList("lore"),
        _customModelData = if (sec.contains("custom_model_data")) sec.getInt("custom_model_data") else null,
        isUnbreakable = sec.getBoolean("unbreakable", false),
        rarity = sec.getInt("rarity", 0),
        // 【新增】读取 max_stack_size，如果不配置则为 null
        maxStackSize = if (sec.contains("max_stack_size")) sec.getInt("max_stack_size") else null,
        // 【新增】从 yml 读取 color 字段
        colorHex = sec.getString("color"),
        reqLevel = sec.getInt("req_level", 1),
        onlyDoctor = sec.getBoolean("only_doctor", false),
        baseExp = sec.getInt("base_exp", 5),
        sicknessTime = sec.getInt("sickness_time", 10),
        hasSicknessTime = sec.contains("sickness_time"),
        jianghuXindeValue = sec.getInt("jianghu_xinde_value", 0)
    )

    // === 功能方法 ===

    /**
     * 判断是否有自定义模型数据
     */
    fun hasCustomModelData(): Boolean {
        return _customModelData != null
    }

    /**
     * 旧版兼容方法
     */
    fun getCustomModelDataMethod(): Int {
        return customModelData
    }

    /**
     * 生成稀有度显示行
     * [修改点]：将前缀颜色与星星颜色统一
     */
    private fun getRarityDisplay(level: Int): String {
        val color = when (level) {
            1 -> "§f" // 白
            2 -> "§a" // 绿
            3 -> "§9" // 蓝 (使用 §9)
            4 -> "§d" // 紫
            5 -> "§6" // 金
            else -> "§c" // 红 (6级及以上)
        }
        val sb = StringBuilder()

        // 先添加颜色代码，让后续的文字和星星颜色一致
        sb.append(color)
        sb.append("稀有度：")

        // 生成星星
        for (i in 0 until level) {
            sb.append("★")
        }

        return sb.toString()
    }
}
