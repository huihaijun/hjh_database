package com.hjh_database.race.shen

import com.hjh_database.Hjh_database
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Display
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * 神族证明被动「神威浩荡」。
 *
 * 所有倒计时只在玩家在线且满足神族证明解锁条件时推进。运行期状态保存在内存，
 * 每分钟和退出时异步写入 SQLite；主线程只做轻量计时、交互和 Bukkit 实体操作。
 */
class ShenTributeManager(private val plugin: Hjh_database) : Listener {

    private enum class TributeCategory(val displayName: String) {
        HUMAN("人族"),
        IMMORTAL("仙族"),
        YAO("妖族")
    }

    private data class TributeState(
        var playerName: String,
        var arrivalRemainingSeconds: Int = ARRIVAL_SECONDS,
        var category: TributeCategory? = null,
        var tier: Int = 0,
        var claimRemainingSeconds: Int = 0,
        var reminderMask: Int = 0,
        var claimWindowStartedAt: Long = 0L,
        var dailyClaimCount: Int = 0
    )

    private data class TributeSnapshot(
        val playerId: UUID,
        val playerName: String,
        val arrivalRemainingSeconds: Int,
        val category: TributeCategory?,
        val tier: Int,
        val claimRemainingSeconds: Int,
        val reminderMask: Int,
        val claimWindowStartedAt: Long,
        val dailyClaimCount: Int
    )

    private data class Reward(val resourceId: String?, val material: Material?, val amount: Int)

    private val states = HashMap<UUID, TributeState>()
    private val pendingLoads = ConcurrentHashMap.newKeySet<UUID>()
    private val sessionTokens = ConcurrentHashMap<UUID, AtomicLong>()
    private val displays = HashMap<UUID, TextDisplay>()
    private var checkpointSeconds = 0
    private var shuttingDown = false
    private val timerTask: BukkitTask

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach(::loadPlayer)
        timerTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        loadPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        sessionTokens.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
        pendingLoads.remove(playerId)
        removeDisplay(playerId)
        states.remove(playerId)?.let { saveSnapshotsAsync(listOf(snapshot(playerId, it))) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTributeMinecartInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND || !isTributeMinecart(event.rightClicked)) return
        event.isCancelled = true

        val player = event.player
        if (!isEligible(player)) {
            player.sendMessage("§c[神威浩荡] §f你尚未获得领取神族贡品的资格。")
            return
        }

        val state = states[player.uniqueId]
        if (state == null) {
            loadPlayer(player)
            player.sendMessage("§e[神威浩荡] §f贡品记录正在同步，请稍后再试。")
            return
        }

