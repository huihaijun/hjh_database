package com.hjh_database.qixiazhen.busuan.data

import com.hjh_database.Hjh_database
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** 所有方法都执行阻塞 JDBC；调用方必须通过 DatabaseManager 的数据库执行器调用。 */
class BusuanRepository(private val plugin: Hjh_database) {
    fun find(playerId: UUID): BusuanRecord? = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM qixiazhen_busuan WHERE player_uuid = ?").use { ps ->
            ps.setString(1, playerId.toString())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                BusuanRecord(
                    playerId,
                    rs.getString("player_name").orEmpty(),
                    LocalDate.parse(rs.getString("request_date")),
                    rs.getString("fortune_id")
                )
            }
        }
    }

    fun markRequested(playerId: UUID, playerName: String, date: LocalDate) = withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO qixiazhen_busuan (player_uuid, player_name, request_date, fortune_id, updated_at)
            VALUES (?, ?, ?, NULL, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                player_name = excluded.player_name,
                request_date = excluded.request_date,
                fortune_id = NULL,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playerId.toString())
            ps.setString(2, playerName)
            ps.setString(3, date.toString())
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    fun setFortune(playerId: UUID, date: LocalDate, fortuneId: String) = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE qixiazhen_busuan SET fortune_id = ?, updated_at = ? WHERE player_uuid = ? AND request_date = ?"
        ).use { ps ->
            ps.setString(1, fortuneId)
            ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, playerId.toString())
            ps.setString(4, date.toString())
            check(ps.executeUpdate() == 1) { "待解签记录不存在或日期已经变化" }
        }
    }

    fun reset(playerId: UUID) = withConnection { conn ->
        conn.prepareStatement("DELETE FROM qixiazhen_busuan WHERE player_uuid = ?").use { ps ->
            ps.setString(1, playerId.toString())
            ps.executeUpdate()
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val dataSource = plugin.databaseManager.dataSource ?: error("数据库连接池不可用")
        return dataSource.connection.use(block)
    }
}
