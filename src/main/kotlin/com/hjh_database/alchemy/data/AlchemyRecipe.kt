package com.hjh_database.alchemy.data

import org.bukkit.inventory.ItemStack

data class AlchemyRecipe(
    val id: String,         // 配方唯一ID (对应效果ID)
    var displayName: String = "未命名丹药",

    // 三个等级的配置 (Map: Tier -> (Inputs, Result))
    val tierData: MutableMap<AlchemyTier, TierConfig> = HashMap(),

    // 限制条件
    var onlyDoctor: Boolean = false, // 仅医师
    var requiredLevel: Int = 0,      // 需要炼药法等级
    var sicknessTime: Int = 10,      // 药毒时间(秒)
    var colorHex: String = "#FFFFFF", // 颜色显示
    var baseExp: Int = 0 // 基础经验值 (默认0)
)

data class TierConfig(
    val ingredients: MutableList<ItemStack>, // 原材料 (最多5个)
    var result: ItemStack                    // 成品丹药
)