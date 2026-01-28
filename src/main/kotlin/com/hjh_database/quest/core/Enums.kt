package com.hjh_database.quest.core

enum class QuestType(val displayName: String) {
    MAIN("主线任务"),
    SIDE("支线任务"),
    BOUNTY("赏金任务"),
    CHALLENGE("挑战任务")
}

enum class QuestStatus {
    LOCKED,      // 未接取/不可见
    IN_PROGRESS, // 进行中
    COMPLETED    // 已完成
}