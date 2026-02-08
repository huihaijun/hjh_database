package com.hjh_database.data

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.weapon.ArmorManager
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

    // 保持原有变量名的访问性
    val weaponManager: WeaponManager
    val armorManager: ArmorManager

    // 【新增】等级配置文件对象
    private var levelsFile: File? = null
    private var levelsConfig: YamlConfiguration? = null

    init {
        this.weaponManager = WeaponManager(plugin)
        this.armorManager = ArmorManager(plugin)
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
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                plugin.databaseManager.savePlayer(data)
            })
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
                dataCache[player.uniqueId] = data

                // 同步锻造数据 (保持原样)
                val dzData = DzPlayerData(player.uniqueId, player.name)
                dzData.forgeLevel = data.forgeLevel!!
                dzData.forgeExp = data.forgeExp!!
                dzData.forgeLicense = data.forgeLicense!!
                dzDataCache[player.uniqueId] = dzData

                val finalData = data
                plugin.server.scheduler.runTask(plugin, Runnable {
                    updateStats(player)
                    syncToVanilla(player, finalData)
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

        // ★ 在这里加上这段代码：从 Map 中提取总稀有度并保存
        if (bonuses.containsKey("total_rarity")) {
            data.totalRarity = bonuses["total_rarity"]!!.toInt()
        }

        // 3. 应用加成 (保持原样)
        data.maxHealth += bonuses.getOrDefault("max_health", 0.0)
        data.attack += bonuses.getOrDefault("attack", 0.0)
        data.archerDamage += bonuses.getOrDefault("archer_damage", 0.0)
        data.zfStr += bonuses.getOrDefault("zf_str", 0.0)
        data.armor += bonuses.getOrDefault("armor", 0.0)
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

        if (bonuses.containsKey("attack_percent")) {
            val multi = 1.0 + bonuses["attack_percent"]!!
            data.attack *= multi
        }
        if (bonuses.containsKey("armor_percent")) {
            val multi = 1.0 + bonuses["armor_percent"]!!
            data.armor *= multi
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