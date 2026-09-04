package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.CreatureSpawner
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

class SpawnerBlockManager(private val plugin: Hjh_database) {

    private val keyMobId = NamespacedKey(plugin, "hjh_spawner_mobid_block")
    private val keyTarget = NamespacedKey(plugin, "hjh_spawner_target_block")
    private val keyNextSpawn = NamespacedKey(plugin, "hjh_spawner_next_spawn")
    private val keyDelayTicks = NamespacedKey(plugin, "hjh_spawner_delay_ticks")
    private val keyInitialDelayApplied = NamespacedKey(plugin, "hjh_spawner_initial_delay_applied")

    private val activationRange = 16.0
    private val activationRangeSquared = activationRange * activationRange
    private val maxNearbyRange = 20.0
    private val maxNearbyRangeSquared = maxNearbyRange * maxNearbyRange

    init {
        MobRegistry.init()
        startSpawnerTask()
    }

    fun writeToSpawner(spawner: CreatureSpawner, mobId: String, targetStr: String?) {
        spawner.persistentDataContainer.set(keyMobId, PersistentDataType.STRING, mobId)
        if (targetStr != null) {
            spawner.persistentDataContainer.set(keyTarget, PersistentDataType.STRING, targetStr)
        } else {
            spawner.persistentDataContainer.remove(keyTarget)
        }

        spawner.spawnCount = 0
        spawner.requiredPlayerRange = 0
        spawner.maxNearbyEntities = 0

        val def = MobRegistry.get(mobId)
        spawner.persistentDataContainer.set(keyDelayTicks, PersistentDataType.INTEGER, initialDelayTicks(def))
        spawner.persistentDataContainer.set(keyInitialDelayApplied, PersistentDataType.BYTE, 1.toByte())
        spawner.persistentDataContainer.remove(keyNextSpawn)
        spawner.update()
    }

    fun getMobId(spawner: CreatureSpawner): String? {
        return spawner.persistentDataContainer.get(keyMobId, PersistentDataType.STRING)
    }

    private fun startSpawnerTask() {
        object : BukkitRunnable() {
            override fun run() {
                showSpawnerParticles()
                val activeSpawners = collectActiveSpawners()
                for (spawner in activeSpawners) {
                    attemptSpawn(spawner)
                }
            }
        }.runTaskTimer(plugin, TASK_PERIOD_TICKS, TASK_PERIOD_TICKS)
    }

    private fun collectActiveSpawners(): List<CreatureSpawner> {
        val candidates = ArrayList<CreatureSpawner>()
        val seen = HashSet<String>()

        for (player in Bukkit.getOnlinePlayers()) {
            if (!canActivateSpawner(player)) continue

            val playerLoc = player.location
            val chunk = playerLoc.chunk
            val world = player.world

            for (chunkXOffset in -1..1) {
                for (chunkZOffset in -1..1) {
                    val chunkX = chunk.x + chunkXOffset
                    val chunkZ = chunk.z + chunkZOffset
                    if (!world.isChunkLoaded(chunkX, chunkZ)) continue

                    val currentChunk = world.getChunkAt(chunkX, chunkZ)
                    for (tile in currentChunk.tileEntities) {
                        val spawner = tile as? CreatureSpawner ?: continue
                        val center = spawner.location.clone().add(0.5, 0.5, 0.5)
                        if (playerLoc.distanceSquared(center) > activationRangeSquared) continue

                        val loc = spawner.location
                        val key = "${world.uid}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"
                        if (seen.add(key)) {
                            candidates.add(spawner)
                        }
                    }
                }
            }
        }

        return candidates
    }

    private fun canActivateSpawner(player: Player): Boolean {
        return !player.isDead &&
            player.gameMode != org.bukkit.GameMode.CREATIVE &&
            player.gameMode != org.bukkit.GameMode.SPECTATOR
    }

