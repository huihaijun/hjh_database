package com.hjh_database.dz.admin

import com.hjh_database.data.DatabaseManager
import java.sql.Connection
import java.util.UUID

enum class ForgeSort(val displayName: String, internal val orderBy: String) {
    NAME_ASC("玩家名升序", "player_name COLLATE NOCASE ASC, uuid ASC"),
    NAME_DESC("玩家名降序", "player_name COLLATE NOCASE DESC, uuid DESC"),
    LEVEL_ASC("锻造等级升序", "forge_level ASC, player_name COLLATE NOCASE ASC, uuid ASC"),
    LEVEL_DESC("锻造等级降序", "forge_level DESC, player_name COLLATE NOCASE ASC, uuid ASC"),
    EXP_ASC("锻造经验升序", "forge_exp ASC, player_name COLLATE NOCASE ASC, uuid ASC"),
    EXP_DESC("锻造经验降序", "forge_exp DESC, player_name COLLATE NOCASE ASC, uuid ASC"),
    LICENSE_ASC("锻造资质升序", "forge_license ASC, player_name COLLATE NOCASE ASC, uuid ASC"),
    LICENSE_DESC("锻造资质降序", "forge_license DESC, player_name COLLATE NOCASE ASC, uuid ASC");

    fun toggled(field: ForgeSortField): ForgeSort = when (field) {
        ForgeSortField.NAME -> if (this == NAME_ASC) NAME_DESC else NAME_ASC
        ForgeSortField.LEVEL -> if (this == LEVEL_DESC) LEVEL_ASC else LEVEL_DESC
        ForgeSortField.EXP -> if (this == EXP_DESC) EXP_ASC else EXP_DESC
        ForgeSortField.LICENSE -> if (this == LICENSE_DESC) LICENSE_ASC else LICENSE_DESC
    }

    companion object {
        fun fromCommand(field: String?, direction: String?): ForgeSort? {
            val ascending = when (direction?.lowercase()) {
                null -> null
                "asc", "ascending", "升序" -> true
                "desc", "descending", "降序" -> false
                else -> return null
            }
            return when (field?.lowercase()) {
                null, "level", "lv", "等级" -> if (ascending == true) LEVEL_ASC else LEVEL_DESC
                "name", "player", "玩家", "玩家名" -> if (ascending == false) NAME_DESC else NAME_ASC
                "exp", "经验" -> if (ascending == true) EXP_ASC else EXP_DESC
                "license", "job", "资质" -> if (ascending == true) LICENSE_ASC else LICENSE_DESC
                else -> null
            }
        }
    }
}

enum class ForgeSortField { NAME, LEVEL, EXP, LICENSE }

data class ForgeAdminRow(
    val uuid: UUID,
    val playerName: String,
    val level: Int,
    val exp: Int,
    val license: Int
)

data class ForgeAdminPage(
    val rows: List<ForgeAdminRow>,
    val page: Int,
    val pageCount: Int,
    val totalPlayers: Int,
    val sort: ForgeSort
)

/** JDBC 查询必须由 DatabaseManager.submitDatabaseOperation 放入后台数据库队列。 */
class ForgeAdminRepository(private val database: DatabaseManager) {
    @Volatile private var indexesReady = false

    fun loadPage(requestedPage: Int, pageSize: Int, sort: ForgeSort): ForgeAdminPage {
        val source = database.dataSource ?: error("数据库连接池尚未初始化")
        source.connection.use { connection ->
            ensureIndexes(connection)
            val total = connection.prepareStatement("SELECT COUNT(*) FROM player_dzlv").use { statement ->
                statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
            }
            val pageCount = maxOf(1, (total + pageSize - 1) / pageSize)
            val page = requestedPage.coerceIn(0, pageCount - 1)
            val rows = ArrayList<ForgeAdminRow>(pageSize)
            val sql = """
                SELECT uuid, player_name, forge_level, forge_exp, forge_license
                FROM player_dzlv
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
                        rows += ForgeAdminRow(
                            uuid,
                            storedName.ifEmpty { uuidText.take(8) },
                            result.getInt("forge_level"),
                            result.getInt("forge_exp"),
                            result.getInt("forge_license")
                        )
                    }
                }
            }
            return ForgeAdminPage(rows, page, pageCount, total, sort)
        }
    }

    private fun ensureIndexes(connection: Connection) {
        if (indexesReady) return
        synchronized(this) {
            if (indexesReady) return
            connection.createStatement().use { statement ->
                statement.execute("CREATE INDEX IF NOT EXISTS idx_dz_admin_name ON player_dzlv (player_name COLLATE NOCASE, uuid)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_dz_admin_level ON player_dzlv (forge_level DESC, player_name COLLATE NOCASE, uuid)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_dz_admin_exp ON player_dzlv (forge_exp DESC, player_name COLLATE NOCASE, uuid)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_dz_admin_license ON player_dzlv (forge_license DESC, player_name COLLATE NOCASE, uuid)")
            }
            indexesReady = true
        }
    }
}
