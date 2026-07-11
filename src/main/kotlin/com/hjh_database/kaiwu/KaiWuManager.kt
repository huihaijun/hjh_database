package com.hjh_database.kaiwu

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.race.impl.YaoRace
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

class KaiWuManager(private val plugin: Hjh_database) {
    private val nodesFile: File = File(plugin.dataFolder, "nodes.yml")
    private val configFile: File = File(plugin.dataFolder, "kaiwu.yml")
    private lateinit var nodesConfig: YamlConfiguration
    private lateinit var config: YamlConfiguration

    // --- 核心缓存 ---
    private val nodeCache: MutableMap<String, NodeConfig> = ConcurrentHashMap()
    private val chunkNodeMap: MutableMap<String, MutableList<NodeConfig>> = ConcurrentHashMap()

    // 运行时数据
    private val miningTasks: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val miningBars: MutableMap<UUID, BossBar> = ConcurrentHashMap()
    private val miningStartLoc: MutableMap<UUID, Location> = ConcurrentHashMap()
    private val inspectionTasks: MutableMap<UUID, Int> = ConcurrentHashMap()
    val deleteConfirmations: MutableMap<UUID, String> = ConcurrentHashMap()

    // 状态后缀
    private val SUFFIX_DEPLETED = "_depleted"   // 枯竭状态
    private val SUFFIX_RECOVERING = "_recover"  // 恢复状态

    // 粒子颜色缓存
    private var dustDepleted: Particle.DustOptions? = null
    private var dustRecovering: Particle.DustOptions? = null
    private val energyRecoveryKey = "__kaiwu_energy_recovery_until"

    init {
        loadConfig()
        loadNodes()
        startRegenTask()
        startVisualTask() // 启动粒子线程
    }

    fun loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("kaiwu.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(configFile)
        dustDepleted = parseColor(config.getString("visual.colors.depleted", "255,170,0")!!, 1.2f)
        dustRecovering = parseColor(config.getString("visual.colors.recovering", "128,128,128")!!, 1.0f)
    }

    private fun parseColor(rgb: String, size: Float): Particle.DustOptions {
        return try {
            val parts = rgb.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            Particle.DustOptions(
                Color.fromRGB(
                    parts[0].trim().toInt(),
                    parts[1].trim().toInt(),
                    parts[2].trim().toInt()
                ), size
            )
        } catch (e: Exception) {
            Particle.DustOptions(Color.GRAY, size)
        }
    }

