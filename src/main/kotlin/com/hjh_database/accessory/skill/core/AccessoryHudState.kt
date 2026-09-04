package com.hjh_database.accessory.skill.core

/** 客户端饰品 HUD 当前需要展示的附加数值。 */
enum class AccessoryHudValueKind(val id: Int) {
    NONE(0),
    ARROWS(1),
    STORED_HEALTH(2),
    MOUNTAIN_STACKS(3),
    ELEMENT_MARK(4)
}

/**
 * 技能运行时状态的只读快照。
 *
 * 冷却和持续时间使用服务端绝对截止时间保存；真正发包时再换算成剩余时间，
 * 客户端便可以在两个状态包之间平滑播放原版冷却遮罩和持续时间条。
 */
data class AccessorySkillHudState(
    val cooldownEndMillis: Long = 0L,
    val cooldownDurationMillis: Long = 0L,
    val valueKind: AccessoryHudValueKind = AccessoryHudValueKind.NONE,
    val currentValue: Int = 0,
    val maxValue: Int = 0,
    val refluxEnabled: Boolean? = null,
    val elementMark: String? = null,
    val effectEndMillis: Long = 0L,
    val effectDurationMillis: Long = 0L
)

/** 已通过职业、等级与槽位判定的职业饰品及其 HUD 状态。 */
data class ActiveAccessoryHudState(
    val accessoryId: String,
    val materialId: String,
    val customModelData: Int,
    val state: AccessorySkillHudState
)
