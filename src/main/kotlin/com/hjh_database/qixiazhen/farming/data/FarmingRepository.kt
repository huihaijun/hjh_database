package com.hjh_database.qixiazhen.farming.data

import com.hjh_database.Hjh_database
import java.sql.Connection
import java.util.UUID

class FarmingRepository(private val plugin: Hjh_database) {
    fun hasLegacyLocationTables(): Boolean = withConnection { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('farm_plots', 'farm_controllers')"
        ).use { ps ->
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }

    fun loadLegacyPlots(): List<FarmPlot> = withConnection { conn ->
        if (!tableExists(conn, "farm_plots")) return@withConnection emptyList()
        conn.prepareStatement("SELECT * FROM farm_plots ORDER BY plot_id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            FarmPlot(
                                rs.getLong("plot_id"),
                                FarmBlockKey(UUID.fromString(rs.getString("world_uuid")), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                                rs.getString("world_name")
                            )
                        )
                    }
                }
            }
        }
    }

    fun loadLegacyControllers(): List<FarmController> = withConnection { conn ->
        if (!tableExists(conn, "farm_controllers")) return@withConnection emptyList()
        conn.prepareStatement("SELECT * FROM farm_controllers ORDER BY controller_id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            FarmController(
                                rs.getLong("controller_id"),
                                FarmBlockKey(UUID.fromString(rs.getString("world_uuid")), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                                rs.getString("world_name")
                            )
                        )
                    }
                }
            }
        }
    }

    fun removeLegacyLocationTables() {
        withConnection { conn ->
            conn.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }
            val oldAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                if (tableExists(conn, "farm_player_plots")) {
                    conn.createStatement().use { statement ->
                        statement.execute("DROP TABLE IF EXISTS farm_player_plots_yml_migration")
                        statement.execute(
                            """
                            CREATE TABLE farm_player_plots_yml_migration (
                                player_uuid VARCHAR(36) NOT NULL,
                                player_name VARCHAR(32),
                                plot_id BIGINT NOT NULL,
                                crop_id VARCHAR(64),
                                seed_resource_id VARCHAR(128),
                                planted_at BIGINT DEFAULT 0,
                                matures_at BIGINT DEFAULT 0,
                                yield_multiplier DOUBLE DEFAULT 1.0,
                                updated_at BIGINT DEFAULT 0,
                                PRIMARY KEY (player_uuid, plot_id)
                            )
                            """.trimIndent()
                        )
                        statement.execute(
                            """
                            INSERT INTO farm_player_plots_yml_migration
                            SELECT player_uuid, player_name, plot_id, crop_id, seed_resource_id,
                                   planted_at, matures_at, yield_multiplier, updated_at
                            FROM farm_player_plots
                            """.trimIndent()
                        )
                        statement.execute("DROP TABLE farm_player_plots")
                        statement.execute("ALTER TABLE farm_player_plots_yml_migration RENAME TO farm_player_plots")
                    }
                }
                conn.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS farm_controllers")
                    statement.execute("DROP TABLE IF EXISTS farm_plots")
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = oldAutoCommit
                conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            }
        }
    }

    fun deletePlotStates(id: Long) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM farm_player_plots WHERE plot_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun loadPlayer(playerId: UUID): MutableMap<Long, PlayerFarmState> = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM farm_player_plots WHERE player_uuid = ?").use { ps ->
            ps.setString(1, playerId.toString())
            ps.executeQuery().use { rs ->
                val result = mutableMapOf<Long, PlayerFarmState>()
                while (rs.next()) {
                    val state = PlayerFarmState(
                        playerId,
                        rs.getLong("plot_id"),
                        rs.getString("crop_id"),
                        rs.getString("seed_resource_id"),
                        rs.getLong("planted_at"),
                        rs.getLong("matures_at"),
                        rs.getDouble("yield_multiplier"),
                        rs.getLong("updated_at")
                    )
                    result[state.plotId] = state
                }
                result
            }
        }
    }

    fun saveState(playerName: String, state: PlayerFarmState) {
        withConnection { conn -> saveState(conn, playerName, state) }
    }

    fun saveAll(playerName: String, states: Collection<PlayerFarmState>) {
        withConnection { conn ->
            val old = conn.autoCommit
            conn.autoCommit = false
            try {
                states.forEach { saveState(conn, playerName, it) }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = old
            }
        }
    }

    private fun saveState(conn: Connection, playerName: String, state: PlayerFarmState) {
        state.updatedAt = System.currentTimeMillis()
        conn.prepareStatement(
            """
            INSERT INTO farm_player_plots (
                player_uuid, player_name, plot_id, crop_id, seed_resource_id,
                planted_at, matures_at, yield_multiplier, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid, plot_id) DO UPDATE SET
                player_name = excluded.player_name,
                crop_id = excluded.crop_id,
                seed_resource_id = excluded.seed_resource_id,
                planted_at = excluded.planted_at,
                matures_at = excluded.matures_at,
                yield_multiplier = excluded.yield_multiplier,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, state.playerId.toString())
            ps.setString(2, playerName)
            ps.setLong(3, state.plotId)
            ps.setString(4, state.cropId)
            ps.setString(5, state.seedResourceId)
            ps.setLong(6, state.plantedAt)
            ps.setLong(7, state.maturesAt)
            ps.setDouble(8, state.yieldMultiplier)
            ps.setLong(9, state.updatedAt)
            ps.executeUpdate()
        }
    }

    fun resetPlayer(playerId: UUID): Boolean {
        return withConnection { conn ->
            conn.prepareStatement("DELETE FROM farm_player_plots WHERE player_uuid = ?").use { ps ->
                ps.setString(1, playerId.toString())
                ps.executeUpdate()
                true
            }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        return try {
            val dataSource = plugin.databaseManager.dataSource ?: error("数据库连接池不可用")
            dataSource.connection.use(block)
        } catch (ex: Exception) {
            plugin.logger.severe("灵田数据库操作失败：${ex.message}")
            throw ex
        }
    }

    private fun tableExists(connection: Connection, table: String): Boolean {
        return connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { it.next() }
        }
    }
}
