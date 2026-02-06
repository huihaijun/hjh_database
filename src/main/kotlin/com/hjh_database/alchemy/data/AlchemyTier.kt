package com.hjh_database.alchemy.data

import org.bukkit.Material

// 必须包含 displayName, levelOffset 和 icon 三个属性
enum class AlchemyTier(val displayName: String, val levelOffset: Int, val icon: Material) {
    LOW("§f初级", 0, Material.POTION),   // 初级图标：铁粒
    MID("§b中级", 3, Material.POTION),   // 中级图标：金粒
    HIGH("§6高级", 6, Material.POTION);      // 高级图标：钻石
}