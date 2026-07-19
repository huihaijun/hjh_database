package com.hjh_database.title

import com.hjh_database.Hjh_database
import java.sql.Connection
import java.util.UUID

internal class TitleRepository(private val plugin: Hjh_database) {

    fun loadProfile(uuid: UUID, playerName: String, settings: TitleSettings): PlayerTitleProfile {
        return connection { conn ->
            ensureProfile(conn, uuid, playerName, settings)
            readProfile(conn, uuid, playerName)
        }
    }

    fun resolveTarget(input: String): ResolvedTitleTarget? = connection { conn ->
        val parsedUuid = runCatching { UUID.fromString(input) }.getOrNull()
        if (parsedUuid != null) {
            findByUuid(conn, parsedUuid)?.let { return@connection it }
        }

        val statements = listOf(
            "SELECT player_uuid AS uuid, player_name FROM player_title_profiles WHERE player_name = ? COLLATE NOCASE LIMIT 1",
            "SELECT uuid, player_name FROM player_data WHERE player_name = ? COLLATE NOCASE LIMIT 1"
        )
        for (sql in statements) {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, input)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return@connection ResolvedTitleTarget(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("player_name") ?: input
                        )
                    }
                }
            }
        }
        null
    }

    fun giveTitle(
        target: ResolvedTitleTarget,
        titleId: String,
        source: String,
        settings: TitleSettings
    ): Pair<Boolean, PlayerTitleProfile> = connection { conn ->
        ensureProfile(conn, target.uuid, target.playerName, settings)
        val changed = conn.prepareStatement(
            """
            INSERT OR IGNORE INTO player_titles
                (player_uuid, title_id, custom_text, obtained_at, obtained_source)
            VALUES (?, ?, NULL, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, target.uuid.toString())
            ps.setString(2, titleId)
            ps.setLong(3, System.currentTimeMillis())
            ps.setString(4, source)
            ps.executeUpdate() > 0
        }
        changed to readProfile(conn, target.uuid, target.playerName)
    }

    fun revokeTitle(
        target: ResolvedTitleTarget,
        titleId: String,
        settings: TitleSettings
    ): Pair<Boolean, PlayerTitleProfile> = connection { conn ->
        ensureProfile(conn, target.uuid, target.playerName, settings)
        val oldAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val changed = conn.prepareStatement(
                "DELETE FROM player_titles WHERE player_uuid = ? AND title_id = ?"
            ).use { ps ->
                ps.setString(1, target.uuid.toString())
                ps.setString(2, titleId)
                ps.executeUpdate() > 0
            }
            conn.prepareStatement(
                """
                UPDATE player_title_profiles
                SET equipped_title_id = CASE WHEN equipped_title_id = ? THEN NULL ELSE equipped_title_id END,
                    updated_at = ?
                WHERE player_uuid = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, titleId)
                ps.setLong(2, System.currentTimeMillis())
                ps.setString(3, target.uuid.toString())
                ps.executeUpdate()
            }
            conn.commit()
            changed to readProfile(conn, target.uuid, target.playerName)
        } catch (ex: Throwable) {
            runCatching { conn.rollback() }
            throw ex
        } finally {
            conn.autoCommit = oldAutoCommit
        }
    }

    fun setEquipped(
        uuid: UUID,
        playerName: String,
        titleId: String?,
        settings: TitleSettings
    ): PlayerTitleProfile = connection { conn ->
        ensureProfile(conn, uuid, playerName, settings)
        if (titleId != null && !ownsTitle(conn, uuid, titleId)) {
            throw IllegalStateException("尚未拥有称号 $titleId")
        }
        conn.prepareStatement(
            "UPDATE player_title_profiles SET equipped_title_id = ?, player_name = ?, updated_at = ? WHERE player_uuid = ?"
        ).use { ps ->
            ps.setString(1, titleId)
            ps.setString(2, playerName)
            ps.setLong(3, System.currentTimeMillis())
            ps.setString(4, uuid.toString())
            ps.executeUpdate()
        }
        readProfile(conn, uuid, playerName)
    }

    fun renameCustomTitle(
        uuid: UUID,
        playerName: String,
        titleId: String,
        customText: String,
        settings: TitleSettings
    ): PlayerTitleProfile = connection { conn ->
        ensureProfile(conn, uuid, playerName, settings)
        val changed = conn.prepareStatement(
            "UPDATE player_titles SET custom_text = ? WHERE player_uuid = ? AND title_id = ?"
        ).use { ps ->
            ps.setString(1, customText)
            ps.setString(2, uuid.toString())
            ps.setString(3, titleId)
            ps.executeUpdate()
        }
        if (changed == 0) throw IllegalStateException("该自定义称号尚未购买")
        readProfile(conn, uuid, playerName)
    }

    fun setDisplay(
        target: ResolvedTitleTarget,
        channel: TitleDisplayChannel,
        enabled: Boolean,
        settings: TitleSettings
    ): PlayerTitleProfile = connection { conn ->
        ensureProfile(conn, target.uuid, target.playerName, settings)
        val column = when (channel) {
            TitleDisplayChannel.CHAT -> "show_chat"
            TitleDisplayChannel.OVERHEAD -> "show_overhead"
            TitleDisplayChannel.TAB -> "show_tab"
        }
        conn.prepareStatement(
            "UPDATE player_title_profiles SET $column = ?, player_name = ?, updated_at = ? WHERE player_uuid = ?"
        ).use { ps ->
            ps.setInt(1, if (enabled) 1 else 0)
            ps.setString(2, target.playerName)
            ps.setLong(3, System.currentTimeMillis())
            ps.setString(4, target.uuid.toString())
            ps.executeUpdate()
        }
        readProfile(conn, target.uuid, target.playerName)
    }

    fun purchaseCustomTitle(
        uuid: UUID,
        playerName: String,
        requestedIndex: Int,
        customIds: List<String>,
        settings: TitleSettings
    ): CustomPurchaseResult = connection { conn ->
        ensureProfile(conn, uuid, playerName, settings)
        val oldAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val ownedIds = mutableSetOf<String>()
            conn.prepareStatement("SELECT title_id FROM player_titles WHERE player_uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) ownedIds += rs.getString("title_id")
                }
            }
            val availableIds = customIds.take(settings.maxCustomTitles)
            val nextIndex = availableIds.indexOfFirst { it !in ownedIds }
            if (nextIndex == -1) {
                conn.rollback()
                return@connection CustomPurchaseResult.Rejected("已达到自定义称号购买上限")
            }
            if (requestedIndex != nextIndex) {
                conn.rollback()
                return@connection CustomPurchaseResult.Rejected("请按顺序购买自定义称号")
            }

            val titleId = availableIds[nextIndex]
            conn.prepareStatement(
                """
                INSERT INTO player_titles (player_uuid, title_id, custom_text, obtained_at, obtained_source)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, titleId)
                ps.setString(3, "&f我的称号")
                ps.setLong(4, System.currentTimeMillis())
                ps.setString(5, "purchase")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "UPDATE player_title_profiles SET player_name = ?, updated_at = ? WHERE player_uuid = ?"
            ).use { ps ->
                ps.setString(1, playerName)
                ps.setLong(2, System.currentTimeMillis())
                ps.setString(3, uuid.toString())
                ps.executeUpdate()
            }
            conn.commit()
            CustomPurchaseResult.Success(readProfile(conn, uuid, playerName), titleId)
        } catch (ex: Throwable) {
            runCatching { conn.rollback() }
            throw ex
        } finally {
            conn.autoCommit = oldAutoCommit
        }
    }

    private fun ensureProfile(
        conn: Connection,
        uuid: UUID,
        playerName: String,
        settings: TitleSettings
    ) {
        conn.prepareStatement(
            """
            INSERT INTO player_title_profiles
                (player_uuid, player_name, equipped_title_id, show_chat, show_overhead, show_tab, updated_at)
            VALUES (?, ?, NULL, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, playerName)
            ps.setInt(3, if (settings.defaultShowChat) 1 else 0)
            ps.setInt(4, if (settings.defaultShowOverhead) 1 else 0)
            ps.setInt(5, if (settings.defaultShowTab) 1 else 0)
            ps.setLong(6, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    private fun readProfile(conn: Connection, uuid: UUID, fallbackName: String): PlayerTitleProfile {
        var playerName = fallbackName
        var equipped: String? = null
        var showChat = true
        var showOverhead = false
        var showTab = false
        conn.prepareStatement(
            "SELECT player_name, equipped_title_id, show_chat, show_overhead, show_tab FROM player_title_profiles WHERE player_uuid = ?"
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    playerName = rs.getString("player_name") ?: fallbackName
                    equipped = rs.getString("equipped_title_id")
                    showChat = rs.getInt("show_chat") != 0
                    showOverhead = rs.getInt("show_overhead") != 0
                    showTab = rs.getInt("show_tab") != 0
                }
            }
        }

        val owned = linkedMapOf<String, OwnedTitle>()
        conn.prepareStatement(
            "SELECT title_id, custom_text, obtained_at, obtained_source FROM player_titles WHERE player_uuid = ?"
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val titleId = rs.getString("title_id")
                    owned[titleId] = OwnedTitle(
                        titleId = titleId,
                        customText = rs.getString("custom_text"),
                        obtainedAt = rs.getLong("obtained_at"),
                        obtainedSource = rs.getString("obtained_source") ?: "unknown"
                    )
                }
            }
        }
        if (equipped !in owned) equipped = null
        return PlayerTitleProfile(uuid, playerName, equipped, showChat, showOverhead, showTab, owned)
    }

    private fun ownsTitle(conn: Connection, uuid: UUID, titleId: String): Boolean {
        return conn.prepareStatement(
            "SELECT 1 FROM player_titles WHERE player_uuid = ? AND title_id = ? LIMIT 1"
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, titleId)
            ps.executeQuery().use { it.next() }
        }
    }

    private fun findByUuid(conn: Connection, uuid: UUID): ResolvedTitleTarget? {
        val statements = listOf(
            "SELECT player_name FROM player_title_profiles WHERE player_uuid = ? LIMIT 1",
            "SELECT player_name FROM player_data WHERE uuid = ? LIMIT 1"
        )
        for (sql in statements) {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return ResolvedTitleTarget(uuid, rs.getString("player_name") ?: uuid.toString())
                }
            }
        }
        return null
    }

    private fun <T> connection(block: (Connection) -> T): T {
        val dataSource = plugin.databaseManager.dataSource
            ?: throw IllegalStateException("数据库连接尚未初始化")
        return dataSource.connection.use(block)
    }
}
