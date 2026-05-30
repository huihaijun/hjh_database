package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.chonghua.ChonghuaData
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.warehouse.data.WarehouseData
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.entity.Player
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DatabaseManager(private val plugin: Hjh_database) {
    var dataSource: HikariDataSource? = null

    private val schema = DatabaseSchema(this, plugin)
    private val players = DatabasePlayerRepository(this, plugin)
    private val subsystems = DatabaseSubsystemRepository(this, plugin)

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
        plugin.logger.info("SQLite 数据库连接成功！文件路径: ${dbFile.absolutePath}")
    }

    fun close() {
        dataSource?.close()
    }

    fun savePlayer(data: PlayerData) = players.savePlayer(data)

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
