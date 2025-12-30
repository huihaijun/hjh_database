package com.hjh_database.dz.data;

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

    // 增加经验的辅助方法
    public void addExp(int amount) {
        this.forgeExp += amount;
        // 这里可以添加升级逻辑检查，比如 if (exp >= limit) levelUp();
    }
}