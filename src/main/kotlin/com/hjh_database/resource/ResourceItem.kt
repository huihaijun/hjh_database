package com.hjh_database.resource

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

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
    val isUnbreakable: Boolean
) {

    // === 属性初始化逻辑 (自动处理颜色和默认值) ===

    // 如果传入的材质为空，自动默认为 STONE
    val material: Material = rawMaterial ?: Material.STONE

    // 自动处理名称颜色
    val name: String = ChatColor.translateAlternateColorCodes('&', rawName)

    // 自动处理 Lore 颜色，如果为空则初始化为空列表
    val lore: MutableList<String> = rawLore?.map {
        ChatColor.translateAlternateColorCodes('&', it)
    }?.toMutableList() ?: ArrayList()

    /**
     * 【修复重点】
     * 显式定义公开属性 customModelData
     * 其他文件调用 res.customModelData 时会走这里的 get() 逻辑
     * 逻辑保持：如果是 null 则返回 0
     */
    val customModelData: Int
        get() = _customModelData ?: 0

    // === 次要构造函数：从 Config 读取 ===
    constructor(id: String?, sec: ConfigurationSection) : this(
        id = id,
        // 从配置读取 Material，默认为 STONE
        rawMaterial = Material.matchMaterial(sec.getString("material", "STONE") ?: "STONE"),
        // 从配置读取 Name，默认为 "未知物品"
        rawName = sec.getString("name", "&f未知物品") ?: "&f未知物品",
        // 从配置读取 Lore List
        rawLore = sec.getStringList("lore"),
        // 检查配置中是否存在 custom_model_data，存在则读取，不存在则为 null
        _customModelData = if (sec.contains("custom_model_data")) sec.getInt("custom_model_data") else null,
        // 读取不可破坏属性
        isUnbreakable = sec.getBoolean("unbreakable", false)
    )

    // === 功能方法 ===

    /**
     * 判断是否有自定义模型数据
     * 逻辑保持：判断原始存储是否为 null
     */
    fun hasCustomModelData(): Boolean {
        return _customModelData != null
    }

    /**
     * 如果你需要保留旧的方法调用方式，可以保留此方法
     * 但现在的推荐用法是直接使用属性：item.customModelData
     */
    fun getCustomModelDataMethod(): Int {
        return customModelData
    }
}