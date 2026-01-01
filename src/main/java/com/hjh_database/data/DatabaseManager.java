package com.hjh_database.data;

import com.hjh_database.Hjh_database;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {
    private final Hjh_database plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(Hjh_database plugin) {
        this.plugin = plugin;
        connect();

        // 1. 建表 (针对新服)
        createTable();
        createBankTable();
        createSkillTable();
        createForgeTable();

        // 2. 【核心修复】自动补全旧表缺失的字段 (使用你正确的列名)
        updateTables();
    }

    private void connect() {
        HikariConfig config = new HikariConfig();
        // 读取 config.yml 配置
        String host = plugin.getConfig().getString("database.host", "localhost");
        String port = plugin.getConfig().getString("database.port", "3306");
        String dbName = plugin.getConfig().getString("database.name", "hjh_rpg");
        String user = plugin.getConfig().getString("database.user", "root");
        String pass = plugin.getConfig().getString("database.password", "root");

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=false&characterEncoding=utf8");
        config.setUsername(user);
        config.setPassword(pass);

        // 连接池配置
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);
        plugin.getLogger().info("数据库连接成功！");
    }

    public void close() {
        if (dataSource != null) dataSource.close();
    }

    // === 自动检测并补全字段 (关键修复) ===
    private void updateTables() {
        plugin.getLogger().info("正在检查数据库表结构...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. 修复 player_data (补全 exp 和 player_name)
            safeAddColumn(stmt, "player_data", "exp", "INT DEFAULT 0");
            safeAddColumn(stmt, "player_data", "player_name", "VARCHAR(16)");

            // 2. 修复 player_element_zf_lvl (使用正确的列名: metal, wood...)
            safeAddColumn(stmt, "player_element_zf_lvl", "player_name", "VARCHAR(16)");
            safeAddColumn(stmt, "player_element_zf_lvl", "metal", "INT DEFAULT 0"); // 之前错写成 metal_lvl
            safeAddColumn(stmt, "player_element_zf_lvl", "wood", "INT DEFAULT 0");
            safeAddColumn(stmt, "player_element_zf_lvl", "water", "INT DEFAULT 0");
            safeAddColumn(stmt, "player_element_zf_lvl", "fire", "INT DEFAULT 0");
            safeAddColumn(stmt, "player_element_zf_lvl", "earth", "INT DEFAULT 0");

            // 3. 修复 player_dzlv (锻造表)
            safeAddColumn(stmt, "player_dzlv", "player_name", "VARCHAR(16)");
            safeAddColumn(stmt, "player_dzlv", "forge_level", "INT DEFAULT 1");
            safeAddColumn(stmt, "player_dzlv", "forge_exp", "INT DEFAULT 0");
            safeAddColumn(stmt, "player_dzlv", "forge_license", "INT DEFAULT 0");

            // 4. 修复 player_elementbank (仓库表)
            safeAddColumn(stmt, "player_elementbank", "player_name", "VARCHAR(16)");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void safeAddColumn(Statement stmt, String table, String column, String type) {
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            plugin.getLogger().info(">> [自动修复] 已为表 " + table + " 添加字段: " + column);
        } catch (SQLException e) {
            // 忽略字段已存在的错误
        }
    }

    // === 建表逻辑 (确保列名正确) ===

    private void createTable() {
        // 主数据表：加入 exp
        String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16), " +
                "lv INT DEFAULT 1, " +
                "exp INT DEFAULT 0, " + // 新增
                "job INT, " +
                "race INT, " +
                "attack DOUBLE DEFAULT 0, " +
                "archer_damage DOUBLE DEFAULT 0, " +
                "armor DOUBLE DEFAULT 0, " +
                "speed DOUBLE DEFAULT 0.2, " +
                "max_health DOUBLE DEFAULT 20.0, " +
                "current_health DOUBLE DEFAULT 20.0, " +
                "toughness DOUBLE DEFAULT 0, " +
                "knock_back_res DOUBLE DEFAULT 0, " +
                "attack_speed DOUBLE DEFAULT 4.0, " +
                "crit_chance DOUBLE DEFAULT 0, " +
                "zf_str DOUBLE DEFAULT 0, " +
                "cool_reduce DOUBLE DEFAULT 0, " +
                "lingli DOUBLE DEFAULT 0" +
                ");";
        executeSql(sql);
    }

    private void createBankTable() {
        String sql = "CREATE TABLE IF NOT EXISTS player_elementbank (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16), " +
                "metal INT DEFAULT 0, " +
                "wood INT DEFAULT 0, " +
                "water INT DEFAULT 0, " +
                "fire INT DEFAULT 0, " +
                "earth INT DEFAULT 0, " +
                "relive_stone INT DEFAULT 0" +
                ");";
        executeSql(sql);
    }

    private void createSkillTable() {
        // 技能表：列名改回 metal, wood, ...
        String sql = "CREATE TABLE IF NOT EXISTS player_element_zf_lvl (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16), " +
                "metal INT DEFAULT 0, " +
                "wood INT DEFAULT 0, " +
                "water INT DEFAULT 0, " +
                "fire INT DEFAULT 0, " +
                "earth INT DEFAULT 0" +
                ");";
        executeSql(sql);
    }

    private void createForgeTable() {
        String sql = "CREATE TABLE IF NOT EXISTS player_dzlv (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16), " +
                "forge_level INT DEFAULT 1, " +
                "forge_exp INT DEFAULT 0, " +
                "forge_license INT DEFAULT 0" +
                ");";
        executeSql(sql);
    }

    private void executeSql(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // === 存取逻辑 ===

    public void savePlayer(PlayerData data) {
        // 1. player_data: 加入 exp
        String updateMain = "UPDATE player_data SET player_name=?, lv=?, exp=?, job=?, race=?, attack=?, archer_damage=?, armor=?, speed=?, max_health=?, current_health=?, toughness=?, knock_back_res=?, attack_speed=?, crit_chance=?, zf_str=?, cool_reduce=?, lingli=? WHERE uuid=?";
        String insertMain = "INSERT INTO player_data (player_name, lv, exp, job, race, attack, archer_damage, armor, speed, max_health, current_health, toughness, knock_back_res, attack_speed, crit_chance, zf_str, cool_reduce, lingli, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // 2. player_elementbank: 保持不变
        String updateBank = "UPDATE player_elementbank SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=?, relive_stone=? WHERE uuid=?";
        String insertBank = "INSERT INTO player_elementbank (player_name, metal, wood, water, fire, earth, relive_stone, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        // 3. player_element_zf_lvl: 改回正确列名 (metal, wood...)
        String updateSkills = "UPDATE player_element_zf_lvl SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=? WHERE uuid=?";
        String insertSkills = "INSERT INTO player_element_zf_lvl (player_name, metal, wood, water, fire, earth, uuid) VALUES (?, ?, ?, ?, ?, ?, ?)";

        // 4. player_dzlv: 保持不变
        String updateForge = "UPDATE player_dzlv SET player_name=?, forge_level=?, forge_exp=?, forge_license=? WHERE uuid=?";
        String insertForge = "INSERT INTO player_dzlv (player_name, forge_level, forge_exp, forge_license, uuid) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            // 保存主数据
            try (PreparedStatement ps = conn.prepareStatement(updateMain)) {
                ps.setString(1, data.getPlayerName());
                ps.setInt(2, data.getLv());
                ps.setInt(3, data.getExp()); // exp
                ps.setObject(4, data.getJob());
                ps.setObject(5, data.getRace());
                ps.setDouble(6, data.getAttack());
                ps.setDouble(7, data.getArcherDamage());
                ps.setDouble(8, data.getArmor());
                ps.setDouble(9, data.getSpeed());
                ps.setDouble(10, data.getMaxHealth());
                ps.setDouble(11, data.getCurrentHealth());
                ps.setDouble(12, data.getToughness());
                ps.setDouble(13, data.getKnockBackRes());
                ps.setDouble(14, data.getAttackSpeed());
                ps.setDouble(15, data.getCritChance());
                ps.setDouble(16, data.getZfStr());
                ps.setDouble(17, data.getCoolReduce());
                ps.setDouble(18, data.getLingli());
                ps.setString(19, data.getUuid().toString());

                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement insertPs = conn.prepareStatement(insertMain)) {
                        insertPs.setString(1, data.getPlayerName());
                        insertPs.setInt(2, data.getLv());
                        insertPs.setInt(3, data.getExp()); // exp
                        insertPs.setObject(4, data.getJob());
                        insertPs.setObject(5, data.getRace());
                        insertPs.setDouble(6, data.getAttack());
                        insertPs.setDouble(7, data.getArcherDamage());
                        insertPs.setDouble(8, data.getArmor());
                        insertPs.setDouble(9, data.getSpeed());
                        insertPs.setDouble(10, data.getMaxHealth());
                        insertPs.setDouble(11, data.getCurrentHealth());
                        insertPs.setDouble(12, data.getToughness());
                        insertPs.setDouble(13, data.getKnockBackRes());
                        insertPs.setDouble(14, data.getAttackSpeed());
                        insertPs.setDouble(15, data.getCritChance());
                        insertPs.setDouble(16, data.getZfStr());
                        insertPs.setDouble(17, data.getCoolReduce());
                        insertPs.setDouble(18, data.getLingli());
                        insertPs.setString(19, data.getUuid().toString());
                        insertPs.executeUpdate();
                    }
                }
            }

            // 保存仓库
            if (checkExists(conn, "player_elementbank", data.getUuid())) {
                try (PreparedStatement ps = conn.prepareStatement(updateBank)) {
                    ps.setString(1, data.getPlayerName());
                    ps.setInt(2, data.getMetal());
                    ps.setInt(3, data.getWood());
                    ps.setInt(4, data.getWater());
                    ps.setInt(5, data.getFire());
                    ps.setInt(6, data.getEarth());
                    ps.setInt(7, data.getReliveStone());
                    ps.setString(8, data.getUuid().toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(insertBank)) {
                    ps.setString(1, data.getPlayerName());
                    ps.setInt(2, data.getMetal());
                    ps.setInt(3, data.getWood());
                    ps.setInt(4, data.getWater());
                    ps.setInt(5, data.getFire());
                    ps.setInt(6, data.getEarth());
                    ps.setInt(7, data.getReliveStone());
                    ps.setString(8, data.getUuid().toString());
                    ps.executeUpdate();
                }
            }

            // 保存技能表 (使用 correct column names: metal, wood...)
            if (data.getElementLevels() != null) {
                if (checkExists(conn, "player_element_zf_lvl", data.getUuid())) {
                    try (PreparedStatement ps = conn.prepareStatement(updateSkills)) {
                        ps.setString(1, data.getPlayerName());
                        ps.setInt(2, data.getElementLevel("METAL"));
                        ps.setInt(3, data.getElementLevel("WOOD"));
                        ps.setInt(4, data.getElementLevel("WATER"));
                        ps.setInt(5, data.getElementLevel("FIRE"));
                        ps.setInt(6, data.getElementLevel("EARTH"));
                        ps.setString(7, data.getUuid().toString());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(insertSkills)) {
                        ps.setString(1, data.getPlayerName());
                        ps.setInt(2, data.getElementLevel("METAL"));
                        ps.setInt(3, data.getElementLevel("WOOD"));
                        ps.setInt(4, data.getElementLevel("WATER"));
                        ps.setInt(5, data.getElementLevel("FIRE"));
                        ps.setInt(6, data.getElementLevel("EARTH"));
                        ps.setString(7, data.getUuid().toString());
                        ps.executeUpdate();
                    }
                }
            }

            // 保存锻造
            if (checkExists(conn, "player_dzlv", data.getUuid())) {
                try (PreparedStatement ps = conn.prepareStatement(updateForge)) {
                    ps.setString(1, data.getPlayerName());
                    ps.setInt(2, data.getForgeLevel());
                    ps.setInt(3, data.getForgeExp());
                    ps.setInt(4, data.getForgeLicense());
                    ps.setString(5, data.getUuid().toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(insertForge)) {
                    ps.setString(1, data.getPlayerName());
                    ps.setInt(2, data.getForgeLevel());
                    ps.setInt(3, data.getForgeExp());
                    ps.setInt(4, data.getForgeLicense());
                    ps.setString(5, data.getUuid().toString());
                    ps.executeUpdate();
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("保存玩家数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean checkExists(Connection conn, String table, UUID uuid) throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public CompletableFuture<PlayerData> loadPlayer(UUID uuid, String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerData data = new PlayerData(uuid, playerName);
            String sqlMain = "SELECT * FROM player_data WHERE uuid = ?";
            String sqlBank = "SELECT * FROM player_elementbank WHERE uuid = ?";
            String sqlSkills = "SELECT * FROM player_element_zf_lvl WHERE uuid = ?";
            String sqlForge = "SELECT * FROM player_dzlv WHERE uuid = ?";

            try (Connection conn = dataSource.getConnection()) {
                // 1. 加载主数据
                try (PreparedStatement ps = conn.prepareStatement(sqlMain)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            data.setLv(rs.getInt("lv"));
                            data.setExp(rs.getInt("exp")); // 读取 exp
                            int job = rs.getInt("job");
                            if (!rs.wasNull()) data.setJob(job);
                            int race = rs.getInt("race");
                            if (!rs.wasNull()) data.setRace(race);
                            data.setAttack(rs.getDouble("attack"));
                            data.setArcherDamage(rs.getDouble("archer_damage"));
                            data.setArmor(rs.getDouble("armor"));
                            data.setSpeed(rs.getDouble("speed"));
                            data.setMaxHealth(rs.getDouble("max_health"));
                            data.setCurrentHealth(rs.getDouble("current_health"));
                            data.setToughness(rs.getDouble("toughness"));
                            data.setKnockBackRes(rs.getDouble("knock_back_res"));
                            data.setAttackSpeed(rs.getDouble("attack_speed"));
                            data.setCritChance(rs.getDouble("crit_chance"));
                            data.setZfStr(rs.getDouble("zf_str"));
                            data.setCoolReduce(rs.getDouble("cool_reduce"));
                            data.setLingli(rs.getDouble("lingli"));
                        }
                    }
                }

                // 2. 加载仓库
                try (PreparedStatement ps = conn.prepareStatement(sqlBank)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            data.setMetal(rs.getInt("metal"));
                            data.setWood(rs.getInt("wood"));
                            data.setWater(rs.getInt("water"));
                            data.setFire(rs.getInt("fire"));
                            data.setEarth(rs.getInt("earth"));
                            data.setReliveStone(rs.getInt("relive_stone"));
                        }
                    }
                }

                // 3. 加载技能 (使用正确列名: metal, wood...)
                try (PreparedStatement ps = conn.prepareStatement(sqlSkills)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            try { data.setElementLevel("METAL", rs.getInt("metal")); } catch (Exception e) {}
                            try { data.setElementLevel("WOOD", rs.getInt("wood")); } catch (Exception e) {}
                            try { data.setElementLevel("WATER", rs.getInt("water")); } catch (Exception e) {}
                            try { data.setElementLevel("FIRE", rs.getInt("fire")); } catch (Exception e) {}
                            try { data.setElementLevel("EARTH", rs.getInt("earth")); } catch (Exception e) {}
                        }
                    }
                }

                // 4. 加载锻造
                try (PreparedStatement ps = conn.prepareStatement(sqlForge)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            try { data.setForgeLevel(rs.getInt("forge_level")); } catch (Exception e) {}
                            try { data.setForgeExp(rs.getInt("forge_exp")); } catch (Exception e) {}
                            try { data.setForgeLicense(rs.getInt("forge_license")); } catch (Exception e) {}
                        }
                    }
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家数据失败: " + e.getMessage());
                e.printStackTrace();
            }
            return data;
        });
    }
}