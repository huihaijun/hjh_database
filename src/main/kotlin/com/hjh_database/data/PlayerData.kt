package com.hjh_database.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hjh_database.dungeon.DungeonRecord
import com.hjh_database.quest.core.QuestStatus
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class PlayerData(val uuid: UUID, val playerName: String) {

    // ==========================================
    //            1. 基础与战斗属性
    // ==========================================

    // --- 基础信息 ---
    var lv: Int = 1
    var exp: Int = 0
    var job: Int? = null
    var race: Int? = null

    // --- 物理战斗属性 ---
    var attack: Double = 0.0
    var archerDamage: Double = 0.0
    var armor: Double = 0.0
    var speed: Double = 0.2
    var maxHealth: Double = 20.0
    var currentHealth: Double = 20.0
    var toughness: Double = 0.0
    var knockBackRes: Double = 0.0
    var attackSpeed: Double = 4.0
    var critChance: Double = 0.0 // 暴击率

    // --- 法术与公用战斗属性 ---
    var zfStr: Double = 0.0
    var coolReduce: Double = 0.0

    // ★★★ 通用临时属性池 ★★★
    // Key 对应 PlayerManager 里的属性名 (如 "attack", "max_health", "speed_percent")
    // 不需要存入数据库，玩家下线或重启后自动清空，非常适合技能 buff
    val tempBonuses = ConcurrentHashMap<String, Double>()

    // --- 灵力系统 ---
    var lingli: Double = 0.0

    @Transient
    var maxLingli: Double = 50.0

    @Transient
    var extraLingli: Double = 0.0

    val totalLingli: Double
        get() = maxLingli

    fun addLingli(amount: Double) {
        val max = maxLingli
        this.lingli += amount
        if (this.lingli > max) this.lingli = max
        if (this.lingli < 0) this.lingli = 0.0
    }

    // ==========================================
    //            2. 资源与经济、技能数据
    // ==========================================

    var jhq: Double = 0.0
    var money: Double = 0.0

    // --- 仓库/元素 ---
    var metal: Int = 0
    var wood: Int = 0
    var water: Int = 0
    var fire: Int = 0
    var earth: Int = 0
    var reliveStone: Int = 0

    // --- 元素技能 ---
    var elementLevels: MutableMap<String, Int> = HashMap()

    fun getElementLevel(element: String): Int {
        return this.elementLevels.getOrDefault(element, 0)
    }

    fun setElementLevel(element: String, level: Int) {
        this.elementLevels[element] = level
    }

    // --- 稀有度系统 ---
    var totalRarity: Int = 0
    val rarityDetails: MutableList<Int> = ArrayList()


    // ==========================================
    //            3. 各子系统数据模块
    // ==========================================

    // ----------------- 任务系统 (Quest) -----------------
    // Key: 任务ID, Value: 状态 (LOCKED, IN_PROGRESS, COMPLETED)
    var questStatuses: HashMap<String, QuestStatus> = HashMap()

    // Key: 任务ID, Value: 当前进度数值 (例如杀怪数)
    var questProgress: HashMap<String, Int> = HashMap()

    // 已完成任务的缓存 (只存 completed 的任务ID)
    val completedQuests: MutableSet<String> = HashSet()


    // ----------------- 医师系统 (Medical) -----------------
    var medicalSkills: MutableList<String> = ArrayList()
    var medicalCooldowns: MutableMap<String, Long> = HashMap()

    fun getMedicalLoadout(): MutableList<String> = medicalSkills

    fun addMedicalSkillMemory(skillId: String) {
        if (!medicalSkills.contains(skillId)) medicalSkills.add(skillId)
    }

    fun removeMedicalSkillMemory(skillId: String) {
        if (medicalSkills.contains(skillId)) medicalSkills.remove(skillId)
    }

    fun clearMedicalSkills() = medicalSkills.clear()

    fun getMedicalSkillsAsString(): String {
        return if (medicalSkills.isEmpty()) "" else java.lang.String.join(",", medicalSkills)
    }

    fun setMedicalSkillsFromString(str: String?) {
        medicalSkills = ArrayList()
        if (!str.isNullOrEmpty()) {
            val parts = str.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            Collections.addAll(medicalSkills, *parts)
        }
    }

    fun getMedicalCooldownsAsJson(): String {
        return if (medicalCooldowns.isEmpty()) "{}" else Gson().toJson(medicalCooldowns)
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


    // ----------------- 锻造系统 (Forge) -----------------
    var forgeLevel: Int = 1
    var forgeExp: Int = 0
    var forgeLicense: Int = 0


    // ----------------- 丹药系统 (Alchemy) -----------------
    var alchemyLevel: Int = 1
    var alchemyExp: Int = 0

    // 当前活跃的药效 (玩家下线后药效保留)
    // @Transient
    val activePills: MutableList<com.hjh_database.alchemy.data.ActivePill> = java.util.ArrayList()

    // 药毒结束时间戳
    var pillSicknessEnd: Long = 0

    val alchemyMaxExp: Int
        get() = alchemyLevel * 50

    fun addAlchemyExp(amount: Int) {
        if (amount <= 0) return
        alchemyExp += amount

        // 循环升级检测
        while (alchemyExp >= alchemyMaxExp) {
            alchemyExp -= alchemyMaxExp
            alchemyLevel++
        }
    }

    fun isSick(): Boolean = System.currentTimeMillis() < pillSicknessEnd

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


    // ----------------- 采集与开物术 (KaiWu) -----------------
    var kaiwuLevel: Int = 1
    var kaiwuExp: Int = 0
    var kaiwuEnergy: Double = 100.0 // 精力值
    var nodeCoolDowns: MutableMap<String, Long> = HashMap()

    val maxKaiWuEnergy: Double
        get() = 100.0 + (this.kaiwuLevel * 10.0)

    val kaiWuNextLevelExp: Int
        get() = this.kaiwuLevel * 100

    @JvmName("setKaiWuEnergyCustom")
    fun setKaiWuEnergy(energy: Double) {
        var newEnergy = energy
        if (newEnergy < 0) newEnergy = 0.0
        this.kaiwuEnergy = newEnergy
    }

    fun getNodeDataAsJsonString(): String {
        return if (nodeCoolDowns.isEmpty()) "{}" else Gson().toJson(nodeCoolDowns)
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


    // ----------------- 金宝箱与副本系统 (Dungeon) -----------------
    var dungeonRecords: MutableMap<String, DungeonRecord> = HashMap()

    fun getDungeonRecordsAsJson(): String {
        return if (dungeonRecords.isEmpty()) "{}" else Gson().toJson(dungeonRecords)
    }

    fun setDungeonRecordsFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "{}" || json == "null") {
            this.dungeonRecords = HashMap()
            return
        }
        try {
            val type = object : TypeToken<Map<String, DungeonRecord>>() {}.type
            this.dungeonRecords = Gson().fromJson(json, type)
        } catch (e: Exception) {
            this.dungeonRecords = HashMap()
            e.printStackTrace()
        }
    }


    // ----------------- 玩家状态系统 (Status) -----------------
    var status: Int = 0
    var statusDescription: String = "新人进入服务器"

    /**
     * 更新状态的唯一入口，调用会自动更新 status 和 description
     */
    fun updateStatus(newStatus: Int) {
        this.status = newStatus
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


    // ----------------- 重华晶系统 (Chonghua) -----------------
    var unlockedWaypoints: MutableSet<String> = HashSet()
    var waypointCooldowns: MutableMap<String, Long> = HashMap()

    fun getUnlockedWaypointsAsJson(): String {
        return if (unlockedWaypoints.isEmpty()) "[]" else Gson().toJson(unlockedWaypoints)
    }

    fun setUnlockedWaypointsFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "[]" || json == "null") {
            this.unlockedWaypoints = HashSet()
            return
        }
        try {
            val type = object : TypeToken<Set<String>>() {}.type
            this.unlockedWaypoints = Gson().fromJson(json, type)
        } catch (e: Exception) {
            this.unlockedWaypoints = HashSet()
            e.printStackTrace()
        }
    }

    fun getWaypointCooldownsAsJson(): String {
        return if (waypointCooldowns.isEmpty()) "{}" else Gson().toJson(waypointCooldowns)
    }

    fun setWaypointCooldownsFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "{}" || json == "null") {
            this.waypointCooldowns = HashMap()
            return
        }
        try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            this.waypointCooldowns = Gson().fromJson(json, type)
        } catch (e: Exception) {
            this.waypointCooldowns = HashMap()
            e.printStackTrace()
        }
    }

    // ==========================================
    //            4. 辅助工具方法
    // ==========================================
    fun getVal(value: Double?): Double {
        return value ?: 0.0
    }
}