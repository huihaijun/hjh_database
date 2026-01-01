package com.hjh_database.skill.element_zf;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.element_zf.impl.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

public class ElementZfManager {
    private final Hjh_database plugin;
    private FileConfiguration config;

    // 存储 元素名 -> 技能逻辑 的映射
    private final Map<String, ElementSkill> skills = new HashMap<>();

    // 【新增】系统级冷却记录表: PlayerUUID -> (ElementType -> CooldownEndTime)
    // 这样即便玩家换了物品，或者重新进服，只要插件没重载，CD逻辑依然在插件内部受控
    // 也可以方便后续写技能来 refreshCD(player, "METAL")
    private final Map<UUID, Map<String, Long>> internalCooldowns = new ConcurrentHashMap<>();

    public ElementZfManager(Hjh_database plugin) {
        this.plugin = plugin;
        reload();
        registerSkills();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "element_zf.yml");
        if (!file.exists()) {
            plugin.saveResource("element_zf.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    private void registerSkills() {
        skills.put("METAL", new MetalSkill(plugin));
        skills.put("WOOD", new WoodSkill(plugin)); // 【新增】注册木系技能
        skills.put("WATER", new WaterSkill(plugin)); // 【新增】注册水元素技能
        skills.put("FIRE", new FireSkill(plugin)); // 【新增】注册火元素
        skills.put("EARTH", new EarthSkill(plugin)); // 【新增】注册土元素
    }

    public void castSkill(Player player, String type, PlayerData data) {
        ElementSkill skill = skills.get(type);
        if (skill == null) return;

        // 1. 检查等级
        int level = data.getElementLevel(type);
        if (level <= 0) {
            player.sendMessage(ChatColor.RED + "你尚未领悟 " + type + " 阵法！");
            return;
        }

        // 2. 【核心修改】检查冷却 (双重检查：优先检查系统内部记录)
        if (isOnCooldown(player, type)) {
            long remainingMillis = getCooldownTime(player, type) - System.currentTimeMillis();
            double remainingSeconds = remainingMillis / 1000.0;

            // 获取技能显示的名称
            String skillName = config.getString("skills." + type + ".name", type);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.translateAlternateColorCodes('&',
                            "&c&l" + skillName + " 阵法冷却中，剩余 " + String.format("%.1f", remainingSeconds) + " 秒")));
            return;
        }

        // 3. 执行技能逻辑
        boolean success = skill.cast(player, level, config.getConfigurationSection("skills." + type));

        if (success) {
            // 4. 计算冷却时间
            double baseCd = config.getDouble("skills." + type + ".levels." + level + ".cooldown",
                    config.getDouble("skills." + type + ".levels.1.cooldown", 5.0));

            // 应用冷却缩减 (硬上限 50%)
            double reduce = data.getCoolReduce();
            if (reduce > 0.5) reduce = 0.5;
            double finalCd = baseCd * (1.0 - reduce);

            // 【核心修改】设置双重冷却
            // A. 设置内部系统冷却 (用于逻辑判断和后续刷新)
            setCooldown(player, type, finalCd);

            // B. 设置原版物品冷却 (用于视觉反馈：物品栏变灰读条)
            Material handItemType = player.getInventory().getItemInMainHand().getType();
            player.setCooldown(handItemType, (int) (finalCd * 20));

            // 5. 发送提示消息
            String msg = config.getString("skills." + type + ".message");
            if (msg != null && !msg.isEmpty()) {
                // 广播给周围的人或者只发给自己，这里使用 broadcastMessage 是原逻辑的延续吗？
                // 通常建议只发给自己，或者范围广播。这里沿用你之前的风格如果是 broadcast
                // 假设原逻辑是发给全服或者周围，这里暂时发给玩家自己
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", player.getName())));
            }
        }
    }

    // === 新增：冷却管理 API ===

    // 判断是否在冷却
    public boolean isOnCooldown(Player player, String type) {
        if (!internalCooldowns.containsKey(player.getUniqueId())) return false;
        Map<String, Long> pCds = internalCooldowns.get(player.getUniqueId());
        if (!pCds.containsKey(type)) return false;
        return pCds.get(type) > System.currentTimeMillis();
    }

    // 获取冷却结束时间戳
    public long getCooldownTime(Player player, String type) {
        if (!internalCooldowns.containsKey(player.getUniqueId())) return 0;
        return internalCooldowns.get(player.getUniqueId()).getOrDefault(type, 0L);
    }

    // 设置冷却 (seconds)
    public void setCooldown(Player player, String type, double seconds) {
        long endTime = System.currentTimeMillis() + (long)(seconds * 1000);
        internalCooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(type, endTime);
    }

    // 【新功能】重置特定元素的冷却 (方便后续技能调用)
    public void resetCooldown(Player player, String type) {
        if (internalCooldowns.containsKey(player.getUniqueId())) {
            internalCooldowns.get(player.getUniqueId()).remove(type);
            // 注意：原版物品的视觉冷却很难单独清除，除非知道玩家当时用的是什么物品
            // 但逻辑上的冷却已经清除了，玩家可以再次施法
        }
    }
}