    // These are client-only particles, so custom spawners remain discoverable without enabling vanilla spawning.
    private fun showSpawnerParticles() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.isDead || player.gameMode == org.bukkit.GameMode.SPECTATOR) continue

            val playerLoc = player.location
            val chunk = playerLoc.chunk
            val world = player.world
            for (chunkXOffset in -1..1) {
                for (chunkZOffset in -1..1) {
                    val chunkX = chunk.x + chunkXOffset
                    val chunkZ = chunk.z + chunkZOffset
                    if (!world.isChunkLoaded(chunkX, chunkZ)) continue

                    for (tile in world.getChunkAt(chunkX, chunkZ).tileEntities) {
                        val spawner = tile as? CreatureSpawner ?: continue
                        if (getMobId(spawner) == null) continue

                        val center = spawner.location.clone().add(0.5, 0.5, 0.5)
                        if (playerLoc.distanceSquared(center) > PARTICLE_RANGE_SQUARED) continue

                        player.spawnParticle(Particle.FLAME, center, 3, 0.28, 0.35, 0.28, 0.01)
                        player.spawnParticle(Particle.SMOKE, center, 2, 0.28, 0.35, 0.28, 0.01)
                    }
                }
            }
        }
    }

    private fun attemptSpawn(spawner: CreatureSpawner) {
        val pdc = spawner.persistentDataContainer
        val mobId = pdc.get(keyMobId, PersistentDataType.STRING) ?: return
        val def = MobRegistry.get(mobId) ?: return

        val remainingTicks = readDelayTicks(spawner, def)
        if (remainingTicks > TASK_PERIOD_TICKS) {
            setDelayTicks(spawner, remainingTicks - TASK_PERIOD_TICKS.toInt())
            return
        }

        val baseLoc = pdc.get(keyTarget, PersistentDataType.STRING)?.let { parseLocation(it) }
            ?: spawner.location.clone().add(0.5, 1.0, 0.5)
        val spawnLoc = findNearbySpawnLocation(baseLoc, def.type)
        if (spawnLoc == null) {
            resetDelay(spawner, def)
            return
        }

        val nearbyEntities = spawnLoc.world!!.getNearbyEntities(spawnLoc, maxNearbyRange, maxNearbyRange, maxNearbyRange)
            .filter { it.location.distanceSquared(spawnLoc) <= maxNearbyRangeSquared }

        val nearby = nearbyEntities.count {
            it.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) == mobId
        }

        if (nearby >= def.maxNearby) {
            resetDelay(spawner, def)
            return
        }

        if (def.maxNearby != 1) {
            val regionalMonsterCount = nearbyEntities.count {
                it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster")
            }
            if (regionalMonsterCount > REGIONAL_MONSTER_LIMIT) {
                resetDelay(spawner, def)
                return
            }
        }

        MobFactory.spawnMob(plugin, spawnLoc, mobId, removeWhenFarAway = true)
        resetDelay(spawner, def)
    }

    private fun readDelayTicks(spawner: CreatureSpawner, def: MobDefinition): Int {
        val pdc = spawner.persistentDataContainer
        pdc.get(keyDelayTicks, PersistentDataType.INTEGER)?.let { storedTicks ->
            if (pdc.has(keyInitialDelayApplied, PersistentDataType.BYTE)) {
                return storedTicks.coerceAtLeast(0)
            }

            val initialTicks = shortenInitialDelay(storedTicks)
            pdc.set(keyInitialDelayApplied, PersistentDataType.BYTE, 1.toByte())
            setDelayTicks(spawner, initialTicks)
            return initialTicks
        }

        val convertedTicks = pdc.get(keyNextSpawn, PersistentDataType.LONG)?.let { oldNextSpawn ->
            val remainingMillis = (oldNextSpawn - System.currentTimeMillis()).coerceAtLeast(0L)
            ((remainingMillis + 49L) / 50L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } ?: randomDelayTicks(def)

        val initialTicks = shortenInitialDelay(convertedTicks)
        pdc.set(keyInitialDelayApplied, PersistentDataType.BYTE, 1.toByte())
        setDelayTicks(spawner, initialTicks)
        return initialTicks
    }

    private fun resetDelay(spawner: CreatureSpawner, def: MobDefinition) {
        setDelayTicks(spawner, randomDelayTicks(def))
    }

    private fun setDelayTicks(spawner: CreatureSpawner, ticks: Int) {
        spawner.persistentDataContainer.set(keyDelayTicks, PersistentDataType.INTEGER, ticks.coerceAtLeast(0))
        spawner.persistentDataContainer.remove(keyNextSpawn)
        spawner.update()
    }

    private fun randomDelayTicks(def: MobDefinition?): Int {
        val minDelay = def?.minSpawnDelay ?: 20
        val maxDelay = (def?.maxSpawnDelay ?: 50).coerceAtLeast(minDelay)
        val seconds = if (minDelay == maxDelay) {
            minDelay
        } else {
            ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)
        }
        return seconds.coerceAtLeast(1) * 20
    }

    private fun initialDelayTicks(def: MobDefinition?): Int {
        return shortenInitialDelay(randomDelayTicks(def))
    }

    private fun shortenInitialDelay(ticks: Int): Int {
        return (ticks * INITIAL_DELAY_FACTOR).toInt().coerceAtLeast(1)
    }

    private fun findNearbySpawnLocation(baseLoc: Location, type: EntityType): Location? {
        if (baseLoc.world == null) return null

        val centered = centerIfBlockAligned(baseLoc)
        val candidates = ArrayList<Location>()
        if (isBlockAligned(baseLoc.x) && isBlockAligned(baseLoc.z)) {
            candidates.add(centered)
            candidates.add(baseLoc)
        } else {
            candidates.add(baseLoc)
            candidates.add(centered)
        }

        val offsets = ArrayList<Triple<Int, Int, Int>>()
        for (y in listOf(0, 1, -1)) {
            for (x in -2..2) {
                for (z in -2..2) {
                    if (x == 0 && y == 0 && z == 0) continue
                    offsets.add(Triple(x, y, z))
                }
            }
        }
        offsets.sortBy { (x, y, z) -> x * x + y * y + z * z }
        offsets.forEach { (x, y, z) ->
            candidates.add(centered.clone().add(x.toDouble(), y.toDouble(), z.toDouble()))
        }

        val tested = HashSet<String>()
        for (candidate in candidates) {
            val key = "${candidate.world!!.uid}:${candidate.x}:${candidate.y}:${candidate.z}"
            if (!tested.add(key)) continue
            if (isSpawnSpaceClear(candidate, type)) return candidate
        }
        return null
    }

    private fun isSpawnSpaceClear(location: Location, type: EntityType): Boolean {
        val world = location.world ?: return false
        val footprint = footprintFor(type)
        val minX = floor(location.x - footprint.radius + EPSILON).toInt()
        val maxX = floor(location.x + footprint.radius - EPSILON).toInt()
        val minY = floor(location.y + EPSILON).toInt()
        val maxY = floor(location.y + footprint.height - EPSILON).toInt()
        val minZ = floor(location.z - footprint.radius + EPSILON).toInt()
        val maxZ = floor(location.z + footprint.radius - EPSILON).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.isPassable || block.type in BLOCKED_SPAWN_MATERIALS) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun footprintFor(type: EntityType): SpawnFootprint {
        return when (type) {
            EntityType.SPIDER -> SpawnFootprint(0.75, 1.0)
            EntityType.CAVE_SPIDER -> SpawnFootprint(0.45, 0.7)
            EntityType.SLIME, EntityType.MAGMA_CUBE -> SpawnFootprint(1.05, 2.1)
            EntityType.PHANTOM -> SpawnFootprint(0.95, 0.6)
            EntityType.BLAZE, EntityType.BREEZE -> SpawnFootprint(0.45, 1.8)
            else -> SpawnFootprint(0.35, 1.95)
        }
    }

    private fun centerIfBlockAligned(location: Location): Location {
        if (!isBlockAligned(location.x) || !isBlockAligned(location.z)) return location.clone()
        return Location(
            location.world,
            location.blockX + 0.5,
            location.y,
            location.blockZ + 0.5,
            location.yaw,
            location.pitch
        )
    }

    private fun isBlockAligned(value: Double): Boolean {
        return abs(value - round(value)) < EPSILON
    }

    private fun parseLocation(str: String): Location? {
        val parts = str.split(",")
        if (parts.size != 4) return null
        val world = Bukkit.getWorld(parts[0]) ?: return null
        return try {
            Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
        } catch (e: Exception) {
            null
        }
    }

    private data class SpawnFootprint(val radius: Double, val height: Double)

    private companion object {
        private const val TASK_PERIOD_TICKS = 20L
        private const val REGIONAL_MONSTER_LIMIT = 9
        private const val INITIAL_DELAY_FACTOR = 0.30
        private const val PARTICLE_RANGE = 20.0
        private const val PARTICLE_RANGE_SQUARED = PARTICLE_RANGE * PARTICLE_RANGE
        private const val EPSILON = 1.0E-6
        private val BLOCKED_SPAWN_MATERIALS = setOf(
            Material.WATER,
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.POWDER_SNOW
        )
    }
}
