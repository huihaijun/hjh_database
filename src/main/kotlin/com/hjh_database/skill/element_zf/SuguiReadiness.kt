package com.hjh_database.skill.element_zf

internal enum class SuguiReadiness { UNAVAILABLE, SAME_TICK, NO_MANA, NO_SPACE, READY }

/** 实际施放与 HUD 颜色共用判定，避免只检查“有返还记录”就错误亮蓝。 */
internal fun suguiReadiness(
    validReceipt: Boolean,
    sameTick: Boolean,
    activeFurnace: Boolean,
    availableMana: Double,
    debit: Double,
    canStoreElement: Boolean
): SuguiReadiness = when {
    !validReceipt -> SuguiReadiness.UNAVAILABLE
    sameTick -> SuguiReadiness.SAME_TICK
    !activeFurnace -> SuguiReadiness.UNAVAILABLE
    !debit.isFinite() || !availableMana.isFinite() || availableMana < debit -> SuguiReadiness.NO_MANA
    !canStoreElement -> SuguiReadiness.NO_SPACE
    else -> SuguiReadiness.READY
}
