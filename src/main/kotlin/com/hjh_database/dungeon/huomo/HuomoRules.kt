package com.hjh_database.dungeon.huomo

import kotlin.math.floor

/** 时间均以服务端 tick 计，炉温内部使用 1/3 个百分点，避免每秒累加的浮点漂移。 */
internal class Furnace {
    private var thirds = 0
    val heat get() = thirds / 3.0
    val full get() = thirds == 300
    val tier get() = thirds / 15
    val armorReduced get() = thirds >= 120
    val slowed get() = thirds >= 240
    val damageMultiplier get() = 1.0 + tier * .05
    /** 返回此次是否从非满温进入满温。 */
    fun addThirds(amount: Int): Boolean {
        val wasFull = full
        thirds = (thirds + amount).coerceIn(0, 300)
        return !wasFull && full
    }
    fun vent() { thirds = (thirds - 90).coerceAtLeast(0) }
    fun skillTicks(stage: Int) = ((if (stage == 3) 10 else 15) - thirds / 30).coerceAtLeast(6) * 20
    fun spawnTicks(players: Int): Int {
        require(players in 1..5)
        val base = intArrayOf(60, 50, 40, 30, 20)[players - 1]
        val minimum = intArrayOf(40, 35, 30, 20, 15)[players - 1]
        return (base - tier * 2).coerceAtLeast(minimum) * 20
    }
}

/** 血线只向前；回血不会重复触发曾经通过的阶段/8%换位。 */
internal class HuomoMilestones {
    var reachedStage = 0
        private set
    private var shifts = 0
    data class Crossings(val stages: List<Int>, val shifts: Int)
    fun observe(health: Double, maximum: Double = 15000.0): Crossings {
        if (health <= 0.0) return Crossings(emptyList(), 0)
        val ratio = (health / maximum).coerceIn(0.0, 1.0)
        val stages = mutableListOf<Int>()
        val gates = doubleArrayOf(.70, .35, .10)
        while (reachedStage < 3 && ratio <= gates[reachedStage] + 1e-9) stages += ++reachedStage
        val crossed = floor(((1.0 - ratio) / .08) + 1e-8).toInt().coerceIn(0, 12)
        val count = (crossed - shifts).coerceAtLeast(0)
        shifts = maxOf(shifts, crossed)
        return Crossings(stages, count)
    }
}

/** 每个 shelter/event 只消耗一次；第三次保护仍有效。 */
internal class ShelterDurability {
    val used = IntArray(2)
    private val protected = Array(2) { hashSetOf<Long>() }
    fun protect(shelter: Int, attack: Long): Boolean {
        if (attack in protected[shelter]) return true
        if (used[shelter] >= 3) return false
        protected[shelter] += attack
        used[shelter]++
        return true
    }
    fun forget(attack: Long) = protected.forEach { it.remove(attack) }
}

internal class VeinProgress(val length: Int) {
    var head = 0
        private set
    var blocked = false
        private set
    var arrived = false
        private set
    val resolved get() = blocked || arrived
    fun block(): Boolean {
        if (resolved) return false
        blocked = true
        return true
    }
    /** 只在首次到达终点时返回 true。 */
    fun advance(): Boolean {
        if (resolved) return false
        head++
        if (head >= length - 1) { head = length - 1; arrived = true; return true }
        return false
    }
}

