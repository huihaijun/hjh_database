package com.hjh_database.skill.weapon;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.weapon.job_0.NoviceSwordSkill;
import com.hjh_database.skill.weapon.job_1.NoviceBowSkill;
import com.hjh_database.weapon.WeaponManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WeaponSkillManager {
    private final Hjh_database plugin;
    private final Map<String, ConfigurationSection> skillConfigCache = new HashMap<>();
    private final Map<String, WeaponSkill> skillRegistry = new HashMap<>();
    private final Map<UUID, Long> globalCooldowns = new ConcurrentHashMap<>();

    public WeaponSkillManager(Hjh_database plugin) {
        this.plugin = plugin;
        registerSkills();
        reload();
    }

    public void reload() {
        skillConfigCache.clear();
        File rootDir = new File(plugin.getDataFolder(), "weapon_skills");
        if (!rootDir.exists()) {
            rootDir.mkdirs();
            createDefaultSkillFile(rootDir);
        }
        loadSkillFiles(rootDir);
    }

    private void loadSkillFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                loadSkillFiles(file);
            } else if (file.getName().endsWith(".yml")) {
                String fileName = file.getName().replace(".yml", "");
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
                skillConfigCache.put(fileName, yml);
            }
        }
    }

    private void createDefaultSkillFile(File root) {
        // 暂时省略创建默认yml 因为已经有了
    }

    private void registerSkills() {
        skillRegistry.put("novice_sword", new NoviceSwordSkill());
        skillRegistry.put("novice_bow", new NoviceBowSkill());
    }

    public void tryCastSkill(Player player, String weaponId, ItemStack item, Entity projectile) {
        if (!skillRegistry.containsKey(weaponId)) return;

        ConfigurationSection config = skillConfigCache.get(weaponId);
        if (config == null || !config.getBoolean("active.enable", false)) return;

        WeaponManager.WeaponData weaponData = plugin.getPlayerManager().getWeaponManager().getWeaponData(weaponId);
        if (weaponData == null) return;

        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        if (!isWeaponActive(player, item, weaponData, data)) {
            player.sendMessage(ChatColor.RED + weaponData.activeLoreLine);
            return;
        }

        // 1. 冷却检查 (红色提示)
        if (isOnCooldown(player)) {
            double preciseTime = (globalCooldowns.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000.0;
            // 【修改】改为红色提示
            String cdMsg = String.format("&c&l武器技处于冷却中，剩余 %.1f 秒", preciseTime);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', cdMsg)));
            return;
        }

        WeaponSkill skill = skillRegistry.get(weaponId);
        ConfigurationSection activeConfig = config.getConfigurationSection("active");

        if (skill.castActive(player, data, activeConfig, projectile)) {
            applyCooldown(player, data, item.getType(), activeConfig.getDouble("cooldown", 10.0));

            String successMsg = activeConfig.getString("message");
            if (successMsg != null && !successMsg.isEmpty()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', successMsg)));
            }
        }
    }

    private boolean isWeaponActive(Player player, ItemStack item, WeaponManager.WeaponData weaponData, PlayerData data) {
        int slot = player.getInventory().getHeldItemSlot();
        if (weaponData.activateSlot != -1 && weaponData.activateSlot != slot) return false;
        if (data.getJob() != weaponData.reqJob) return false;
        if (data.getLv() < weaponData.reqLv) return false;
        return true;
    }

    private void applyCooldown(Player player, PlayerData data, Material mat, double baseSeconds) {
        double reduce = data.getCoolReduce();
        if (reduce > 0.5) reduce = 0.5;
        double finalSeconds = baseSeconds * (1.0 - reduce);
        int ticks = (int) (finalSeconds * 20);

        // 如果不是弓/弩，才设置视觉冷却，防止弓无法拉开
        if (mat != Material.BOW && mat != Material.CROSSBOW) {
            player.setCooldown(mat, ticks);
        }

        long endTime = System.currentTimeMillis() + (long)(finalSeconds * 1000);
        globalCooldowns.put(player.getUniqueId(), endTime);

        // 【新增】冷却结束后的提示任务
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                // 只有当玩家当前确实不在冷却中时（防止重复提示），发送提示
                if (!isOnCooldown(player)) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f);
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', "&a&l武器技冷却完毕！")));
                }
            }
        }.runTaskLater(plugin, ticks + 1); // 延迟 1 tick 确保状态已过
    }

    private boolean isOnCooldown(Player player) {
        if (!globalCooldowns.containsKey(player.getUniqueId())) return false;
        return globalCooldowns.get(player.getUniqueId()) > System.currentTimeMillis();
    }

    // 提供给外部获取管理器的方法
    public Hjh_database getPlugin() { return plugin; }
}