package com.hjh_database.teleport

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestStatus
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class TeleportManager(private val plugin: Hjh_database) {

    private val configFile = File(plugin.dataFolder, "teleports.yml")
    private val blockDataFile = File(plugin.dataFolder, "teleport_blocks.yml")

    val points = HashMap<String, TeleportPoint>()
    private val blockMap = HashMap<String, String>()
    private val actionHandler = TeleportActionHandler(plugin)

    init {
        loadConfig()
        loadBlockData()
    }

    fun reload() {
        loadConfig()
        loadBlockData()
    }

    // === 1. 加载配置 ===
    private fun loadConfig() {
        if (!configFile.exists()) plugin.saveResource("teleports.yml", false)

        // 强制 UTF-8 读取，防止中文乱码
        val config = YamlConfiguration()
        try {
            config.load(InputStreamReader(java.io.FileInputStream(configFile), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            plugin.logger.severe("加载 teleports.yml 失败: ${e.message}")
            return
        }

        points.clear()

        val section = config.getConfigurationSection("points") ?: return
        for (key in section.getKeys(false)) {
            val path = "points.$key"

            // --- 基础坐标 ---
            val worldName = config.getString("$path.world") ?: "world"
            val loc = Location(
                Bukkit.getWorld(worldName),
                config.getDouble("$path.x"),
                config.getDouble("$path.y"),
                config.getDouble("$path.z"),
                config.getDouble("$path.yaw").toFloat(),
                config.getDouble("$path.pitch").toFloat()
            )

            // --- 限制条件 ---
            val jobs = getIntegerValues(config, "$path.conditions.job.values")
            val jobMsg = config.getString("$path.conditions.job.message")

            val minLv = config.getInt("$path.conditions.min_level", -1)
            val lvMsg = config.getString("$path.conditions.level_message")

            val statusList = getIntegerValues(config, "$path.conditions.status.values")
            val statusMsg = config.getString("$path.conditions.status.message")

            val raceList = getIntegerValues(config, "$path.conditions.race.values")
            val raceMsg = config.getString("$path.conditions.race.message")

            val requiredCompletedQuestId = config.getString("$path.conditions.completed_quest.id")
            val completedQuestMsg = config.getString("$path.conditions.completed_quest.message")

            // --- 动作 (传送成功后执行) ---
            val rewriteStatus = if (config.contains("$path.actions.rewrite_status")) config.getInt("$path.actions.rewrite_status") else null
            val setJob = if (config.contains("$path.actions.set_job")) config.getInt("$path.actions.set_job") else null
            val setRace = if (config.contains("$path.actions.set_race")) config.getInt("$path.actions.set_race") else null

            // ★★★ 硬编码标识 ★★★
            // 如果填了这个字符串，就会去代码里找对应的逻辑
            val customAction = config.getString("$path.actions.custom_action")

            val successMsg = config.getString("$path.actions.message")

            points[key] = TeleportPoint(
                id = key,
                location = loc,
                allowedJobs = jobs, jobFailMsg = jobMsg,
                minLevel = minLv, levelFailMsg = lvMsg,
                allowedStatus = statusList, statusFailMsg = statusMsg,
                allowedRaces = raceList, raceFailMsg = raceMsg,
                requiredCompletedQuestId = requiredCompletedQuestId, completedQuestFailMsg = completedQuestMsg,
                // Actions
                rewriteStatus = rewriteStatus,
                setJob = setJob,
                setRace = setRace,
                customAction = customAction,
                successMsg = successMsg
            )
        }
        plugin.logger.info("已加载 ${points.size} 个传送点配置。")
    }

    private fun getIntegerValues(config: YamlConfiguration, path: String): List<Int> {
        if (!config.contains(path)) return emptyList()

        val raw = config.get(path)
        return when (raw) {
            is Int -> listOf(raw)
            is Number -> listOf(raw.toInt())
            is List<*> -> raw.mapNotNull {
                when (it) {
                    is Int -> it
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            }
            is String -> raw.toIntOrNull()?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    // === 2. 方块数据管理 (保持不变) ===
    private fun loadBlockData() {
        if (!blockDataFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(blockDataFile)
        blockMap.clear()
        for (key in config.getKeys(false)) {
            val pointId = config.getString(key)
            if (pointId != null) blockMap[key] = pointId
        }
    }

    fun saveBlockData() {
        val config = YamlConfiguration()
        for ((locStr, pointId) in blockMap) {
            config.set(locStr, pointId)
        }
        config.save(blockDataFile)
    }

    fun addBlock(loc: Location, pointId: String) {
        blockMap[locToString(loc)] = pointId
        saveBlockData()
    }

    fun removeBlock(loc: Location) {
        blockMap.remove(locToString(loc))?.let { saveBlockData() }
    }

    fun getPointIdByBlock(loc: Location): String? = blockMap[locToString(loc)]

    private fun locToString(loc: Location): String = "${loc.world?.name},${loc.blockX},${loc.blockY},${loc.blockZ}"

    // === 3. 核心传送逻辑 ===
    fun tryTeleport(player: Player, pointId: String) {
        val point = points[pointId]
        if (point == null) {
            player.sendMessage("§c[错误] 未知的传送点ID: $pointId")
            return
        }

        val data = plugin.playerManager.getPlayerData(player) ?: return

        // --- 条件检查 ---
        if (point.allowedJobs.isNotEmpty() && data.job !in point.allowedJobs) {
            player.sendMessage(point.jobFailMsg?.replace("&", "§") ?: "§c职业不符。")
            return
        }
        if (point.minLevel > 0 && data.lv < point.minLevel) {
            player.sendMessage(point.levelFailMsg?.replace("&", "§") ?: "§c等级不足。")
            return
        }
        if (point.allowedStatus.isNotEmpty() && data.status !in point.allowedStatus) {
            player.sendMessage(point.statusFailMsg?.replace("&", "§") ?: "§c当前剧情状态不可传送。")
            return
        }
        if (point.allowedRaces.isNotEmpty() && data.race !in point.allowedRaces) {
            player.sendMessage(point.raceFailMsg?.replace("&", "§") ?: "§c种族不符。")
            return
        }
        if (point.requiredCompletedQuestId != null &&
            data.questStatuses[point.requiredCompletedQuestId] != QuestStatus.COMPLETED
        ) {
            player.sendMessage(point.completedQuestFailMsg?.replace("&", "§") ?: "§c请先完成前置任务。")
            return
        }

        // ★★★ 新增：前置硬编码条件检查，不满足直接 return 拦截传送 ★★★
        if (point.customAction != null) {
            if (!actionHandler.checkConditions(player, data, point.customAction)) {
                return // 条件不满足，安全终止
            }
        }

        // --- 执行传送 ---
        player.teleport(point.location)
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)

        if (point.successMsg != null) {
            player.sendMessage(point.successMsg.replace("&", "§"))
        }

        // --- 数据变更 ---
        var needSave = false

        // 1. 改状态
        if (point.rewriteStatus != null) {
            data.updateStatus(point.rewriteStatus) // 这个方法会自动更新 statusDescription
            needSave = true
        }
        // 2. 改职业
        if (point.setJob != null) {
            data.job = point.setJob
            needSave = true
        }
        // 3. 改种族
        if (point.setRace != null) {
            data.race = point.setRace
            needSave = true
        }

        // 4. ★★★ 硬编码特殊逻辑 ★★★
        if (point.customAction != null) {
            if (actionHandler.handle(player, data, point.customAction)) {
                needSave = true
            }
        }

        // --- 统一保存 ---
        if (needSave) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                // ★★★ 修复点：分开调用，防止死锁 ★★★
                // 1. 先保存基础数据 (savePlayer 内部自己会获取和关闭连接)
                try {
                    plugin.databaseManager.savePlayer(data)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // 2. 再保存状态数据 (使用 use 获取连接)
                try {
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        plugin.databaseManager.savePlayerStatus(conn, data)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
        }
    }

    data class TeleportPoint(
        val id: String,
        val location: Location,
        // Conditions
        val allowedJobs: List<Int>, val jobFailMsg: String?,
        val minLevel: Int, val levelFailMsg: String?,
        val allowedStatus: List<Int>, val statusFailMsg: String?,
        val allowedRaces: List<Int>, val raceFailMsg: String?,
        val requiredCompletedQuestId: String?, val completedQuestFailMsg: String?,
        // Actions
        val rewriteStatus: Int?,
        val setJob: Int?,
        val setRace: Int?,
        val customAction: String?, // 硬编码Key
        val successMsg: String?
    )
}

