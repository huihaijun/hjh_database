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
        createTable();      // 主数据表 (player_data)
        createBankTable();  // 仓库表 (player_elementbank)
        createSkillTable(); // 技能表 (player_element_zf_lvl)
        createForgeTable(); // 锻造表 (player_dzlv)
    }

    private void connect() {
        HikariConfig config = new HikariConfig();
        // 你的数据库配置
        config.setJdbcUrl("jdbc:mysql://localhost:3306/hjh_rpg?useSSL=false&characterEncoding=utf8");
        config.setUsername("root");
        config.setPassword("root");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        this.dataSource = new HikariDataSource(config);
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    // --- 建表逻辑 ---

    private void createTable() {
        // 【修改】在末尾添加了 crit_chance 字段
        String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16), " +
                "lv INT, job INT, race INT, " +
                "attack DOUBLE, archer_damage DOUBLE, armor DOUBLE, speed DOUBLE, " +
                "max_health DOUBLE, current_health DOUBLE, toughness DOUBLE, " +
                "knock_back_res DOUBLE, attack_speed DOUBLE, " +
                "zf_str DOUBLE, cool_reduce DOUBLE, lingli DOUBLE, jhq DOUBLE, money DOUBLE, " +
                "crit_chance DOUBLE DEFAULT 0" + // 新增
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createBankTable() {
        String sql = "CREATE TABLE IF NOT EXISTS player_elementbank (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "metal INT DEFAULT 0, wood INT DEFAULT 0, water INT DEFAULT 0, " +
                "fire INT DEFAULT 0, earth INT DEFAULT 0, relive_stone INT DEFAULT 0" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createSkillTable() {
        String sql = "CREATE TABLE IF NOT EXISTS player_element_zf_lvl (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(32), " +
                "metal INT DEFAULT 0, wood INT DEFAULT 0, water INT DEFAULT 0, " +
                "fire INT DEFAULT 0, earth INT DEFAULT 0" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createForgeTable() {
        String sql = "CREATE TABLE IF NOT EXISTS player_dzlv (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(32), " +
                "forge_level INT DEFAULT 1, " +
                "forge_exp INT DEFAULT 0, " +
                "forge_license INT DEFAULT 0" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("创建锻造表失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- 数据加载 ---

    public CompletableFuture<PlayerData> loadPlayer(UUID uuid, String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerData data = null;

            String selectMain = "SELECT * FROM player_data WHERE uuid=?";
            String selectBank = "SELECT * FROM player_elementbank WHERE uuid=?";
            String selectSkills = "SELECT * FROM player_element_zf_lvl WHERE uuid=?";
            String selectForge = "SELECT * FROM player_dzlv WHERE uuid=?";

            try (Connection conn = dataSource.getConnection()) {

                // 1. 加载主表 (player_data)
                try (PreparedStatement ps = conn.prepareStatement(selectMain)) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        data = new PlayerData(uuid, playerName);
                        data.setLv(rs.getInt("lv"));
                        data.setJob((Integer) rs.getObject("job"));
                        data.setRace((Integer) rs.getObject("race"));
                        data.setAttack(rs.getDouble("attack"));
                        data.setArcherDamage(rs.getDouble("archer_damage"));
                        data.setArmor(rs.getDouble("armor"));
                        data.setSpeed(rs.getDouble("speed"));
                        data.setMaxHealth(rs.getDouble("max_health"));
                        data.setCurrentHealth(rs.getDouble("current_health"));
                        data.setToughness(rs.getDouble("toughness"));
                        data.setKnockBackRes(rs.getDouble("knock_back_res"));
                        data.setAttackSpeed(rs.getDouble("attack_speed"));
                        data.setZfStr(rs.getDouble("zf_str"));
                        data.setCoolReduce(rs.getDouble("cool_reduce"));
                        data.setLingli(rs.getDouble("lingli"));
                        data.setJhq(rs.getDouble("jhq"));
                        data.setMoney(rs.getDouble("money"));

                        // 【新增】读取暴击率
                        // 使用 try-catch 兼容旧数据库结构，防止报错（虽然建议你跑 SQL ALTER 命令）
                        try {
                            data.setCritChance(rs.getDouble("crit_chance"));
                        } catch (SQLException ex) {
                            data.setCritChance(0.0);
                        }
                    }
                }

                if (data == null) {
                    return null;
                }

                // 2. 加载仓库 (player_elementbank)
                try (PreparedStatement ps = conn.prepareStatement(selectBank)) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        data.setMetal(rs.getInt("metal"));
                        data.setWood(rs.getInt("wood"));
                        data.setWater(rs.getInt("water"));
                        data.setFire(rs.getInt("fire"));
                        data.setEarth(rs.getInt("earth"));
                        data.setReliveStone(rs.getInt("relive_stone"));
                    }
                }

                // 3. 加载技能等级 (player_element_zf_lvl)
                try (PreparedStatement ps = conn.prepareStatement(selectSkills)) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        if (data.getElementLevels() == null && data.getJob() != null && data.getJob() == 2) {
                            data.setJob(2);
                        }
                        data.setElementLevel("METAL", rs.getInt("metal"));
                        data.setElementLevel("WOOD", rs.getInt("wood"));
                        data.setElementLevel("WATER", rs.getInt("water"));
                        data.setElementLevel("FIRE", rs.getInt("fire"));
                        data.setElementLevel("EARTH", rs.getInt("earth"));
                    }
                }

                // 4. 加载锻造数据 (player_dzlv)
                try (PreparedStatement ps = conn.prepareStatement(selectForge)) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        data.setForgeLevel(rs.getInt("forge_level"));
                        data.setForgeExp(rs.getInt("forge_exp"));
                        data.setForgeLicense(rs.getInt("forge_license"));
                    } else {
                        data.setForgeLevel(1);
                        data.setForgeExp(0);
                        data.setForgeLicense(0);
                    }
                }

            } catch (SQLException e) {
                e.printStackTrace();
            }
            return data;
        });
    }

    public void savePlayer(PlayerData data) {
        CompletableFuture.runAsync(() -> savePlayerSync(data));
    }

    // --- 数据保存 ---

    public void savePlayerSync(PlayerData data) {
        // 【修改】在 SQL 中加入了 crit_chance=?
        String updateMain = "UPDATE player_data SET " +
                "lv=?, job=?, race=?, " +
                "attack=?, archer_damage=?, armor=?, speed=?, max_health=?, current_health=?, " +
                "toughness=?, knock_back_res=?, attack_speed=?, " +
                "zf_str=?, cool_reduce=?, lingli=?, jhq=?, money=?, crit_chance=? " + // 这里加了
                "WHERE uuid=?";

        String updateBank = "INSERT INTO player_elementbank (uuid, metal, wood, water, fire, earth, relive_stone) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "metal=VALUES(metal), wood=VALUES(wood), water=VALUES(water), " +
                "fire=VALUES(fire), earth=VALUES(earth), relive_stone=VALUES(relive_stone)";

        String updateSkills = "INSERT INTO player_element_zf_lvl (uuid, player_name, metal, wood, water, fire, earth) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "player_name=VALUES(player_name), metal=VALUES(metal), wood=VALUES(wood), water=VALUES(water), fire=VALUES(fire), earth=VALUES(earth)";

        String updateForge = "INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "player_name=VALUES(player_name), forge_level=VALUES(forge_level), " +
                "forge_exp=VALUES(forge_exp), forge_license=VALUES(forge_license)";

        try (Connection conn = dataSource.getConnection()) {
            // 1. 保存主表 (player_data)
            try (PreparedStatement ps = conn.prepareStatement(updateMain)) {
                ps.setInt(1, data.getLv());
                ps.setObject(2, data.getJob());
                ps.setObject(3, data.getRace());
                ps.setDouble(4, data.getVal(data.getAttack()));
                ps.setDouble(5, data.getVal(data.getArcherDamage()));
                ps.setDouble(6, data.getVal(data.getArmor()));
                ps.setDouble(7, data.getVal(data.getSpeed()));
                ps.setDouble(8, data.getVal(data.getMaxHealth()));
                ps.setDouble(9, data.getVal(data.getCurrentHealth()));
                ps.setDouble(10, data.getVal(data.getToughness()));
                ps.setDouble(11, data.getVal(data.getKnockBackRes()));
                ps.setDouble(12, data.getVal(data.getAttackSpeed()));
                ps.setDouble(13, data.getVal(data.getZfStr()));
                ps.setDouble(14, data.getVal(data.getCoolReduce()));
                ps.setDouble(15, data.getVal(data.getLingli()));
                ps.setDouble(16, data.getVal(data.getJhq()));
                ps.setDouble(17, data.getVal(data.getMoney()));

                // 【新增】设置暴击率参数
                ps.setDouble(18, data.getVal(data.getCritChance()));

                // UUID 是第 19 个参数
                ps.setString(19, data.getUuid().toString());

                ps.executeUpdate();
            }

            // 2. 保存仓库表 (player_elementbank)
            try (PreparedStatement ps = conn.prepareStatement(updateBank)) {
                ps.setString(1, data.getUuid().toString());
                ps.setInt(2, data.getMetal());
                ps.setInt(3, data.getWood());
                ps.setInt(4, data.getWater());
                ps.setInt(5, data.getFire());
                ps.setInt(6, data.getEarth());
                ps.setInt(7, data.getReliveStone());
                ps.executeUpdate();
            }

            // 3. 保存技能表 (player_element_zf_lvl)
            if (data.getElementLevels() != null) {
                try (PreparedStatement ps = conn.prepareStatement(updateSkills)) {
                    ps.setString(1, data.getUuid().toString());
                    ps.setString(2, data.getPlayerName());
                    ps.setInt(3, data.getElementLevel("METAL"));
                    ps.setInt(4, data.getElementLevel("WOOD"));
                    ps.setInt(5, data.getElementLevel("WATER"));
                    ps.setInt(6, data.getElementLevel("FIRE"));
                    ps.setInt(7, data.getElementLevel("EARTH"));
                    ps.executeUpdate();
                }
            }

            // 4. 保存锻造表 (player_dzlv)
            try (PreparedStatement ps = conn.prepareStatement(updateForge)) {
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, data.getPlayerName());
                ps.setInt(3, data.getForgeLevel());
                ps.setInt(4, data.getForgeExp());
                ps.setInt(5, data.getForgeLicense());
                ps.executeUpdate();
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("保存玩家数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}