package com.hjh_database.teleport

import com.hjh_database.Hjh_database
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
            val jobs = config.getIntegerList("$path.conditions.job.values")
            val jobMsg = config.getString("$path.conditions.job.message")

            val minLv = config.getInt("$path.conditions.min_level", -1)
            val lvMsg = config.getString("$path.conditions.level_message")

            val statusList = config.getIntegerList("$path.conditions.status.values")
            val statusMsg = config.getString("$path.conditions.status.message")

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
            if (handleCustomAction(player, data, point.customAction)) {
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

    /**
     * ★★★ 在这里写你的个性化硬编码逻辑 ★★★
     * @param actionStr 配置文件里 actions.custom_action 的值
     * @return 如果修改了玩家数据需要保存，返回 true；否则返回 false
     */
    private fun handleCustomAction(player: Player, data: com.hjh_database.data.PlayerData, actionStr: String): Boolean {
        when (actionStr) {
            // === 新增：选择人族-前往新手前置 ===
            "CHOOSE_HUMAN_START" -> {
                // 1. 处理队伍 (关闭友伤)
                val board = Bukkit.getScoreboardManager().mainScoreboard
                var team = board.getTeam("player")
                if (team == null) {
                    team = board.registerNewTeam("player")
                    team.setAllowFriendlyFire(false) // 关闭队友伤害
                    team.setCanSeeFriendlyInvisibles(true)
                    team.color = org.bukkit.ChatColor.WHITE
                }
                if (!team.hasEntry(player.name)) {
                    team.addEntry(player.name)
                }
                // 2. 修改状态 -> 1
                data.updateStatus(1) // 这会自动更新 description
                // 3. 修改种族 -> 2 (人族)
                data.race = 2
                // 4. 恢复满状态 (瞬间治疗 + 饱和)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 5. 传送逻辑已经由 tryTeleport 方法根据 yml 配置执行了，这里不需要重复写 tp 代码
                return true // 返回 true 表示修改了 data (race/status)，需要保存数据库
            }
            // === 新增：根据种族传送 ===
            "TELEPORT_BY_RACE" -> {
                // 获取当前世界（假设就在当前世界传送，如果跨世界请指定 world 名字）
                val world = player.world
                // 获取玩家当前种族，如果未设置(null)则默认为 0
                val raceId = data.race ?: 0
                // 根据种族决定 X 坐标
                val targetX = when (raceId) {
                    0 -> 1200.5 // 神
                    1 -> 1296.5 // 仙
                    2 -> 1392.5 // 人
                    3 -> 1344.5 // 战神
                    4 -> 1248.5 // 妖
                    else -> 1200.5 // 默认/未知种族去神族点
                }
                // 固定的 Y, Z 和 Yaw (-90)
                val targetY = 43.0
                val targetZ = 647.5 // 加 .5 居中
                val targetYaw = -90f
                // Pitch 使用 player.location.pitch (即 ~ )
                val targetPitch = player.location.pitch
                // 构建位置并传送
                val targetLoc = org.bukkit.Location(world, targetX, targetY, targetZ, targetYaw, targetPitch)
                player.teleport(targetLoc)
                // 播放个音效提示传送成功
                player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                // 这里只是传送，没有修改 data 数据，所以返回 false (不需要存库)
                return false
            }
            // === 新增：反悔-重选种族 ===
            "RESET_RACE" -> {
                // 1. 将种族重置为空
                data.race = null
                // 2. 将状态重置为 0
                data.updateStatus(0)
                // 3. 给点提示
                player.sendMessage("§7你放弃了之前的选择...")
                player.playSound(player.location, org.bukkit.Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
                // 4. 返回 true，表示数据发生了变动，需要保存数据库
                return true
            }
            // === 新增：确认进入盘古大陆 ===
            "ENTER_PANGU" -> {
                // 获取世界，假设都在主世界 "world"，如果不是请修改 world 的名字
                // 如果你想让它就在玩家当前所在的世界传送，用 player.world 即可
                val world = Bukkit.getWorld("world") ?: player.world
                val raceId = data.race ?: 0
                // 1. 根据种族获取目标坐标
                // 注意：坐标加了 .5 以防卡墙，角度按你要求的填
                val targetLoc = when (raceId) {
                    0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                    1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                    2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                    3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                    4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                    else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 默认去种族0
                }
                // 执行传送
                player.teleport(targetLoc)
                // 2. 修改 status -> 2
                data.updateStatus(2)
                // 3. 补满状态 (瞬间治疗 + 饱和)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 4. 发送提示消息
                player.sendMessage("§6恭喜正式进入盘古大陆。请与新手引导员进行交流，接取任务吧！")
                return true // 需要保存数据
            }
        }


        return false
    }

    data class TeleportPoint(
        val id: String,
        val location: Location,
        // Conditions
        val allowedJobs: List<Int>, val jobFailMsg: String?,
        val minLevel: Int, val levelFailMsg: String?,
        val allowedStatus: List<Int>, val statusFailMsg: String?,
        // Actions
        val rewriteStatus: Int?,
        val setJob: Int?,
        val setRace: Int?,
        val customAction: String?, // 硬编码Key
        val successMsg: String?
    )
}