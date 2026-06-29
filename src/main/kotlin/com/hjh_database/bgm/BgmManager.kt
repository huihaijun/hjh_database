package com.hjh_database.bgm

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID

class BgmManager(private val plugin: Hjh_database) {

    private data class BgmArea(
        val id: String,
        val sound: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val radius: Double,
        val volume: Float,
        val pitch: Float,
        val loopTicks: Long,
        val category: SoundCategory
    ) {
        private val radiusSquared = radius * radius

        fun contains(player: Player): Boolean {
            if (world.isNotBlank() && world != "*" && !player.world.name.equals(world, ignoreCase = true)) {
                return false
            }

            val loc = player.location
            val dx = loc.x - x
            val dy = loc.y - y
            val dz = loc.z - z
            return dx * dx + dy * dy + dz * dz <= radiusSquared
        }

        fun locationFor(player: Player): Location = Location(player.world, x, y, z)
    }

    private data class PlayerBgmState(
        var areaId: String?,
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
        val sound = section.getString("$id.sound")?.takeIf { it.isNotBlank() }
            ?: "panling:bgm_$id"
        val category = runCatching {
            SoundCategory.valueOf(section.getString("$id.category", "RECORDS")!!.uppercase())
        }.getOrElse {
            plugin.logger.warning("BGM area '$id' has invalid category, using RECORDS.")
            SoundCategory.RECORDS
        }

        val loopTicks = section.getLong("$id.loop-ticks", 20L * 60L).coerceAtLeast(checkIntervalTicks)
        val radius = section.getDouble("$id.radius", 0.0)
        if (radius <= 0.0) {
            plugin.logger.warning("BGM area '$id' has invalid radius, skipped.")
            return null
        }

        return BgmArea(
            id = id,
            sound = sound,
            world = section.getString("$id.world", "world") ?: "world",
            x = section.getDouble("$id.center.x"),
            y = section.getDouble("$id.center.y"),
            z = section.getDouble("$id.center.z"),
            radius = radius,
            volume = section.getDouble("$id.volume", 1.0).toFloat(),
            pitch = section.getDouble("$id.pitch", 1.0).toFloat(),
            loopTicks = loopTicks,
            category = category
        )
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
            if (state?.areaId != null) {
                areas.firstOrNull { it.id == state.areaId }?.let { stop(player, it) }
                state.areaId = null
            }
            return
        }

        if (state == null) {
            play(player, currentArea)
            playerStates[player.uniqueId] = PlayerBgmState(
                areaId = currentArea.id,
                nextPlayAtMillis = nextPlayTime(now, currentArea)
            )
            return
        }

        if (state.areaId != currentArea.id) {
            areas.firstOrNull { it.id == state.areaId }?.let { stop(player, it) }
            play(player, currentArea)
            state.areaId = currentArea.id
            state.nextPlayAtMillis = nextPlayTime(now, currentArea)
            return
        }

        if (now >= state.nextPlayAtMillis) {
            play(player, currentArea)
            state.nextPlayAtMillis = nextPlayTime(now, currentArea)
        }
    }

    private fun play(player: Player, area: BgmArea) {
        player.stopSound(area.sound, area.category)
        player.playSound(area.locationFor(player), area.sound, area.category, area.volume, area.pitch)
    }

    private fun stop(player: Player, area: BgmArea) {
        player.stopSound(area.sound, area.category)
        player.stopSound(area.sound)
    }

    private fun nextPlayTime(now: Long, area: BgmArea): Long {
        return now + area.loopTicks * 50L
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
