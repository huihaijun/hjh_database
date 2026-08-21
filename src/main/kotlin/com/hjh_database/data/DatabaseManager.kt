package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.chonghua.ChonghuaData
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.warehouse.data.WarehouseData
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DatabaseManager(private val plugin: Hjh_database) {
    var dataSource: HikariDataSource? = null

    private val schema = DatabaseSchema(this, plugin)
    private val players = DatabasePlayerRepository(this, plugin)
    private val subsystems = DatabaseSubsystemRepository(this, plugin)
    private val queuedPlayerSaves = ConcurrentHashMap<UUID, BukkitTask>()
    private val acceptingAsyncWrites = AtomicBoolean(true)
    private val playerDataWriteLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hjh-database-writer").apply {
            isDaemon = true
        }
    }

    init {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        connect()
        schema.initialize()
    }

    private fun connect() {
        val config = HikariConfig()
        val dbFile = File(plugin.dataFolder, "hjh_rpg.db")

        config.jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        config.driverClassName = "org.sqlite.JDBC"
        config.maximumPoolSize = 1
        config.minimumIdle = 1
        config.connectionTimeout = 30000
        config.idleTimeout = 600000
        config.maxLifetime = 1800000

        dataSource = HikariDataSource(config)
        configureSqlite()
        plugin.logger.info("SQLite 数据库连接成功！文件路径: ${dbFile.absolutePath}")
    }

    private fun configureSqlite() {
        try {
            dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                    stmt.execute("PRAGMA busy_timeout=5000")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("SQLite runtime pragma setup failed: ${ex.message}")
        }
    }

    fun close() {
        acceptingAsyncWrites.set(false)
        cancelQueuedPlayerSaves()
        writeExecutor.shutdown()
        try {
            if (!writeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.logger.warning("数据库后台写入队列等待超时，正在强制关闭。")
                writeExecutor.shutdownNow()
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            writeExecutor.shutdownNow()
        }
        dataSource?.close()
    }

    fun savePlayer(data: PlayerData) = withPlayerDataWriteLock { players.savePlayer(data) }

    /**
     * 让会修改 player_data（尤其是 money）的跨表事务与常规玩家存档互斥，
     * 防止后台旧存档在市场交易提交后覆盖最新余额。
     */
    fun <T> withPlayerDataWriteLock(block: () -> T): T = synchronized(playerDataWriteLock) { block() }

    /**
     * 将数据库工作排入插件共用的单线程写入队列。调用方只接收 Future，绝不在
     * Bukkit 主线程回退为同步执行；市场等交互功能用它避免卡住服务器 Tick。
     */
    fun <T> submitDatabaseOperation(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (!acceptingAsyncWrites.get()) {
            future.completeExceptionally(RejectedExecutionException("数据库写入队列已关闭"))
            return future
        }
        try {
            writeExecutor.execute {
                try {
                    future.complete(withPlayerDataWriteLock(block))
                } catch (ex: Throwable) {
                    future.completeExceptionally(ex)
                }
            }
        } catch (ex: RejectedExecutionException) {
            future.completeExceptionally(ex)
        }
        return future
    }

    fun savePlayerAsync(data: PlayerData) {
        queuedPlayerSaves.remove(data.uuid)?.cancel()
        submitPlayerSave(data)
    }

    private fun submitPlayerSave(data: PlayerData) {
        if (!acceptingAsyncWrites.get() || !plugin.isEnabled) {
            savePlayer(data)
            return
        }

        try {
            writeExecutor.execute {
                savePlayer(data)
            }
        } catch (_: RejectedExecutionException) {
            savePlayer(data)
        }
    }

    fun queuePlayerSave(data: PlayerData, delayTicks: Long = 100L) {
        if (!acceptingAsyncWrites.get() || !plugin.isEnabled) {
            savePlayer(data)
            return
        }

        val delay = delayTicks.coerceAtLeast(1L)
        queuedPlayerSaves.compute(data.uuid) { uuid, existing ->
            if (existing != null && !existing.isCancelled) {
                existing
            } else {
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    queuedPlayerSaves.remove(uuid)
                    submitPlayerSave(data)
                }, delay)
            }
        }
    }

    fun cancelQueuedPlayerSaves() {
        queuedPlayerSaves.values.forEach { task ->
            if (!task.isCancelled) task.cancel()
        }
        queuedPlayerSaves.clear()
    }

    fun cancelQueuedPlayerSave(uuid: UUID) {
        queuedPlayerSaves.remove(uuid)?.let { task ->
            if (!task.isCancelled) task.cancel()
        }
    }

    fun resetPlayerPersistentData(uuid: UUID, playerName: String) {
        cancelQueuedPlayerSave(uuid)

        val freshData = PlayerData(uuid, playerName).apply {
            updateStatus(0)
        }
        savePlayer(freshData)

        dataSource?.connection?.use { conn ->
            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM player_quests WHERE uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeUpdate()
                }

                conn.prepareStatement("DELETE FROM player_titles WHERE player_uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM player_title_profiles WHERE player_uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeUpdate()
                }

                val playerUuidTables = listOf(
                    "player_shen_consciousness",
                    "player_shen_tribute",
                    "player_xian_talent",
                    "qixiazhen_busuan",
                    "farm_player_plots"
                )
                for (table in playerUuidTables) {
                    conn.prepareStatement("DELETE FROM $table WHERE player_uuid = ?").use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeUpdate()
                    }
                }

                conn.prepareStatement("DELETE FROM player_baihu_miasma WHERE uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeUpdate()
                }

                subsystems.saveWarehouse(conn, WarehouseData(uuid, playerName))

                conn.prepareStatement(
                    """
                    INSERT INTO player_chonghua (uuid, player_name, unlocked_waypoints, waypoint_cooldowns)
                    VALUES (?, ?, '[]', '{}')
                    ON CONFLICT(uuid) DO UPDATE SET
                        player_name = excluded.player_name,
                        unlocked_waypoints = excluded.unlocked_waypoints,
                        waypoint_cooldowns = excluded.waypoint_cooldowns
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setString(2, playerName)
                    ps.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    INSERT INTO player_element_crystal (player_uuid, player_name, gold_points, wood_points, water_points, fire_points, earth_points)
                    VALUES (?, ?, 0, 0, 0, 0, 0)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        player_name = excluded.player_name,
                        gold_points = 0,
                        wood_points = 0,
                        water_points = 0,
                        fire_points = 0,
                        earth_points = 0
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setString(2, playerName)
                    ps.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    INSERT INTO player_test (uuid, player_name, qinglong, baihu, zhuque, xuanwu)
                    VALUES (?, ?, 0, 0, 0, 0)
                    ON CONFLICT(uuid) DO UPDATE SET
                        player_name = excluded.player_name,
                        qinglong = 0,
                        baihu = 0,
                        zhuque = 0,
                        xuanwu = 0
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setString(2, playerName)
                    ps.executeUpdate()
                }

                conn.commit()
            } catch (ex: Exception) {
                try {
                    conn.rollback()
                } catch (rollbackEx: Exception) {
                    ex.addSuppressed(rollbackEx)
                }
                throw ex
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }
    }

    fun loadPlayer(uuid: UUID, playerName: String): CompletableFuture<PlayerData> =
        players.loadPlayer(uuid, playerName)

    fun loadPlayerQuests(conn: Connection, data: PlayerData) =
        subsystems.loadPlayerQuests(conn, data)

    fun saveQuestData(player: Player, questId: String, status: QuestStatus, progress: Int) =
        subsystems.saveQuestData(player, questId, status, progress)

    fun saveMedicalData(data: PlayerData) = subsystems.saveMedicalData(data)

    fun saveMedicalData(conn: Connection, data: PlayerData) =
        subsystems.saveMedicalData(conn, data)

    fun loadMedicalData(data: PlayerData) = subsystems.loadMedicalData(data)

    fun loadMedicalData(conn: Connection, data: PlayerData) =
        subsystems.loadMedicalData(conn, data)

    fun saveAlchemyData(conn: Connection, data: PlayerData) =
        subsystems.saveAlchemyData(conn, data)

    fun loadAlchemyData(conn: Connection, data: PlayerData) =
        subsystems.loadAlchemyData(conn, data)

    fun savePlayerStatus(conn: Connection, data: PlayerData) =
        subsystems.savePlayerStatus(conn, data)

    fun loadPlayerStatus(conn: Connection, data: PlayerData) =
        subsystems.loadPlayerStatus(conn, data)

    fun savePlayerGoldenChest(conn: Connection, data: PlayerData) =
        subsystems.savePlayerGoldenChest(conn, data)

    fun loadPlayerGoldenChest(conn: Connection, data: PlayerData) =
        subsystems.loadPlayerGoldenChest(conn, data)

    fun saveChonghuaData(data: ChonghuaData) = subsystems.saveChonghuaData(data)

    fun loadChonghuaData(uuid: UUID, playerName: String): ChonghuaData =
        subsystems.loadChonghuaData(uuid, playerName)

    fun saveDzPlayerData(data: DzPlayerData) = subsystems.saveDzPlayerData(data)

    fun saveWarehouse(conn: Connection, data: WarehouseData) =
        subsystems.saveWarehouse(conn, data)

    fun loadWarehouse(conn: Connection, uuid: UUID, playerName: String): WarehouseData =
        subsystems.loadWarehouse(conn, uuid, playerName)

    fun loadCompletedMedicalTrials(conn: Connection, data: PlayerData) =
        subsystems.loadCompletedMedicalTrials(conn, data)

    fun saveCompletedMedicalTrials(conn: Connection, data: PlayerData) =
        subsystems.saveCompletedMedicalTrials(conn, data)
}
