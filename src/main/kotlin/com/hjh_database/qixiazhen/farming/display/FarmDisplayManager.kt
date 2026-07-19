package com.hjh_database.qixiazhen.farming.display

import com.hjh_database.Hjh_database
import com.hjh_database.qixiazhen.farming.data.FarmPlot
import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.block.data.Ageable
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FarmDisplayManager(
    private val plugin: Hjh_database,
    private val manager: FarmingManager
) {
    private data class CropEntry(val display: BlockDisplay, val state: PlayerFarmState)
    private data class CropSession(
        val entries: MutableMap<Long, CropEntry>,
        var expiresAt: Long,
        var task: BukkitTask? = null
    )

    private data class Entry(
        val plot: FarmPlot,
        val state: PlayerFarmState,
        val text: TextDisplay
    )

    private data class Session(val entries: MutableList<Entry>, var task: BukkitTask? = null)

    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val cropSessions = ConcurrentHashMap<UUID, CropSession>()
    private val serializer = LegacyComponentSerializer.legacySection()

    fun showSummary(player: Player, entries: List<Pair<FarmPlot, PlayerFarmState>>) {
        clear(player)
        if (entries.isEmpty()) return
        val session = Session(mutableListOf())
        entries.forEach { (plot, state) ->
            createEntry(player, plot, state, detailed = false)?.let(session.entries::add)
        }
        if (session.entries.isNotEmpty()) start(player, session, detailed = false)
    }

    fun showDetailed(player: Player, plot: FarmPlot, state: PlayerFarmState) {
        clear(player)
        val entry = createEntry(player, plot, state, detailed = true) ?: return
        start(player, Session(mutableListOf(entry)), detailed = true)
    }

    private fun createEntry(player: Player, plot: FarmPlot, state: PlayerFarmState, detailed: Boolean): Entry? {
        val world = plugin.server.getWorld(plot.key.worldId) ?: return null
        val base = world.getBlockAt(plot.key.x, plot.key.y, plot.key.z).location
        val text = world.spawn(base.clone().add(0.5, manager.config.textHeight, 0.5), TextDisplay::class.java) { display ->
            display.billboard = Display.Billboard.CENTER
            display.isPersistent = false
            display.isInvulnerable = true
            display.isVisibleByDefault = false
            display.text(renderText(state, detailed))
        }
        player.showEntity(plugin, text)

        return Entry(plot, state, text)
    }

    private fun start(player: Player, session: Session, detailed: Boolean) {
        sessions[player.uniqueId] = session
        val maxTicks = manager.config.displayDurationSeconds * 20L
        var elapsed = 0L
        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || elapsed >= maxTicks) {
                    clear(player)
                    cancel()
                    return
                }
                session.entries.forEach { entry ->
                    if (entry.text.isValid) entry.text.text(renderText(entry.state, detailed))
                }
                elapsed += manager.config.displayUpdateTicks
            }
        }.runTaskTimer(plugin, 0L, manager.config.displayUpdateTicks)
    }

    private fun renderText(state: PlayerFarmState, detailed: Boolean): Component {
        val crop = manager.config.crop(state.cropId)
        val cropName = crop?.name ?: "§7空置"
        if (!state.planted) return serializer.deserialize("$cropName\n§7未种植")
        val status = if (state.ready()) "§a已成熟" else "§e生长中"
        val text = if (detailed) {
            val seconds = (state.remainingMs() + 999L) / 1000L
            "$cropName\n$status\n§7距离成熟：§f${seconds}秒\n§7预计产量倍率：§f${"%.0f".format(state.yieldMultiplier * 100)}%"
        } else {
            "$cropName\n$status"
        }
        return serializer.deserialize(text)
    }

    private fun cropData(state: PlayerFarmState): org.bukkit.block.data.BlockData {
        val crop = manager.config.crop(state.cropId)
        val material = crop?.material?.takeIf { it.isBlock } ?: org.bukkit.Material.WHEAT
        val data = material.createBlockData()
        if (data is Ageable) {
            val duration = (state.maturesAt - state.plantedAt).coerceAtLeast(1L)
            val elapsed = (System.currentTimeMillis() - state.plantedAt).coerceAtLeast(0L)
            data.age = ((elapsed.toDouble() / duration) * data.maximumAge).toInt().coerceIn(0, data.maximumAge)
        }
        return data
    }

    fun clear(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        session.task?.cancel()
        session.entries.forEach {
            if (it.text.isValid) it.text.remove()
        }
    }

    fun showCrops(player: Player, entries: List<Pair<FarmPlot, PlayerFarmState>>) {
        clearCrops(player.uniqueId)
        val session = CropSession(mutableMapOf(), cropExpiry())
        entries.filter { it.second.planted }.forEach { (plot, state) ->
            spawnCrop(player, plot, state)?.let { session.entries[plot.id] = it }
        }
        if (session.entries.isNotEmpty()) startCropSession(player, session)
    }

    fun ensureCrop(player: Player, plot: FarmPlot, state: PlayerFarmState) {
        if (!state.planted) {
            cropSessions[player.uniqueId]?.entries?.remove(plot.id)?.display?.takeIf { it.isValid }?.remove()
            return
        }
        var session = cropSessions[player.uniqueId]
        if (session == null) {
            session = CropSession(mutableMapOf(), cropExpiry())
            cropSessions[player.uniqueId] = session
            startCropSession(player, session)
        }
        session.expiresAt = cropExpiry()
        val old = session.entries[plot.id]
        if (old == null || !old.display.isValid) {
            spawnCrop(player, plot, state)?.let { session.entries[plot.id] = it }
        } else {
            old.display.block = cropData(state)
        }
    }

    private fun spawnCrop(player: Player, plot: FarmPlot, state: PlayerFarmState): CropEntry? {
        val world = plugin.server.getWorld(plot.key.worldId) ?: return null
        if (!world.isChunkLoaded(plot.key.x shr 4, plot.key.z shr 4)) return null
        val base = world.getBlockAt(plot.key.x, plot.key.y, plot.key.z).location
        val display = world.spawn(base.clone().add(0.0, manager.config.cropHeight, 0.0), BlockDisplay::class.java) { entity ->
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.isVisibleByDefault = false
            entity.block = cropData(state)
        }
        player.showEntity(plugin, display)
        return CropEntry(display, state)
    }

    private fun startCropSession(player: Player, session: CropSession) {
        cropSessions[player.uniqueId] = session
        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || System.currentTimeMillis() >= session.expiresAt) {
                    clearCrops(player.uniqueId)
                    cancel()
                    return
                }
                session.entries.entries.toList().forEach { (plotId, entry) ->
                    if (!entry.state.planted || !entry.display.isValid) {
                        if (entry.display.isValid) entry.display.remove()
                        session.entries.remove(plotId)
                    } else {
                        entry.display.block = cropData(entry.state)
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun cropExpiry(): Long = System.currentTimeMillis() + manager.config.cropDisplayDurationSeconds * 1000L

    fun clearPlayer(player: Player) {
        clear(player)
        clearCrops(player.uniqueId)
    }

    private fun clearCrops(playerId: UUID) {
        cropSessions.remove(playerId)?.let { session ->
            session.task?.cancel()
            session.entries.values.forEach { if (it.display.isValid) it.display.remove() }
        }
    }

    fun clearAll() {
        sessions.keys.toList().forEach { id -> plugin.server.getPlayer(id)?.let(::clear) }
        sessions.values.forEach { session ->
            session.task?.cancel()
            session.entries.forEach {
                if (it.text.isValid) it.text.remove()
            }
        }
        sessions.clear()
        cropSessions.keys.toList().forEach(::clearCrops)
    }
}
