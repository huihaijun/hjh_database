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
        updateTables()
    }

    // ==========================================

    // === 自动检测并补全字段 ===
    private fun updateTables() {
        plugin.logger.info("正在检查数据库表结构...")
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    // 1. 修复 player_data
                    safeAddColumn(stmt, "player_data", "exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_data", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_data", "total_rarity", "INT DEFAULT 0")

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
                total_rarity INT DEFAULT 0
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
                pill_sickness_end BIGINT DEFAULT 0
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

}
