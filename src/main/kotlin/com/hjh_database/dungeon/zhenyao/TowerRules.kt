package com.hjh_database.dungeon.zhenyao

import java.util.UUID

/** 游戏编号从二层开始；此文件不依赖 Bukkit，波次规则可独立回归验证。 */
internal data class TowerFloor(
    val number: Int, val name: String, val types: List<String>, val waves: Int,
    val damage: Double, val health: Double, val armor: Double, val speed: Double
) {
    val feetY get() = 5.0 + (number - 2) * 15
    val totalMobs get() = waves * 10
    fun mobId(type: String, strong: Boolean) = "zhenyao_${number}_${type}_${if (strong) "strong" else "normal"}"
}

internal object TowerFloors {
    val all = listOf(
        TowerFloor(2, "坤之地", listOf("zombie", "spider"), 1, 22.0, 180.0, 25.0, .23),
        TowerFloor(3, "艮之山", listOf("spider", "skeleton"), 1, 26.0, 220.0, 30.0, .24),
        TowerFloor(4, "坎之水", listOf("spider", "zombie", "magma_cube"), 1, 30.0, 260.0, 35.0, .25),
        TowerFloor(5, "巽之风", listOf("skeleton", "magma_cube"), 2, 34.0, 300.0, 40.0, .26),
        TowerFloor(6, "震之雷", listOf("magma_cube", "zombie"), 2, 38.0, 350.0, 45.0, .27),
        TowerFloor(7, "离之火", listOf("blaze", "magma_cube"), 2, 42.0, 400.0, 50.0, .28),
        TowerFloor(8, "兑之泽", listOf("husk", "skeleton"), 3, 46.0, 450.0, 55.0, .29),
        TowerFloor(9, "乾之天", listOf("wither_skeleton", "skeleton"), 3, 50.0, 500.0, 60.0, .30)
    )
    fun get(number: Int) = all.single { it.number == number }
    fun inEntry(x: Double, y: Double, z: Double) =
        x >= -1047 && x < -1043 && z >= 3013 && z < 3018 && y >= 6 && y <= 6.6
    fun inAdvanceCircle(x: Double, y: Double, z: Double, feetY: Double): Boolean =
        kotlin.math.abs(y - feetY) <= 1.5 &&
            (x + 992.5) * (x + 992.5) + (z - 3006.5) * (z - 3006.5) <= 25.0
}

internal enum class TowerBattleAction { WAIT, SPAWN, COMPLETE, TIMEOUT }

internal class TowerBattle(private val waveLimit: Int) {
    init { require(waveLimit in 1..3) }
    val alive = linkedMapOf<UUID, Int>()
    private val spawned = hashSetOf<UUID>()
    var wavesSpawned = 0
        private set
    var kills = 0
        private set
    private var startTick = -1L
    private var lastWaveTick = -1L

    fun remainingTicks(tick: Long) = if (startTick < 0) 6000L else (6000 - (tick - startTick)).coerceAtLeast(0)
    fun action(tick: Long): TowerBattleAction = when {
        wavesSpawned == waveLimit && alive.isEmpty() -> TowerBattleAction.COMPLETE
        startTick >= 0 && remainingTicks(tick) == 0L -> TowerBattleAction.TIMEOUT
        wavesSpawned == 0 || wavesSpawned < waveLimit &&
            (alive.values.none { it == wavesSpawned } || tick - lastWaveTick >= 400) -> TowerBattleAction.SPAWN
        else -> TowerBattleAction.WAIT
    }

    fun addWave(ids: List<UUID>, tick: Long) {
        check(action(tick) == TowerBattleAction.SPAWN)
        require(ids.size == 10 && ids.toSet().size == 10 && ids.none { it in spawned })
        if (startTick < 0) startTick = tick
        lastWaveTick = tick
        wavesSpawned++
        ids.forEach { alive[it] = wavesSpawned; spawned.add(it) }
    }

    fun killed(id: UUID): Boolean {
        if (alive.remove(id) == null) return false
        kills++
        return true
    }
}
