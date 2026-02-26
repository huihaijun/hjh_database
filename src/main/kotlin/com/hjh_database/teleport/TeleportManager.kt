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

        // ★★★ 新增：前置硬编码条件检查，不满足直接 return 拦截传送 ★★★
        if (point.customAction != null) {
            if (!checkCustomActionConditions(player, data, point.customAction)) {
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
     * 前置检查自定义动作的条件
     * @return true 允许传送，false 拦截传送
     */
    private fun checkCustomActionConditions(player: Player, data: com.hjh_database.data.PlayerData, actionStr: String): Boolean {
        when (actionStr) {
            "JOB_TRIAL_WARRIOR" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_ARCHER" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_MAGIC" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_ALCHEMY" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }
                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            // === 新增：重生石前置检查 ===
            "RELIVE_STONE_TO_CITY", "RELIVE_STONE_TO_RACE" -> {
                val itemInHand = player.inventory.itemInMainHand
                // 从 ResourceManager 获取正确的重生石模板
                val rm = Hjh_database.instance.resourceManager
                val reliveStone = rm.getItem("relive_stone")
                // 如果没拿东西或者配置获取失败
                if (itemInHand.type == org.bukkit.Material.AIR || !itemInHand.hasItemMeta()) {
                    player.sendMessage("§c请手持重生石重生！")
                    return false
                }
                // 比对材质和名字判定是否为重生石
                if (reliveStone != null) {
                    val handMeta = itemInHand.itemMeta
                    val stoneMeta = reliveStone.itemMeta
                    if (itemInHand.type != reliveStone.type || handMeta?.displayName != stoneMeta?.displayName) {
                        player.sendMessage("§c请手持重生石重生！")
                        return false
                    }
                } else {
                    player.sendMessage("§c[错误] 缺失重生石配置，请联系管理员！")
                    return false
                }
                return true // 检查通过，允许传送
            }
        }
        return true
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
                // ★★★ 新增：自动接取人族主线第一个任务 ★★★
                if (raceId == 2) {  // 只给人族接取
                    plugin.questManager.acceptQuest(player, "main_ren_1")
                }
                // 4. 发送提示消息
                player.sendMessage("§6恭喜正式进入盘古大陆。请与新手引导员进行交流，接取任务吧！")
                return true // 需要保存数据
            }
            // === 职业体验-战士 ===
            "JOB_TRIAL_WARRIOR" -> {
                // 此时能运行到这里，说明 checkCustomActionConditions 已经通过了
                // 且玩家已经被 tryTeleport 方法准确传送到了 yml 填写的坐标
                // 设置职业为 1，状态为 4
                data.job = 0
                data.updateStatus(4)
                player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
                return true // 返回 true 以保存数据库
            }

            "JOB_TRIAL_ARCHER" -> {
                // 3. 传送与数据更新
                // 设置职业为 1，状态为 4
                data.job = 1
                data.updateStatus(4)
                return true // 返回 true 以保存数据
            }
            "JOB_TRIAL_MAGIC" -> {
                // 3. 传送与数据更新
                data.job = 2
                data.updateStatus(4)
                return true // 返回 true 以保存数据
            }
            "JOB_TRIAL_ALCHEMY" -> {
                // 3. 传送与数据更新
                // 设置职业为 1，状态为 4
                data.job = 3
                data.updateStatus(4)
                return true // 返回 true 以保存数据
            }
            // === 新增：全职业暂离-体验其他职业 ===
            "LEAVE_JOB_TRIAL_WARRIOR",
            "LEAVE_JOB_TRIAL_ARCHER",
            "LEAVE_JOB_TRIAL_MAGIC",
            "LEAVE_JOB_TRIAL_DOCTOR" -> {
                // 1. 清空玩家背包和装备栏
                player.inventory.clear()
                player.inventory.armorContents = arrayOfNulls(4)
                // 2. 将玩家 status 设置为 3
                data.updateStatus(3)
                // 3. 将玩家 job 设置为 null
                data.job = null
                // 清空该玩家所有的医术记忆
                data.clearMedicalSkills()
                // 异步保存医术数据（如果你的转职逻辑最后有统一的 savePlayer(data)，这行也可以省略，但加上最保险）
                java.util.concurrent.CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }
                // 4. 将玩家体力和饱和恢复满 (瞬间治疗 + 饱和度，255级)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 5. 根据具体的 actionStr 动态获取职业名称并发送消息
                val jobName = when (actionStr) {
                    "LEAVE_JOB_TRIAL_WARRIOR" -> "战士"
                    "LEAVE_JOB_TRIAL_ARCHER" -> "弓箭手"
                    "LEAVE_JOB_TRIAL_MAGIC" -> "术士"
                    "LEAVE_JOB_TRIAL_DOCTOR" -> "医师"
                    else -> "未知"
                }
                player.sendMessage("§7你暂时离开了${jobName}职业体验……")
                // 返回 true 表示数据已发生变动，需要保存数据库
                return true
            }
            // === 新增：决定成为职业 (全职业合并处理) ===
            "CHOOSE_JOB_WARRIOR",
            "CHOOSE_JOB_ARCHER",
            "CHOOSE_JOB_MAGIC",
            "CHOOSE_JOB_DOCTOR" -> {
                // 1 & 2. 清空背包和装备栏
                player.inventory.clear()
                player.inventory.armorContents = arrayOfNulls(4)
                // 3. 将玩家状态设为 3
                data.updateStatus(3)
                // 5. 恢复满状态 (瞬间治疗 + 饱和度)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 6. 播放升级音效，降低音量 (0.6f) 避免太吵
                player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1f)
                // 获取你的资源管理器 (根据你的之前代码，通常是这样获取的，如果报错请改为 plugin.resourceManager)
                val rm = Hjh_database.instance.resourceManager
                // 用来收集溢出背包的物品
                val leftovers = mutableMapOf<Int, org.bukkit.inventory.ItemStack>()
                // ★★★ 新增：全职业通用防具 (初心套) ★★★
                val helmet = rm.getItem("chuxinzhepimao") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_HELMET).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhepimao") }
                }
                val chestplate = rm.getItem("chuxinzhehujia") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_CHESTPLATE).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhehujia") }
                }
                val leggings = rm.getItem("chuxinzhehutui") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_LEGGINGS).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhehutui") }
                }
                val boots = rm.getItem("chuxinzhepixue") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_BOOTS).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhepixue") }
                }

                // 将防具发放给玩家
                leftovers.putAll(player.inventory.addItem(helmet, chestplate, leggings, boots))
                // ★★★ ============================ ★★★
                // 4 & 7 & 8 & 9. 分支处理具体职业的 job、消息与物品
                when (actionStr) {
                    "CHOOSE_JOB_WARRIOR" -> {
                        data.job = 0
                        player.sendMessage("§6恭喜你成功加入职业——【战士】！")
                        player.sendMessage("§e[顾镇岳]: §f好小子，果然没看错你！这些就都给你了！")
                        val weapon = rm.getItem("taomujian") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.WOODEN_SWORD).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] taomujian") }
                        }
                        // 手搓包子
                        val food = org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 15).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
                        }
                        leftovers.putAll(player.inventory.addItem(weapon, food))
                    }
                    "CHOOSE_JOB_ARCHER" -> {
                        data.job = 1
                        player.sendMessage("§6恭喜你成功加入职业——【弓箭手】！")
                        player.sendMessage("§e[余步云]: §f不错，很有风度，拿好这把弓！")
                        val weapon = rm.getItem("tengmugong") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.BOW).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] tengmugong") }
                        }
                        // 手搓原版箭
                        val arrows = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW, 64)
                        leftovers.putAll(player.inventory.addItem(weapon, arrows))
                    }
                    "CHOOSE_JOB_MAGIC" -> {
                        data.job = 2
                        player.sendMessage("§6恭喜你成功加入职业——【术士】！")
                        player.sendMessage("§e[秦观星]: §f果然符合我们术士的审美，这炉子和元素你且拿好~")
                        val weapon = rm.getItem("xuetulu") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.CARROT_ON_A_STICK).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] xuetulu") }
                        }
                        // 批量生成5种元素
                        val elements = listOf("metal", "wood", "fire", "water", "earth").map { id ->
                            val item = rm.getItem(id) ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_NUGGET).apply {
                                itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] $id") }
                            }
                            item.amount = 8
                            item
                        }
                        // 武器和元素一起塞入背包
                        leftovers.putAll(player.inventory.addItem(weapon, *elements.toTypedArray()))
                    }
                    "CHOOSE_JOB_DOCTOR" -> {
                        data.job = 3
                        // 清空该玩家所有的医术记忆
                        data.clearMedicalSkills()
                        // 异步保存医术数据（如果你的转职逻辑最后有统一的 savePlayer(data)，这行也可以省略，但加上最保险）
                        java.util.concurrent.CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }
                        player.sendMessage("§6恭喜你成功加入职业——【医师】！")
                        player.sendMessage("§e[韩济世]: §f老夫后继有人了！这初阶的医术和医旗就交付给你了，拿去绘制台自己绘制吧！")
                        // 1. 给素布旗 (原有逻辑)
                        val weapon = rm.getItem("subuqi") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.WHITE_BANNER).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] subuqi") }
                        }
                        weapon.amount = 5
                        leftovers.putAll(player.inventory.addItem(weapon))
                        // 2. 发放医术：愈合花
                        // 注意：这里的 plugin 是你的主类实例，请根据你当前类的实际情况替换 (比如 plugin.medicalManager)
                        val yuhehua = plugin.medicalManager.getSkillBook("yuhehua")
                        if (yuhehua != null) {
                            // 默认 getSkillBook 出来的 amount 就是 1，直接给玩家即可
                            leftovers.putAll(player.inventory.addItem(yuhehua))
                        } else {
                            player.sendMessage("§c[配置缺失] 找不到医术：yuhehua")
                        }
                        // 3. 发放医术：退敌
                        val tuidi = plugin.medicalManager.getSkillBook("tuidi")
                        if (tuidi != null) {
                            leftovers.putAll(player.inventory.addItem(tuidi))
                        } else {
                            player.sendMessage("§c[配置缺失] 找不到医术：tuidi")
                        }
                    }
                }

                // 处理背包满的情况，把塞不下的掉在玩家脚下
                if (leftovers.isNotEmpty()) {
                    player.sendMessage("§c[提示] 背包已满，部分物品掉落在脚下！")
                    for (item in leftovers.values) {
                        player.world.dropItem(player.location, item)
                    }
                }
                // 返回 true，保存写入的 job 和 status 数据
                return true
            }
            // === 新增：奈何桥——重生石回皇城 ===
            "RELIVE_STONE_TO_CITY" -> {
                // 1. 扣除主手1个重生石
                val itemInHand = player.inventory.itemInMainHand
                itemInHand.amount = itemInHand.amount - 1

                // 2. 设置状态为 3
                data.updateStatus(3)

                // 第一种情况的传送已经由 tryTeleport 通过 yml 中的 179.58 坐标自动完成了，无需在代码写传送
                return true
            }

            // === 新增：奈何桥——重生石回种族 ===
            "RELIVE_STONE_TO_RACE" -> {
                // 1. 扣除主手1个重生石
                val itemInHand = player.inventory.itemInMainHand
                itemInHand.amount = itemInHand.amount - 1
                // 2. 设置状态为 3
                data.updateStatus(3)
                // 3. 执行种族动态传送 (覆盖 yml 的空坐标)
                val world = player.world
                val raceId = data.race ?: 0
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
                return true
            }

            // === 新增：奈何桥——无重生石打回种族 ===
            "NO_STONE_TO_RACE" -> {
                // 1. 设置状态为 3
                data.updateStatus(3)
                // 2. 执行种族动态传送 (覆盖 yml 的空坐标)
                val world = player.world
                val raceId = data.race ?: 0
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
                // 3. 扣除 20% 的当前经验
                val currentExp = data.exp
                val deductExp = (currentExp * 0.2).toInt()
                data.exp = (currentExp - deductExp).coerceAtLeast(0) // 防止变负数
                // 为了让经验条立即在原版 UI 刷新，可以给个 0 经验触动一下 PlayerManager 的经验刷新逻辑
                plugin.playerManager.giveExp(player, 0)
                // 4. 给予持续 1 分钟的缓慢 2 (1分钟 = 1200 Tick，缓慢2 = amplifier 为 1)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 1200, 1, false, false))
                return true
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