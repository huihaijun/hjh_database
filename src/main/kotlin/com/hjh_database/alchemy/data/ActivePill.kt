package com.hjh_database.alchemy.data

/**
 * 存储玩家身上正在生效的丹药信息
 */
data class ActivePill(
    val effectId: String,       // 对应 AlchemyEffect 的 ID
    val tier: AlchemyTier,      // 品质
    var remainingSeconds: Long, // 剩余持续时间 (秒)
    val startTime: Long = System.currentTimeMillis()
)