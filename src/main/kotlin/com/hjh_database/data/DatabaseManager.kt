package com.hjh_database.data

import com.google.gson.Gson
import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.warehouse.data.WarehouseData
import com.hjh_database.warehouse.utils.ItemSerializer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.*
import java.util.concurrent.CompletableFuture

class DatabaseManager(private val plugin: Hjh_database) {
    var dataSource: HikariDataSource? = null

    init {
        // 确保插件数据文件夹存在
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        connect()

        // 1. 建表 (针对新服)
        createTable()
        createBankTable()
        createSkillTable()
        createForgeTable()
        createKaiWuTable()
        createQuestTable() // 【新增】任务独立表
        createAlchemyTable() // 新增 丹药表

        // 医师技能列表
        createMedicalTable()

        // 【新增】创建玩家状态表
        createPlayerStatusTable()
        // 新建重华晶系统数据库
        createChonghuaTable()
        // 【新增】创建四圣兽副本记录表
        createPlayerTestTable()
        // 【新增】创建金宝箱记录表
        createGoldenChestTable()
        // 【新增】玩家仓库表
        createWarehouseTable()


        // 2. 【核心修复】自动补全旧表缺失的字段
        updateTables()
    }

    // ==========================================
    //            1. 数据库连接与关闭
    // ==========================================

