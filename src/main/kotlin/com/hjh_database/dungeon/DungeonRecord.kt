package com.hjh_database.dungeon

// 记录单个副本的数据
data class DungeonRecord(
    var clears: Int = 0, // 通关次数
    var opens: Int = 0, // 开箱次数
    var availableOpens: Int = 0, // 【新增】当前剩余的“可开箱次数”
    // 记录某物品掉落的总次数 (用于“仅能开出一次”的逻辑)
    var dropCounts: MutableMap<String, Int> = HashMap(),
    // 记录某物品距离上次开出已经垫了多少发 (用于保底机制)
    var opensSinceLastDrop: MutableMap<String, Int> = HashMap()
)
