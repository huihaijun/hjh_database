package com.hjh_database.event.qixi

import com.hjh_database.Hjh_database
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.UUID

internal data class QixiBridgeGlobalState(
    val progress: Double = 0.0,
    val revision: Long = 0L
)

internal data class QixiBridgePlayerState(
    val uuid: UUID,
    val playerName: String,
    val dailyClaimDate: String? = null,
    val dailyUseDate: String? = null,
    val dailyUses: Int = 0,
    val dungeonRewardDate: String? = null,
    val dungeonRewardCount: Int = 0,
    val contribution: Int = 0,
    val claimedMilestones: Int = 0,
    val personalClaimedTiers: Int = 0,
    val revision: Long = 0L
)

/** 此类只包含阻塞 JDBC/迁移操作，调用方必须经 DatabaseManager.submitDatabaseOperation 执行。 */
internal class QixiBridgeBuildRepository(private val plugin: Hjh_database) {

    fun loadGlobal(): QixiBridgeGlobalState = connection { conn ->
        ensureGlobalRow(conn)
        conn.prepareStatement(
            "SELECT global_progress, revision FROM qixi_bridge_build WHERE uuid = ?"
        ).use { statement ->
            statement.setString(1, GLOBAL_UUID)
            statement.executeQuery().use { result ->
                check(result.next()) { "共建鹊桥全服进度行不存在" }
                QixiBridgeGlobalState(
                    progress = result.getDouble("global_progress").coerceIn(0.0, 100.0),
                    revision = result.getLong("revision")
                )
            }
        }
    }

    fun loadPlayer(uuid: UUID, currentName: String): QixiBridgePlayerState = connection { conn ->
        val loaded = selectPlayer(conn, uuid)
        if (loaded == null) {
            val created = QixiBridgePlayerState(uuid, currentName)
            savePlayer(conn, created)
            created
        } else if (loaded.playerName != currentName) {
            conn.prepareStatement("UPDATE qixi_bridge_build SET player_name = ?, updated_at = ? WHERE uuid = ?").use { statement ->
                statement.setString(1, currentName)
                statement.setLong(2, System.currentTimeMillis())
                statement.setString(3, uuid.toString())
                statement.executeUpdate()
            }
            loaded.copy(playerName = currentName)
        } else {
            loaded
        }
    }

    fun savePlayer(state: QixiBridgePlayerState) = connection { conn ->
        savePlayer(conn, state)
    }

    fun saveUse(global: QixiBridgeGlobalState, player: QixiBridgePlayerState) = transaction { conn ->
        saveGlobal(conn, global)
        savePlayer(conn, player)
    }

    /** 只在旧 YML 存在时迁移；成功后改名保留备份，后续启动不会重复读取。 */
    fun migrateLegacyYaml(file: File) {
        if (!file.exists()) return
        val legacy = YamlConfiguration.loadConfiguration(file)
        val migrationRevision = System.currentTimeMillis()

        transaction { conn ->
            ensureGlobalRow(conn)
            conn.prepareStatement(
                """
                UPDATE qixi_bridge_build
                SET global_progress = MAX(global_progress, ?), revision = MAX(revision, ?), updated_at = ?
                WHERE uuid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, legacy.getInt("global-progress", 0).coerceIn(0, 100))
                statement.setLong(2, migrationRevision)
                statement.setLong(3, migrationRevision)
                statement.setString(4, GLOBAL_UUID)
                statement.executeUpdate()
            }

            val players = legacy.getConfigurationSection("players")
            players?.getKeys(false)?.forEach { rawUuid ->
                val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return@forEach
                val path = "players.$rawUuid"
                val name = findPlayerName(conn, uuid) ?: rawUuid
                val claimedMask = legacy.getIntegerList("$path.claimed-milestones")
                    .fold(0) { mask, threshold -> mask or milestoneBit(threshold) }
                val migrated = QixiBridgePlayerState(
                    uuid = uuid,
                    playerName = name,
                    dailyClaimDate = legacy.getString("$path.daily-claim-date"),
                    dailyUseDate = legacy.getString("$path.daily-use-date"),
                    dailyUses = legacy.getInt("$path.daily-uses", 0).coerceIn(0, DAILY_USE_LIMIT),
                    contribution = legacy.getInt("$path.contribution", 0).coerceAtLeast(0),
                    claimedMilestones = claimedMask,
                    personalClaimedTiers = legacy.getInt("$path.personal-claimed-tiers", 0).coerceIn(0, 10),
                    revision = migrationRevision
                )
                insertPlayerIfAbsent(conn, migrated)
            }
        }

        val backup = File(file.parentFile, "${file.name}.migrated.bak")
        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        plugin.logger.info("旧版共建鹊桥 YML 数据已迁移至 qixi_bridge_build，并备份为 ${backup.name}。")
    }

    private fun selectPlayer(conn: Connection, uuid: UUID): QixiBridgePlayerState? {
        conn.prepareStatement(
            """
            SELECT player_name, daily_claim_date, daily_use_date, daily_uses,
                   dungeon_reward_date, dungeon_reward_count, contribution,
                   claimed_milestones, personal_claimed_tiers, revision
            FROM qixi_bridge_build WHERE uuid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                return QixiBridgePlayerState(
                    uuid = uuid,
                    playerName = result.getString("player_name"),
                    dailyClaimDate = result.getString("daily_claim_date"),
                    dailyUseDate = result.getString("daily_use_date"),
                    dailyUses = result.getInt("daily_uses").coerceIn(0, DAILY_USE_LIMIT),
                    dungeonRewardDate = result.getString("dungeon_reward_date"),
                    dungeonRewardCount = result.getInt("dungeon_reward_count").coerceIn(0, DAILY_DUNGEON_REWARD_LIMIT),
                    contribution = result.getInt("contribution").coerceAtLeast(0),
                    claimedMilestones = result.getInt("claimed_milestones"),
                    personalClaimedTiers = result.getInt("personal_claimed_tiers").coerceIn(0, 10),
                    revision = result.getLong("revision")
                )
            }
        }
    }

