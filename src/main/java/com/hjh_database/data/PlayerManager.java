package com.hjh_database.data;

import com.hjh_database.Hjh_database;
import com.hjh_database.weapon.ArmorManager;
import com.hjh_database.weapon.WeaponManager;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import com.hjh_database.dz.data.DzPlayerData;

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

    public PlayerManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.weaponManager = new WeaponManager(plugin);
        this.armorManager = new ArmorManager(plugin);
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

                    // 同步锻造数据
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

        // 1. 重置基础属性
        data.setMaxHealth(20.0);
        data.setAttack(0.0);
        data.setArcherDamage(0.0);
        data.setZfStr(0.0);
        data.setArmor(0.0);
        data.setKnockBackRes(0.0);
        data.setSpeed(0.2);
        data.setCritChance(0.0);

        // 2. 获取各模块加成 (武器 + 防具)
        Map<String, Double> bonuses = new HashMap<>();

        Map<String, Double> weaponStats = weaponManager.calculateWeaponStats(player, data);
        weaponStats.forEach((k, v) -> bonuses.merge(k, v, Double::sum));

        Map<String, Double> armorStats = armorManager.calculateArmorStats(player, data);
        armorStats.forEach((k, v) -> bonuses.merge(k, v, Double::sum));

        // 3. 应用加成
        data.setMaxHealth(data.getMaxHealth() + bonuses.getOrDefault("max_health", 0.0));
        data.setAttack(data.getAttack() + bonuses.getOrDefault("attack", 0.0));
        data.setArcherDamage(data.getArcherDamage() + bonuses.getOrDefault("archer_damage", 0.0));
        data.setZfStr(data.getZfStr() + bonuses.getOrDefault("zf_str", 0.0));
        data.setArmor(data.getArmor() + bonuses.getOrDefault("armor", 0.0));
        data.setKnockBackRes(data.getKnockBackRes() + bonuses.getOrDefault("knock_back_res", 0.0));
        data.setCritChance(data.getCritChance() + bonuses.getOrDefault("crit_chance", 0.0));

        // === 【核心修改】 灵力计算逻辑 ===
        // 公式：50 + (等级 * 3)
        double baseLingli = 50.0 + (data.getLv() * 3.0);

        // 限制最高 300 点 (针对基础成长)
        if (baseLingli > 300.0) {
            baseLingli = 300.0;
        }

        // 获取装备提供的额外灵力
        double equipLingli = bonuses.getOrDefault("lingli", 0.0);
        data.setExtraLingli(equipLingli);

        // 设置总灵力上限 (基础 + 装备)
        data.setMaxLingli(baseLingli + equipLingli);

        // 如果当前灵力超过了上限，则修正为上限 (防止换装备后蓝量溢出)
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
    }
}