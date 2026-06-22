package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.weapon.ArmorManager
import com.hjh_database.weapon.CrystalManager
import com.hjh_database.weapon.WeaponManager
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class PlayerManager(private val plugin: Hjh_database) {
    private val dataCache: MutableMap<UUID, PlayerData> = ConcurrentHashMap()
    private val dzDataCache: MutableMap<UUID, DzPlayerData> = ConcurrentHashMap()

    companion object {
        const val CURRENT_EXP_CURVE_VERSION = 2
        private const val MAX_LEVEL_FOR_EXP_MIGRATION = 100
    }

    // 保持原有变量名的访问性
    val weaponManager: WeaponManager
    val armorManager: ArmorManager
    val crystalManager: CrystalManager // 【新增声明】

    // 【新增】等级配置文件对象
    private var levelsFile: File? = null
    private var levelsConfig: YamlConfiguration? = null

    init {
        this.weaponManager = WeaponManager(plugin)
        this.armorManager = ArmorManager(plugin)
        this.crystalManager = CrystalManager(plugin) // 【新增初始化】
        // 【新增】初始化时加载等级配置
        loadLevelConfig()
    }

    // 【新增】加载 levels.yml
    fun loadLevelConfig() {
        levelsFile = File(plugin.dataFolder, "levels.yml")
        if (!levelsFile!!.exists()) {
            plugin.saveResource("levels.yml", false)
        }
        levelsConfig = YamlConfiguration.loadConfiguration(levelsFile!!)
        upgradeLevelConfigIfNeeded()
    }

    private fun upgradeLevelConfigIfNeeded() {
        val config = levelsConfig ?: return
        val file = levelsFile ?: return
        if (config.getInt("curve_version", 1) >= CURRENT_EXP_CURVE_VERSION) return

        config.set("curve_version", CURRENT_EXP_CURVE_VERSION)
        config.set("level_stages", null)

        setLevelStage(config, "stage_1", 1, 10, 80, 40)
        setLevelStage(config, "stage_2", 11, 20, 500, 60)
        setLevelStage(config, "stage_3", 21, 30, 1200, 90)
        setLevelStage(config, "stage_4", 31, 40, 2500, 140)
        setLevelStage(config, "stage_5", 41, 100, 4500, 220)

        config.save(file)
        plugin.logger.info("[ExpCurve] 已将 levels.yml 升级到经验曲线版本 $CURRENT_EXP_CURVE_VERSION")
    }

    private fun setLevelStage(config: YamlConfiguration, key: String, min: Int, max: Int, base: Int, multiplier: Int) {
        val path = "level_stages.$key"
        config.set("$path.min_level", min)
        config.set("$path.max_level", max)
        config.set("$path.base", base)
        config.set("$path.multiplier", multiplier)
    }

    // 【新增】获取怪物经验配置
    fun getMobExp(): Int {
        return levelsConfig!!.getInt("mobs.panling_monster_exp", 20)
    }

    // 【新增】计算升级所需经验
    fun getMaxExpRequired(currentLevel: Int): Int {
        val stages = levelsConfig!!.getConfigurationSection("level_stages")
        if (stages != null) {
            for (key in stages.getKeys(false)) {
                val stage = stages.getConfigurationSection(key) ?: continue
                val min = stage.getInt("min_level")
                val max = stage.getInt("max_level")
                if (currentLevel in min..max) {
                    val base = stage.getInt("base")
                    val multiplier = stage.getInt("multiplier")
                    return base + (currentLevel * multiplier)
                }
            }
        }
        return 100 + (currentLevel * 50) // 默认公式
    }

    private fun getOldMaxExpRequired(currentLevel: Int): Int {
        return when {
            currentLevel in 1..10 -> 100 + (currentLevel * 50)
            currentLevel in 11..20 -> 1000 + (currentLevel * 100)
            currentLevel in 21..100 -> 5000 + (currentLevel * 200)
            else -> 100 + (currentLevel * 50)
        }
    }

    private fun getOldTotalExp(lv: Int, exp: Int): Long {
        var total = exp.coerceAtLeast(0).toLong()
        for (level in 1 until lv.coerceAtLeast(1)) {
            total += getOldMaxExpRequired(level).toLong()
        }
        return total
    }

    private fun applyTotalExpToCurrentCurve(data: PlayerData, totalExp: Long) {
        var level = 1
        var remaining = totalExp.coerceAtLeast(0)

        while (level < MAX_LEVEL_FOR_EXP_MIGRATION) {
            val required = getMaxExpRequired(level).toLong()
            if (required <= 0 || remaining < required) break
            remaining -= required
            level++
        }

        data.lv = level
        data.exp = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        data.expCurveVersion = CURRENT_EXP_CURVE_VERSION
    }

    private fun migrateExpCurveIfNeeded(data: PlayerData): Boolean {
        if (data.expCurveVersion >= CURRENT_EXP_CURVE_VERSION) return false

        val oldLv = data.lv
        val oldExp = data.exp
        val totalExp = getOldTotalExp(oldLv, oldExp)
        applyTotalExpToCurrentCurve(data, totalExp)

        plugin.logger.info("[ExpCurve] ${data.playerName} 经验曲线迁移: Lv.$oldLv/$oldExp -> Lv.${data.lv}/${data.exp}, total=$totalExp")
        return true
    }

    // 【新增】核心：给予经验
    fun giveExp(player: Player, amount: Int) {
        val data = getData(player.uniqueId) ?: return

        var currentExp = data.exp
        var maxExp = getMaxExpRequired(data.lv)

        currentExp += amount
        var leveledUp = false

        // 循环升级逻辑
        while (currentExp >= maxExp) {
            currentExp -= maxExp
            data.lv = data.lv + 1
            maxExp = getMaxExpRequired(data.lv)
            leveledUp = true

            player.sendMessage("§a§l[升级] §e你的等级提升到了 " + data.lv + " 级！")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        }

        data.exp = currentExp

        // 刷新属性（因为升级了，且需要同步经验条）
        updateStats(player)

        // 升级保存
        if (leveledUp) {
            tryAutoAcceptLevelQuests(player, data)
            plugin.databaseManager.savePlayerAsync(data)
        }
    }

    private fun tryAutoAcceptLevelQuests(player: Player, data: PlayerData) {
        val questIds = listOf(
            "side_warrior_shield_book",
            "side_archer_quiver_book",
            "side_warlock_backflow_book",
            "side_medical_taolizhi_book",
            "side_tianjige_rumor"
        )
        for (questId in questIds) {
            val quest = plugin.questManager.getQuest(questId) ?: continue
            val status = data.questStatuses[quest.id] ?: QuestStatus.LOCKED
            if (status == QuestStatus.LOCKED && quest.canAccept(player, data)) {
                plugin.questManager.acceptQuest(player, quest.id)
                player.sendMessage("§a[任务系统] 新任务已接取: ${quest.title}")
            }
        }
    }

    fun getData(uuid: UUID): PlayerData? {
        return dataCache[uuid]
    }

    fun getDzData(uuid: UUID): DzPlayerData? {
        return dzDataCache[uuid]
    }

    fun loadAndCache(player: Player) {
        plugin.databaseManager.loadPlayer(player.uniqueId, player.name)
            .thenAccept { loadedData ->
                // 如果数据库为空，创建新数据
                val data = loadedData ?: PlayerData(player.uniqueId, player.name)
                val migratedExpCurve = migrateExpCurveIfNeeded(data)
                dataCache[player.uniqueId] = data

                // 同步锻造数据 (保持原样)
                val dzData = DzPlayerData(player.uniqueId, player.name)
                dzData.forgeLevel = data.forgeLevel!!
                dzData.forgeExp = data.forgeExp!!
                dzData.forgeLicense = data.forgeLicense!!
                dzDataCache[player.uniqueId] = dzData

                val finalData = data
                if (migratedExpCurve) {
                    plugin.databaseManager.savePlayer(finalData)
                }
                plugin.server.scheduler.runTask(plugin, Runnable {
                    updateStats(player)
                    syncToVanilla(player, finalData)
                    tryAutoAcceptLevelQuests(player, finalData)
                    if (migratedExpCurve) {
                        player.sendMessage("§a[经验系统] §7已按新版经验曲线无损折算你的等级与经验。")
                    }
                })
            }
    }

    fun unloadAndSave(uuid: UUID) {
        val data = dataCache.remove(uuid)
        val dzData = dzDataCache.remove(uuid)

        if (data != null) {
            if (dzData != null) {
                data.forgeLevel = dzData.forgeLevel
                data.forgeExp = dzData.forgeExp
                data.forgeLicense = dzData.forgeLicense
            }
            plugin.databaseManager.savePlayer(data)
        }
    }

    fun saveAllOnline() {
        for (uuid in dataCache.keys) {
            val data = dataCache[uuid]
            val dzData = dzDataCache[uuid]

            if (data != null && dzData != null) {
                data.forgeLevel = dzData.forgeLevel
                data.forgeExp = dzData.forgeExp
                data.forgeLicense = dzData.forgeLicense
                plugin.databaseManager.savePlayer(data)
            }
        }
    }

    fun updateStats(player: Player) {
        val data = dataCache[player.uniqueId] ?: return

        // 1. 重置基础属性 (保持原样)
        data.maxHealth = 20.0
        data.attack = 0.0
        data.archerDamage = 0.0
        data.zfStr = 0.0
        data.armor = 0.0
        data.knockBackRes = 0.0
        data.speed = 0.2
        data.critChance = 0.0
        data.coolReduce = 0.0
        data.totalRarity = 0

        // ★【新增】重置总分 和 清空明细列表 (必须加这句！)
        data.rarityDetails.clear()

        // 2. 获取各模块加成 (保持原有的 Map 计算逻辑)
        val bonuses: MutableMap<String, Double> = HashMap()

        val weaponStats = weaponManager.calculateWeaponStats(player, data)
        weaponStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        val armorStats = armorManager.calculateArmorStats(player, data)
        armorStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        // 【新增】仿照护甲，将饰品第一格的结晶属性也计算进去
        val crystalStats = crystalManager.calculateCrystalStats(player, data)
        crystalStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        // ★★★ (C) 【新增】技能/Buff 临时加成 ★★★
        // 这一步让技能可以直接影响最终面板，而不需要改写 PlayerData 的具体字段
        data.tempBonuses.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        // ★ 在这里加上这段代码：从 Map 中提取总稀有度并保存
        if (bonuses.containsKey("total_rarity")) {
            data.totalRarity = bonuses["total_rarity"]!!.toInt()
        }

        // --- 生命值 ---
        var extraHealth = bonuses.getOrDefault("max_health", 0.0)
        data.maxHealth += extraHealth
        // 处理生命百分比
        if (bonuses.containsKey("max_health_percent")) {
            val percent = bonuses["max_health_percent"]!!
            data.maxHealth *= (1.0 + percent)
        }

        // --- 攻击力 ---
        var baseAttack = bonuses.getOrDefault("attack", 0.0)
        // 处理攻击力百分比
        if (bonuses.containsKey("attack_percent")) {
            // 目前逻辑：装备给的攻击力 * (1 + 百分比)
            baseAttack *= (1.0 + bonuses["attack_percent"]!!)
        }
        data.attack += baseAttack

        // --- 箭矢强度 ---
        var baseArcher = bonuses.getOrDefault("archer_damage", 0.0)
        if (bonuses.containsKey("archer_damage_percent")) {
            baseArcher *= (1.0 + bonuses["archer_damage_percent"]!!)
        }
        data.archerDamage += baseArcher

        // --- 阵法强度 ---
        var baseZfStr = bonuses.getOrDefault("zf_str", 0.0)
        if (bonuses.containsKey("zf_str_percent")) {
            baseZfStr *= (1.0 + bonuses["zf_str_percent"]!!)
        }
        data.zfStr += baseZfStr


        // --- 护甲 ---
        data.armor += bonuses.getOrDefault("armor", 0.0)
        // 处理护甲百分比 (青铜剑)
        if (bonuses.containsKey("armor_percent")) {
            data.armor *= (1.0 + bonuses["armor_percent"]!!)
        }

        data.knockBackRes += bonuses.getOrDefault("knock_back_res", 0.0)
        data.critChance += bonuses.getOrDefault("crit_chance", 0.0)
        data.coolReduce += bonuses.getOrDefault("cool_reduce", 0.0)

        // === 灵力计算逻辑 (保持原样) ===
        // 公式：50 + (等级 * 3)
        var baseLingli = 50.0 + (data.lv * 3.0)

        // 限制最高 300 点 (针对基础成长)
        if (baseLingli > 300.0) {
            baseLingli = 300.0
        }

        // === 【核心修改】保存冷却缩减 (限制最高 50%) ===
        var coolReduce = data.coolReduce
        if (coolReduce > 0.5) coolReduce = 0.5
        data.coolReduce = coolReduce


        // 获取装备提供的额外灵力
        val equipLingli = bonuses.getOrDefault("lingli", 0.0)
        data.extraLingli = equipLingli

        // 设置总灵力上限 (基础 + 装备)
        data.maxLingli = baseLingli + equipLingli

        // 如果当前灵力超过了上限，则修正为上限
        if (data.lingli > data.maxLingli) {
            data.lingli = data.maxLingli
        }
        // ==============================

        if (bonuses.containsKey("speed")) {
            data.speed += bonuses["speed"]!!
        }

        // === 【新增】速度百分比逻辑 ===
        // 在 weapons.yml 里写 speed_percent: 0.5 代表增加 50% 移速
        // --- 移速 ---
        if (bonuses.containsKey("speed")) {
            data.speed += bonuses["speed"]!!
        }
        // 处理移速百分比
        if (bonuses.containsKey("speed_percent")) {
            // speed_percent 为负数时即为减速
            data.speed *= (1.0 + bonuses["speed_percent"]!!)
        }

        syncToVanilla(player, data)
    }

    private fun syncToVanilla(player: Player, data: PlayerData) {
        // (保持原有的属性同步)
        // 1.21.3 适配：Attribute 枚举去除了 GENERIC_ 前缀
        val maxHp = max(1.0, data.maxHealth)
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.getAttribute(Attribute.MAX_HEALTH)!!.baseValue = maxHp
        }

        val speed = min(1.0, max(0.0, data.speed))
        player.walkSpeed = speed.toFloat()

        if (player.getAttribute(Attribute.KNOCKBACK_RESISTANCE) != null) {
            val kb = min(1.0, max(0.0, data.knockBackRes))
            player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)!!.baseValue = kb
        }

        if (player.getAttribute(Attribute.ARMOR) != null) {
            player.getAttribute(Attribute.ARMOR)!!.baseValue = 0.0
            for (modifier in player.getAttribute(Attribute.ARMOR)!!.modifiers) {
                player.getAttribute(Attribute.ARMOR)!!.removeModifier(modifier)
            }
        }

        if (player.getAttribute(Attribute.ARMOR_TOUGHNESS) != null) {
            player.getAttribute(Attribute.ARMOR_TOUGHNESS)!!.baseValue = 0.0
            for (modifier in player.getAttribute(Attribute.ARMOR_TOUGHNESS)!!.modifiers) {
                player.getAttribute(Attribute.ARMOR_TOUGHNESS)!!.removeModifier(modifier)
            }
        }

        // === 【新增】同步等级和经验条到原版界面 ===
        player.level = data.lv!!

        val currentExp = data.exp
        val maxExp = getMaxExpRequired(data.lv!!)
        // 计算百分比 0.0 - 1.0
        var progress = 0.0f
        if (maxExp > 0) {
            progress = currentExp.toFloat() / maxExp.toFloat()
        }
        // 限制进度条范围，防止客户端显示鬼畜
        progress = min(0.999f, max(0.0f, progress))
        player.exp = progress
    }

    /**
     * 【新增】获取玩家数据对象
     * 供外部系统（如开物术、菜单等）调用
     */
    fun getPlayerData(player: Player?): PlayerData? {
        if (player == null) return null
        return dataCache[player.uniqueId]
    }
}
