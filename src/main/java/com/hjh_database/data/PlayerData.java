package com.hjh_database.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerData {
    private final UUID uuid;
    private final String playerName;

    // === 基础信息 ===
    private Integer lv = 1;
    private Integer job = null;
    private Integer race = null;

    // === 物理战斗属性 ===
    private Double attack = 0.0;
    private Double archerDamage = 0.0;
    private Double armor = 0.0;
    private Double speed = 0.2;
    private Double maxHealth = 20.0;
    private Double currentHealth = 20.0;
    private Double toughness = 0.0;
    private Double knockBackRes = 0.0;
    private Double attackSpeed = 4.0;

    // 暴击率
    private Double critChance = 0.0;

    // === 法术战斗属性 ===
    private Double zfStr = 0.0;

    // === 公用属性 ===
    private Double coolReduce = 0.0;

    // === 灵力系统 ===
    // 【修改】这个字段现在代表 "当前灵力" (Current Lingli)
    // 它会由 DatabaseManager 自动存取
    private Double lingli = 0.0;

    // 【新增】这个字段代表 "灵力上限" (Max Lingli)
    // 它是动态计算的 (50 + Lv*3 + 装备)，不需要存数据库
    private transient double maxLingli = 50.0;

    // 额外灵力 (装备提供的上限加成)
    private transient double extraLingli = 0.0;

    private Double jhq = 0.0;
    private Double money = 0.0;

    // 仓库
    private Integer metal = 0;
    private Integer wood = 0;
    private Integer water = 0;
    private Integer fire = 0;
    private Integer earth = 0;
    private Integer reliveStone = 0;

    // 技能
    private Map<String, Integer> elementLevels = new HashMap<>();

    // 锻造
    private Integer forgeLevel = 1;
    private Integer forgeExp = 0;
    private Integer forgeLicense = 0;

    public PlayerData(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
    }

    // === Getters and Setters ===

    public UUID getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }

    public Integer getLv() { return lv; }
    public void setLv(Integer lv) { this.lv = lv; }

    public Integer getJob() { return job; }
    public void setJob(Integer job) { this.job = job; }

    public Integer getRace() { return race; }
    public void setRace(Integer race) { this.race = race; }

    public Double getAttack() { return attack; }
    public void setAttack(Double attack) { this.attack = attack; }

    public Double getArcherDamage() { return archerDamage; }
    public void setArcherDamage(Double archerDamage) { this.archerDamage = archerDamage; }

    public Double getArmor() { return armor; }
    public void setArmor(Double armor) { this.armor = armor; }

    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }

    public Double getMaxHealth() { return maxHealth; }
    public void setMaxHealth(Double maxHealth) { this.maxHealth = maxHealth; }

    public Double getCurrentHealth() { return currentHealth; }
    public void setCurrentHealth(Double currentHealth) { this.currentHealth = currentHealth; }

    public Double getToughness() { return toughness; }
    public void setToughness(Double toughness) { this.toughness = toughness; }

    public Double getKnockBackRes() { return knockBackRes; }
    public void setKnockBackRes(Double knockBackRes) { this.knockBackRes = knockBackRes; }

    public Double getAttackSpeed() { return attackSpeed; }
    public void setAttackSpeed(Double attackSpeed) { this.attackSpeed = attackSpeed; }

    public Double getCritChance() { return critChance; }
    public void setCritChance(Double critChance) { this.critChance = critChance; }

    public Double getZfStr() { return zfStr; }
    public void setZfStr(Double zfStr) { this.zfStr = zfStr; }

    public Double getCoolReduce() { return coolReduce; }
    public void setCoolReduce(Double coolReduce) { this.coolReduce = coolReduce; }

    // === 灵力相关修改 ===

    // 获取当前灵力
    public Double getLingli() { return lingli; }
    // 设置当前灵力
    public void setLingli(Double lingli) { this.lingli = lingli; }

    // 获取灵力上限
    public double getMaxLingli() { return maxLingli; }
    // 设置灵力上限 (由 PlayerManager 计算后写入)
    public void setMaxLingli(double maxLingli) { this.maxLingli = maxLingli; }

    // 获取额外灵力上限 (装备提供)
    public double getExtraLingli() { return extraLingli; }
    public void setExtraLingli(double extraLingli) { this.extraLingli = extraLingli; }

    // 兼容方法：获取总灵力上限
    public double getTotalLingli() { return maxLingli; }

    public Double getJhq() { return jhq; }
    public void setJhq(Double jhq) { this.jhq = jhq; }

    public Double getMoney() { return money; }
    public void setMoney(Double money) { this.money = money; }

    // 仓库 Getter/Setter
    public int getMetal() { return metal; }
    public void setMetal(int metal) { this.metal = metal; }
    public int getWood() { return wood; }
    public void setWood(int wood) { this.wood = wood; }
    public int getWater() { return water; }
    public void setWater(int water) { this.water = water; }
    public int getFire() { return fire; }
    public void setFire(int fire) { this.fire = fire; }
    public int getEarth() { return earth; }
    public void setEarth(int earth) { this.earth = earth; }
    public int getReliveStone() { return reliveStone; }
    public void setReliveStone(int reliveStone) { this.reliveStone = reliveStone; }

    // 技能
    public Map<String, Integer> getElementLevels() { return elementLevels; }
    public Integer getElementLevel(String element) { return this.elementLevels.getOrDefault(element, 0); }
    public void setElementLevel(String element, int level) { this.elementLevels.put(element, level); }

    // 锻造
    public Integer getForgeLevel() { return forgeLevel; }
    public void setForgeLevel(Integer forgeLevel) { this.forgeLevel = forgeLevel; }
    public Integer getForgeExp() { return forgeExp; }
    public void setForgeExp(Integer forgeExp) { this.forgeExp = forgeExp; }
    public Integer getForgeLicense() { return forgeLicense; }
    public void setForgeLicense(Integer forgeLicense) { this.forgeLicense = forgeLicense; }

    public double getVal(Double val) {
        return val == null ? 0.0 : val;
    }
}