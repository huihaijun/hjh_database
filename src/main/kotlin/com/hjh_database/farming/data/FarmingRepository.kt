package com.hjh_database.farming.data

import com.hjh_database.Hjh_database
import com.hjh_database.farming.config.FarmingConfig
import java.sql.Connection

class FarmingRepository(
    private val plugin: Hjh_database,
    private val config: FarmingConfig
) {
    fun load(uuid: String, playerName: String): FarmingPlayerData {
        val data = FarmingPlayerData(uuid, playerName, config.defaultMaxFields)
        try {
            plugin.databaseManager.dataSource?.connection?.use { conn ->
                loadProfile(conn, data)
                loadFields(conn, data)
            }
        } catch (ex: Exception) {
            plugin.logger.severe("加载灵田数据失败 [$playerName]: ${ex.message}")
            ex.printStackTrace()
        }
        data.allFields(config.fieldSlots.size)
        return data
    }

    private fun loadProfile(conn: Connection, data: FarmingPlayerData) {
        conn.prepareStatement("SELECT max_fields, total_harvests FROM player_farming_profile WHERE uuid = ?").use { ps ->
            ps.setString(1, data.uuid)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    data.maxFields = rs.getInt("max_fields").coerceAtLeast(1)
                    data.totalHarvests = rs.getInt("total_harvests")
                }
            }
        }
    }

    private fun loadFields(conn: Connection, data: FarmingPlayerData) {
        conn.prepareStatement("SELECT * FROM player_farming_fields WHERE uuid = ? ORDER BY field_index").use { ps ->
            ps.setString(1, data.uuid)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val field = data.field(rs.getInt("field_index"))
                    field.unlocked = rs.getInt("is_unlocked") != 0
                    field.plantType = rs.getString("plant_type")
                    field.plantedAt = rs.getLong("planted_at")
                    field.maturesAt = rs.getLong("matures_at")
                    field.growthStage = rs.getInt("growth_stage")
                    field.acceleratorId = rs.getString("accelerator_id")
                    field.boosterId = rs.getString("booster_id")
                    field.protectionId = rs.getString("protection_id")
                    field.protectionUntil = rs.getLong("protection_until")
                    field.yieldPenalty = rs.getDouble("yield_penalty")
                    field.delayPenaltyMs = rs.getLong("delay_penalty_ms")
                }
            }
        }
    }

    fun saveAll(data: FarmingPlayerData) {
        try {
            plugin.databaseManager.dataSource?.connection?.use { conn ->
                val originalAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    saveProfile(conn, data)
                    data.allFields(config.fieldSlots.size).forEach { saveField(conn, data, it) }
                    conn.commit()
                } catch (ex: Exception) {
                    conn.rollback()
                    throw ex
                } finally {
                    conn.autoCommit = originalAutoCommit
                }
            }
        } catch (ex: Exception) {
            plugin.logger.severe("保存灵田数据失败 [${data.playerName}]: ${ex.message}")
            ex.printStackTrace()
        }
    }

    fun saveProfile(data: FarmingPlayerData) {
        try {
            plugin.databaseManager.dataSource?.connection?.use { conn -> saveProfile(conn, data) }
        } catch (ex: Exception) {
            plugin.logger.severe("保存灵田玩家档案失败 [${data.playerName}]: ${ex.message}")
        }
    }

    fun saveField(data: FarmingPlayerData, field: FarmingField) {
        try {
            plugin.databaseManager.dataSource?.connection?.use { conn ->
                saveProfile(conn, data)
                saveField(conn, data, field)
            }
        } catch (ex: Exception) {
            plugin.logger.severe("保存灵田槽位失败 [${data.playerName} #${field.index + 1}]: ${ex.message}")
        }
    }

    private fun saveProfile(conn: Connection, data: FarmingPlayerData) {
        conn.prepareStatement(
            """
            INSERT INTO player_farming_profile (uuid, player_name, max_fields, total_harvests, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                max_fields = excluded.max_fields,
                total_harvests = excluded.total_harvests,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, data.uuid)
            ps.setString(2, data.playerName)
            ps.setInt(3, data.maxFields)
            ps.setInt(4, data.totalHarvests)
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    private fun saveField(conn: Connection, data: FarmingPlayerData, field: FarmingField) {
        conn.prepareStatement(
            """
            INSERT INTO player_farming_fields (
                uuid, player_name, field_index, is_unlocked, plant_type, planted_at, matures_at,
                growth_stage, accelerator_id, booster_id, protection_id, protection_until,
                yield_penalty, delay_penalty_ms, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid, field_index) DO UPDATE SET
                player_name = excluded.player_name,
                is_unlocked = excluded.is_unlocked,
                plant_type = excluded.plant_type,
                planted_at = excluded.planted_at,
                matures_at = excluded.matures_at,
                growth_stage = excluded.growth_stage,
                accelerator_id = excluded.accelerator_id,
                booster_id = excluded.booster_id,
                protection_id = excluded.protection_id,
                protection_until = excluded.protection_until,
                yield_penalty = excluded.yield_penalty,
                delay_penalty_ms = excluded.delay_penalty_ms,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, data.uuid)
            ps.setString(2, data.playerName)
            ps.setInt(3, field.index)
            ps.setInt(4, if (field.unlocked) 1 else 0)
            ps.setString(5, field.plantType)
            ps.setLong(6, field.plantedAt)
            ps.setLong(7, field.maturesAt)
            ps.setInt(8, field.growthStage)
            ps.setString(9, field.acceleratorId)
            ps.setString(10, field.boosterId)
            ps.setString(11, field.protectionId)
            ps.setLong(12, field.protectionUntil)
            ps.setDouble(13, field.yieldPenalty)
            ps.setLong(14, field.delayPenaltyMs)
            ps.setLong(15, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }
}
