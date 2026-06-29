package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class BaihuMiasmaManager(private val plugin: Hjh_database) : Listener {

    private data class Region(
        val worldKey: String,
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
        val minZ: Double,
        val maxZ: Double
    ) {
        fun contains(player: Player): Boolean {
            if (player.world.key.toString() != worldKey) return false
            val loc = player.location
            return loc.x in minX..maxX && loc.y in minY..maxY && loc.z in minZ..maxZ
        }
    }

    private data class MiasmaStatus(
        val uuid: UUID,
        var playerName: String,
        var value: Int = 0,
        var lastIncreaseMs: Long = 0L,
        var lastInCave: Boolean = false,
        var dirty: Boolean = false
    )

    private val regions = listOf(
        Region(
            "minecraft:overworld",
            minX = -907.58,
            maxX = -706.85,
            minY = 117.51,
            maxY = 170.00,
            minZ = -317.89,
            maxZ = 165.46
        ),
        Region(
            "minecraft:overworld",
            minX = -708.44,
            maxX = -463.32,
            minY = 87.90,
            maxY = 179.01,
            minZ = -103.59,
            maxZ = 259.96
        )
    )

    private val statuses = ConcurrentHashMap<UUID, MiasmaStatus>()
    private val bossBars = ConcurrentHashMap<UUID, BossBar>()
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hjh-baihu-miasma-db").apply { isDaemon = true }
    }

    private var tickTask: BukkitTask? = null
    private var saveTask: BukkitTask? = null

    fun start() {
        createTable()
        tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 40L, 40L)
        saveTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { saveDirtyAsync() }, 1200L, 1200L)

        for (player in Bukkit.getOnlinePlayers()) {
            load(player)
        }
    }

    fun shutdown() {
        tickTask?.cancel()
        saveTask?.cancel()
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            ioExecutor.shutdownNow()
        }
        saveAllSync()
        bossBars.values.forEach { it.removeAll() }
        bossBars.clear()
    }

    private fun createTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_baihu_miasma (
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32),
                miasma INT DEFAULT 0,
                last_in_cave INT DEFAULT 0,
                updated_at BIGINT DEFAULT 0
            )
        """.trimIndent()

        plugin.databaseManager.dataSource?.connection?.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }

    private fun load(player: Player) {
        val uuid = player.uniqueId
        val playerName = player.name
        ioExecutor.execute {
            val loaded = plugin.databaseManager.dataSource?.connection?.use { conn ->
                conn.prepareStatement(
                    "SELECT miasma, last_in_cave, updated_at FROM player_baihu_miasma WHERE uuid = ?"
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            MiasmaStatus(
                                uuid = uuid,
                                playerName = playerName,
                                value = rs.getInt("miasma").coerceIn(0, 1000),
                                lastIncreaseMs = System.currentTimeMillis(),
                                lastInCave = rs.getInt("last_in_cave") != 0,
                                dirty = false
                            )
                        } else {
                            MiasmaStatus(uuid, playerName)
                        }
                    }
                }
            } ?: MiasmaStatus(uuid, playerName)

            plugin.server.scheduler.runTask(plugin, Runnable {
                val online = Bukkit.getPlayer(uuid) ?: return@Runnable
                if (!online.isOnline) return@Runnable
                statuses[uuid] = loaded.apply {
                    this.playerName = online.name
                    if (lastIncreaseMs <= 0L) lastIncreaseMs = System.currentTimeMillis()
                }
                updateBossBar(online, loaded.value)
                applyMiasmaBonuses(online, loaded.value)
            })
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            val status = statuses[player.uniqueId] ?: continue
            status.playerName = player.name

            val inCave = isInBaihuCave(player)
            if (inCave) {
                if (!status.lastInCave) {
                    status.lastInCave = true
                    status.lastIncreaseMs = now
                    changeMiasma(status, ENTRY_MIASMA, player, true)
                    player.sendMessage(color("&7你进入了白虎洞，感受到了一股令人窒息的气息……"))
                }
                status.lastInCave = true
                if (now - status.lastIncreaseMs >= INCREASE_INTERVAL_MS) {
                    val steps = ((now - status.lastIncreaseMs) / INCREASE_INTERVAL_MS).toInt().coerceAtLeast(1)
                    changeMiasma(status, INCREASE_PER_STEP * steps, player, true)
                    status.lastIncreaseMs += INCREASE_INTERVAL_MS * steps
                }
            } else {
                if (status.lastInCave) {
                    player.sendMessage(color("&a你离开了白虎洞，感觉呼吸顺畅了不少……"))
                }
                status.lastInCave = false
                status.lastIncreaseMs = now
                if (status.value > 0) {
                    changeMiasma(status, -OUTSIDE_DECAY_PER_TICK, player, false)
                }
            }

            updateBossBar(player, status.value)
            applyMiasmaBonuses(player, status.value)

            if (status.value >= 1000) {
                damageAtFullMiasma(player)
            }
        }
    }

    private fun changeMiasma(status: MiasmaStatus, delta: Int, player: Player?, notifyDebuffs: Boolean) {
        val oldValue = status.value
        status.value = (status.value + delta).coerceIn(0, 1000)
        if (status.value != oldValue) {
            status.dirty = true
            if (notifyDebuffs && player != null && status.value > oldValue) {
                notifyCrossedDebuffThresholds(player, oldValue, status.value)
            }
            if (player != null && (oldValue > 0) != (status.value > 0)) {
                plugin.playerManager.updateStats(player)
                plugin.baihuDzManager.refreshPlayerEquipment(player)
            }
        }
    }

    private fun notifyCrossedDebuffThresholds(player: Player, oldValue: Int, newValue: Int) {
        if (oldValue < 300 && newValue >= 300) {
            player.sendMessage(color("&c白虎洞窒息的气息让你寸步难行……"))
        }
        if (oldValue < 600 && newValue >= 600) {
            player.sendMessage(color("&c瘴气侵入了你的甲胄，好像变得更加脆弱了……"))
        }
        if (oldValue < 900 && newValue >= 900) {
            player.sendMessage(color("&c瘴气入体，你感受到一股威压，让你经脉受损……"))
        }
    }

    private fun isInBaihuCave(player: Player): Boolean {
        return regions.any { it.contains(player) }
    }

    private fun updateBossBar(player: Player, value: Int) {
        val uuid = player.uniqueId
        if (value <= 0) {
            bossBars.remove(uuid)?.removeAll()
            return
        }

        val percent = value / 10.0
        val bar = bossBars.computeIfAbsent(uuid) {
            Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10).apply {
                addPlayer(player)
            }
        }

        if (!bar.players.contains(player)) {
            bar.addPlayer(player)
        }

        bar.progress = (value / 1000.0).coerceIn(0.0, 1.0)
        bar.color = when {
            value >= 1000 -> BarColor.PURPLE
            value >= 900 -> BarColor.RED
            value >= 600 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }
        bar.setTitle("§6§l虎瘴 §f${String.format(Locale.US, "%.1f", percent)}%")
        bar.isVisible = true
    }

    private fun applyMiasmaBonuses(player: Player, value: Int) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        var changed = false
        changed = setOrRemoveBonus(data.tempBonuses, SPEED_DEBUFF_KEY, value >= 300, -0.08) || changed
        changed = setOrRemoveBonus(data.tempBonuses, ARMOR_DEBUFF_KEY, value >= 600, -0.15) || changed
        changed = setOrRemoveBonus(data.tempBonuses, MAX_HEALTH_DEBUFF_KEY, value >= 900, -0.15) || changed

        if (changed) {
            plugin.playerManager.updateStats(player)
        }
    }

    private fun setOrRemoveBonus(
        bonuses: MutableMap<String, Double>,
        key: String,
        active: Boolean,
        value: Double
    ): Boolean {
        if (active) {
            if (bonuses[key] == value) return false
            bonuses[key] = value
            return true
        }
        return bonuses.remove(key) != null
    }

    private fun damageAtFullMiasma(player: Player) {
        if (player.isDead || player.health <= 0.0) return
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val damage = 2.0 + maxHealth * 0.03
        player.health = max(0.0, player.health - damage)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        load(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        statuses[player.uniqueId]?.let { status ->
            status.lastInCave = isInBaihuCave(player)
            status.dirty = true
            saveSync(status)
        }
        bossBars.remove(player.uniqueId)?.removeAll()
        statuses.remove(player.uniqueId)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val status = statuses[player.uniqueId] ?: MiasmaStatus(player.uniqueId, player.name)
        status.value = 0
        status.lastInCave = false
        status.lastIncreaseMs = System.currentTimeMillis()
        status.dirty = true
        statuses[player.uniqueId] = status
        clearMiasmaBonuses(player)
        bossBars.remove(player.uniqueId)?.removeAll()
        saveAsync(status)
    }

    private fun clearMiasmaBonuses(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        var changed = false
        changed = data.tempBonuses.remove(SPEED_DEBUFF_KEY) != null || changed
        changed = data.tempBonuses.remove(ARMOR_DEBUFF_KEY) != null || changed
        changed = data.tempBonuses.remove(MAX_HEALTH_DEBUFF_KEY) != null || changed
        if (changed) {
            plugin.playerManager.updateStats(player)
        }
    }

    private fun saveDirtyAsync() {
        val dirty = statuses.values
            .filter { it.dirty }
            .map { it.copy() }
        if (dirty.isEmpty()) return

        ioExecutor.execute {
            dirty.forEach { saveStatus(it) }
            plugin.server.scheduler.runTask(plugin, Runnable {
                dirty.forEach { saved ->
                    val live = statuses[saved.uuid]
                    if (live != null && live.value == saved.value) {
                        live.dirty = false
                    }
                }
            })
        }
    }

    private fun saveAsync(status: MiasmaStatus) {
        val snapshot = status.copy()
        ioExecutor.execute { saveStatus(snapshot) }
    }

    private fun saveSync(status: MiasmaStatus) {
        saveStatus(status.copy())
        status.dirty = false
    }

    private fun saveAllSync() {
        statuses.values.forEach { saveSync(it) }
    }

    private fun saveStatus(status: MiasmaStatus) {
        val sql = """
            INSERT INTO player_baihu_miasma (uuid, player_name, miasma, last_in_cave, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                miasma = excluded.miasma,
                last_in_cave = excluded.last_in_cave,
                updated_at = excluded.updated_at
        """.trimIndent()

        plugin.databaseManager.dataSource?.connection?.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, status.uuid.toString())
                ps.setString(2, status.playerName)
                ps.setInt(3, status.value.coerceIn(0, 1000))
                ps.setInt(4, if (status.lastInCave) 1 else 0)
                ps.setLong(5, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    companion object {
        private const val INCREASE_INTERVAL_MS = 5_000L
        private const val INCREASE_PER_STEP = 20
        private const val ENTRY_MIASMA = 60
        private const val OUTSIDE_DECAY_PER_TICK = 50
        private const val SPEED_DEBUFF_KEY = "baihu_miasma_speed_percent"
        private const val ARMOR_DEBUFF_KEY = "baihu_miasma_armor_percent"
        private const val MAX_HEALTH_DEBUFF_KEY = "baihu_miasma_max_health_percent"

        private fun color(text: String): String {
            return ChatColor.translateAlternateColorCodes('&', text)
        }
    }

    fun getMiasma(player: Player): Int {
        return statuses[player.uniqueId]?.value ?: 0
    }
}

