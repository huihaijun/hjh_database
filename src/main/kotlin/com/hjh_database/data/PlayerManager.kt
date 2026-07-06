package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.effect.impl.JuliWan
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

    // 淇濇寔鍘熸湁鍙橀噺鍚嶇殑璁块棶鎬?
    val weaponManager: WeaponManager
    val armorManager: ArmorManager
    val crystalManager: CrystalManager // 銆愭柊澧炲０鏄庛€?

    // 銆愭柊澧炪€戠瓑绾ч厤缃枃浠跺璞?
    private var levelsFile: File? = null
    private var levelsConfig: YamlConfiguration? = null

    init {
        this.weaponManager = WeaponManager(plugin)
        this.armorManager = ArmorManager(plugin)
        this.crystalManager = CrystalManager(plugin) // 銆愭柊澧炲垵濮嬪寲銆?
        // 銆愭柊澧炪€戝垵濮嬪寲鏃跺姞杞界瓑绾ч厤缃?
        loadLevelConfig()
    }

    // 銆愭柊澧炪€戝姞杞?levels.yml
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
        plugin.logger.info("[ExpCurve] 宸插皢 levels.yml 鍗囩骇鍒扮粡楠屾洸绾跨増鏈?$CURRENT_EXP_CURVE_VERSION")
    }

    private fun setLevelStage(config: YamlConfiguration, key: String, min: Int, max: Int, base: Int, multiplier: Int) {
        val path = "level_stages.$key"
        config.set("$path.min_level", min)
        config.set("$path.max_level", max)
        config.set("$path.base", base)
        config.set("$path.multiplier", multiplier)
    }

    // 銆愭柊澧炪€戣幏鍙栨€墿缁忛獙閰嶇疆
    fun getMobExp(): Int {
        return levelsConfig!!.getInt("mobs.panling_monster_exp", 20)
    }

    // 銆愭柊澧炪€戣绠楀崌绾ф墍闇€缁忛獙
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
        return 100 + (currentLevel * 50) // 榛樿鍏紡
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

        plugin.logger.info("[ExpCurve] ${data.playerName} 缁忛獙鏇茬嚎杩佺Щ: Lv.$oldLv/$oldExp -> Lv.${data.lv}/${data.exp}, total=$totalExp")
        return true
    }

    // 銆愭柊澧炪€戞牳蹇冿細缁欎簣缁忛獙
    fun giveExp(player: Player, amount: Int) {
        val data = getData(player.uniqueId) ?: return

        var currentExp = data.exp
        var maxExp = getMaxExpRequired(data.lv)

        currentExp += amount
        var leveledUp = false

        // 寰幆鍗囩骇閫昏緫
        while (currentExp >= maxExp) {
            currentExp -= maxExp
            data.lv = data.lv + 1
            maxExp = getMaxExpRequired(data.lv)
            leveledUp = true

            player.sendMessage("§a§l[升级] §e你的等级提升到了 " + data.lv + " 级！")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        }

        data.exp = currentExp

        // 鍒锋柊灞炴€э紙鍥犱负鍗囩骇浜嗭紝涓旈渶瑕佸悓姝ョ粡楠屾潯锛?
        updateStats(player)

        // 鍗囩骇淇濆瓨
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

    fun resetCachedData(player: Player): PlayerData {
        val data = PlayerData(player.uniqueId, player.name).apply {
            updateStatus(0)
        }
        dataCache[player.uniqueId] = data

        val dzData = DzPlayerData(player.uniqueId, player.name)
        dzDataCache[player.uniqueId] = dzData

        updateStats(player)
        syncToVanilla(player, data)
        return data
    }

    fun loadAndCache(player: Player) {
        plugin.databaseManager.loadPlayer(player.uniqueId, player.name)
            .thenAccept { loadedData ->
                // 如果数据库为空，创建新数据。
                val data = loadedData ?: PlayerData(player.uniqueId, player.name)
                val migratedExpCurve = migrateExpCurveIfNeeded(data)
                dataCache[player.uniqueId] = data

                // 鍚屾閿婚€犳暟鎹?(淇濇寔鍘熸牱)
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
                        player.sendMessage("§a[经验系统] §7已按新版经验曲线折算你的等级与经验。")
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

        // 1. 閲嶇疆鍩虹灞炴€?(淇濇寔鍘熸牱)
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

        // 鈽呫€愭柊澧炪€戦噸缃€诲垎 鍜?娓呯┖鏄庣粏鍒楄〃 (蹇呴』鍔犺繖鍙ワ紒)
        data.rarityDetails.clear()

        // 2. 鑾峰彇鍚勬ā鍧楀姞鎴?(淇濇寔鍘熸湁鐨?Map 璁＄畻閫昏緫)
        val bonuses: MutableMap<String, Double> = HashMap()

        val weaponStats = weaponManager.calculateWeaponStats(player, data)
        weaponStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        val baihuWeaponStats = plugin.baihuDzManager.calculateWeaponStats(player, data)
        baihuWeaponStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        val armorStats = armorManager.calculateArmorStats(player, data)
        armorStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        // 銆愭柊澧炪€戜豢鐓ф姢鐢诧紝灏嗛グ鍝佺涓€鏍肩殑缁撴櫠灞炴€т篃璁＄畻杩涘幓
        val crystalStats = crystalManager.calculateCrystalStats(player, data)
        crystalStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        val baihuArtifactStats = plugin.baihuDzManager.calculateArtifactStats(player, data)
        baihuArtifactStats.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }

        // 鈽呪槄鈽?(C) 銆愭柊澧炪€戞妧鑳?Buff 涓存椂鍔犳垚 鈽呪槄鈽?
        // 杩欎竴姝ヨ鎶€鑳藉彲浠ョ洿鎺ュ奖鍝嶆渶缁堥潰鏉匡紝鑰屼笉闇€瑕佹敼鍐?PlayerData 鐨勫叿浣撳瓧娈?
        data.tempBonuses.forEach { (k, v) ->
            bonuses.merge(k, v) { a, b -> a + b }
        }
        JuliWan.applyStatBonuses(bonuses, data)

        // 鈽?鍦ㄨ繖閲屽姞涓婅繖娈典唬鐮侊細浠?Map 涓彁鍙栨€荤█鏈夊害骞朵繚瀛?
        if (bonuses.containsKey("total_rarity")) {
            data.totalRarity = bonuses["total_rarity"]!!.toInt()
        }

        // --- 鐢熷懡鍊?---
        var extraHealth = bonuses.getOrDefault("max_health", 0.0)
        data.maxHealth += extraHealth
        // 澶勭悊鐢熷懡鐧惧垎姣?
        if (bonuses.containsKey("max_health_percent")) {
            val percent = bonuses["max_health_percent"]!!
            data.maxHealth *= (1.0 + percent)
        }
        if (bonuses.containsKey("baihu_miasma_max_health_percent")) {
            val percent = bonuses["baihu_miasma_max_health_percent"]!!
            data.maxHealth *= (1.0 + percent)
        }

        // --- 鏀诲嚮鍔?---
        var baseAttack = bonuses.getOrDefault("attack", 0.0)
        // 澶勭悊鏀诲嚮鍔涚櫨鍒嗘瘮
        if (bonuses.containsKey("attack_percent")) {
            // 鐩墠閫昏緫锛氳澶囩粰鐨勬敾鍑诲姏 * (1 + 鐧惧垎姣?
            baseAttack *= (1.0 + bonuses["attack_percent"]!!)
        }
        data.attack += baseAttack

        // --- 绠煝寮哄害 ---
        var baseArcher = bonuses.getOrDefault("archer_damage", 0.0)
        if (bonuses.containsKey("archer_damage_percent")) {
            baseArcher *= (1.0 + bonuses["archer_damage_percent"]!!)
        }
        data.archerDamage += baseArcher

        // --- 闃垫硶寮哄害 ---
        var baseZfStr = bonuses.getOrDefault("zf_str", 0.0)
        if (bonuses.containsKey("zf_str_percent")) {
            baseZfStr *= (1.0 + bonuses["zf_str_percent"]!!)
        }
        data.zfStr += baseZfStr


        // --- 鎶ょ敳 ---
        data.armor += bonuses.getOrDefault("armor", 0.0)
        // 澶勭悊鎶ょ敳鐧惧垎姣?(闈掗摐鍓?
        if (bonuses.containsKey("armor_percent")) {
            data.armor *= (1.0 + bonuses["armor_percent"]!!)
        }
        if (bonuses.containsKey("baihu_miasma_armor_percent")) {
            data.armor *= (1.0 + bonuses["baihu_miasma_armor_percent"]!!)
        }

        data.knockBackRes += bonuses.getOrDefault("knock_back_res", 0.0)
        data.critChance += bonuses.getOrDefault("crit_chance", 0.0)
        data.coolReduce += bonuses.getOrDefault("cool_reduce", 0.0)

        // === 鐏靛姏璁＄畻閫昏緫 (淇濇寔鍘熸牱) ===
        // 鍏紡锛?0 + (绛夌骇 * 3)
        var baseLingli = 50.0 + (data.lv * 3.0)

        // 闄愬埗鏈€楂?300 鐐?(閽堝鍩虹鎴愰暱)
        if (baseLingli > 300.0) {
            baseLingli = 300.0
        }

        // === 銆愭牳蹇冧慨鏀广€戜繚瀛樺喎鍗寸缉鍑?(闄愬埗鏈€楂?50%) ===
        var coolReduce = data.coolReduce
        if (coolReduce > 0.5) coolReduce = 0.5
        data.coolReduce = coolReduce


        // 鑾峰彇瑁呭鎻愪緵鐨勯澶栫伒鍔?
        val equipLingli = bonuses.getOrDefault("lingli", 0.0)
        data.extraLingli = equipLingli

        // 璁剧疆鎬荤伒鍔涗笂闄?(鍩虹 + 瑁呭)
        data.maxLingli = baseLingli + equipLingli

        // 濡傛灉褰撳墠鐏靛姏瓒呰繃浜嗕笂闄愶紝鍒欎慨姝ｄ负涓婇檺
        if (data.lingli > data.maxLingli) {
            data.lingli = data.maxLingli
        }
        // ==============================

        if (bonuses.containsKey("speed")) {
            data.speed += bonuses["speed"]!!
        }

        // === 銆愭柊澧炪€戦€熷害鐧惧垎姣旈€昏緫 ===
        // 鍦?weapons.yml 閲屽啓 speed_percent: 0.5 浠ｈ〃澧炲姞 50% 绉婚€?
        // --- 绉婚€?---
        if (bonuses.containsKey("speed")) {
            data.speed += bonuses["speed"]!!
        }
        // 澶勭悊绉婚€熺櫨鍒嗘瘮
        if (bonuses.containsKey("speed_percent")) {
            // speed_percent 涓鸿礋鏁版椂鍗充负鍑忛€?            data.speed *= (1.0 + bonuses["speed_percent"]!!)
        }
        if (bonuses.containsKey("baihu_miasma_speed_percent")) {
            data.speed *= (1.0 + bonuses["baihu_miasma_speed_percent"]!!)
        }

        syncToVanilla(player, data)
    }

    private fun syncToVanilla(player: Player, data: PlayerData) {
        // (淇濇寔鍘熸湁鐨勫睘鎬у悓姝?
        // 1.21.3 閫傞厤锛欰ttribute 鏋氫妇鍘婚櫎浜?GENERIC_ 鍓嶇紑
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

        // === 銆愭柊澧炪€戝悓姝ョ瓑绾у拰缁忛獙鏉″埌鍘熺増鐣岄潰 ===
        player.level = data.lv!!

        val currentExp = data.exp
        val maxExp = getMaxExpRequired(data.lv!!)
        // 璁＄畻鐧惧垎姣?0.0 - 1.0
        var progress = 0.0f
        if (maxExp > 0) {
            progress = currentExp.toFloat() / maxExp.toFloat()
        }
        // 闄愬埗杩涘害鏉¤寖鍥达紝闃叉瀹㈡埛绔樉绀洪鐣?
        progress = min(0.999f, max(0.0f, progress))
        player.exp = progress
    }

    /**
     * 銆愭柊澧炪€戣幏鍙栫帺瀹舵暟鎹璞?
     * 渚涘閮ㄧ郴缁燂紙濡傚紑鐗╂湳銆佽彍鍗曠瓑锛夎皟鐢?
     */
    fun getPlayerData(player: Player?): PlayerData? {
        if (player == null) return null
        return dataCache[player.uniqueId]
    }
}