    fun loadNodes() {
        if (!nodesFile.exists()) {
            try {
                nodesFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        nodesConfig = YamlConfiguration.loadConfiguration(nodesFile)

        nodeCache.clear()
        chunkNodeMap.clear()

        for (key in nodesConfig.getKeys(false)) {
            val sec = nodesConfig.getConfigurationSection(key) ?: continue

            val node = NodeConfig()
            val list = sec.getList("drops")
            node.drops = ArrayList()
            if (list != null) {
                for (o in list) {
                    if (o is ItemStack) node.drops.add(o)
                }
            }
            node.timeSeconds = sec.getDouble("time", 2.0)
            node.energyCost = sec.getDouble("energy", 5.0)
            node.exp = sec.getInt("exp", 10)
            node.reqLevel = sec.getInt("req_level", 1)
            node.cooldownSec = sec.getInt("cooldown", 60)

            val globalDepleted = config.getInt("mining.depleted_duration", 300)
            node.depletedSec = sec.getInt("depleted", globalDepleted)

            val parts = key.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size == 4) {
                try {
                    node.worldName = parts[0]
                    node.x = parts[1].toDouble()
                    node.y = parts[2].toDouble()
                    node.z = parts[3].toDouble()

                    val world = Bukkit.getWorld(node.worldName!!)
                    if (world != null) {
                        node.cachedLoc = Location(world, node.x, node.y, node.z)
                        nodeCache[key] = node

                        val chunkX = node.x.toInt() shr 4
                        val chunkZ = node.z.toInt() shr 4
                        val chunkKey = "${node.worldName},$chunkX,$chunkZ"

                        chunkNodeMap.computeIfAbsent(chunkKey) {
                            Collections.synchronizedList(ArrayList())
                        }.add(node)
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("资源点坐标解析失败: $key")
                }
            }
        }
        plugin.logger.info("已加载 " + nodeCache.size + " 个开物资源点。")
    }

    fun refreshNodeResourceItems(): Int {
        var refreshed = 0
        for ((key, node) in nodeCache) {
            var changed = false
            for (drop in node.drops) {
                val amount = drop.amount
                if (plugin.resourceManager.refreshItem(drop)) {
                    drop.amount = amount
                    refreshed++
                    changed = true
                }
            }
            if (changed) {
                nodesConfig.set("$key.drops", node.drops)
            }
        }
        if (refreshed > 0) {
            try {
                nodesConfig.save(nodesFile)
            } catch (e: IOException) {
                plugin.logger.warning("保存开物资源点物品刷新结果失败: ${e.message}")
            }
        }
        return refreshed
    }

    // ==========================================
    //           开采逻辑
    // ==========================================

    /**
     * 左键查看资源点信息。信息会短暂保持并刷新，因此枯竭/恢复倒计时能够实时变化。
     * 再次查看资源点时会替换玩家之前的查看任务，避免重复调度。
     */
    fun showNodeInfo(player: Player, loc: Location) {
        val locKey = serializeLoc(loc)
        if (!nodeCache.containsKey(locKey)) return

        cancelInspection(player.uniqueId)
        sendNodeInfoActionBar(player, locKey)

        val refreshTicks = 5L
        val displayTicks = 100L
        val task = object : BukkitRunnable() {
            var elapsedTicks = 0L

            override fun run() {
                if (!player.isOnline || !nodeCache.containsKey(locKey) || elapsedTicks >= displayTicks) {
                    cancelInspection(player.uniqueId)
                    return
                }

                sendNodeInfoActionBar(player, locKey)
                elapsedTicks += refreshTicks
            }
        }

        task.runTaskTimer(plugin, refreshTicks, refreshTicks)
        inspectionTasks[player.uniqueId] = task.taskId
    }

    private fun sendNodeInfoActionBar(player: Player, locKey: String) {
        val node = nodeCache[locKey] ?: return
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val status = getNodeStatus(data, locKey, System.currentTimeMillis())

        val resources = node.drops
            .filter { it.type != Material.AIR }
            .joinToString("§7 / ") { item ->
                val amount = item.amount.coerceAtLeast(1)
                val expectedAmount = if (amount == 1) "1" else "1~$amount"
                "${getDisplayName(item)}§r §7×§f$expectedAmount"
            }
            .ifEmpty { "§8未配置" }

        val playerLevel = data.kaiwuLevel
        val levelText = "§7需求：§eLv.${node.reqLevel} §8(你 Lv.$playerLevel)"
        val availability = getAvailabilityText(player, data, node, status)
        val statusText = when (status.state) {
            NodeState.RICH -> "§a富饶"
            NodeState.DEPLETED -> "§e枯竭 §f${formatRemainingSeconds(status.deadlineMillis)}"
            NodeState.RECOVERING -> "§7恢复中 §f${formatRemainingSeconds(status.deadlineMillis)}"
        }

        val message = "§6【开物】 §7资源：§f$resources §8| $levelText §8| $availability §8| §7状态：$statusText"
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message))
    }

    private fun getAvailabilityText(
        player: Player,
        data: PlayerData,
        node: NodeConfig,
        status: NodeStatus
    ): String {
        if (data.kaiwuLevel < node.reqLevel) return "§c✘ 不可开采（等级不足）"
        if (status.state == NodeState.RECOVERING) return "§c✘ 不可开采（恢复中）"
        if (isMining(player)) return "§e● 正在开采"

        val yaoRace = plugin.raceModule.getRace(4) as? YaoRace
        val hasDepletedPenalty = status.state == NodeState.DEPLETED &&
            yaoRace?.ignoresDepletedPenalty(player) != true
        val energyMultiplier = if (hasDepletedPenalty) {
            config.getDouble("mining.depleted_energy_multiplier", 1.5)
        } else {
            1.0
        }
        val requiredEnergy = node.energyCost * energyMultiplier
        if (data.kaiwuEnergy < requiredEnergy) return "§c✘ 不可开采（精力不足）"

        return "§a✔ 可以开采"
    }

