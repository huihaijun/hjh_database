package com.hjh_database.dungeon.huomo

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.boss.BossBar
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

internal enum class HuomoDifficulty { TEST }
internal enum class HuomoPhase { INTRO, FIGHT, VICTORY, CLEANUP }
internal enum class HuomoSkill { BREATH, MARK, CHAIN, ABSORB, VEINS, COMPRESS, WORLD_BURN }
internal class HuomoCast(val skill: HuomoSkill, val start: Long, val id: Long) {
    var target: UUID? = null
    var point: Location? = null
    var projectile: Fireball? = null
    var shot = 0
    var shotStart = start
    var falling = false
    var fallTicks = 0
    val chosen = hashSetOf<UUID>()
    val seeds = linkedMapOf<UUID, Location>()
    val veins = mutableListOf<VeinProgress>()
    val standing = hashMapOf<Pair<UUID, Int>, Long>()
    val protectedAttacks = hashSetOf<Long>()
    val jumps = hashSetOf<UUID>()
    var nextStage: Int? = null
    var activated = false
}
internal data class HuomoFire(val point: Location, val expires: Long)
internal class HuomoPillars(val points: List<Location>, val start: Long) { val hit = hashSetOf<UUID>() }
internal class HuomoSession(val world: World, val previous: Map<UUID, Int>, val difficulty: HuomoDifficulty) {
    val id = UUID.randomUUID().toString()
    val players = previous.keys.toMutableSet()
    val entities = hashSetOf<UUID>()
    val mobs = hashSetOf<UUID>()
    val chunks = hashSetOf<Pair<Int, Int>>()
    val born = hashMapOf<UUID, Long>()
    val furnace = Furnace()
    val recovery = FurnaceRecovery()
    val coreThermal = CoreThermalResponse()
    val veinCycle = EscalatingVeinCycle()
    val milestones = HuomoMilestones()
    val shelters = ShelterDurability()
    val pendingStages = ArrayDeque<Int>()
    var pendingShifts = 0
    var shiftStart: Long? = null
    var tick = 0L
    var phaseStart = 0L
    var phase = HuomoPhase.INTRO
    var stage = 0
    var cycle = 0
    var cast: HuomoCast? = null
    var serial = 0L
    var cooldownStart = 0L
    var lastSpawn = 0L
    var combatStart = 0L
    var pendingHeat = 0
    var nextPillars = 0L
    val fires = mutableListOf<HuomoFire>()
    val pillars = mutableListOf<HuomoPillars>()
    val breathHit = hashMapOf<UUID, Long>()
    val lastJump = hashMapOf<UUID, Long>()
    val shelterHud = hashMapOf<UUID, Pair<Int, Int>>()
    var coreIndex = 0
    lateinit var core: LivingEntity
    var healthBar: BossBar? = null
    var heatBar: BossBar? = null
    var skillBar: BossBar? = null
    var task: BukkitTask? = null
    var cleaning = false
    var internalDamage = false
}
