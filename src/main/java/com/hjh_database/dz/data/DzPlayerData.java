package com.hjh_database.dz.data;

import com.hjh_database.Hjh_database;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

public class DzPlayerData {
    private final UUID uuid;
    private final String playerName;
    private int forgeLevel;      // 锻造等级
    private int forgeExp;        // 锻造经验
    private int forgeLicense;    // 锻造许可 (0=无, 1=初级, 2=中级...)

    public DzPlayerData(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.forgeLevel = 1;
        this.forgeExp = 0;
        this.forgeLicense = 0;
    }

    // Getters and Setters...
    public UUID getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }

    public int getForgeLevel() { return forgeLevel; }
    public void setForgeLevel(int level) { this.forgeLevel = level; }

    public int getForgeExp() { return forgeExp; }
    public void setForgeExp(int exp) { this.forgeExp = exp; }

    public int getForgeLicense() { return forgeLicense; }
    public void setForgeLicense(int license) { this.forgeLicense = license; }

    /**
     * 核心升级逻辑：增加经验并检查升级
     * @param amount 增加的经验值
     * @param plugin 插件实例 (用于读取配置和保存数据)
     * @param player 玩家对象 (用于发送升级提示和音效)
     */
    public void addExp(int amount, Hjh_database plugin, Player player) {
        // 1. 增加经验
        this.forgeExp += amount;

        // 2. 循环检查是否满足升级条件 (防止一次加大量经验导致只升一级)
        boolean hasLeveledUp = false;

        while (true) {
            // 获取当前等级升级所需的最大经验
            int maxExp = plugin.getDzLevelManager().getMaxExp(this.forgeLevel);

            // 如果 maxExp <= 0，说明已经满级了，不再升级
            if (maxExp <= 0) {
                break;
            }

            // 如果经验足够升级
            if (this.forgeExp >= maxExp) {
                this.forgeExp -= maxExp; // 扣除升级所需经验 (保留溢出部分)
                this.forgeLevel++;       // 等级 +1
                hasLeveledUp = true;
            } else {
                // 经验不足以继续升级，跳出循环
                break;
            }
        }

        // 3. 升级反馈 (音效与提示)
        if (hasLeveledUp) {
            player.sendMessage("§6§l锻造升级！", "§e当前等级: §fLv." + this.forgeLevel);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            player.sendMessage("§8[§6锻造§8] §a恭喜！你的锻造等级提升到了 §eLv." + this.forgeLevel);
        }

        // 4. 【关键】保存数据到数据库
        // 我们在 PlayerManager 中最好有一个异步保存的方法，或者直接调用数据库更新
        // 这里假设 PlayerManager 有一个专门保存单个玩家数据的方法
        saveToDatabase(plugin);
    }

    // 简单的保存辅助方法
    private void saveToDatabase(Hjh_database plugin) {
        // 使用异步任务保存，防止卡主线程
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().saveDzPlayerData(this);
        });
    }
}