    private fun getNodeStatus(data: PlayerData, locKey: String, now: Long): NodeStatus {
        val recoveringUntil = data.nodeCoolDowns[locKey + SUFFIX_RECOVERING]
        if (recoveringUntil != null && recoveringUntil > now) {
            return NodeStatus(NodeState.RECOVERING, recoveringUntil)
        }

        val depletedUntil = data.nodeCoolDowns[locKey + SUFFIX_DEPLETED]
        if (depletedUntil != null && depletedUntil > now) {
            return NodeStatus(NodeState.DEPLETED, depletedUntil)
        }

        return NodeStatus(NodeState.RICH)
    }

    private fun formatRemainingSeconds(deadlineMillis: Long?): String {
        if (deadlineMillis == null) return "0秒"
        val remainingMillis = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val remainingSeconds = (remainingMillis + 999L) / 1000L
        return "${remainingSeconds}秒"
    }

    private fun cancelInspection(uuid: UUID) {
        val taskId = inspectionTasks.remove(uuid) ?: return
        Bukkit.getScheduler().cancelTask(taskId)
    }

    fun startMining(player: Player, loc: Location) {
        val locKey = serializeLoc(loc)
        val node = nodeCache[locKey] ?: return
        if (miningTasks.containsKey(player.uniqueId)) return

        val data = plugin.playerManager.getPlayerData(player) ?: return

        // 修复1: 空安全处理
        if ((data.kaiwuLevel ?: 0) < node.reqLevel) {
            player.sendMessage("§c等级不足！需要 Lv." + node.reqLevel)
            return
        }

        val now = System.currentTimeMillis()

        // 修复2: 强制转换为 MutableMap 以解决 "Initializer type mismatch"
        // 这样 Kotlin 才会允许 put/remove 操作，且操作的是原对象
        @Suppress("UNCHECKED_CAST")
        val cds = data.nodeCoolDowns as MutableMap<String, Long>

        // 1. 检查【恢复期】
        val recoveryKey = locKey + SUFFIX_RECOVERING
        if (cds.containsKey(recoveryKey)) {
            val recoverEnd = cds[recoveryKey]!!
            if (now < recoverEnd) {
                val left = (recoverEnd - now) / 1000
                player.sendMessage(getMsg("messages.node_recovering").replace("%time%", left.toString()))
                return
            } else {
                cds.remove(recoveryKey)
                player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f)
            }
        }

        // 2. 检查【枯竭期】
        var isDepleted = false
        val depletedKey = locKey + SUFFIX_DEPLETED
        if (cds.containsKey(depletedKey)) {
            val depletedEnd = cds[depletedKey]!!

            if (now < depletedEnd) {
                isDepleted = true
            } else {
                cds.remove(depletedKey)
            }
        }

        // 3. 计算倍率
        var timeMult = 1.0
        var energyMult = 1.0
        var yieldMult = 1.0
        val yaoRace = plugin.raceModule.getRace(4) as? YaoRace
        val ignoresDepletedPenalty = yaoRace?.ignoresDepletedPenalty(player) == true

        if (isDepleted && !ignoresDepletedPenalty) {
            timeMult = config.getDouble("mining.depleted_time_multiplier", 2.0)
            energyMult = config.getDouble("mining.depleted_energy_multiplier", 1.5)
            yieldMult = config.getDouble("mining.depleted_yield_ratio", 0.5)

            val hint = getMsg("messages.node_depleted_hint")
                .replace("%time%", timeMult.toString())
                .replace("%energy%", energyMult.toString())
                .replace("%yield%", (yieldMult * 100).toInt().toString())
            player.sendMessage(hint)
        }

        val finalEnergyCost = node.energyCost * energyMult
        val levelSpeedMultiplier = getLevelMiningSpeedMultiplier(data.kaiwuLevel)
        val finalTime = node.timeSeconds * timeMult *
            (yaoRace?.getKaiwuMiningTimeMultiplier(player) ?: 1.0) / levelSpeedMultiplier

        // 修复3: 空安全处理
        if ((data.kaiwuEnergy ?: 0.0) < finalEnergyCost) {
            ensureEnergyRecovery(data)
            player.sendMessage("§c精力不足！需要 " + String.format("%.1f", finalEnergyCost) + " 点。")
            return
        }

