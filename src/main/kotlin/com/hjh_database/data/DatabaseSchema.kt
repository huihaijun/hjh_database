package com.hjh_database.data

import com.hjh_database.Hjh_database
import java.sql.SQLException
import java.sql.Statement

internal class DatabaseSchema(
    private val manager: DatabaseManager,
    private val plugin: Hjh_database
) {

    fun initialize() {
        createTable()
        createBankTable()
        createSkillTable()
        createForgeTable()
        createKaiWuTable()
        createQuestTable()
        createAlchemyTable()
        createMedicalTable()
        createPlayerStatusTable()
        createChonghuaTable()
        createPlayerTestTable()
        createGoldenChestTable()
        createWarehouseTable()
        createMedicalTestTable()
        createJianghuXindeTable()
        createElementCrystalTable()
        createFarmingTable()
        createBusuanTable()
        createShenConsciousnessTable()
        createShenTributeTable()
        createTitleTables()
        updateTables()
    }

    private fun createBusuanTable() {
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS qixiazhen_busuan (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32),
                request_date VARCHAR(10) NOT NULL,
                fortune_id VARCHAR(64),
                updated_at BIGINT DEFAULT 0
            );
            """.trimIndent()
        )
    }

    private fun createShenConsciousnessTable() {
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS player_shen_consciousness (
                player_uuid VARCHAR(36) NOT NULL,
                template_id VARCHAR(128) NOT NULL,
                display_name TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                PRIMARY KEY (player_uuid, template_id)
            );
            """.trimIndent()
        )
    }

    private fun createShenTributeTable() {
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS player_shen_tribute (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32) NOT NULL,
                arrival_remaining_seconds INTEGER NOT NULL DEFAULT 7200,
                tribute_category VARCHAR(16),
                tribute_tier INTEGER NOT NULL DEFAULT 0,
                claim_remaining_seconds INTEGER NOT NULL DEFAULT 0,
                reminder_mask INTEGER NOT NULL DEFAULT 0,
                claim_window_started_at BIGINT NOT NULL DEFAULT 0,
                daily_claim_count INTEGER NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
    }

    private fun createTitleTables() {
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS player_title_profiles (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32) NOT NULL,
                equipped_title_id VARCHAR(128),
                show_chat INTEGER NOT NULL DEFAULT 1,
                show_overhead INTEGER NOT NULL DEFAULT 0,
                show_tab INTEGER NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS player_titles (
                player_uuid VARCHAR(36) NOT NULL,
                title_id VARCHAR(128) NOT NULL,
                custom_text TEXT,
                obtained_at BIGINT NOT NULL,
                obtained_source VARCHAR(128) NOT NULL,
                PRIMARY KEY (player_uuid, title_id)
            );
            """.trimIndent()
        )
        executeSql(
            "CREATE INDEX IF NOT EXISTS idx_player_titles_title_id ON player_titles(title_id);"
        )
    }

    private fun createElementCrystalTable() {
        executeSql(
            """
            CREATE TABLE IF NOT EXISTS player_element_crystal (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(16) NOT NULL,
                gold_points INT DEFAULT 0,
                wood_points INT DEFAULT 0,
                water_points INT DEFAULT 0,
                fire_points INT DEFAULT 0,
                earth_points INT DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // === 自动检测并补全字段 ===
    private fun updateTables() {
        plugin.logger.info("正在检查数据库表结构...")
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    // 1. 修复 player_data
                    safeAddColumn(stmt, "player_data", "exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_data", "exp_curve_version", "INT DEFAULT 1")
                    safeAddColumn(stmt, "player_data", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_data", "money", "DOUBLE DEFAULT 0")
                    safeAddColumn(stmt, "player_data", "total_rarity", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_data", "spirit_siphon", "DOUBLE DEFAULT 0")

                    // 2. 修复 player_element_zf_lvl
                    safeAddColumn(stmt, "player_element_zf_lvl", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_element_zf_lvl", "metal", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_element_zf_lvl", "wood", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_element_zf_lvl", "water", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_element_zf_lvl", "fire", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_element_zf_lvl", "earth", "INT DEFAULT 0")

                    // 3. 修复 player_dzlv (锻造表)
                    safeAddColumn(stmt, "player_dzlv", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_dzlv", "forge_level", "INT DEFAULT 1")
                    safeAddColumn(stmt, "player_dzlv", "forge_exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_dzlv", "forge_license", "INT DEFAULT 0")

                    // 4. 修复 player_elementbank (仓库表)
                    safeAddColumn(stmt, "player_elementbank", "player_name", "VARCHAR(16)")

                    // 5. 修复 player_kaiwu (开物术表)
                    safeAddColumn(stmt, "player_kaiwu", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_level", "INT DEFAULT 1")
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_energy", "DOUBLE DEFAULT 100.0")
                    safeAddColumn(stmt, "player_kaiwu", "node_data", "LONGTEXT")

                    // 6. 修复 player_alchemy 冶药法表
                    safeAddColumn(stmt, "player_alchemy", "alchemy_exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_alchemy", "juezhang_pill_sickness_end", "BIGINT DEFAULT 0")
                    safeAddColumn(stmt, "player_alchemy", "qushi_pill_sickness_end", "BIGINT DEFAULT 0")
                    safeAddColumn(stmt, "player_alchemy", "jiedu_pill_sickness_end", "BIGINT DEFAULT 0")
                    safeAddColumn(stmt, "player_jianghu_xinde", "player_name", "VARCHAR(32)")
                    safeAddColumn(stmt, "player_jianghu_xinde", "jianghu_xinde", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_jianghu_xinde", "xiushen_exp_gained", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_jianghu_xinde", "xiushen_last_level", "INT DEFAULT 1")

                    // 神族贡品：以首次领取为起点的现实时间 24 小时领取窗口。
                    safeAddColumn(stmt, "player_shen_tribute", "claim_window_started_at", "BIGINT NOT NULL DEFAULT 0")
                    safeAddColumn(stmt, "player_shen_tribute", "daily_claim_count", "INTEGER NOT NULL DEFAULT 0")
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun safeAddColumn(stmt: Statement, table: String, column: String, type: String) {
        try {
            stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $type")
            plugin.logger.info(">> [自动修复] 已为表 $table 添加字段: $column")
        } catch (e: SQLException) {
            // 忽略字段已存在的错误
        }
    }


    // ==========================================
    //            3. 数据库表创建逻辑
    // ==========================================

    private fun executeSql(sql: String) {
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun createTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_data (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                lv INT DEFAULT 1, 
                exp INT DEFAULT 0, 
                exp_curve_version INT DEFAULT 2,
                job INT, 
                race INT, 
                attack DOUBLE DEFAULT 0, 
                archer_damage DOUBLE DEFAULT 0, 
                armor DOUBLE DEFAULT 0, 
                speed DOUBLE DEFAULT 0.2, 
                max_health DOUBLE DEFAULT 20.0, 
                current_health DOUBLE DEFAULT 20.0, 
                toughness DOUBLE DEFAULT 0, 
                knock_back_res DOUBLE DEFAULT 0, 
                attack_speed DOUBLE DEFAULT 4.0, 
                crit_chance DOUBLE DEFAULT 0, 
                zf_str DOUBLE DEFAULT 0, 
                cool_reduce DOUBLE DEFAULT 0, 
                lingli DOUBLE DEFAULT 0, 
                money DOUBLE DEFAULT 0,
                total_rarity INT DEFAULT 0,
                spirit_siphon DOUBLE DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createBankTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_elementbank (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                metal INT DEFAULT 0, 
                wood INT DEFAULT 0, 
                water INT DEFAULT 0, 
                fire INT DEFAULT 0, 
                earth INT DEFAULT 0, 
                relive_stone INT DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createSkillTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_element_zf_lvl (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                metal INT DEFAULT 0, 
                wood INT DEFAULT 0, 
                water INT DEFAULT 0, 
                fire INT DEFAULT 0, 
                earth INT DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createForgeTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_dzlv (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                forge_level INT DEFAULT 1, 
                forge_exp INT DEFAULT 0, 
                forge_license INT DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createKaiWuTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_kaiwu (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                kaiwu_level INT DEFAULT 1, 
                kaiwu_exp INT DEFAULT 0, 
                kaiwu_energy DOUBLE DEFAULT 100.0, 
                node_data LONGTEXT
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createQuestTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_quests (
                uuid VARCHAR(36) NOT NULL,
                player_name VARCHAR(16),
                quest_id VARCHAR(64) NOT NULL,
                status VARCHAR(16) DEFAULT 'LOCKED',
                progress INT DEFAULT 0,
                PRIMARY KEY (uuid, quest_id),
                FOREIGN KEY (uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createAlchemyTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_alchemy (
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32),
                alchemy_level INT DEFAULT 1,
                alchemy_exp INT DEFAULT 0,
                pill_sickness_end BIGINT DEFAULT 0,
                juezhang_pill_sickness_end BIGINT DEFAULT 0,
                qushi_pill_sickness_end BIGINT DEFAULT 0,
                jiedu_pill_sickness_end BIGINT DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    fun createMedicalTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_medical (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(32), 
                medical_skills TEXT
            );
        """.trimIndent()
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun createPlayerStatusTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_status (
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32),
                status INTEGER DEFAULT 0,
                description TEXT
            );
        """.trimIndent()
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("创建玩家状态表失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun createChonghuaTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_chonghua (
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(50),
                unlocked_waypoints LONGTEXT,
                waypoint_cooldowns LONGTEXT
            )
        """.trimIndent()
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("创建重华晶数据表失败: ${e.message}")
        }
    }

    private fun createPlayerTestTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_test (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(32), 
                qinglong INTEGER DEFAULT 0, 
                baihu INTEGER DEFAULT 0, 
                zhuque INTEGER DEFAULT 0, 
                xuanwu INTEGER DEFAULT 0
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createGoldenChestTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_goldenchest (
                uuid VARCHAR(36) PRIMARY KEY, 
                player_name VARCHAR(16), 
                dungeon_data TEXT
            );
        """.trimIndent()
        executeSql(sql)
    }

    fun createWarehouseTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS player_warehouse (
            uuid VARCHAR(36) PRIMARY KEY,
            player_name VARCHAR(32),
            category_names TEXT,
            items_data LONGTEXT
        );
    """.trimIndent()
        executeSql(sql)
    }

    fun createMedicalTestTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS player_medicaltest (
            uuid VARCHAR(36) PRIMARY KEY,
            player_name VARCHAR(255),
            completed_trials TEXT
        )
    """.trimIndent()
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { it.executeUpdate(sql) }
            }
        } catch (e: Exception) {
            plugin.logger.severe("创建 player_medicaltest 表失败: ${e.message}")
        }
    }

    private fun createJianghuXindeTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_jianghu_xinde (
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32),
                jianghu_xinde INT DEFAULT 0,
                xiushen_exp_gained INT DEFAULT 0,
                xiushen_last_level INT DEFAULT 1
            );
        """.trimIndent()
        executeSql(sql)
    }

    private fun createFarmingTable() {
        // 旧版灵田是 GUI 九宫格槽位。新版改为世界田位，按需求直接清空旧数据结构。
        executeSql("DROP TABLE IF EXISTS player_farming_fields;")
        executeSql("DROP TABLE IF EXISTS player_farming_profile;")

        executeSql(
            """
            CREATE TABLE IF NOT EXISTS farm_player_plots (
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
            );
            """.trimIndent()
        )
    }

}
