package com.hjh_database.dungeon.zhenyao

import kotlin.math.*

/** 无实体的几何、锁血和计时规则；服务端碰撞/环境伤害与测试共同使用。 */
internal object TowerFinalRules {
    const val COOLDOWN = 300L
    const val HALF_HEALTH = 5000.0
    const val EYE_DURATION = 900L
    const val NORMAL_INTERVAL = 20L
    val DEMOLITION_FLOORS = (10 downTo 3).toList()
    const val PHASE_TWO_SPEED_MULTIPLIER = 1.2
    const val PHASE_TWO_ATTRIBUTE_MULTIPLIER = 1.2
    const val COLLAPSE_HEALTH = 8000.0
    const val DASH_BASE_SPEED = 1.2
    const val BEAM_CHARGE_TICKS = 60L
    const val BEAM_LOCK_TICKS = 20L
    const val BEAM_ARM_TICKS = 10L
    const val BEAM_TRACK_SPEED = .4
    const val BEAM_LENGTH = 200.0
    const val SWORD_FLIGHT_SPEED = .5
    const val SWORD_TRACK_TICKS = 100L
    const val SWORD_COUNT = 5
    const val FLOOD_LAYER_TICKS = 1200L
    const val FLOOD_CYCLE_TICKS = FLOOD_LAYER_TICKS * 9
    const val ORBS_PER_FLOOR = 20
    fun orbHeightRange(floor: Int): ClosedFloatingPointRange<Double> {
        require(floor in 2..10)
        val low = 5.5 + (floor - 2) * 15
        return low..min(low + 13.0, 132.0)
    }
    fun swordStep(remaining: Double) = remaining.coerceIn(0.0, SWORD_FLIGHT_SPEED)
    fun collapseHealth(elapsed: Long): Double = HALF_HEALTH + (COLLAPSE_HEALTH - HALF_HEALTH) *
        (elapsed.toDouble() / (DEMOLITION_FLOORS.size * 20)).coerceIn(0.0, 1.0)
    fun attackDamage(base: Double, transformed: Boolean, empoweredNormal: Boolean = false) =
        base * (if (transformed) PHASE_TWO_ATTRIBUTE_MULTIPLIER else 1.0) * (if (empoweredNormal) 1.1 else 1.0)
    fun movementSpeed(base: Double, transformed: Boolean) = base * if (transformed) PHASE_TWO_SPEED_MULTIPLIER else 1.0
    fun beamUpdatesChargeAim(elapsed: Long) = elapsed < BEAM_CHARGE_TICKS - BEAM_LOCK_TICKS
    fun beamIsArmed(elapsed: Long) = elapsed >= BEAM_ARM_TICKS
    fun cappedDamage(health: Double, damage: Double, transformed: Boolean): Double =
        if (transformed) damage else damage.coerceAtMost((health - HALF_HEALTH).coerceAtLeast(0.0))

    // 每60秒侵染一层，最后5秒推进；充盈九层后吸收妖气并从底部重新凝聚。
    fun floodHeight(ticks: Long): Double {
        val time = ticks.coerceAtLeast(0) % FLOOD_CYCLE_TICKS
        val completed = time / FLOOD_LAYER_TICKS
        val fraction = ((time % FLOOD_LAYER_TICKS - (FLOOD_LAYER_TICKS - 100)) / 100.0).coerceIn(0.0, 1.0)
        val from = 5.0 + completed * 15
        val to = (from + 15).coerceAtMost(134.0)
        return from + (to - from) * fraction
    }
    fun insideOctagon(x: Double, z: Double, radius: Double): Boolean {
        val dx = abs(x + 1001.5); val dz = abs(z - 3006.5)
        return max(dx + (sqrt(2.0) - 1) * dz, dz + (sqrt(2.0) - 1) * dx) <= radius
    }
    fun remainingEyesPenalty(remaining: Int) = remaining.coerceIn(0, 4) * 50.0
}

internal class TowerFloodCycle {
    private var absorbed = 0L
    fun absorb(ticks: Long): Boolean {
        val completed = ticks.coerceAtLeast(0) / TowerFinalRules.FLOOD_CYCLE_TICKS
        if (completed <= absorbed) return false
        absorbed = completed
        return true
    }
}

/** 每把追踪计时单独开始，收剑前不能启动下一把或结束整项技能。 */
internal class TowerSwordSequence {
    var completed = 0
        private set
    private var trackingSince = 0L
    var collecting = false
        private set
    val finished get() = completed >= TowerFinalRules.SWORD_COUNT
    fun startTracking(now: Long) { check(!finished && !collecting); trackingSince = now }
    fun update(now: Long): Boolean {
        if (!finished && now - trackingSince >= TowerFinalRules.SWORD_TRACK_TICKS) collecting = true
        return collecting
    }
    fun collected() { check(collecting && !finished); completed++; collecting = false }
}

/** 五个大技能共用冷却；预警、飞行和未结算机制期间不计时。重复结算不会延后冷却。 */
internal class TowerSkillCooldown {
    private var settledAt: Long? = null
    fun start() { settledAt = null }
    fun settle(tick: Long) { if (settledAt == null) settledAt = tick }
    fun isReady(tick: Long) = settledAt?.let { tick - it >= TowerFinalRules.COOLDOWN } ?: false
}

internal enum class YinYangResult { RUNNING, SUCCESS, PUNISH }
internal class YinYangWindow(private val started: Long) {
    var exposedAt: Long? = null
        private set
    private val dead = hashSetOf<Int>()
    private var punished = false
    fun update(tick: Long, distance: Double): YinYangResult {
        if (punished) return YinYangResult.PUNISH
        if (dead.size == 2) return YinYangResult.SUCCESS
        if (tick - started >= 600 || exposedAt?.let { tick - it >= 200 } == true) return YinYangResult.PUNISH
        if (dead.isEmpty()) {
            if (distance <= 5 && exposedAt == null) exposedAt = tick
            if (distance > 5) exposedAt = null
        }
        return YinYangResult.RUNNING
    }
    fun death(index: Int, tick: Long): YinYangResult {
        if (exposedAt == null || tick - started >= 600 || tick - exposedAt!! >= 200) punished = true
        dead.add(index)
        return when { punished -> YinYangResult.PUNISH; dead.size == 2 -> YinYangResult.SUCCESS; else -> YinYangResult.RUNNING }
    }
}