        val title = if (isDepleted) getMsg("bossbar.title_depleted") else getMsg("bossbar.title_rich")
        val barColor = if (isDepleted)
            safeBarColor(config.getString("bossbar.color_depleted", "YELLOW")!!)
        else
            safeBarColor(config.getString("bossbar.color_rich", "GREEN")!!)

        val bar = Bukkit.createBossBar(title, barColor, BarStyle.SOLID)
        bar.addPlayer(player)
        miningBars[player.uniqueId] = bar
        miningStartLoc[player.uniqueId] = player.location

        val finalIsDepleted = isDepleted
        val finalYield = yieldMult
        val costEnergy = finalEnergyCost

        val task = object : BukkitRunnable() {
            var progress = 0.0
            val tickAdd = 1.0 / (finalTime * 20)

            override fun run() {
                if (!player.isOnline || player.isDead) {
                    cancelMining(player, false)
                    return
                }

                val maxDist = config.getDouble("mining.interrupt_distance", 5.0)
                val start = miningStartLoc[player.uniqueId]
                if (start != null && player.location.distance(start) > maxDist) {
                    player.sendMessage(getMsg("messages.mining_interrupted_move"))
                    cancelMining(player, false)
                    return
                }

                progress += tickAdd
                if (progress >= 1.0) progress = 1.0
                bar.progress = progress

                if (progress >= 1.0) {
                    finishMining(player, data, node, locKey, finalIsDepleted, costEnergy, finalYield)
                    cancelMining(player, false)
                }
            }
        }

