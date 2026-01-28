package com.hjh_database.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
// 【新增】导入任务状态枚举
import com.hjh_database.quest.core.QuestStatus

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
    var lingli: Double = 0.0

    @Transient
    var maxLingli: Double = 50.0

    @Transient
    var extraLingli: Double = 0.0

    // ==========================================
    //           任务系统 (Quest) - 【新增】
    // ==========================================
    // Key: 任务ID, Value: 状态 (LOCKED, IN_PROGRESS, COMPLETED)
    var questStatuses: HashMap<String, QuestStatus> = HashMap()

    // Key: 任务ID, Value: 当前进度数值 (例如杀怪数)
    var questProgress: HashMap<String, Int> = HashMap()


    // ==========================================
    //           医师系统 (Medical)
    // ==========================================
    var medicalSkills: MutableList<String> = ArrayList()

    // 1. 获取当前持有的医术
    fun getMedicalLoadout(): MutableList<String> {
        return medicalSkills
    }

    // 2. 添加记忆
    fun addMedicalSkillMemory(skillId: String) {
        if (!medicalSkills.contains(skillId)) {
            medicalSkills.add(skillId)
        }
    }

    // 3. 遗忘/移除记忆
    fun removeMedicalSkillMemory(skillId: String) {
        if (medicalSkills.contains(skillId)) {
            medicalSkills.remove(skillId)
        }
    }

    // 4.清空所有医术记忆
    fun clearMedicalSkills() {
        medicalSkills.clear()
    }

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

    var medicalCooldowns: MutableMap<String, Long> = HashMap()

    fun getMedicalCooldownsAsJson(): String {
        if (medicalCooldowns.isEmpty()) {
            return "{}"
        }
        return Gson().toJson(medicalCooldowns)
    }

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
    var elementLevels: MutableMap<String, Int> = HashMap()

    // 锻造
    var forgeLevel: Int = 1
    var forgeExp: Int = 0
    var forgeLicense: Int = 0

    // 当前经验值
    var exp: Int = 0

    // === 稀有度系统 ===
    var totalRarity: Int = 0
    val rarityDetails: MutableList<Int> = ArrayList()

    // 采集系统-开物术
    var kaiwuLevel: Int = 1
    var kaiwuExp: Int = 0
    var kaiwuEnergy: Double = 100.0 // 精力值

    var nodeCoolDowns: MutableMap<String, Long> = HashMap()

    // === Getters and Setters ===

    fun addLingli(amount: Double) {
        val max = maxLingli
        this.lingli += amount
        if (this.lingli > max) this.lingli = max
        if (this.lingli < 0) this.lingli = 0.0
    }

    val totalLingli: Double
        get() = maxLingli

    fun getElementLevel(element: String): Int {
        return this.elementLevels.getOrDefault(element, 0)
    }

    fun setElementLevel(element: String, level: Int) {
        this.elementLevels[element] = level
    }

    fun getVal(value: Double?): Double {
        return value ?: 0.0
    }

    // === KaiWu 开物系统特殊 Getter/Setter ===
    val maxKaiWuEnergy: Double
        get() = 100.0 + (this.kaiwuLevel * 10.0)

    @JvmName("setKaiWuEnergyCustom")
    fun setKaiWuEnergy(energy: Double) {
        var newEnergy = energy
        if (newEnergy < 0) newEnergy = 0.0
        this.kaiwuEnergy = newEnergy
    }

    val kaiWuNextLevelExp: Int
        get() = this.kaiwuLevel * 100

    // --- JSON 序列化逻辑 ---
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