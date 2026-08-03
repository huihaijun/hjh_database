package com.hjh_database.admin.money

import com.hjh_database.data.DatabaseManager
import java.util.UUID

enum class MoneySort(val displayName: String, internal val orderBy: String) {
    NAME_ASC("玩家名升序", "player_name COLLATE NOCASE ASC, uuid ASC"),
    NAME_DESC("玩家名降序", "player_name COLLATE NOCASE DESC, uuid DESC"),
    MONEY_ASC("财产升序", "COALESCE(money, 0) ASC, player_name COLLATE NOCASE ASC, uuid ASC"),
    MONEY_DESC("财产降序", "COALESCE(money, 0) DESC, player_name COLLATE NOCASE ASC, uuid ASC");

    companion object {
        fun fromCommand(field: String?, direction: String?): MoneySort? {
            val ascending = when (direction?.lowercase()) {
                null -> null
                "desc", "descending", "降序" -> false
                "asc", "ascending", "升序" -> true
                else -> return null
            }
            return when (field?.lowercase()) {
                null, "money", "财产" -> if (ascending == true) MONEY_ASC else MONEY_DESC
                "name", "player", "玩家", "玩家名" -> if (ascending != false) NAME_ASC else NAME_DESC
                else -> null
            }
        }
    }
}

data class MoneyAdminRow(
    val uuid: UUID,
    val playerName: String,
    val money: Double
)

data class MoneyAdminPage(
    val rows: List<MoneyAdminRow>,
    val page: Int,
    val pageCount: Int,
    val totalPlayers: Int,
    val sort: MoneySort
)

/**
 * 这里的所有 JDBC 操作都是阻塞操作，必须通过 DatabaseManager.submitDatabaseOperation 调用。
 * 查询只取当前 GUI 页，并依靠索引完成排序，避免把整张玩家表载入内存。
 */
class MoneyAdminRepository(private val database: DatabaseManager) {
    @Volatile
    private var indexesReady = false

    fun loadPage(requestedPage: Int, pageSize: Int, sort: MoneySort): MoneyAdminPage {
        val dataSource = database.dataSource ?: error("数据库连接池尚未初始化")
        dataSource.connection.use { connection ->
            ensureIndexes(connection)

            val totalPlayers = connection.prepareStatement("SELECT COUNT(*) FROM player_data").use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
            val pageCount = maxOf(1, (totalPlayers + pageSize - 1) / pageSize)
            val page = requestedPage.coerceIn(0, pageCount - 1)
            val rows = ArrayList<MoneyAdminRow>(pageSize)
            val sql = """
                SELECT uuid, player_name, COALESCE(money, 0) AS money_value
                FROM player_data
                ORDER BY ${sort.orderBy}
                LIMIT ? OFFSET ?
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, pageSize)
                statement.setLong(2, page.toLong() * pageSize)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val uuidText = result.getString("uuid")
                        val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull() ?: continue
                        val storedName = result.getString("player_name")?.trim().orEmpty()
                        rows += MoneyAdminRow(
                            uuid = uuid,
                            playerName = storedName.ifEmpty { uuidText.take(8) },
                            money = result.getDouble("money_value")
                        )
                    }
                }
            }
            return MoneyAdminPage(rows, page, pageCount, totalPlayers, sort)
        }
    }

    private fun ensureIndexes(connection: java.sql.Connection) {
        if (indexesReady) return
        synchronized(this) {
            if (indexesReady) return
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_player_data_admin_name " +
                        "ON player_data (player_name COLLATE NOCASE, uuid)"
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_player_data_admin_money " +
                        "ON player_data (COALESCE(money, 0) DESC, player_name COLLATE NOCASE, uuid)"
                )
            }
            indexesReady = true
        }
    }
}