        task.runTaskTimer(plugin, 0L, 1L)
        miningTasks[player.uniqueId] = task.taskId
    }

    private fun getLevelMiningSpeedMultiplier(level: Int): Double {
        val bonusPerLevel = config.getDouble("mining.level_speed_bonus_per_level", 0.10).coerceAtLeast(0.0)
        val maxBonus = config.getDouble("mining.max_level_speed_bonus", 0.50).coerceAtLeast(0.0)
        val levelBonus = ((level - 1).coerceAtLeast(0) * bonusPerLevel).coerceAtMost(maxBonus)
        return 1.0 + levelBonus
    }

    private fun finishMining(
        player: Player,
        data: PlayerData,
        node: NodeConfig,
        locKey: String,
        wasDepleted: Boolean,
        energyCost: Double,
        yieldMult: Double
    ) {
        val currentEnergy = data.kaiwuEnergy ?: 0.0
        if (currentEnergy < energyCost) return
        data.kaiwuEnergy = currentEnergy - energyCost
        ensureEnergyRecovery(data)

        val now = System.currentTimeMillis()

        // 修复4: 同样使用强转
        @Suppress("UNCHECKED_CAST")
        val cds = data.nodeCoolDowns as MutableMap<String, Long>

        if (!wasDepleted) {
            val duration = node.depletedSec * 1000L
            cds[locKey + SUFFIX_DEPLETED] = now + duration
        } else {
            val cooldown = node.cooldownSec * 1000L
            cds[locKey + SUFFIX_RECOVERING] = now + cooldown
            cds.remove(locKey + SUFFIX_DEPLETED)
        }

        if (node.drops.isNotEmpty()) {
            val template = node.drops[Random().nextInt(node.drops.size)].clone()
            val maxAmount = template.amount
            var finalAmount = 1
            if (maxAmount > 1) finalAmount = 1 + Random().nextInt(maxAmount)

            val calcAmount = finalAmount * yieldMult
            finalAmount = if (calcAmount < 1.0) {
                if (Math.random() > calcAmount) 0 else 1
            } else {
                calcAmount.roundToInt()
            }

            if (finalAmount > 0 && template.type != Material.AIR) {
                template.amount = finalAmount
                val left = player.inventory.addItem(template)
                if (left.isNotEmpty()) {
                    player.world.dropItem(player.location, left[0]!!)
                    player.sendMessage(getMsg("messages.mining_fail_bag_full"))
                }
                val name = getDisplayName(template)
                player.sendMessage(
                    getMsg("messages.mining_success")
                        .replace("%item%", name)
                        .replace("%amount%", finalAmount.toString())
                )
            } else {
                player.sendMessage("§7资源过于贫瘠，本次开采化为乌有...")
            }
        } else {
            player.sendMessage("§7一无所获...")
        }

        val currentExp = data.kaiwuExp ?: 0
        data.kaiwuExp = currentExp + node.exp

        checkLevelUp(player, data)
        plugin.databaseManager.queuePlayerSave(data)
    }

    // ==========================================
    //           视觉特效
    // ==========================================

    private fun startVisualTask() {
        val interval = config.getInt("visual.check_interval", 20)
        object : BukkitRunnable() {
            override fun run() {
                if (!config.getBoolean("visual.enabled", true)) return
                val baseRange = config.getDouble("visual.default_range", 10.0)
                val yaoRace = plugin.raceModule.getRace(4) as? YaoRace
                for (p in Bukkit.getOnlinePlayers()) {
                    try {
                        val range = yaoRace?.getKaiwuSenseRange(p, baseRange) ?: baseRange
                        highlightNodes(p, range)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, interval.toLong())
    }

    fun highlightNodes(p: Player, range: Double) {
        if (!p.isOnline) return

        val data = plugin.playerManager.getPlayerData(p) ?: return

        val pLoc = p.location
        val worldName = pLoc.world?.name ?: return
        val pChunkX = pLoc.blockX shr 4
        val pChunkZ = pLoc.blockZ shr 4

        // 修复5: 这里也需要强转，因为后面调用了 remove()
        @Suppress("UNCHECKED_CAST")
        val cds = data.nodeCoolDowns as MutableMap<String, Long>

        val now = System.currentTimeMillis()
        val particleCount = config.getInt("visual.particle_count", 3)

        val chunkRadius = ceil(range / 16.0).toInt().coerceAtLeast(1)
        for (cx in pChunkX - chunkRadius..pChunkX + chunkRadius) {
            for (cz in pChunkZ - chunkRadius..pChunkZ + chunkRadius) {
                val chunkKey = "$worldName,$cx,$cz"
                val nodes = chunkNodeMap[chunkKey] ?: continue

                for (node in ArrayList(nodes)) {
                    if (node.cachedLoc == null || node.cachedLoc!!.distanceSquared(pLoc) > range * range) continue

                    val locKey = serializeLoc(node.cachedLoc!!)
                    val recoverKey = locKey + SUFFIX_RECOVERING
                    val depletedKey = locKey + SUFFIX_DEPLETED

                    if (cds.containsKey(recoverKey)) {
                        val recoverEnd = cds[recoverKey]!!
                        if (now >= recoverEnd) {
                            p.spawnParticle(
                                Particle.HAPPY_VILLAGER, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                15, 0.5, 0.5, 0.5, 0.0
                            )
                            p.spawnParticle(
                                Particle.TOTEM_OF_UNDYING, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                5, 0.2, 0.2, 0.2, 0.1
                            )
                            p.playSound(node.cachedLoc!!, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f)

                            // 这里是合法的，因为 cds 被强转为了 MutableMap
                            cds.remove(recoverKey)
                        } else {
                            if (dustRecovering != null) {
                                p.spawnParticle(
                                    Particle.DUST, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    particleCount, 0.3, 0.3, 0.3, 0.0, dustRecovering
                                )
                            }
                        }
                        continue
                    }

                    if (cds.containsKey(depletedKey)) {
                        val depletedEnd = cds[depletedKey]!!
                        if (now >= depletedEnd) {
                            cds.remove(depletedKey)
                        } else {
                            if (dustDepleted != null) {
                                p.spawnParticle(
                                    Particle.DUST, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    particleCount, 0.3, 0.3, 0.3, 0.0, dustDepleted
                                )
                            }
                            continue
                        }
                    }

                    p.spawnParticle(
                        Particle.HAPPY_VILLAGER, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                        particleCount, 0.4, 0.2, 0.4, 0.0
                    )
                }
            }
        }
    }

    // ==========================================
    //           辅助与管理
    // ==========================================

    private fun startRegenTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (p in Bukkit.getOnlinePlayers()) {
                    val data = plugin.playerManager.getPlayerData(p) ?: continue
                    ensureEnergyRecovery(data)
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    fun getMillisUntilEnergyFull(data: PlayerData): Long {
        return ensureEnergyRecovery(data)
    }

    /**
     * 精力只使用一个固定恢复窗口：首次不满时开始计时，期间不会因开采或精力不足而顺延。
     * 到期后直接回满，并通过数据库后台写入队列持久化。
     */
    private fun ensureEnergyRecovery(data: PlayerData): Long {
        val now = System.currentTimeMillis()
        if (data.kaiwuEnergy >= data.maxKaiWuEnergy) {
            if (data.nodeCoolDowns.remove(energyRecoveryKey) != null) {
                plugin.databaseManager.queuePlayerSave(data)
            }
            return 0L
        }

        val existingDeadline = data.nodeCoolDowns[energyRecoveryKey]
        if (existingDeadline != null) {
            if (now < existingDeadline) return existingDeadline - now

            data.kaiwuEnergy = data.maxKaiWuEnergy
            data.nodeCoolDowns.remove(energyRecoveryKey)
            plugin.databaseManager.queuePlayerSave(data)
            return 0L
        }

        val deadline = now + getEnergyRegenIntervalMillis(data)
        data.nodeCoolDowns[energyRecoveryKey] = deadline
        plugin.databaseManager.queuePlayerSave(data)
        return deadline - now
    }

    private fun getEnergyRegenIntervalMillis(data: PlayerData): Long {
        val baseMillis = config.getInt("energy.regen_interval_min", 10).coerceAtLeast(1) * 60_000L
        return if (YaoRace.isNatureSpiritActive(data)) {
            (baseMillis * 0.8).toLong().coerceAtLeast(1L)
        } else {
            baseMillis
        }
    }

    fun removeNode(p: Player?, locKey: String) {
        val removed = nodeCache.remove(locKey)
        nodesConfig.set(locKey, null)
        try {
            nodesConfig.save(nodesFile)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        if (removed != null) {
            val cx = removed.x.toInt() shr 4
            val cz = removed.z.toInt() shr 4
            val chunkKey = "${removed.worldName},$cx,$cz"
            if (chunkNodeMap.containsKey(chunkKey)) {
                val list = chunkNodeMap[chunkKey]
                list?.remove(removed)
            }
        }

        try {
            val parts = locKey.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size == 4) {
                val w = Bukkit.getWorld(parts[0])
                if (w != null) {
                    Location(
                        w,
                        parts[1].toDouble(),
                        parts[2].toDouble(),
                        parts[3].toDouble()
                    ).block.type = Material.AIR
                }
            }
        } catch (ignored: Exception) {
        }

        p?.sendMessage(getMsg("messages.admin_delete_success"))
    }

    fun requestDeleteNode(p: Player, locKey: String) {
        deleteConfirmations[p.uniqueId] = locKey
        p.sendMessage(getMsg("messages.admin_confirm_delete"))
        object : BukkitRunnable() {
            override fun run() {
                if (deleteConfirmations.containsKey(p.uniqueId)) {
                    deleteConfirmations.remove(p.uniqueId)
                    p.sendMessage("§7操作超时。")
                }
            }
        }.runTaskLater(plugin, (60 * 20).toLong())
    }

    fun confirmDeleteNode(p: Player) {
        val key = deleteConfirmations.remove(p.uniqueId)
        if (key != null) removeNode(p, key)
    }

    fun saveNodeFromEditor(
        locKey: String,
        drops: List<ItemStack>?,
        time: Double,
        energy: Double,
        exp: Int,
        reqLv: Int,
        cooldown: Int,
        depleted: Int
    ) {
        nodesConfig.set("$locKey.world", locKey.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0])
        nodesConfig.set("$locKey.drops", drops)
        nodesConfig.set("$locKey.time", time)
        nodesConfig.set("$locKey.energy", energy)
        nodesConfig.set("$locKey.exp", exp)
        nodesConfig.set("$locKey.req_level", reqLv)
        nodesConfig.set("$locKey.cooldown", cooldown)
        nodesConfig.set("$locKey.depleted", depleted)
        try {
            nodesConfig.save(nodesFile)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        loadNodes()
    }

    fun cancelMining(player: Player, isDamage: Boolean) {
        val uuid = player.uniqueId
        if (miningTasks.containsKey(uuid)) {
            Bukkit.getScheduler().cancelTask(miningTasks.remove(uuid)!!)
            if (miningBars.containsKey(uuid)) {
                miningBars.remove(uuid)!!.removeAll()
            }
            miningStartLoc.remove(uuid)
            if (isDamage) player.sendMessage(getMsg("messages.mining_interrupted_damage"))
        }
    }

    fun isMining(player: Player): Boolean {
        return miningTasks.containsKey(player.uniqueId)
    }

    private fun checkLevelUp(p: Player, data: PlayerData) {
        val base = config.getInt("level_exp_base", 100)
        // 修复7: 空安全
        val level = data.kaiwuLevel ?: 0
        val exp = data.kaiwuExp ?: 0

        val req = level * base
        if (exp >= req) {
            data.kaiwuExp = exp - req
            data.kaiwuLevel = level + 1
            p.sendMessage(getMsg("messages.level_up").replace("%level%", (level + 1).toString()))
            p.playSound(p.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }
    }

    private fun getMsg(path: String): String {
        val prefix = ChatColor.translateAlternateColorCodes('&', config.getString("messages.prefix", "")!!)
        val msg = ChatColor.translateAlternateColorCodes('&', config.getString(path, "")!!)
        return prefix + msg
    }

    private fun safeBarColor(name: String): BarColor {
        return try {
            BarColor.valueOf(name)
        } catch (e: IllegalArgumentException) {
            BarColor.GREEN
        }
    }

    private fun getDisplayName(item: ItemStack): String {
        if (item.itemMeta != null && item.itemMeta!!.hasDisplayName()) return item.itemMeta!!.displayName
        val type = item.type.name.lowercase(Locale.getDefault()).replace("_", " ")
        val sb = StringBuilder()
        for (s in type.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (s.isNotEmpty()) sb.append(s[0].uppercaseChar()).append(s.substring(1)).append(" ")
        }
        return sb.toString().trim { it <= ' ' }
    }

    fun serializeLoc(loc: Location): String {
        return "${loc.world!!.name},${loc.blockX},${loc.blockY},${loc.blockZ}"
    }

    fun getNode(key: String): NodeConfig? {
        return nodeCache[key]
    }

    fun getAllNodes(): List<Pair<String, NodeConfig>> = nodeCache.entries
        .map { it.key to it.value }

    fun isNode(loc: Location): Boolean {
        return nodeCache.containsKey(serializeLoc(loc))
    }

    fun setPlayerLevel(p: Player, lv: Int) {
        val data = plugin.playerManager.getPlayerData(p)
        if (data != null) {
            data.kaiwuLevel = lv.coerceAtLeast(1)
            data.kaiwuEnergy = data.kaiwuEnergy.coerceAtMost(data.maxKaiWuEnergy)
            ensureEnergyRecovery(data)
            plugin.databaseManager.queuePlayerSave(data, 1L)
            p.sendMessage("§a等级已设为 ${data.kaiwuLevel}")
        }
    }

    fun setPlayerEnergy(p: Player, energy: Double) {
        val data = plugin.playerManager.getPlayerData(p)
        if (data != null) {
            data.kaiwuEnergy = energy.coerceIn(0.0, data.maxKaiWuEnergy)
            ensureEnergyRecovery(data)
            plugin.databaseManager.queuePlayerSave(data, 1L)
            p.sendMessage("§a精力已设为 ${data.kaiwuEnergy}")
        }
    }

    class NodeConfig {
        var drops: MutableList<ItemStack> = ArrayList()
        var timeSeconds: Double = 0.0
        var energyCost: Double = 0.0
        var exp: Int = 0
        var reqLevel: Int = 0
        var cooldownSec: Int = 0
        var depletedSec: Int = 0
        var worldName: String? = null
        var x: Double = 0.0
        var y: Double = 0.0
        var z: Double = 0.0
        var cachedLoc: Location? = null
    }

    private data class NodeStatus(
        val state: NodeState,
        val deadlineMillis: Long? = null
    )

    private enum class NodeState {
        RICH,
        DEPLETED,
        RECOVERING
    }
}
