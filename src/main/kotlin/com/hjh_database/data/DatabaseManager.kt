package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzPlayerData
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Statement
import java.util.*
import java.util.concurrent.CompletableFuture

class DatabaseManager(private val plugin: Hjh_database) {
    private var dataSource: HikariDataSource? = null

    init {
        connect()

        // 1. 建表 (针对新服)
        createTable()
        createBankTable()
        createSkillTable()
        createForgeTable()
        // 在 createForgeTable(); 下面添加：
        createKaiWuTable()

        // 医师技能列表
        createMedicalTable()

        // 2. 【核心修复】自动补全旧表缺失的字段
        updateTables()
    }

    private fun connect() {
        val config = HikariConfig()
        // 读取 config.yml 配置
        val host = plugin.config.getString("database.host", "localhost")
        val port = plugin.config.getString("database.port", "3306")
        val dbName = plugin.config.getString("database.name", "hjh_rpg")
        val user = plugin.config.getString("database.user", "root")
        val pass = plugin.config.getString("database.password", "root")

        config.jdbcUrl = "jdbc:mysql://$host:$port/$dbName?useSSL=false&characterEncoding=utf8"
        config.username = user
        config.password = pass

        // 连接池配置
        config.maximumPoolSize = 10
        config.minimumIdle = 5
        config.connectionTimeout = 30000
        config.idleTimeout = 600000
        config.maxLifetime = 1800000

        dataSource = HikariDataSource(config)
        plugin.logger.info("数据库连接成功！")
    }

    fun close() {
        dataSource?.close()
    }

    // === 自动检测并补全字段 (关键修复) ===
    private fun updateTables() {
        plugin.logger.info("正在检查数据库表结构...")
        try {
            dataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    // 1. 修复 player_data
                    safeAddColumn(stmt, "player_data", "exp", "INT DEFAULT 0")
                    safeAddColumn(stmt, "player_data", "player_name", "VARCHAR(16)")
                    // 【新增】自动为旧数据表添加 total_rarity 字段
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

                    // 5. 【修复】player_kaiwu (开物术表)
                    safeAddColumn(stmt, "player_kaiwu", "player_name", "VARCHAR(16)")
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_level", "INT DEFAULT 1")
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_exp", "INT DEFAULT 0")
                    // 核心：精力值 + 稀疏存储JSON
                    safeAddColumn(stmt, "player_kaiwu", "kaiwu_energy", "DOUBLE DEFAULT 100.0")
                    safeAddColumn(stmt, "player_kaiwu", "node_data", "LONGTEXT")
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

    // === 建表逻辑 (确保列名正确) ===

    private fun createTable() {
        // 主数据表：加入 exp 和 total_rarity
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

    // === 存取逻辑 ===

    fun savePlayer(data: PlayerData) {
        // 1. player_data: 加入 total_rarity
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
            ON DUPLICATE KEY UPDATE player_name=?, kaiwu_level=?, kaiwu_exp=?, kaiwu_energy=?, node_data=?
        """.trimIndent()

        try {
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
                    // INSERT Values
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.kaiwuLevel)
                    ps.setInt(4, data.kaiwuExp)
                    ps.setDouble(5, data.kaiwuEnergy)
                    ps.setString(6, data.getNodeDataAsJsonString())
                    // UPDATE Values
                    ps.setString(7, data.playerName)
                    ps.setInt(8, data.kaiwuLevel)
                    ps.setInt(9, data.kaiwuExp)
                    ps.setDouble(10, data.kaiwuEnergy)
                    ps.setString(11, data.getNodeDataAsJsonString())

                    ps.executeUpdate()
                }

                // 保存医术
                saveMedicalData(data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存玩家数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    @Throws(SQLException::class)
    private fun checkExists(conn: java.sql.Connection, table: String, uuid: UUID): Boolean {
        val sql = "SELECT 1 FROM $table WHERE uuid = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return rs.next()
            }
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

            try {
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
                                } catch (e: Exception) {
                                }
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

                    // 5. 【加载】开物术
                    conn.prepareStatement(sqlKaiWu).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.kaiwuLevel = rs.getInt("kaiwu_level") } catch (e: Exception) {}
                                try { data.kaiwuExp = rs.getInt("kaiwu_exp") } catch (e: Exception) {}
                                try { data.kaiwuEnergy = rs.getDouble("kaiwu_energy") } catch (e: Exception) {}

                                // 读取 JSON 并还原为 Map
                                val jsonNode = rs.getString("node_data")
                                data.setNodeDataFromJsonString(jsonNode)
                            }
                        }
                    }

                    // 5. 加载医师数据
                    loadMedicalData(data)
                }
            } catch (e: SQLException) {
                plugin.logger.severe("加载玩家数据失败: " + e.message)
                e.printStackTrace()
            }
            data
        }
    }

    // ==========================================
    //            医师系统数据库逻辑
    // ==========================================

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

    /**
     * 单独保存玩家的医术数据
     */
    fun saveMedicalData(data: PlayerData) {
        val sql = """
            INSERT INTO player_medical (uuid, player_name, medical_skills) VALUES (?, ?, ?) 
            ON DUPLICATE KEY UPDATE player_name=?, medical_skills=?
        """.trimIndent()

        try {
            dataSource?.connection?.use { conn ->
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
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存医术数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadMedicalData(data: PlayerData) {
        val sql = "SELECT medical_skills FROM player_medical WHERE uuid=?"
        try {
            dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, data.uuid.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            data.setMedicalSkillsFromString(rs.getString("medical_skills"))
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载医师数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    /**
     * 保存/更新玩家的锻造数据
     */
    fun saveDzPlayerData(data: DzPlayerData) {
        val sql = """
            INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license) 
            VALUES (?, ?, ?, ?, ?) 
            ON DUPLICATE KEY UPDATE player_name=?, forge_level=?, forge_exp=?, forge_license=?
        """.trimIndent()

        try {
            dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    // INSERT 部分
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.forgeLevel)
                    ps.setInt(4, data.forgeExp)
                    ps.setInt(5, data.forgeLicense)

                    // UPDATE 部分
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
}