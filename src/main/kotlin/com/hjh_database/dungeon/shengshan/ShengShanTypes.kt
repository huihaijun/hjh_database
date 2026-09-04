package com.hjh_database.dungeon.shengshan

import org.bukkit.Location
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.potion.PotionEffect
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

internal enum class ShengShanPhase { INTRO, GUESSING, COMBAT, CORE, ENDING }

internal enum class Trigram(
    val displayName: String,
    val mobId: String,
    val color: String,
    val spawn: (org.bukkit.World) -> Location
) {
    THUNDER("震雷之相·靐", "shengshan_thunder", "§e") ,
    SKY("乾天之相·晶", "shengshan_sky", "§6"),
    WATER("坎水之相·淼", "shengshan_water", "§9"),
    MOUNTAIN("艮山之相·芔", "shengshan_mountain", "§6"),
    FIRE("离火之相·焱", "shengshan_fire", "§c"),
    WIND("巽风之相·雾", "shengshan_wind", "§f"),
    SWAMP("兑泽之相·恶", "shengshan_swamp", "§5"),
    EARTH("坤地之相·垚", "shengshan_earth", "§8");

    constructor(displayName: String, mobId: String, color: String) : this(
        displayName,
        mobId,
        color,
        ::shengShanBossSpawn
    )
}

/** 玩家进入战斗场景、破阵回场及卦象间回场统一使用此点。 */
internal fun shengShanPlayerStart(world: org.bukkit.World): Location =
    Location(world, 3179.02, 131.0, -1839.64, 90.05f, 1.35f)

/** 八只卦象Boss统一出场点；3149.90°等价规范化为-90.10°。 */
internal fun shengShanBossSpawn(world: org.bukkit.World): Location =
    Location(world, 3148.61, 131.0, -1839.53, -90.10f, -2.40f)

/** 建筑与地面阵法使用Boss点正下方的可见地面中心。 */
internal fun shengShanMechanicCenter(world: org.bukkit.World, y: Double = 129.0): Location =
    Location(world, 3148.61, y, -1839.53, -90.10f, -2.40f)

/** 固定五个落点：每名玩家脚下一个，盈余从互不重叠的场地网格中补足。 */
internal fun spacedArenaTargets(
    world: org.bukkit.World,
    playerLocations: Collection<Location>,
    total: Int = 5,
    minSeparation: Double = 10.5
): List<Location> {
    val result = playerLocations.take(total).mapTo(ArrayList()) { Location(world, it.x, 129.0, it.z) }
    if (result.size >= total) return result
    val candidates = buildList {
        for (x in 3119..3179 step 6) for (z in -1869..-1809 step 6) {
            add(Location(world, x + .5, 129.0, z + .5))
        }
    }.shuffled().toMutableList()
    val minimumSquared = minSeparation * minSeparation
    while (result.size < total && candidates.isNotEmpty()) {
        val index = candidates.indices.maxByOrNull { candidateIndex ->
            val candidate = candidates[candidateIndex]
            result.minOfOrNull {
                val dx = candidate.x - it.x
                val dz = candidate.z - it.z
                dx * dx + dz * dz
            } ?: Double.MAX_VALUE
        } ?: break
        val candidate = candidates.removeAt(index)
        val separated = result.all {
            val dx = candidate.x - it.x
            val dz = candidate.z - it.z
            dx * dx + dz * dz >= minimumSquared
        }
        if (separated) result += candidate
    }
    return result
}

internal data class ShengShanSession(
    val worldName: String,
    val playerIds: MutableSet<UUID>,
    val entryPlayerCount: Int,
    val previousStatuses: MutableMap<UUID, Int>,
    val entityIds: MutableSet<UUID> = HashSet(),
    val tasks: MutableSet<BukkitTask> = HashSet(),
    val bars: MutableSet<BossBar> = HashSet(),
    val remaining: MutableList<Trigram> = Trigram.entries.toMutableList(),
    val guesses: MutableMap<UUID, Trigram> = HashMap(),
    val successfulGuessCounts: MutableMap<UUID, Int> = HashMap(),
    val guessBuffPlayers: MutableSet<UUID> = HashSet(),
    val previousRegeneration: MutableMap<UUID, PotionEffect?> = HashMap(),
    val previousNightVision: MutableMap<UUID, PotionEffect?> = HashMap(),
    val shownElderHints: MutableSet<String> = HashSet(),
    val clientEffectKeys: MutableSet<String> = HashSet(),
    var phase: ShengShanPhase = ShengShanPhase.INTRO,
    var nextTrigram: Trigram? = null,
    var guessToken: String = "",
    var round: Int = 0,
    var current: ShengShanBossController? = null,
    var activeEcho: Trigram? = null,
    var pendingEcho: Trigram? = null,
    var coreEntityId: UUID? = null,
    var coreBar: BossBar? = null,
    var elderEntityId: UUID? = null,
    var arenaEntered: Boolean = false,
    var loopTestMode: Boolean = false,
    var loopTestOwnerId: UUID? = null,
    var bgmStarted: Boolean = false,
    var bgmTask: BukkitTask? = null,
    var ending: Boolean = false
)

internal fun Entity.isShengShanEntity(): Boolean = scoreboardTags.contains(ShengShanDungeonManager.ENTITY_TAG)