internal object HuomoTiming {
    const val BREATH_WARNING = 80
    const val BREATH_SWEEP = 200
    const val VEIN_WARNING = 120
    const val VEIN_STEP = 40
    const val COMPRESSION_WARNING = 60
    const val WORLD_BURN_WARNING = 100
    const val ABSORB_WARNING = 160
    const val ABSORB_WAIT = 200
    const val POLLUTION_RADIUS = 4.0
    const val JUMP_TOLERANCE = 10
    fun breathProgress(elapsed: Long): Double? =
        if (elapsed < BREATH_WARNING || elapsed >= BREATH_WARNING + BREATH_SWEEP) null
        else (elapsed - BREATH_WARNING).toDouble() / (BREATH_SWEEP - 1)
    fun veinAdvances(elapsed: Long) = elapsed > VEIN_WARNING && (elapsed - VEIN_WARNING) % VEIN_STEP == 0L
    fun compressionJumpAllowed(relativeJump: Long) = relativeJump in
        (COMPRESSION_WARNING - JUMP_TOLERANCE)..(COMPRESSION_WARNING + JUMP_TOLERANCE)
    fun markTrackingTicks(enhanced: Boolean) = if (enhanced) 60 else 100
    // 与玩家自由落体相近，略快；逐 tick 积分，落地爆炸前不重新追踪。
    fun fallSpeed(elapsedTicks: Int): Double = minOf(2.0, .10 * (elapsedTicks + 1))
    fun chainBaseDamage(z: Double): Double = 3.0 + 27.0 * ((919.0 - z) / (919.0 - 873.0)).coerceIn(0.0, 1.0)
}

internal object HuomoWaves {
    fun count(players: Int): Int { require(players in 1..5); return intArrayOf(5, 6, 6, 7, 8)[players - 1] }
    fun seedCap(players: Int): Int { require(players in 1..5); return when(players) { 1 -> 3; 2 -> 4; 3 -> 5; else -> Int.MAX_VALUE } }
    /** 仍使用原八个标记；空位不刷怪，普通波次保留四种类型，史莱姆只放在宽敞点。 */
    fun ids(widePositions: List<Int>, zombies: Boolean, players: Int = 5): List<String?> {
        val size = count(players)
        val result = MutableList<String?>(8) { null }
        if (zombies) { (0..7).shuffled().take(size).forEach { result[it] = "huomo_guard" }; return result }
        val four = listOf("huomo_guard", "huomo_archer", "huomo_pet", "huomo_spirit")
        val types = four + four.shuffled().take(size - 4)
        val petCount = types.count { it == "huomo_pet" }
        require(widePositions.distinct().size >= petCount)
        val pets = widePositions.distinct().shuffled().take(petCount)
        pets.forEach { result[it] = "huomo_pet" }
        val positions = (0..7).filter { it !in pets }.shuffled()
        types.filter { it != "huomo_pet" }.shuffled().forEachIndexed { i, type -> result[positions[i]] = type }
        return result
    }
}

internal class FurnaceRecovery {
    private val used = hashSetOf<Int>()
    fun claim(stage: Int): Boolean = stage in 0..2 && used.add(stage)
}

/** 返回炉温变化的三分之一百分点单位；泄压期间即便达到降温上限，也不转回受击升温。 */
internal class CoreThermalResponse {
    private var lastHeating = -60L
    private var reliefUntil = Long.MIN_VALUE
    private var accumulated = 0.0
    private var credited = 0
    fun relieving(tick: Long) = tick < reliefUntil
    fun startRelief(tick: Long) { reliefUntil = tick + 100; accumulated = 0.0; credited = 0 }
    fun hit(tick: Long, damage: Double): Int {
        if (!damage.isFinite() || damage <= 0.0) return 0
        if (relieving(tick)) {
            accumulated = (accumulated + damage).coerceAtMost(900.0)
            val steps = floor((accumulated + 1e-8) / 300.0).toInt().coerceAtMost(3)
            val change = (steps - credited) * -9
            credited = steps
            return change
        }
        if (tick - lastHeating < 60) return 0
        lastHeating = tick
        return 3
    }
}

/** 仅技能正常完成时推进；被转阶段打断的技能不计入下一轮。 */
internal class EscalatingVeinCycle {
    var marksRequired = 1
        private set
    private var marksCompleted = 0
    val nextIsVein get() = marksCompleted >= marksRequired
    fun completeMark() { marksCompleted++ }
    fun completeVein() { marksCompleted = 0; marksRequired++ }
}