    private fun connect() {
        val config = HikariConfig()

        // --- 适配 SQLite 路径 ---
        val dbFile = File(plugin.dataFolder, "hjh_rpg.db")
        config.jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        config.driverClassName = "org.sqlite.JDBC"

        // --- 连接池配置 ---
        // SQLite 强烈建议 maximumPoolSize 设为 1，防止文件写入锁冲突
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


    // ==========================================
    //            2. 数据库表结构更新与修复
    // ==========================================

    // === 自动检测并补全字段 ===
    private fun updateTables() {
        plugin.logger.info("正在检查数据库表结构...")
        try {
            dataSource?.connection?.use { conn ->
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
            dataSource?.connection?.use { conn ->
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
            dataSource?.connection?.use { conn ->
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
            dataSource?.connection?.use { conn ->
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
            dataSource?.connection?.use { conn ->
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


    // ==========================================
    //            4. 核心玩家数据 存 / 取
    // ==========================================

    @Throws(SQLException::class)
    private fun checkExists(conn: Connection, table: String, uuid: UUID): Boolean {
        val sql = "SELECT 1 FROM $table WHERE uuid = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    fun savePlayer(data: PlayerData) {
        val updateMain = "UPDATE player_data SET player_name=?, lv=?, exp=?, job=?, race=?, attack=?, archer_damage=?, armor=?, speed=?, max_health=?, current_health=?, toughness=?, knock_back_res=?, attack_speed=?, crit_chance=?, zf_str=?, cool_reduce=?, lingli=?, total_rarity=? WHERE uuid=?"
        val insertMain = "INSERT INTO player_data (player_name, lv, exp, job, race, attack, archer_damage, armor, speed, max_health, current_health, toughness, knock_back_res, attack_speed, crit_chance, zf_str, cool_reduce, lingli, total_rarity, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val updateBank = "UPDATE player_elementbank SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=?, relive_stone=? WHERE uuid=?"
        val insertBank = "INSERT INTO player_elementbank (player_name, metal, wood, water, fire, earth, relive_stone, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val updateSkills = "UPDATE player_element_zf_lvl SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=? WHERE uuid=?"
        val insertSkills = "INSERT INTO player_element_zf_lvl (player_name, metal, wood, water, fire, earth, uuid) VALUES (?, ?, ?, ?, ?, ?, ?)"

        val updateForge = "UPDATE player_dzlv SET player_name=?, forge_level=?, forge_exp=?, forge_license=? WHERE uuid=?"
        val insertForge = "INSERT INTO player_dzlv (player_name, forge_level, forge_exp, forge_license, uuid) VALUES (?, ?, ?, ?, ?)"

        val saveKaiWu = """
            INSERT INTO player_kaiwu (uuid, player_name, kaiwu_level, kaiwu_exp, kaiwu_energy, node_data) 
            VALUES (?, ?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, kaiwu_level=?, kaiwu_exp=?, kaiwu_energy=?, node_data=?
        """.trimIndent()

        try {
            // 【死锁修复】这里获取唯一连接，并一直持有到所有数据保存完毕
            dataSource?.connection?.use { conn ->
                // 保存主数据
                conn.prepareStatement(updateMain).use { ps ->
                    ps.setString(1, data.playerName)
                    ps.setInt(2, data.lv)
                    ps.setInt(3, data.exp)
                    ps.setObject(4, data.job)
                    ps.setObject(5, data.race)
                    ps.setDouble(6, data.attack)
                    ps.setDouble(7, data.archerDamage)
                    ps.setDouble(8, data.armor)
                    ps.setDouble(9, data.speed)
                    ps.setDouble(10, data.maxHealth)
                    ps.setDouble(11, data.currentHealth)
                    ps.setDouble(12, data.toughness)
                    ps.setDouble(13, data.knockBackRes)
                    ps.setDouble(14, data.attackSpeed)
                    ps.setDouble(15, data.critChance)
                    ps.setDouble(16, data.zfStr)
                    ps.setDouble(17, data.coolReduce)
                    ps.setDouble(18, data.lingli)
                    ps.setInt(19, data.totalRarity)
                    ps.setString(20, data.uuid.toString())

                    if (ps.executeUpdate() == 0) {
                        conn.prepareStatement(insertMain).use { insertPs ->
                            insertPs.setString(1, data.playerName)
                            insertPs.setInt(2, data.lv)
                            insertPs.setInt(3, data.exp)
                            insertPs.setObject(4, data.job)
                            insertPs.setObject(5, data.race)
                            insertPs.setDouble(6, data.attack)
                            insertPs.setDouble(7, data.archerDamage)
                            insertPs.setDouble(8, data.armor)
                            insertPs.setDouble(9, data.speed)
                            insertPs.setDouble(10, data.maxHealth)
                            insertPs.setDouble(11, data.currentHealth)
                            insertPs.setDouble(12, data.toughness)
                            insertPs.setDouble(13, data.knockBackRes)
                            insertPs.setDouble(14, data.attackSpeed)
                            insertPs.setDouble(15, data.critChance)
                            insertPs.setDouble(16, data.zfStr)
                            insertPs.setDouble(17, data.coolReduce)
                            insertPs.setDouble(18, data.lingli)
                            insertPs.setInt(19, data.totalRarity)
                            insertPs.setString(20, data.uuid.toString())
                            insertPs.executeUpdate()
                        }
                    }
                }

                // 保存仓库
                if (checkExists(conn, "player_elementbank", data.uuid)) {
                    conn.prepareStatement(updateBank).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.metal)
                        ps.setInt(3, data.wood)
                        ps.setInt(4, data.water)
                        ps.setInt(5, data.fire)
                        ps.setInt(6, data.earth)
                        ps.setInt(7, data.reliveStone)
                        ps.setString(8, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertBank).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.metal)
                        ps.setInt(3, data.wood)
                        ps.setInt(4, data.water)
                        ps.setInt(5, data.fire)
                        ps.setInt(6, data.earth)
                        ps.setInt(7, data.reliveStone)
                        ps.setString(8, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存技能表
                if (checkExists(conn, "player_element_zf_lvl", data.uuid)) {
                    conn.prepareStatement(updateSkills).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.getElementLevel("METAL"))
                        ps.setInt(3, data.getElementLevel("WOOD"))
                        ps.setInt(4, data.getElementLevel("WATER"))
                        ps.setInt(5, data.getElementLevel("FIRE"))
                        ps.setInt(6, data.getElementLevel("EARTH"))
                        ps.setString(7, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertSkills).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.getElementLevel("METAL"))
                        ps.setInt(3, data.getElementLevel("WOOD"))
                        ps.setInt(4, data.getElementLevel("WATER"))
                        ps.setInt(5, data.getElementLevel("FIRE"))
                        ps.setInt(6, data.getElementLevel("EARTH"))
                        ps.setString(7, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存锻造
                if (checkExists(conn, "player_dzlv", data.uuid)) {
                    conn.prepareStatement(updateForge).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.forgeLevel)
                        ps.setInt(3, data.forgeExp)
                        ps.setInt(4, data.forgeLicense)
                        ps.setString(5, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertForge).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.forgeLevel)
                        ps.setInt(3, data.forgeExp)
                        ps.setInt(4, data.forgeLicense)
                        ps.setString(5, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存开物术
                conn.prepareStatement(saveKaiWu).use { ps ->
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.kaiwuLevel)
                    ps.setInt(4, data.kaiwuExp)
                    ps.setDouble(5, data.kaiwuEnergy)
                    ps.setString(6, data.getNodeDataAsJsonString())
                    ps.setString(7, data.playerName)
                    ps.setInt(8, data.kaiwuLevel)
                    ps.setInt(9, data.kaiwuExp)
                    ps.setDouble(10, data.kaiwuEnergy)
                    ps.setString(11, data.getNodeDataAsJsonString())
                    ps.executeUpdate()
                }

                // 【死锁修复】保存子系统 (传入当前 conn)
                saveMedicalData(conn, data)
                saveAlchemyData(conn, data)
                savePlayerStatus(conn, data)
                savePlayerGoldenChest(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存玩家数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadPlayer(uuid: UUID, playerName: String): CompletableFuture<PlayerData> {
        return CompletableFuture.supplyAsync {
            val data = PlayerData(uuid, playerName)
            val sqlMain = "SELECT * FROM player_data WHERE uuid = ?"
            val sqlBank = "SELECT * FROM player_elementbank WHERE uuid = ?"
            val sqlSkills = "SELECT * FROM player_element_zf_lvl WHERE uuid = ?"
            val sqlForge = "SELECT * FROM player_dzlv WHERE uuid = ?"
            val sqlKaiWu = "SELECT * FROM player_kaiwu WHERE uuid = ?"
            val sqlCompletedQuests = "SELECT quest_id FROM player_quests WHERE uuid = ? AND status = 'COMPLETED'"

            try {
                // 【死锁修复】获取唯一连接
                dataSource?.connection?.use { conn ->
                    // 1. 加载主数据
                    conn.prepareStatement(sqlMain).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                data.lv = rs.getInt("lv")
                                data.exp = rs.getInt("exp")
                                val job = rs.getObject("job") as? Int
                                if (job != null) data.job = job
                                val race = rs.getObject("race") as? Int
                                if (race != null) data.race = race
                                data.attack = rs.getDouble("attack")
                                data.archerDamage = rs.getDouble("archer_damage")
                                data.armor = rs.getDouble("armor")
                                data.speed = rs.getDouble("speed")
                                data.maxHealth = rs.getDouble("max_health")
                                data.currentHealth = rs.getDouble("current_health")
                                data.toughness = rs.getDouble("toughness")
                                data.knockBackRes = rs.getDouble("knock_back_res")
                                data.attackSpeed = rs.getDouble("attack_speed")
                                data.critChance = rs.getDouble("crit_chance")
                                data.zfStr = rs.getDouble("zf_str")
                                data.coolReduce = rs.getDouble("cool_reduce")
                                data.lingli = rs.getDouble("lingli")
                                try {
                                    data.totalRarity = rs.getInt("total_rarity")
                                } catch (e: Exception) {}
                            }
                        }
                    }

                    // 2. 加载仓库
                    conn.prepareStatement(sqlBank).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                data.metal = rs.getInt("metal")
                                data.wood = rs.getInt("wood")
                                data.water = rs.getInt("water")
                                data.fire = rs.getInt("fire")
                                data.earth = rs.getInt("earth")
                                data.reliveStone = rs.getInt("relive_stone")
                            }
                        }
                    }

                    // 3. 加载技能
                    conn.prepareStatement(sqlSkills).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.setElementLevel("METAL", rs.getInt("metal")) } catch (e: Exception) {}
                                try { data.setElementLevel("WOOD", rs.getInt("wood")) } catch (e: Exception) {}
                                try { data.setElementLevel("WATER", rs.getInt("water")) } catch (e: Exception) {}
                                try { data.setElementLevel("FIRE", rs.getInt("fire")) } catch (e: Exception) {}
                                try { data.setElementLevel("EARTH", rs.getInt("earth")) } catch (e: Exception) {}
                            }
                        }
                    }

                    // 4. 加载锻造
                    conn.prepareStatement(sqlForge).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.forgeLevel = rs.getInt("forge_level") } catch (e: Exception) {}
                                try { data.forgeExp = rs.getInt("forge_exp") } catch (e: Exception) {}
                                try { data.forgeLicense = rs.getInt("forge_license") } catch (e: Exception) {}
                            }
                        }
                    }

                    // 5. 加载开物术
                    conn.prepareStatement(sqlKaiWu).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.kaiwuLevel = rs.getInt("kaiwu_level") } catch (e: Exception) {}
                                try { data.kaiwuExp = rs.getInt("kaiwu_exp") } catch (e: Exception) {}
                                try { data.kaiwuEnergy = rs.getDouble("kaiwu_energy") } catch (e: Exception) {}
                                val jsonNode = rs.getString("node_data")
                                data.setNodeDataFromJsonString(jsonNode)
                            }
                        }
                    }

                    // 6. 加载各项子系统 (传入 conn 避免死锁)
                    loadMedicalData(conn, data)
                    loadPlayerQuests(conn, data)
                    loadAlchemyData(conn, data)
                    loadPlayerStatus(conn, data)
                    loadPlayerGoldenChest(conn, data)

                    // 7. 加载已完成的任务到缓存 (优化 RaceManager)
                    conn.prepareStatement(sqlCompletedQuests).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val qId = rs.getString("quest_id")
                                if (qId != null) {
                                    data.completedQuests.add(qId)
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("加载玩家数据失败: " + e.message)
                e.printStackTrace()
            }
            data
        }
    }


    // ==========================================
    //            5. 各子系统数据独立 存 / 取
    // ==========================================

    // ----------------- Quest 任务 -----------------
    fun loadPlayerQuests(conn: Connection, data: PlayerData) {
        val sql = "SELECT quest_id, status, progress FROM player_quests WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val qId = rs.getString("quest_id")
                        val statusStr = rs.getString("status")
                        val progress = rs.getInt("progress")

                        val status = try {
                            QuestStatus.valueOf(statusStr)
                        } catch (e: Exception) { QuestStatus.LOCKED }

                        data.questStatuses[qId] = status
                        data.questProgress[qId] = progress
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载任务数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 保存单个任务状态 (异步 Upsert 操作)
     */
    fun saveQuestData(player: org.bukkit.entity.Player, questId: String, status: QuestStatus, progress: Int) {
        val sql = """
            INSERT INTO player_quests (uuid, player_name, quest_id, status, progress) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid, quest_id) DO UPDATE SET 
            player_name=?, status=?, progress=?
        """.trimIndent()

        java.util.concurrent.CompletableFuture.runAsync {
            try {
                dataSource?.connection?.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, player.uniqueId.toString())
                        ps.setString(2, player.name)
                        ps.setString(3, questId)
                        ps.setString(4, status.name)
                        ps.setInt(5, progress)

                        ps.setString(6, player.name)
                        ps.setString(7, status.name)
                        ps.setInt(8, progress)

                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("保存任务 $questId 失败: ${e.message}")
            }
        }
    }


    // ----------------- Medical 医师 -----------------
    fun saveMedicalData(data: PlayerData) {
        try {
            dataSource?.connection?.use { conn ->
                saveMedicalData(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("独立保存医术数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun saveMedicalData(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_medical (uuid, player_name, medical_skills) VALUES (?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, medical_skills=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                val uuidStr = data.uuid.toString()
                val name = data.playerName
                val skillsStr = data.getMedicalSkillsAsString()

                ps.setString(1, uuidStr)
                ps.setString(2, name)
                ps.setString(3, skillsStr)

                ps.setString(4, name)
                ps.setString(5, skillsStr)

                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存医术数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadMedicalData(data: PlayerData) {
        try {
            dataSource?.connection?.use { conn ->
                loadMedicalData(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("独立加载医师数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadMedicalData(conn: Connection, data: PlayerData) {
        val sql = "SELECT medical_skills FROM player_medical WHERE uuid=?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        data.setMedicalSkillsFromString(rs.getString("medical_skills"))
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载医师数据失败: " + e.message)
            e.printStackTrace()
        }
    }


    // ----------------- Alchemy 丹药 -----------------
    fun saveAlchemyData(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_alchemy (uuid, player_name, alchemy_level, alchemy_exp, pill_sickness_end) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, alchemy_level=?, alchemy_exp=?, pill_sickness_end=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setInt(3, data.alchemyLevel)
                ps.setInt(4, data.alchemyExp)
                ps.setLong(5, data.pillSicknessEnd)

                ps.setString(6, data.playerName)
                ps.setInt(7, data.alchemyLevel)
                ps.setInt(8, data.alchemyExp)
                ps.setLong(9, data.pillSicknessEnd)

                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存丹药数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadAlchemyData(conn: Connection, data: PlayerData) {
        val sql = "SELECT alchemy_level, alchemy_exp, pill_sickness_end FROM player_alchemy WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        data.alchemyLevel = rs.getInt("alchemy_level")
                        data.alchemyExp = rs.getInt("alchemy_exp")
                        data.pillSicknessEnd = rs.getLong("pill_sickness_end")
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载丹药数据失败: " + e.message)
            e.printStackTrace()
        }
    }


    // ----------------- Status 玩家状态 -----------------
    fun savePlayerStatus(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_status (uuid, status, description) 
            VALUES (?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET status = ?, description = ?;
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setInt(2, data.status)
                ps.setString(3, data.statusDescription)
                ps.setInt(4, data.status)
                ps.setString(5, data.statusDescription)
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.warning("保存玩家状态失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadPlayerStatus(conn: Connection, data: PlayerData) {
        val sql = "SELECT status, description FROM player_status WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val s = rs.getInt("status")
                        data.updateStatus(s)
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("读取玩家状态失败: ${e.message}")
            e.printStackTrace()
        }
    }


    // ----------------- Golden Chest 金宝箱 -----------------
    fun savePlayerGoldenChest(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_goldenchest (uuid, player_name, dungeon_data) 
            VALUES (?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, dungeon_data=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                val json = data.getDungeonRecordsAsJson()
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setString(3, json)

                ps.setString(4, data.playerName)
                ps.setString(5, json)

                ps.executeUpdate()
            }
        } catch (e: Exception) {
            plugin.logger.severe("保存玩家 ${data.playerName} 的金宝箱数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadPlayerGoldenChest(conn: Connection, data: PlayerData) {
        val sql = "SELECT dungeon_data FROM player_goldenchest WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val json = rs.getString("dungeon_data")
                        data.setDungeonRecordsFromJson(json)
                    } else {
                        data.setDungeonRecordsFromJson("{}")
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取玩家 ${data.playerName} 的金宝箱数据失败: ${e.message}")
            e.printStackTrace()
            data.setDungeonRecordsFromJson("{}")
        }
    }


    // ----------------- Chonghua 重华晶 -----------------
    fun saveChonghuaData(data: com.hjh_database.chonghua.ChonghuaData) {
        val sql = """
            INSERT INTO player_chonghua (uuid, player_name, unlocked_waypoints, waypoint_cooldowns) 
            VALUES (?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET 
            player_name=?, unlocked_waypoints=?, waypoint_cooldowns=?
        """.trimIndent()

        try {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                dataSource?.connection?.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setString(3, data.getUnlockedAsJson())
                        ps.setString(4, data.getCooldownsAsJson())

                        ps.setString(5, data.playerName)
                        ps.setString(6, data.getUnlockedAsJson())
                        ps.setString(7, data.getCooldownsAsJson())

                        ps.executeUpdate()
                    }
                }
            })
        } catch (e: Exception) {
            plugin.logger.severe("保存重华晶数据失败: ${e.message}")
        }
    }

    fun loadChonghuaData(uuid: UUID, playerName: String): com.hjh_database.chonghua.ChonghuaData {
        val data = com.hjh_database.chonghua.ChonghuaData(uuid, playerName)
        val sql = "SELECT * FROM player_chonghua WHERE uuid = ?"
        try {
            dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, uuid.toString())
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        data.playerName = rs.getString("player_name")
                        data.setUnlockedFromJson(rs.getString("unlocked_waypoints"))
                        data.setCooldownsFromJson(rs.getString("waypoint_cooldowns"))
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取重华晶数据失败: ${e.message}")
        }
        return data
    }


    // ----------------- DzPlayer 锻造 (独立保存) -----------------
    fun saveDzPlayerData(data: DzPlayerData) {
        val sql = """
            INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, forge_level=?, forge_exp=?, forge_license=?
        """.trimIndent()

        try {
            dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.forgeLevel)
                    ps.setInt(4, data.forgeExp)
                    ps.setInt(5, data.forgeLicense)

                    ps.setString(6, data.playerName)
                    ps.setInt(7, data.forgeLevel)
                    ps.setInt(8, data.forgeExp)
                    ps.setInt(9, data.forgeLicense)
                    ps.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存锻造数据失败 [" + data.playerName + "]: " + e.message)
            e.printStackTrace()
        }
    }

    // ----------------- WareHouse 个人仓库 -----------------
    fun saveWarehouse(conn: Connection, data: WarehouseData) {
        val sql = """
            INSERT INTO player_warehouse (uuid, player_name, category_names, items_data) 
            VALUES (?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, category_names=?, items_data=?
        """.trimIndent()

        try {
            // 直接使用传进来的 conn
            conn.prepareStatement(sql).use { ps ->
                val namesJson = Gson().toJson(data.categoryNames)
                val allItems = data.items.flatten().toTypedArray()
                val itemsBase64 = ItemSerializer.itemsToBase64(allItems)

                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setString(3, namesJson)
                ps.setString(4, itemsBase64)

                ps.setString(5, data.playerName)
                ps.setString(6, namesJson)
                ps.setString(7, itemsBase64)
                ps.executeUpdate()
            }
        } catch (e: Exception) {
            plugin.logger.severe("保存仓库数据失败: ${e.message}")
        }
    }

    fun loadWarehouse(conn: Connection, uuid: UUID, playerName: String): WarehouseData {
        val data = WarehouseData(uuid, playerName)
        val sql = "SELECT * FROM player_warehouse WHERE uuid = ?"
        try {
            // 直接使用传进来的 conn，删掉 dataSource?.connection?.use
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val namesJson = rs.getString("category_names")
                        data.categoryNames = Gson().fromJson(namesJson, Array<String>::class.java)

                        val itemsBase64 = rs.getString("items_data")
                        val allItems = ItemSerializer.base64ToItems(itemsBase64)
                        // 恢复到二维数组
                        for (i in 0..7) {
                            System.arraycopy(allItems, i * 108, data.items[i], 0, 108)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取仓库数据失败: ${e.message}")
        }
        return data
    }
}