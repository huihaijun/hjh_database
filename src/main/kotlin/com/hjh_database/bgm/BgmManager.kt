package com.hjh_database.bgm

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID

class BgmManager(private val plugin: Hjh_database) {

    private data class BgmTrack(
        val sound: String,
        val loopTicks: Long
    )

    private sealed interface AreaShape {
        fun contains(location: Location): Boolean
        fun center(world: World): Location
    }

    private data class SphereShape(
        val x: Double,
        val y: Double,
        val z: Double,
        val radius: Double
    ) : AreaShape {
        private val radiusSquared = radius * radius

        override fun contains(location: Location): Boolean {
            val dx = location.x - x
            val dy = location.y - y
            val dz = location.z - z
            return dx * dx + dy * dy + dz * dz <= radiusSquared
        }

        override fun center(world: World): Location = Location(world, x, y, z)
    }

    private data class CuboidShape(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double
    ) : AreaShape {
        override fun contains(location: Location): Boolean {
            return location.x in minX..maxX &&
                location.y in minY..maxY &&
                location.z in minZ..maxZ
        }

        override fun center(world: World): Location {
            return Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0)
        }
    }

    private data class BgmArea(
        val id: String,
        val tracks: List<BgmTrack>,
        val world: String,
        val shape: AreaShape,
        val volume: Float,
        val pitch: Float,
        val category: SoundCategory,
        val randomStart: Boolean,
        val followPlayer: Boolean
    ) {
        fun contains(player: Player): Boolean {
            if (world.isNotBlank() && world != "*" && !player.world.name.equals(world, ignoreCase = true)) {
                return false
            }
            return shape.contains(player.location)
        }

        fun locationFor(player: Player): Location = shape.center(player.world)
    }

    private data class PlayerBgmState(
        var areaId: String,
        var trackIndex: Int,
        var nextPlayAtMillis: Long
    )

    private val configFile = File(plugin.dataFolder, "bgm.yml")
    private val areas = mutableListOf<BgmArea>()
    private val playerStates = mutableMapOf<UUID, PlayerBgmState>()

    private var enabled = true
    private var checkIntervalTicks = 20L
    private var task: BukkitTask? = null

    init {
        reload()
    }

    fun reload() {
        ensureConfig()

        val config = YamlConfiguration.loadConfiguration(configFile)
        enabled = config.getBoolean("enabled", true)
        checkIntervalTicks = config.getLong("check-interval-ticks", 20L).coerceAtLeast(1L)

        val loaded = mutableListOf<BgmArea>()
        val section = config.getConfigurationSection("areas")
        if (section != null) {
            for (id in section.getKeys(false)) {
                parseArea(section, id)?.let(loaded::add)
            }
        }

        stopAllKnownSounds()
        playerStates.clear()
        areas.clear()
        areas.addAll(loaded)

        restart()
        plugin.logger.info("Loaded ${areas.size} BGM areas.")
    }

    fun shutdown() {
        task?.cancel()
        task = null
        stopAllKnownSounds()
        playerStates.clear()
    }

    private fun ensureConfig() {
        if (configFile.exists()) return
        configFile.parentFile.mkdirs()
        plugin.saveResource("bgm.yml", false)
    }

    private fun parseArea(section: ConfigurationSection, id: String): BgmArea? {
        val category = runCatching {
            SoundCategory.valueOf(section.getString("$id.category", "RECORDS")!!.uppercase())
        }.getOrElse {
            plugin.logger.warning("BGM area '$id' has invalid category, using RECORDS.")
            SoundCategory.RECORDS
        }

        val tracks = parseTracks(section, id)
        if (tracks.isEmpty()) return null
        val shape = parseShape(section, id) ?: return null

        return BgmArea(
            id = id,
            tracks = tracks,
            world = section.getString("$id.world", "world") ?: "world",
            shape = shape,
            volume = section.getDouble("$id.volume", 1.0).toFloat(),
            pitch = section.getDouble("$id.pitch", 1.0).toFloat(),
            category = category,
            randomStart = section.getBoolean("$id.random-start", false),
            followPlayer = section.getBoolean("$id.follow-player", false)
        )
    }

    private fun parseTracks(section: ConfigurationSection, id: String): List<BgmTrack> {
        val configuredTracks = section.getMapList("$id.tracks").mapNotNull { values ->
            val sound = (values["sound"] as? String)?.takeIf { it.isNotBlank() }
            val loopTicks = when (val value = values["loop-ticks"]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }

            if (sound == null || loopTicks == null || loopTicks <= 0L) {
                plugin.logger.warning("BGM area '$id' contains an invalid track, skipped.")
                null
            } else {
                BgmTrack(sound, loopTicks.coerceAtLeast(checkIntervalTicks))
            }
        }
        if (configuredTracks.isNotEmpty()) return configuredTracks

        val sound = section.getString("$id.sound")?.takeIf { it.isNotBlank() }
            ?: "panling:bgm_$id"
        val loopTicks = section.getLong("$id.loop-ticks", 20L * 60L).coerceAtLeast(checkIntervalTicks)
        return listOf(BgmTrack(sound, loopTicks))
    }

    private fun parseShape(section: ConfigurationSection, id: String): AreaShape? {
        return when (section.getString("$id.shape", "SPHERE")!!.uppercase()) {
            "SPHERE" -> {
                val radius = section.getDouble("$id.radius", 0.0)
                if (radius <= 0.0) {
                    plugin.logger.warning("BGM area '$id' has invalid radius, skipped.")
                    null
                } else {
                    SphereShape(
                        x = section.getDouble("$id.center.x"),
                        y = section.getDouble("$id.center.y"),
                        z = section.getDouble("$id.center.z"),
                        radius = radius
                    )
                }
            }

            "CUBOID" -> {
                val firstX = section.getDouble("$id.bounds.min.x")
                val firstY = section.getDouble("$id.bounds.min.y")
                val firstZ = section.getDouble("$id.bounds.min.z")
                val secondX = section.getDouble("$id.bounds.max.x")
                val secondY = section.getDouble("$id.bounds.max.y")
                val secondZ = section.getDouble("$id.bounds.max.z")
                CuboidShape(
                    minX = minOf(firstX, secondX),
                    minY = minOf(firstY, secondY),
                    minZ = minOf(firstZ, secondZ),
                    maxX = maxOf(firstX, secondX),
                    maxY = maxOf(firstY, secondY),
                    maxZ = maxOf(firstZ, secondZ)
                )
            }

            else -> {
                plugin.logger.warning("BGM area '$id' has invalid shape, skipped.")
                null
            }
        }
    }

    private fun start() {
        if (!enabled || areas.isEmpty()) return

        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            tick()
        }, 20L, checkIntervalTicks)
    }

    private fun restart() {
        task?.cancel()
        task = null
        start()
    }

    private fun tick() {
        if (!enabled) return

        val now = System.currentTimeMillis()
        val onlinePlayers = Bukkit.getOnlinePlayers()
        val onlineIds = onlinePlayers.mapTo(mutableSetOf()) { it.uniqueId }
        playerStates.keys.removeIf { it !in onlineIds }

        for (player in onlinePlayers) {
            updatePlayer(player, now)
        }
    }

    private fun updatePlayer(player: Player, now: Long) {
        val currentArea = areas.firstOrNull { it.contains(player) }
        val state = playerStates[player.uniqueId]

        if (currentArea == null) {
            if (state != null) {
                areas.firstOrNull { it.id == state.areaId }?.let { stop(player, it) }
                playerStates.remove(player.uniqueId)
            }
            return
        }

        if (state == null) {
            val trackIndex = firstTrackIndex(currentArea)
            play(player, currentArea, trackIndex)
            playerStates[player.uniqueId] = PlayerBgmState(
                areaId = currentArea.id,
                trackIndex = trackIndex,
                nextPlayAtMillis = nextPlayTime(now, currentArea.tracks[trackIndex])
            )
            return
        }

        if (state.areaId != currentArea.id) {
            areas.firstOrNull { it.id == state.areaId }?.let { stop(player, it) }
            val trackIndex = firstTrackIndex(currentArea)
            play(player, currentArea, trackIndex)
            playerStates[player.uniqueId] = PlayerBgmState(
                areaId = currentArea.id,
                trackIndex = trackIndex,
                nextPlayAtMillis = nextPlayTime(now, currentArea.tracks[trackIndex])
            )
            return
        }

        if (now >= state.nextPlayAtMillis) {
            stop(player, currentArea.tracks[state.trackIndex], currentArea.category)
            state.trackIndex = (state.trackIndex + 1) % currentArea.tracks.size
            play(player, currentArea, state.trackIndex)
            state.nextPlayAtMillis = nextPlayTime(now, currentArea.tracks[state.trackIndex])
        }
    }

    private fun firstTrackIndex(area: BgmArea): Int {
        return if (area.randomStart && area.tracks.size > 1) area.tracks.indices.random() else 0
    }

    private fun play(player: Player, area: BgmArea, trackIndex: Int) {
        val track = area.tracks[trackIndex]
        stop(player, track, area.category)
        if (area.followPlayer) {
            player.playSound(player, track.sound, area.category, area.volume, area.pitch)
        } else {
            player.playSound(area.locationFor(player), track.sound, area.category, area.volume, area.pitch)
        }
    }

    private fun stop(player: Player, area: BgmArea) {
        for (track in area.tracks) {
            stop(player, track, area.category)
        }
    }

    private fun stop(player: Player, track: BgmTrack, category: SoundCategory) {
        player.stopSound(track.sound, category)
        player.stopSound(track.sound)
    }

    private fun nextPlayTime(now: Long, track: BgmTrack): Long {
        return now + track.loopTicks * 50L
    }

    private fun stopAllKnownSounds() {
        if (areas.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            for (area in areas) {
                stop(player, area)
            }
        }
    }
}
