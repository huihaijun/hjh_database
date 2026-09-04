package com.hjh_database.spawner

import org.bukkit.Location

/**
 * 极简化的刷怪笼数据
 */
data class SpawnerData(
    var mobId: String = "test_zombie", // 默认为测试怪
    var targetLocation: Location? = null // 如果有值，则在定点生成；如果为null，在方块周围生成
)