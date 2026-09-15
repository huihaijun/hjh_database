package com.hjh_database.dungeon.zhenyao

/** Normalized trajectories share exact animation landing/growth endpoints. */
internal object ChiyouPresentationRules {
    private fun smooth(t: Double): Double = t.coerceIn(0.0,1.0).let { it*it*(3-2*it) }
    /** Predicate checks the full descent column, not ground support at the airborne endpoint. */
    fun chooseIntroLift(clearColumn: (Double) -> Boolean): Double =
        (16 downTo 0).map { it/4.0 }.firstOrNull(clearColumn) ?: 0.0
    fun introHeight(ticks: Long, lift: Double = 4.0) = lift.coerceIn(0.0,4.0)*(1.0-smooth(ticks/40.0))
    fun collapseScale(ticks: Long) = 1.0+smooth(ticks/120.0)
}
