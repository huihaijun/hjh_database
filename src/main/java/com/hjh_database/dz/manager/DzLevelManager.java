package com.hjh_database.dz.manager;

import com.hjh_database.Hjh_database;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class DzLevelManager {
    private final Hjh_database plugin;
    private final Map<Integer, Integer> levelExpMap = new HashMap<>();
    private final Map<Integer, String> licenseNameMap = new HashMap<>();

    public DzLevelManager(Hjh_database plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        levelExpMap.clear();
        licenseNameMap.clear();

        File file = new File(plugin.getDataFolder(), "dzlvl.yml");
        if (!file.exists()) {
            plugin.saveResource("dzlvl.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // 加载经验表
        if (config.isConfigurationSection("levels")) {
            for (String key : config.getConfigurationSection("levels").getKeys(false)) {
                try {
                    int lv = Integer.parseInt(key);
                    int exp = config.getInt("levels." + key);
                    levelExpMap.put(lv, exp);
                } catch (NumberFormatException ignored) {}
            }
        }

        // 加载资质名
        if (config.isConfigurationSection("licenses")) {
            for (String key : config.getConfigurationSection("licenses").getKeys(false)) {
                try {
                    int id = Integer.parseInt(key);
                    String name = config.getString("licenses." + key);
                    licenseNameMap.put(id, ChatColor.translateAlternateColorCodes('&', name));
                } catch (NumberFormatException ignored) {}
            }
        }

        plugin.getLogger().info("已加载 " + levelExpMap.size() + " 个锻造等级设定和 " + licenseNameMap.size() + " 个资质名称。");
    }

    /**
     * 获取当前等级升级所需的最大经验
     * @param level 当前等级
     * @return 所需经验，如果达到满级（没有下一级配置）返回 -1
     */
    public int getMaxExp(int level) {
        // 默认为 Integer.MAX_VALUE 防止除以0报错，或者表示满级
        return levelExpMap.getOrDefault(level, -1);
    }

    /**
     * 获取资质名称
     */
    public String getLicenseName(int licenseId) {
        return licenseNameMap.getOrDefault(licenseId, "§7未知资质");
    }
}