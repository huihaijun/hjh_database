package com.hjh_database.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
// 【新增】导入任务状态枚举
import com.hjh_database.quest.core.QuestStatus
import java.util.concurrent.ConcurrentHashMap

class PlayerData(val uuid: UUID, val playerName: String) {

    // === 基础信息 ===
    var lv: Int = 1
    var job: Int? = null
    var race: Int? = null

    // === 物理战斗属性 ===
    var attack: Double = 0.0
    var archerDamage: Double = 0.0
    var armor: Double = 0.0

    // ★★★ 【新增】通用临时属性池 ★★★
    // Key 对应 PlayerManager 里的属性名 (如 "attack", "max_health", "speed_percent")
    // 不需要存入数据库，玩家下线或重启后自动清空，非常适合技能 buff
    val tempBonuses = ConcurrentHashMap<String, Double>()

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

    // 【新增】冶药法属性
    var alchemyLevel: Int = 1
    var alchemyExp: Int = 0

    // 【新增】获取当前等级升级所需经验 (1级50, 2级100, 3级150...)
    val alchemyMaxExp: Int
        get() = alchemyLevel * 50

    // 【新增】增加经验逻辑
    fun addAlchemyExp(amount: Int) {
        if (amount <= 0) return
        alchemyExp += amount

        // 循环升级检测
        while (alchemyExp >= alchemyMaxExp) {
            alchemyExp -= alchemyMaxExp
            alchemyLevel++
            // 可以根据需要在这里播放升级音效或发送消息
            // Bukkit.getPlayer(uuid)?.sendMessage("§a[冶药法] 你的冶药等级提升到了 Lv.$alchemyLevel！")
        }
    }

    // 当前经验值
    var exp: Int = 0

    // === 稀有度系统 ===
    var totalRarity: Int = 0
    val rarityDetails: MutableList<Int> = ArrayList()

    // 采集系统-开物术
    var kaiwuLevel: Int = 1
    var kaiwuExp: Int = 0
    var kaiwuEnergy: Double = 100.0 // 精力值


    // === 【新增】当前活跃的药效 ===
    // 玩家下线后药效保留（
//    @Transient
    val activePills: MutableList<com.hjh_database.alchemy.data.ActivePill> = java.util.ArrayList()

    // 药毒结束时间戳
    var pillSicknessEnd: Long = 0
    fun isSick(): Boolean = System.currentTimeMillis() < pillSicknessEnd

    // 【新增】已完成任务的缓存 (只存 completed 的任务ID)
    val completedQuests: MutableSet<String> = HashSet()

    /**
     * 每秒调用的心跳函数 (由 AlchemyManager 驱动)
     */
    fun tickAlchemyEffects(plugin: com.hjh_database.Hjh_database, player: org.bukkit.entity.Player) {
        val iterator = activePills.iterator()
        while (iterator.hasNext()) {
            val pill = iterator.next()
            val effect = plugin.alchemyManager.getEffect(pill.effectId)

            if (effect != null) {
                // 执行每秒逻辑
                effect.onTick(player, this, pill.tier, pill.remainingSeconds)
            }

            // 扣除时间
            pill.remainingSeconds--

            // 检查过期
            if (pill.remainingSeconds < 0) {
                effect?.onExpire(player, this, pill.tier)
                iterator.remove()
            }
        }
    }

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

    // 【新增】玩家状态系统 (Status)
    // ==========================================

    // 状态值 (默认 0)
    var status: Int = 0
    // 状态描述
    var statusDescription: String = "新人进入服务器"

    /**
     * 更新状态的唯一入口
     * 调用这个方法，会自动更新 status 和 description
     */
    fun updateStatus(newStatus: Int) {
        this.status = newStatus
        // 根据数字自动匹配描述 (你可以随时在这里修改文案)
        this.statusDescription = when (newStatus) {
            0 -> "新人进入服务器"
            1 -> "新人-过前置描述-未进入盘古大陆"
            2 -> "新人-已进入大陆-过剧情ing"
            3 -> "大陆中"
            4 -> "新人-已进入大陆-职业体验中"
            5 -> "副本中"
            6 -> "奈何桥中"
            else -> "未知状态"
        }
    }
}