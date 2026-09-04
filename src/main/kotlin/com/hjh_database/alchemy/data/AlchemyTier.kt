package com.hjh_database.alchemy.data

import org.bukkit.Material

// 必须包含 displayName, levelOffset 和 icon 三个属性
enum class AlchemyTier(val displayName: String, val levelOffset: Int, val icon: Material) {
    LOW("§f初级", 0, Material.POTION),
    MID("§b中级", 3, Material.POTION),
    HIGH("§6高级", 6, Material.POTION);
}