    private fun saveGlobal(conn: Connection, state: QixiBridgeGlobalState) {
        conn.prepareStatement(
            """
            INSERT INTO qixi_bridge_build (uuid, player_name, global_progress, revision, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                global_progress = excluded.global_progress,
                revision = excluded.revision,
                updated_at = excluded.updated_at
            WHERE excluded.revision >= qixi_bridge_build.revision
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, GLOBAL_UUID)
            statement.setString(2, GLOBAL_NAME)
            statement.setDouble(3, state.progress.coerceIn(0.0, 100.0))
            statement.setLong(4, state.revision)
            statement.setLong(5, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    private fun savePlayer(conn: Connection, state: QixiBridgePlayerState) {
        conn.prepareStatement(
            """
            INSERT INTO qixi_bridge_build (
                uuid, player_name, daily_claim_date, daily_use_date, daily_uses,
                dungeon_reward_date, dungeon_reward_count, contribution,
                claimed_milestones, personal_claimed_tiers, revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                daily_claim_date = excluded.daily_claim_date,
                daily_use_date = excluded.daily_use_date,
                daily_uses = excluded.daily_uses,
                dungeon_reward_date = excluded.dungeon_reward_date,
                dungeon_reward_count = excluded.dungeon_reward_count,
                contribution = excluded.contribution,
                claimed_milestones = excluded.claimed_milestones,
                personal_claimed_tiers = excluded.personal_claimed_tiers,
                revision = excluded.revision,
                updated_at = excluded.updated_at
            WHERE excluded.revision >= qixi_bridge_build.revision
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, state.uuid.toString())
            statement.setString(2, state.playerName)
            statement.setString(3, state.dailyClaimDate)
            statement.setString(4, state.dailyUseDate)
            statement.setInt(5, state.dailyUses.coerceIn(0, DAILY_USE_LIMIT))
            statement.setString(6, state.dungeonRewardDate)
            statement.setInt(7, state.dungeonRewardCount.coerceIn(0, DAILY_DUNGEON_REWARD_LIMIT))
            statement.setInt(8, state.contribution.coerceAtLeast(0))
            statement.setInt(9, state.claimedMilestones)
            statement.setInt(10, state.personalClaimedTiers.coerceIn(0, 10))
            statement.setLong(11, state.revision)
            statement.setLong(12, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    private fun insertPlayerIfAbsent(conn: Connection, state: QixiBridgePlayerState) {
        conn.prepareStatement(
            """
            INSERT INTO qixi_bridge_build (
                uuid, player_name, daily_claim_date, daily_use_date, daily_uses,
                dungeon_reward_date, dungeon_reward_count, contribution,
                claimed_milestones, personal_claimed_tiers, revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, state.uuid.toString())
            statement.setString(2, state.playerName)
            statement.setString(3, state.dailyClaimDate)
            statement.setString(4, state.dailyUseDate)
            statement.setInt(5, state.dailyUses)
            statement.setString(6, state.dungeonRewardDate)
            statement.setInt(7, state.dungeonRewardCount)
            statement.setInt(8, state.contribution)
            statement.setInt(9, state.claimedMilestones)
            statement.setInt(10, state.personalClaimedTiers)
            statement.setLong(11, state.revision)
            statement.setLong(12, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    private fun ensureGlobalRow(conn: Connection) {
        conn.prepareStatement(
            "INSERT INTO qixi_bridge_build (uuid, player_name) VALUES (?, ?) ON CONFLICT(uuid) DO NOTHING"
        ).use { statement ->
            statement.setString(1, GLOBAL_UUID)
            statement.setString(2, GLOBAL_NAME)
            statement.executeUpdate()
        }
    }

    private fun findPlayerName(conn: Connection, uuid: UUID): String? {
        conn.prepareStatement("SELECT player_name FROM player_data WHERE uuid = ?").use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { result ->
                return if (result.next()) result.getString("player_name") else null
            }
        }
    }

    private fun milestoneBit(threshold: Int): Int = when (threshold) {
        30 -> 1
        60 -> 2
        90 -> 4
        100 -> 8
        else -> 0
    }

    private fun <T> connection(block: (Connection) -> T): T {
        val source = plugin.databaseManager.dataSource ?: error("数据库连接池尚未初始化")
        return source.connection.use(block)
    }

    private fun <T> transaction(block: (Connection) -> T): T = connection { conn ->
        val oldAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (error: Throwable) {
            runCatching { conn.rollback() }
            throw error
        } finally {
            conn.autoCommit = oldAutoCommit
        }
    }

    companion object {
        private const val DAILY_USE_LIMIT = 7
        private const val DAILY_DUNGEON_REWARD_LIMIT = 6
        private const val GLOBAL_UUID = "00000000-0000-0000-0000-000000000000"
        private const val GLOBAL_NAME = "__GLOBAL__"
    }
}
