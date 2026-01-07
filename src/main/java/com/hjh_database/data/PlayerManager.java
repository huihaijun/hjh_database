package com.hjh_database.data;

import com.hjh_database.Hjh_database;
import com.hjh_database.weapon.ArmorManager;
import com.hjh_database.weapon.WeaponManager;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import com.hjh_database.dz.data.DzPlayerData;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {
    private final Hjh_database plugin;
    private final Map<UUID, PlayerData> dataCache = new ConcurrentHashMap<>();
    private final Map<UUID, DzPlayerData> dzDataCache = new ConcurrentHashMap<>();
    private final WeaponManager weaponManager;
    private final ArmorManager armorManager;

    // 【新增】等级配置文件对象
    private File levelsFile;
    private YamlConfiguration levelsConfig;

    public PlayerManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.weaponManager = new WeaponManager(plugin);
        this.armorManager = new ArmorManager(plugin);
        // 【新增】初始化时加载等级配置
        loadLevelConfig();
    }

    // 【新增】加载 levels.yml
    public void loadLevelConfig() {
        levelsFile = new File(plugin.getDataFolder(), "levels.yml");
        if (!levelsFile.exists()) {
            plugin.saveResource("levels.yml", false);
        }
        levelsConfig = YamlConfiguration.loadConfiguration(levelsFile);
    }

    // 【新增】获取怪物经验配置
    public int getMobExp() {
        return levelsConfig.getInt("mobs.panling_monster_exp", 20);
    }

    // 【新增】计算升级所需经验
    public int getMaxExpRequired(int currentLevel) {
        ConfigurationSection stages = levelsConfig.getConfigurationSection("level_stages");
        if (stages != null) {
            for (String key : stages.getKeys(false)) {
                ConfigurationSection stage = stages.getConfigurationSection(key);
                int min = stage.getInt("min_level");
                int max = stage.getInt("max_level");
                if (currentLevel >= min && currentLevel <= max) {
                    int base = stage.getInt("base");
                    int multiplier = stage.getInt("multiplier");
                    return base + (currentLevel * multiplier);
                }
            }
        }
        return 100 + (currentLevel * 50); // 默认公式
    }

    // 【新增】核心：给予经验
    public void giveExp(Player player, int amount) {
        PlayerData data = getData(player.getUniqueId());
        if (data == null) return;

        int currentExp = data.getExp();
        int maxExp = getMaxExpRequired(data.getLv());

        currentExp += amount;
        boolean leveledUp = false;

        // 循环升级逻辑
        while (currentExp >= maxExp) {
            currentExp -= maxExp;
            data.setLv(data.getLv() + 1);
            maxExp = getMaxExpRequired(data.getLv());
            leveledUp = true;

            player.sendMessage("§a§l[升级] §e你的等级提升到了 " + data.getLv() + " 级！");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
        }

        data.setExp(currentExp);

        // 刷新属性（因为升级了，且需要同步经验条）
        updateStats(player);

        // 升级保存
        if (leveledUp) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getDatabaseManager().savePlayer(data);
            });
        }
    }

    public WeaponManager getWeaponManager() { return weaponManager; }
    public ArmorManager getArmorManager() { return armorManager; }

    public PlayerData getData(UUID uuid) {
        return dataCache.get(uuid);
    }

    public DzPlayerData getDzData(UUID uuid) {
        return dzDataCache.get(uuid);
    }

    public void loadAndCache(Player player) {
        plugin.getDatabaseManager().loadPlayer(player.getUniqueId(), player.getName())
                .thenAccept(data -> {
                    if (data == null) {
                        data = new PlayerData(player.getUniqueId(), player.getName());
                    }
                    dataCache.put(player.getUniqueId(), data);

                    // 同步锻造数据 (保持原样)
                    DzPlayerData dzData = new DzPlayerData(player.getUniqueId(), player.getName());
                    dzData.setForgeLevel(data.getForgeLevel());
                    dzData.setForgeExp(data.getForgeExp());
                    dzData.setForgeLicense(data.getForgeLicense());
                    dzDataCache.put(player.getUniqueId(), dzData);

                    PlayerData finalData = data;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        updateStats(player);
                        syncToVanilla(player, finalData);
                    });
                });
    }

    public void unloadAndSave(UUID uuid) {
        PlayerData data = dataCache.remove(uuid);
        DzPlayerData dzData = dzDataCache.remove(uuid);

        if (data != null) {
            if (dzData != null) {
                data.setForgeLevel(dzData.getForgeLevel());
                data.setForgeExp(dzData.getForgeExp());
                data.setForgeLicense(dzData.getForgeLicense());
            }
            plugin.getDatabaseManager().savePlayer(data);
        }
    }

    public void saveAllOnline() {
        for (UUID uuid : dataCache.keySet()) {
            PlayerData data = dataCache.get(uuid);
            DzPlayerData dzData = dzDataCache.get(uuid);

            if (data != null && dzData != null) {
                data.setForgeLevel(dzData.getForgeLevel());
                data.setForgeExp(dzData.getForgeExp());
                data.setForgeLicense(dzData.getForgeLicense());
                plugin.getDatabaseManager().savePlayer(data);
            }
        }
    }

    public void updateStats(Player player) {
        PlayerData data = dataCache.get(player.getUniqueId());
        if (data == null) return;

        // 1. 重置基础属性 (保持原样)
        data.setMaxHealth(20.0);
        data.setAttack(0.0);
        data.setArcherDamage(0.0);
        data.setZfStr(0.0);
        data.setArmor(0.0);
        data.setKnockBackRes(0.0);
        data.setSpeed(0.2);
        data.setCritChance(0.0);
        data.setCoolReduce(0.0);
        data.setTotalRarity(0);

        // ★【新增】重置总分 和 清空明细列表 (必须加这句！)
        data.getRarityDetails().clear();

        // 2. 获取各模块加成 (保持原有的 Map 计算逻辑)
        Map<String, Double> bonuses = new HashMap<>();

        Map<String, Double> weaponStats = weaponManager.calculateWeaponStats(player, data);
        weaponStats.forEach((k, v) -> bonuses.merge(k, v, Double::sum));

        Map<String, Double> armorStats = armorManager.calculateArmorStats(player, data);
        armorStats.forEach((k, v) -> bonuses.merge(k, v, Double::sum));

        // ★ 在这里加上这段代码：从 Map 中提取总稀有度并保存
        if (bonuses.containsKey("total_rarity")) {
            data.setTotalRarity(bonuses.get("total_rarity").intValue());
        }

        // 3. 应用加成 (保持原样)
        data.setMaxHealth(data.getMaxHealth() + bonuses.getOrDefault("max_health", 0.0));
        data.setAttack(data.getAttack() + bonuses.getOrDefault("attack", 0.0));
        data.setArcherDamage(data.getArcherDamage() + bonuses.getOrDefault("archer_damage", 0.0));
        data.setZfStr(data.getZfStr() + bonuses.getOrDefault("zf_str", 0.0));
        data.setArmor(data.getArmor() + bonuses.getOrDefault("armor", 0.0));
        data.setKnockBackRes(data.getKnockBackRes() + bonuses.getOrDefault("knock_back_res", 0.0));
        data.setCritChance(data.getCritChance() + bonuses.getOrDefault("crit_chance", 0.0));
        data.setCoolReduce(data.getCoolReduce() + bonuses.getOrDefault("cool_reduce",0.0));

        // === 灵力计算逻辑 (保持原样) ===
        // 公式：50 + (等级 * 3)
        double baseLingli = 50.0 + (data.getLv() * 3.0);

        // 限制最高 300 点 (针对基础成长)
        if (baseLingli > 300.0) {
            baseLingli = 300.0;
        }

        // === 【核心修改】保存冷却缩减 (限制最高 50%) ===
        double coolReduce = data.getCoolReduce();
        if (coolReduce > 0.5) coolReduce = 0.5;
        data.setCoolReduce(coolReduce);


        // 获取装备提供的额外灵力
        double equipLingli = bonuses.getOrDefault("lingli", 0.0);
        data.setExtraLingli(equipLingli);

        // 设置总灵力上限 (基础 + 装备)
        data.setMaxLingli(baseLingli + equipLingli);

        // 如果当前灵力超过了上限，则修正为上限
        if (data.getLingli() > data.getMaxLingli()) {
            data.setLingli(data.getMaxLingli());
        }
        // ==============================

        if (bonuses.containsKey("speed")) {
            data.setSpeed(data.getSpeed() + bonuses.get("speed"));
        }

        if (bonuses.containsKey("attack_percent")) {
            double multi = 1.0 + bonuses.get("attack_percent");
            data.setAttack(data.getAttack() * multi);
        }
        if (bonuses.containsKey("armor_percent")) {
            double multi = 1.0 + bonuses.get("armor_percent");
            data.setArmor(data.getArmor() * multi);
        }

        syncToVanilla(player, data);
    }

    private void syncToVanilla(Player player, PlayerData data) {
        // (保持原有的属性同步)
        double maxHp = Math.max(1.0, data.getMaxHealth());
        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHp);
        }
        double speed = Math.min(1.0, Math.max(0.0, data.getSpeed()));
        player.setWalkSpeed((float) speed);
        if (player.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            double kb = Math.min(1.0, Math.max(0.0, data.getKnockBackRes()));
            player.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(kb);
        }
        if (player.getAttribute(Attribute.GENERIC_ARMOR) != null) {
            player.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(0);
            for (AttributeModifier modifier : player.getAttribute(Attribute.GENERIC_ARMOR).getModifiers()) {
                player.getAttribute(Attribute.GENERIC_ARMOR).removeModifier(modifier);
            }
        }
        if (player.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS) != null) {
            player.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS).setBaseValue(0);
            for (AttributeModifier modifier : player.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS).getModifiers()) {
                player.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS).removeModifier(modifier);
            }
        }

        // === 【新增】同步等级和经验条到原版界面 ===
        player.setLevel(data.getLv());

        int currentExp = data.getExp();
        int maxExp = getMaxExpRequired(data.getLv());
        // 计算百分比 0.0 - 1.0
        float progress = 0.0f;
        if (maxExp > 0) {
            progress = (float) currentExp / (float) maxExp;
        }
        // 限制进度条范围，防止客户端显示鬼畜
        progress = Math.min(0.999f, Math.max(0.0f, progress));
        player.setExp(progress);
    }

    /**
     * 【新增】获取玩家数据对象
     * 供外部系统（如开物术、菜单等）调用
     */
    public PlayerData getPlayerData(Player player) {
        if (player == null) return null;
        return dataCache.get(player.getUniqueId());
    }

}