        normalizeClaimWindow(state)
        if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
            sendClaimLimitMessage(player, state)
            return
        }

        if (state.category == null || state.claimRemainingSeconds <= 0) {
            player.sendMessage("§e[神威浩荡] §f贡品还需 §b${formatDuration(state.arrivalRemainingSeconds)} §f到达。")
            player.sendMessage(claimUsageText(state))
            return
        }

        claimTribute(player, state)
    }

    fun handleAdminCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage("§c用法: /hjhadmin shengong <玩家> <剩余到达秒数>")
            return true
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            sender.sendMessage("§c玩家不在线。")
            return true
        }
        if (!isEligible(target)) {
            sender.sendMessage("§c${target.name} 尚未满足神族证明的生效条件。")
            return true
        }
        val seconds = args[1].toIntOrNull()
        if (seconds == null || seconds < 0) {
            sender.sendMessage("§c剩余时间必须是大于或等于 0 的整数秒。")
            return true
        }

        val state = states[target.uniqueId]
        if (state == null) {
            loadPlayer(target)
            sender.sendMessage("§e该玩家的贡品记录正在同步，请稍后重试。")
            return true
        }

        clearActiveTribute(target.uniqueId, state)
        state.arrivalRemainingSeconds = seconds
        if (seconds == 0) {
            activateTribute(target, state)
        } else {
            saveSnapshotsAsync(listOf(snapshot(target.uniqueId, state)))
        }
        sender.sendMessage("§a已将 ${target.name} 的贡品到达时间设置为 ${seconds} 秒。")
        return true
    }

    fun isTributeMinecart(entity: Entity): Boolean {
        if (entity !is Minecart || entity.world.name != TRIBUTE_WORLD) return false
        val location = entity.location
        val dx = location.x - TRIBUTE_X
        val dy = location.y - TRIBUTE_Y
        val dz = location.z - TRIBUTE_Z
        return dx * dx + dy * dy + dz * dz <= MINECART_RADIUS_SQUARED
    }

    fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        timerTask.cancel()
        displays.keys.toList().forEach(::removeDisplay)
        val snapshots = states.map { (playerId, state) -> snapshot(playerId, state) }
        if (snapshots.isNotEmpty()) saveSnapshotsAsync(snapshots)
        states.clear()
        pendingLoads.clear()
    }

    fun resetPlayerData(player: Player) {
        val playerId = player.uniqueId
        sessionTokens.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
        pendingLoads.remove(playerId)
        removeDisplay(playerId)
        states[playerId] = TributeState(player.name)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        for (player in plugin.server.onlinePlayers) {
            if (!isEligible(player)) {
                removeDisplay(player.uniqueId)
                continue
            }

            val state = states[player.uniqueId] ?: continue
            state.playerName = player.name
            normalizeClaimWindow(state, now)

            // 达到领取上限后不创建贡品、不发提醒、不维护悬浮文字。
            // 两小时在线倒计时仍继续累计，归零后等待现实时间窗口重置。
            if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
                if (state.category != null) {
                    clearActiveTribute(player.uniqueId, state)
                    state.arrivalRemainingSeconds = 0
                    saveSnapshotsAsync(listOf(snapshot(player.uniqueId, state)))
                } else if (state.arrivalRemainingSeconds > 0) {
                    state.arrivalRemainingSeconds--
                }
                removeDisplay(player.uniqueId)
                continue
            }

            if (state.category == null) {
                state.arrivalRemainingSeconds = (state.arrivalRemainingSeconds - 1).coerceAtLeast(0)
                if (state.arrivalRemainingSeconds == 0) activateTribute(player, state)
                removeDisplay(player.uniqueId)
            } else {
                val previous = state.claimRemainingSeconds
                state.claimRemainingSeconds = (previous - 1).coerceAtLeast(0)
                sendDueReminders(player, state, previous, state.claimRemainingSeconds)
                if (state.claimRemainingSeconds == 0) {
                    expireTribute(player, state)
                } else {
                    updateDisplay(player, state)
                }
            }
        }

        checkpointSeconds++
        if (checkpointSeconds >= CHECKPOINT_SECONDS) {
            checkpointSeconds = 0
            val snapshots = states.map { (playerId, state) -> snapshot(playerId, state) }
            if (snapshots.isNotEmpty()) saveSnapshotsAsync(snapshots)
        }
    }

    private fun activateTribute(player: Player, state: TributeState) {
        normalizeClaimWindow(state)
        if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
            state.arrivalRemainingSeconds = 0
            clearActiveTribute(player.uniqueId, state)
            saveSnapshotsAsync(listOf(snapshot(player.uniqueId, state)))
            return
        }

        state.category = TributeCategory.entries[ThreadLocalRandom.current().nextInt(TributeCategory.entries.size)]
        state.tier = tierForLevel(plugin.playerManager.getData(player.uniqueId)?.lv ?: 1)
        state.claimRemainingSeconds = CLAIM_SECONDS
        state.arrivalRemainingSeconds = 0
        state.reminderMask = 0

        player.sendMessage("§6§l[神威浩荡] §e${state.category!!.displayName}的贡品已抵达皇宫西侧，请在30分钟内领取！")
        player.sendMessage(claimUsageText(state))
        player.playSound(player.location, Sound.BLOCK_BELL_USE, 0.9f, 1.25f)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.9f)
        updateDisplay(player, state)
        // 到达事件很稀少，立即异步落库，避免重启后重新随机类别或阶级。
        saveSnapshotsAsync(listOf(snapshot(player.uniqueId, state)))
    }

    private fun expireTribute(player: Player, state: TributeState) {
        val categoryName = state.category?.displayName ?: "本次"
        clearActiveTribute(player.uniqueId, state)
        state.arrivalRemainingSeconds = ARRIVAL_SECONDS
        player.sendMessage("§c[神威浩荡] §f$categoryName 的贡品因逾期未领取，已经消失。")
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.7f, 0.8f)
        saveSnapshotsAsync(listOf(snapshot(player.uniqueId, state)))
    }

    private fun claimTribute(player: Player, state: TributeState) {
        val category = state.category ?: return
        normalizeClaimWindow(state)
        if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
            sendClaimLimitMessage(player, state)
            return
        }

        val rewards = rewardsFor(category, state.tier)
        val preparedItems = rewards.mapNotNull { reward ->
            val item = when {
                reward.material != null -> ItemStack(reward.material)
                reward.resourceId != null -> plugin.resourceManager.getItem(reward.resourceId)?.clone()
                else -> null
            }
            if (item == null) {
                plugin.logger.warning("[神族贡品] 无法生成贡品物品: ${reward.resourceId ?: reward.material}")
                return@mapNotNull null
            }
            item.amount = reward.amount
            item
        }
        if (preparedItems.size != rewards.size) {
            player.sendMessage("§c[神威浩荡] §f贡品配置异常，请联系管理员；本次贡品已为你保留。")
            return
        }

        preparedItems.forEach { item ->
            player.inventory.addItem(item).values.forEach { overflow ->
                player.world.dropItemNaturally(player.location, overflow)
            }
        }

        val now = System.currentTimeMillis()
        if (state.dailyClaimCount == 0 || state.claimWindowStartedAt <= 0L) {
            state.claimWindowStartedAt = now
        }
        state.dailyClaimCount++
        player.sendMessage("§a§l[神威浩荡] §f成功领取 §e${category.displayName}${state.tier}阶贡品§f！")
        player.sendMessage("§6你本次获得的贡品清单：")
        preparedItems.forEach { item ->
            player.sendMessage(
                Component.text("  ", NamedTextColor.WHITE)
                    .append(item.displayName())
                    .append(Component.text(" × ${item.amount}", NamedTextColor.YELLOW))
            )
        }
        if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
            player.sendMessage("§6§l[神威浩荡] §e今日贡品次数已全部领取完毕！")
            player.sendMessage("§7今日已领取：§e${state.dailyClaimCount}/$MAX_DAILY_CLAIMS 次 §8| §7将于 §b${formatResetTime(state)} §7重置")
        } else {
            player.sendMessage(claimUsageText(state, now))
        }
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, player.location.clone().add(0.0, 1.0, 0.0), 35, 0.55, 0.8, 0.55, 0.08)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f)

        clearActiveTribute(player.uniqueId, state)
        state.arrivalRemainingSeconds = ARRIVAL_SECONDS
        saveSnapshotsAsync(listOf(snapshot(player.uniqueId, state)))
    }

    private fun rewardsFor(category: TributeCategory, tier: Int): List<Reward> = when (category) {
        TributeCategory.HUMAN -> when (tier) {
            1 -> listOf(
                vanilla(Material.COOKED_BEEF, 64),
                resource("yuansuduihuanquan", 8)
            )
            2 -> listOf(
                vanilla(Material.COOKED_BEEF, 64),
                resource("yuansuduihuanquan", 16),
                resource("jianghuxinde_dalu", 10)
            )
            else -> listOf(
                vanilla(Material.COOKED_BEEF, 64),
                resource("yuansuduihuanquan", 32),
                resource("jianghuxinde_dalu", 20)
            )
        }

        TributeCategory.IMMORTAL -> when (tier) {
            1 -> listOf(
                resource("yuansuduihuanquan", 12),
                resource("yuansuzhuanhuaquan", 12)
            )
            2 -> listOf(
                resource("jinyuanbao", 2),
                resource("yuansuduihuanquan", 24),
                resource("yuansuzhuanhuaquan", 24)
            )
            else -> listOf(
                resource("jinyuanbao", 4),
                resource("yuansuduihuanquan", 48),
                resource("yuansuzhuanhuaquan", 48)
            )
        }

        TributeCategory.YAO -> when (tier) {
            1 -> listOf(
                resource("yuhedan0", 16),
                resource("huilingdan0", 16)
            )
            2 -> listOf(
                resource("yuhedan1", 20),
                resource("huilingdan1", 20)
            )
            else -> listOf(
                resource("yuhedan2", 20),
                resource("huilingdan2", 20),
                resource("yy_tongyong2", 5)
            )
        }
    }

    private fun sendDueReminders(player: Player, state: TributeState, previous: Int, current: Int) {
        REMINDER_SECONDS.forEachIndexed { index, threshold ->
            val bit = 1 shl index
            if (state.reminderMask and bit != 0 || previous <= threshold || current > threshold) return@forEachIndexed
            state.reminderMask = state.reminderMask or bit
            player.sendMessage("§e[神威浩荡] §f${state.category?.displayName}的贡品将在 §c${formatDuration(threshold)} §f后消失，请尽快领取！")
            player.sendMessage(claimUsageText(state))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.75f, if (threshold <= 60) 1.6f else 1.25f)
        }
    }

    private fun updateDisplay(player: Player, state: TributeState) {
        normalizeClaimWindow(state)
        if (state.dailyClaimCount >= MAX_DAILY_CLAIMS) {
            removeDisplay(player.uniqueId)
            return
        }

        val world = Bukkit.getWorld(TRIBUTE_WORLD) ?: run {
            removeDisplay(player.uniqueId)
            return
        }
        if (player.world.uid != world.uid || player.location.distanceSquared(tributeLocation(world)) > DISPLAY_RANGE_SQUARED) {
            removeDisplay(player.uniqueId)
            return
        }

        val display = displays[player.uniqueId]?.takeIf { it.isValid } ?: world.spawn(
            tributeLocation(world).add(0.0, 2.0, 0.0),
            TextDisplay::class.java
        ) { entity ->
            entity.isPersistent = false
            entity.isVisibleByDefault = false
            entity.billboard = Display.Billboard.CENTER
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.isShadowed = true
            entity.isSeeThrough = true
            entity.backgroundColor = Color.fromARGB(112, 0, 0, 0)
            entity.lineWidth = 240
            entity.viewRange = 0.75f
        }.also { entity ->
            displays[player.uniqueId] = entity
            player.showEntity(plugin, entity)
        }

        val categoryName = state.category?.displayName ?: return
        normalizeClaimWindow(state)
        display.text(
            Component.text("${categoryName}的贡品已到达", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("贡品还剩${state.claimRemainingSeconds}秒停留时间", NamedTextColor.AQUA))
                .append(Component.newline())
                .append(Component.text("今日已领取：${state.dailyClaimCount}/$MAX_DAILY_CLAIMS 次", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("右键下方矿车领取", NamedTextColor.GREEN))
        )
    }

    private fun clearActiveTribute(playerId: UUID, state: TributeState) {
        state.category = null
        state.tier = 0
        state.claimRemainingSeconds = 0
        state.reminderMask = 0
        removeDisplay(playerId)
    }

    private fun removeDisplay(playerId: UUID) {
        displays.remove(playerId)?.remove()
    }

    private fun loadPlayer(player: Player) {
        if (shuttingDown || states.containsKey(player.uniqueId) || !pendingLoads.add(player.uniqueId)) return
        val playerId = player.uniqueId
        val token = sessionTokens.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
        plugin.databaseManager.submitDatabaseOperation { loadState(playerId, player.name) }
            .whenComplete { loadedState, error ->
                if (shuttingDown) return@whenComplete
                plugin.server.scheduler.runTask(plugin, Runnable {
                    pendingLoads.remove(playerId)
                    if (sessionTokens[playerId]?.get() != token || !player.isOnline) return@Runnable
                    if (error != null) {
                        plugin.logger.warning("加载 ${player.name} 的神族贡品数据失败: ${error.message}")
                        player.sendMessage("§c[神威浩荡] §f贡品数据同步失败，请稍后重试。")
                        return@Runnable
                    }
                    states[playerId] = loadedState
                })
            }
    }

    private fun loadState(playerId: UUID, playerName: String): TributeState {
        val connection = plugin.databaseManager.dataSource?.connection ?: return TributeState(playerName)
        connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT arrival_remaining_seconds, tribute_category, tribute_tier,
                       claim_remaining_seconds, reminder_mask,
                       claim_window_started_at, daily_claim_count
                FROM player_shen_tribute WHERE player_uuid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { result ->
                    if (!result.next()) return TributeState(playerName)
                    val category = result.getString("tribute_category")?.let { stored ->
                        runCatching { TributeCategory.valueOf(stored) }.getOrNull()
                    }
                    val claimRemaining = result.getInt("claim_remaining_seconds").coerceAtLeast(0)
                    return TributeState(
                        playerName = playerName,
                        arrivalRemainingSeconds = result.getInt("arrival_remaining_seconds").coerceAtLeast(0),
                        category = category.takeIf { claimRemaining > 0 },
                        // 旧数据可能仍存有四、五阶贡品；统一折算为新版最高三阶。
                        tier = result.getInt("tribute_tier").coerceIn(0, 3),
                        claimRemainingSeconds = if (category != null) claimRemaining else 0,
                        reminderMask = result.getInt("reminder_mask"),
                        claimWindowStartedAt = result.getLong("claim_window_started_at"),
                        dailyClaimCount = result.getInt("daily_claim_count").coerceIn(0, MAX_DAILY_CLAIMS)
                    )
                }
            }
        }
    }

    private fun saveSnapshotsAsync(snapshots: List<TributeSnapshot>) {
        if (snapshots.isEmpty()) return
        plugin.databaseManager.submitDatabaseOperation { persistSnapshots(snapshots) }
            .whenComplete { _, error ->
                if (error != null) plugin.logger.warning("保存神族贡品数据失败: ${error.message}")
            }
    }

    private fun persistSnapshots(snapshots: List<TributeSnapshot>) {
        val connection = plugin.databaseManager.dataSource?.connection ?: return
        connection.use { conn ->
            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(UPSERT_SQL).use { statement ->
                    snapshots.forEach { data ->
                        statement.setString(1, data.playerId.toString())
                        statement.setString(2, data.playerName)
                        statement.setInt(3, data.arrivalRemainingSeconds)
                        statement.setString(4, data.category?.name)
                        statement.setInt(5, data.tier)
                        statement.setInt(6, data.claimRemainingSeconds)
                        statement.setInt(7, data.reminderMask)
                        statement.setLong(8, data.claimWindowStartedAt)
                        statement.setInt(9, data.dailyClaimCount)
                        statement.setLong(10, System.currentTimeMillis())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                conn.commit()
            } catch (error: Exception) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }
    }

    private fun snapshot(playerId: UUID, state: TributeState) = TributeSnapshot(
        playerId,
        state.playerName,
        state.arrivalRemainingSeconds,
        state.category,
        state.tier,
        state.claimRemainingSeconds,
        state.reminderMask,
        state.claimWindowStartedAt,
        state.dailyClaimCount
    )

    private fun isEligible(player: Player): Boolean =
        plugin.raceModule.getRace(0)?.isRaceActive(player) == true

    private fun tierForLevel(level: Int): Int = when {
        level <= 19 -> 1
        level <= 39 -> 2
        else -> 3
    }

    private fun tributeLocation(world: org.bukkit.World): Location = Location(world, TRIBUTE_X, TRIBUTE_Y, TRIBUTE_Z)

    private fun formatDuration(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        return "${safeSeconds / 60}分${safeSeconds % 60}秒"
    }

    /** 领取上限使用现实时间；贡品到达、停留倒计时仍由每秒在线 Tick 推进。 */
    private fun normalizeClaimWindow(state: TributeState, now: Long = System.currentTimeMillis()) {
        val elapsed = now - state.claimWindowStartedAt
        if (state.dailyClaimCount <= 0 || state.claimWindowStartedAt <= 0L || elapsed < 0L || elapsed >= CLAIM_WINDOW_MILLIS) {
            state.dailyClaimCount = 0
            state.claimWindowStartedAt = 0L
        }
    }

    private fun claimUsageText(state: TributeState, now: Long = System.currentTimeMillis()): String {
        normalizeClaimWindow(state, now)
        return "§7今日已领取：§e${state.dailyClaimCount}/$MAX_DAILY_CLAIMS 次"
    }

    private fun claimResetRemainingMillis(state: TributeState, now: Long = System.currentTimeMillis()): Long {
        normalizeClaimWindow(state, now)
        if (state.claimWindowStartedAt <= 0L) return 0L
        return (state.claimWindowStartedAt + CLAIM_WINDOW_MILLIS - now).coerceAtLeast(0L)
    }

    private fun formatRealDuration(totalMillis: Long): String {
        val totalSeconds = ((totalMillis + 999L) / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return "${hours}时${minutes}分${seconds}秒"
    }

    private fun formatResetTime(state: TributeState): String {
        val resetAt = state.claimWindowStartedAt + CLAIM_WINDOW_MILLIS
        return RESET_TIME_FORMATTER.format(Instant.ofEpochMilli(resetAt))
    }

    private fun sendClaimLimitMessage(player: Player, state: TributeState) {
        player.sendMessage("§c[神威浩荡] §f本轮24小时内的贡品领取次数已用完。")
        player.sendMessage(
            "§7今日已领取：§e${state.dailyClaimCount}/$MAX_DAILY_CLAIMS 次 §8| " +
                "§7将于 §b${formatResetTime(state)} §7重置（剩余${formatRealDuration(claimResetRemainingMillis(state))}）"
        )
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
    }

    private fun resource(id: String, amount: Int) = Reward(id, null, amount)
    private fun vanilla(material: Material, amount: Int) = Reward(null, material, amount)

    companion object {
        private const val ARRIVAL_SECONDS = 2 * 60 * 60
        private const val CLAIM_SECONDS = 30 * 60
        private const val CHECKPOINT_SECONDS = 60
        private const val MAX_DAILY_CLAIMS = 3
        private const val CLAIM_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
        private const val TRIBUTE_WORLD = "world"
        private const val TRIBUTE_X = 118.51
        private const val TRIBUTE_Y = 59.06
        private const val TRIBUTE_Z = -146.50
        private const val MINECART_RADIUS_SQUARED = 2.25
        private const val DISPLAY_RANGE_SQUARED = 48.0 * 48.0
        private val REMINDER_SECONDS = intArrayOf(15 * 60, 10 * 60, 5 * 60, 60, 30)
        private val RESET_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss").withZone(ZoneId.systemDefault())

        private val UPSERT_SQL = """
            INSERT INTO player_shen_tribute (
                player_uuid, player_name, arrival_remaining_seconds, tribute_category,
                tribute_tier, claim_remaining_seconds, reminder_mask,
                claim_window_started_at, daily_claim_count, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                player_name = excluded.player_name,
                arrival_remaining_seconds = excluded.arrival_remaining_seconds,
                tribute_category = excluded.tribute_category,
                tribute_tier = excluded.tribute_tier,
                claim_remaining_seconds = excluded.claim_remaining_seconds,
                reminder_mask = excluded.reminder_mask,
                claim_window_started_at = excluded.claim_window_started_at,
                daily_claim_count = excluded.daily_claim_count,
                updated_at = excluded.updated_at
        """.trimIndent()
    }
}
