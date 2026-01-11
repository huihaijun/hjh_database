package com.hjh_database.skill.medical.spell;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.spell.impl.TuiDiSpell;
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell;
import net.md_5.bungee.api.ChatMessageType; // 导入 Bungee API
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MedicalSpellManager {
    private final Hjh_database plugin;
    private final Map<String, MedicalSpell> spells = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationSection> spellConfigs = new HashMap<>();

    public MedicalSpellManager(Hjh_database plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        spells.clear();
        spellConfigs.clear();
        registerSpell("yuhehua", new YuHeHuaSpell(plugin));
        // 【新增】注册退敌
        registerSpell("tuidi", new TuiDiSpell(plugin));
        loadConfig();
    }

    private void registerSpell(String id, MedicalSpell spell) { spells.put(id, spell); }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), "medical_items.yml");
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                String skillId = sec.getString("skill_id", key);
                spellConfigs.put(skillId, sec);
            }
        }
    }

    public void castSpell(Player player, String skillId) {
        MedicalSpell spell = spells.get(skillId);
        ConfigurationSection config = spellConfigs.get(skillId);

        if (spell == null) return;
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) return;

        String skillFullName = plugin.getMedicalManager().getSkillName(skillId);

        // 1. 冷却检查
        double baseCd = config != null ? config.getDouble("cooldown", 1.0) : 1.0;
        long cdMillis = (long) (baseCd * (1.0 - data.getCoolReduce()) * 1000L);

        if (isOnCooldown(player, skillId)) {
            long remainingMillis = getCooldownEndTime(player, skillId) - System.currentTimeMillis();
            long remainingSeconds = (remainingMillis / 1000) + 1; // 向上取整显示

            // Fix #1: 红色加粗 ActionBar，只读名字不读颜色
            String rawName = ChatColor.stripColor(skillFullName); // 去除颜色代码
            String barMsg = "§c§l" + rawName + " 正在冷却中，剩余 " + remainingSeconds + " 秒";

            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(barMsg));
            return;
        }

        // 2. 灵力检查
        double manaCost = config != null ? config.getDouble("mana_cost", 0) : 0;
        if (data.getLingli() < manaCost) {
            player.sendMessage("§c灵力不足，无法施展 " + skillFullName + "！");
            return;
        }

        // 3. 释放
        if (spell.cast(player, data, config)) {
            data.setLingli(data.getLingli() - manaCost);
            setCooldown(player, skillId, cdMillis);

            // Fix #2: 释放消息与 YML 一致
            // 读取 YML 中的 cast_message
            String msg = config != null ? config.getString("cast_message") : null;

            if (msg != null) {
                // 处理变量
                msg = msg.replace("%player%", player.getName());
                // 如果 YML 里写了 %skill%，我们再替换；如果没写（如你提供的配置），这里就不会替换，完全保留你YML里的“愈合花”
                if (msg.contains("%skill%")) {
                    msg = msg.replace("%skill%", skillFullName);
                }
                // 最后统一处理颜色代码，确保显示正确
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            } else {
                // 默认消息 (兜底)
                player.sendMessage("§e" + player.getName() + " §f释放了 §e" + skillFullName);
            }

            // 原版物品冷却 (转圈圈)
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getType() != Material.AIR) {
                int cooldownTicks = (int) (cdMillis / 50);
                player.setCooldown(hand.getType(), cooldownTicks);
            }
        }
    }

    public boolean isOnCooldown(Player player, String skillId) {
        if (!cooldowns.containsKey(player.getUniqueId())) return false;
        return cooldowns.get(player.getUniqueId()).getOrDefault(skillId, 0L) > System.currentTimeMillis();
    }

    // 获取冷却结束时间戳
    public long getCooldownEndTime(Player player, String skillId) {
        if (!cooldowns.containsKey(player.getUniqueId())) return 0L;
        return cooldowns.get(player.getUniqueId()).getOrDefault(skillId, 0L);
    }

    private void setCooldown(Player player, String skillId, long durationMillis) {
        long endTime = System.currentTimeMillis() + durationMillis;
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(skillId, endTime);
    }
}