package com.hjh_database.data;

import java.util.*;

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

    // ==========================================
    //           医师系统 (Medical)
    // ==========================================
    // 存储已绘制成功的医术ID (例如: "test1", "test2")
    // 存储已学会的医术
    private List<String> medicalSkills = new ArrayList<>();

    // --- 修复报错的核心方法 ---

    // 1. 获取当前持有的医术 (对应 Manager 中的 getMedicalLoadout)
    public List<String> getMedicalLoadout() {
        return medicalSkills;
    }

    // 2. 添加记忆 (对应 Manager 中的 addMedicalSkillMemory)
    public void addMedicalSkillMemory(String skillId) {
        if (!medicalSkills.contains(skillId)) {
            medicalSkills.add(skillId);
        }
    }

    // 3. 遗忘/移除记忆 (对应 Manager 分离时的逻辑)
    public void removeMedicalSkillMemory(String skillId) {
        if (medicalSkills.contains(skillId)) {
            medicalSkills.remove(skillId);
        }
    }

    // 4.清空所有医术记忆
    public void clearMedicalSkills() {
        medicalSkills.clear();
    }

    // --- 数据库辅助方法 (保持不变) ---
    public String getMedicalSkillsAsString() {
        if (medicalSkills == null || medicalSkills.isEmpty()) return "";
        return String.join(",", medicalSkills);
    }

    public void setMedicalSkillsFromString(String str) {
        medicalSkills = new ArrayList<>();
        if (str != null && !str.isEmpty()) {
            String[] parts = str.split(",");
            Collections.addAll(medicalSkills, parts);
        }
    }

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

    // 【新增】当前经验值
    private int exp = 0;

    // === 稀有度系统 ===
    // 【新增】装备总稀有度 (武器+护甲)
    private Integer totalRarity = 0;

//    采集系统-开物术
    private Integer kaiwuLevel = 1;
    private Integer kaiwuExp = 0;
    private Double kaiwuEnergy = 100.0; // 精力值

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

    public int getExp() { return exp; }
    public void setExp(int exp) { this.exp = exp; }

    //装备稀有度
    public Integer getTotalRarity() { return totalRarity; }
    public void setTotalRarity(Integer totalRarity) { this.totalRarity = totalRarity; }

    // ... 原有的 totalRarity ...
    // ★【新增】用来存明细的列表 (例如存 [5, 5, 2, 3])
    private final java.util.List<Integer> rarityDetails = new java.util.ArrayList<>();

    public java.util.List<Integer> getRarityDetails() {
        return rarityDetails;
    }

    //采集系统——开物术
    // 稀疏存储核心：只存冷却中的节点 { "world,100,64,200": 1700000000 }
    private Map<String, Long> nodeCoolDowns = new HashMap<>();

    public Integer getKaiWuLevel() { return kaiwuLevel; }
    public void setKaiWuLevel(Integer kaiwuLevel) { this.kaiwuLevel = kaiwuLevel; }

    public Integer getKaiWuExp() { return kaiwuExp; }
    public void setKaiWuExp(Integer kaiwuExp) { this.kaiwuExp = kaiwuExp; }

    public Double getKaiWuEnergy() { return kaiwuEnergy; }
//    public void setKaiWuEnergy(Double kaiwuEnergy) { this.kaiwuEnergy = kaiwuEnergy; }

    public double getMaxKaiWuEnergy() {
        return 100.0 + (this.kaiwuLevel * 10.0);
    }

    // 【修改】设置精力时防止低于0，也防止超过上限(可选，这里只防负数)
    public void setKaiWuEnergy(Double kaiwuEnergy) {
        if (kaiwuEnergy < 0) kaiwuEnergy = 0.0;
        // 如果你需要限制上限，可以解开下面这行
        // if (kaiwuEnergy > getMaxKaiWuEnergy()) kaiwuEnergy = getMaxKaiWuEnergy();
        this.kaiwuEnergy = kaiwuEnergy;
    }

    public int getKaiWuNextLevelExp() {
        // 为了方便，这里暂时硬编码默认值，实际建议从 KaiWuManager 传参或做成静态配置读取
        // 假设基础值是 100
        return this.kaiwuLevel * 100;
    }

    public Map<String, Long> getNodeCoolDowns() { return nodeCoolDowns; }
    public void setNodeCoolDowns(Map<String, Long> nodeCoolDowns) { this.nodeCoolDowns = nodeCoolDowns; }

    // --- JSON 序列化逻辑 (用于存入数据库 LONGTEXT) ---
    // 简单实现，避免依赖复杂库，格式： "key:value,key2:value2"
    // 如果你服务器有 Gson，建议用 Gson，这里手写一个轻量级的防报错

    public String getNodeDataAsJsonString() {
        if (nodeCoolDowns == null || nodeCoolDowns.isEmpty()) {
            return "{}";
        }
        // 使用 Gson (Spigot 自带)
        return new com.google.gson.Gson().toJson(nodeCoolDowns);
    }

    public void setNodeDataFromJsonString(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            this.nodeCoolDowns = new HashMap<>();
            return;
        }
        try {
            // 使用 Gson 反序列化
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, Long>>(){}.getType();
            this.nodeCoolDowns = new com.google.gson.Gson().fromJson(json, type);
        } catch (Exception e) {
            this.nodeCoolDowns = new HashMap<>();
            e.printStackTrace();
        }
    }


}