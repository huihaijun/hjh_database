package com.hjh_database.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class PlayerData(val uuid: UUID, val playerName: String) {

    // === 基础信息 ===
    var lv: Int = 1
    var job: Int? = null
    var race: Int? = null

    // === 物理战斗属性 ===
    var attack: Double = 0.0
    var archerDamage: Double = 0.0
    var armor: Double = 0.0
    var speed: Double = 0.2
    var maxHealth: Double = 20.0
    var currentHealth: Double = 20.0
    var toughness: Double = 0.0
    var knockBackRes: Double = 0.0
    var attackSpeed: Double = 4.0

    // 暴击率
    var critChance: Double = 0.0

    // === 法术战斗属性 ===
    var zfStr: Double = 0.0

    // === 公用属性 ===
    var coolReduce: Double = 0.0

    // === 灵力系统 ===
    // 【修改】这个字段现在代表 "当前灵力" (Current Lingli)
    // 它会由 DatabaseManager 自动存取
    var lingli: Double = 0.0

    // 【新增】这个字段代表 "灵力上限" (Max Lingli)
    // 它是动态计算的 (50 + Lv*3 + 装备)，不需要存数据库
    @Transient
    var maxLingli: Double = 50.0

    // 额外灵力 (装备提供的上限加成)
    @Transient
    var extraLingli: Double = 0.0

    // ==========================================
    //           医师系统 (Medical)
    // ==========================================
    // 存储已绘制成功的医术ID (例如: "test1", "test2")
    // 存储已学会的医术
    // 【修复】使用 MutableList 以支持 add/remove
    var medicalSkills: MutableList<String> = ArrayList()

    // --- 修复报错的核心方法 ---

    // 1. 获取当前持有的医术 (对应 Manager 中的 getMedicalLoadout)
    fun getMedicalLoadout(): MutableList<String> {
        return medicalSkills
    }

    // 2. 添加记忆 (对应 Manager 中的 addMedicalSkillMemory)
    fun addMedicalSkillMemory(skillId: String) {
        if (!medicalSkills.contains(skillId)) {
            medicalSkills.add(skillId)
        }
    }

    // 3. 遗忘/移除记忆 (对应 Manager 分离时的逻辑)
    fun removeMedicalSkillMemory(skillId: String) {
        if (medicalSkills.contains(skillId)) {
            medicalSkills.remove(skillId)
        }
    }

    // 4.清空所有医术记忆
    fun clearMedicalSkills() {
        medicalSkills.clear()
    }

    // --- 数据库辅助方法 (保持不变) ---
    fun getMedicalSkillsAsString(): String {
        if (medicalSkills.isEmpty()) return ""
        return java.lang.String.join(",", medicalSkills)
    }

    fun setMedicalSkillsFromString(str: String?) {
        medicalSkills = ArrayList()
        if (!str.isNullOrEmpty()) {
            val parts = str.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            Collections.addAll(medicalSkills, *parts)
        }
    }

    // 【新增】存储医术离线剩余冷却时间 (Key=医术ID, Value=剩余毫秒)
    // 【修复】使用 MutableMap
    var medicalCooldowns: MutableMap<String, Long> = HashMap()

    // JSON 序列化：存入数据库时变成字符串
    fun getMedicalCooldownsAsJson(): String {
        if (medicalCooldowns.isEmpty()) {
            return "{}"
        }
        return Gson().toJson(medicalCooldowns)
    }

    // JSON 反序列化：从数据库读出来变回 Map
    fun setMedicalCooldownsFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "{}") {
            this.medicalCooldowns = HashMap()
            return
        }
        try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            this.medicalCooldowns = Gson().fromJson(json, type)
        } catch (e: Exception) {
            this.medicalCooldowns = HashMap()
            e.printStackTrace()
        }
    }

    var jhq: Double = 0.0
    var money: Double = 0.0

    // 仓库
    var metal: Int = 0
    var wood: Int = 0
    var water: Int = 0
    var fire: Int = 0
    var earth: Int = 0
    var reliveStone: Int = 0

    // 技能
    // 【修复】使用 MutableMap
    var elementLevels: MutableMap<String, Int> = HashMap()

    // 锻造
    var forgeLevel: Int = 1
    var forgeExp: Int = 0
    var forgeLicense: Int = 0

    // 【新增】当前经验值
    var exp: Int = 0

    // === 稀有度系统 ===
    // 【新增】装备总稀有度 (武器+护甲)
    var totalRarity: Int = 0

    // ★【新增】用来存明细的列表 (例如存 [5, 5, 2, 3])
    // 【修复】使用 MutableList
    val rarityDetails: MutableList<Int> = ArrayList()

    // 采集系统-开物术
    // 注意：这里改为 Int 彻底解决了 "Operator call is prohibited on a nullable receiver"
    var kaiwuLevel: Int = 1
    var kaiwuExp: Int = 0
    var kaiwuEnergy: Double = 100.0 // 精力值

    // 采集系统——开物术
    // 稀疏存储核心：只存冷却中的节点 { "world,100,64,200": 1700000000 }
    // 【修复】使用 MutableMap 彻底解决 remove/put 报错
    var nodeCoolDowns: MutableMap<String, Long> = HashMap()

    // === Getters and Setters ===
    // Kotlin 自动生成了属性的 getter/setter，以下仅保留有特殊逻辑的方法
    // 或者为了兼容旧的 Java 命名习惯而显式定义的方法

    // 特殊逻辑：安全增加灵力
    fun addLingli(amount: Double) {
        val max = maxLingli
        this.lingli += amount
        if (this.lingli > max) this.lingli = max
        if (this.lingli < 0) this.lingli = 0.0
    }

    // 兼容方法：获取总灵力上限
    val totalLingli: Double
        get() = maxLingli

    // 技能 Map 操作
    fun getElementLevel(element: String): Int {
        return this.elementLevels.getOrDefault(element, 0)
    }

    fun setElementLevel(element: String, level: Int) {
        this.elementLevels[element] = level
    }

    // 辅助方法：处理空值 (虽然现在大多数字段非空，但保留此方法以兼容逻辑)
    fun getVal(value: Double?): Double {
        return value ?: 0.0
    }

    // === KaiWu 开物系统特殊 Getter/Setter ===

    // Kotlin 属性 kaiwuLevel 自动生成 getKaiwuLevel()
    // 如果必须严格匹配 Java 的 getKaiWuLevel (大写W)，在纯 Kotlin 调用中通常使用属性访问 data.kaiwuLevel
    // 这里保持属性名与原代码一致：kaiwuLevel

    val maxKaiWuEnergy: Double
        get() = 100.0 + (this.kaiwuLevel * 10.0)

    // 【修改】设置精力时防止低于0
    // 注意：Kotlin 中如果要覆盖 setter 逻辑，写法如下：
    // 但由于你可能直接访问属性，这里保留原函数名的 set 方法风格，或者使用属性的 setter
    // 为了不改变源代码逻辑，这里模拟原 Java 的 setKaiWuEnergy 方法
    @JvmName("setKaiWuEnergyCustom") // 避免与属性默认 setter 冲突
    fun setKaiWuEnergy(energy: Double) {
        var newEnergy = energy
        if (newEnergy < 0) newEnergy = 0.0
        // if (newEnergy > maxKaiWuEnergy) newEnergy = maxKaiWuEnergy
        this.kaiwuEnergy = newEnergy
    }

    val kaiWuNextLevelExp: Int
        get() = this.kaiwuLevel * 100

    // --- JSON 序列化逻辑 (用于存入数据库 LONGTEXT) ---
    // 简单实现，避免依赖复杂库，格式： "key:value,key2:value2"

    fun getNodeDataAsJsonString(): String {
        if (nodeCoolDowns.isEmpty()) {
            return "{}"
        }
        return Gson().toJson(nodeCoolDowns)
    }

    fun setNodeDataFromJsonString(json: String?) {
        if (json.isNullOrEmpty() || json == "{}") {
            this.nodeCoolDowns = HashMap()
            return
        }
        try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            this.nodeCoolDowns = Gson().fromJson(json, type)
        } catch (e: Exception) {
            this.nodeCoolDowns = HashMap()
            e.printStackTrace()
        }
    }
}