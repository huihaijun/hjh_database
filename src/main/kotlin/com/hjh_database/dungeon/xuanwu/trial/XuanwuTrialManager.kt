package com.hjh_database.dungeon.xuanwu.trial

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestType
import com.hjh_database.dungeon.DungeonRecord
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.WeatherType
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

class XuanwuTrialManager(private val plugin: Hjh_database) : Listener {

    companion object {
        const val PLAYER_TAG = "xuanwu_trial"

        private const val TRIAL_SECONDS = 15 * 60
        private const val MAX_PLAYERS = 3
        private const val MAX_CARGO = 3
        private const val REQUIRED_OFFERINGS = 9
        private const val INTERACTION_TICKS = 5 * 20
        private const val WIND_WARNING_TICKS = 3 * 20
        private const val WIND_HOLD_TICKS = 3 * 20
        private const val REQUIRED_STILL_TICKS = 3 * 20
        private const val BASE_WALK_SPEED_BLOCKS_PER_SECOND = 4.317
        private const val STILL_DISTANCE_PER_TICK = 0.05
        private val FACING_DOT_THRESHOLD = cos(Math.toRadians(40.0))
        private const val BGM_SOUND = "hjh:bgm_xuanwu"
        private const val BGM_LOOP_TICKS = 3925L
    }

    var isDungeonActive = false
        private set
    var isStarting = false
        private set

    private var world: World? = null
    private var dialogueTask: BukkitTask? = null
    private var mainTask: BukkitTask? = null
    private var bgmTask: BukkitTask? = null
    private var trialBar: BossBar? = null
    private var windBar: BossBar? = null
    private val stabilityBars = mutableMapOf<UUID, BossBar>()
    private val cargoBars = mutableMapOf<UUID, BossBar>()
    private val playerStates = mutableMapOf<UUID, PlayerState>()

    private var ending = false
    private var offeredCount = 0
    private var remainingTicks = TRIAL_SECONDS * 20
    private var nextWindTicks = 0
    private var windWarningTicks = 0
    private var windHoldTicks = 0
    private var activeWind: WindDirection? = null
    private var mainTick = 0

    private val triggerPoint = BlockPoint(-114, 5, -401)
    private val startLocation = TrialLocation(2207.37, 87.50, 958.62, 3510.34f, 3.00f)
    private val rewardLocation = TrialLocation(2092.37, 88.50, 1055.66, 5058.81f, 12.75f)
    private val offeringSource = Point(2188.0, 88.0, 958.0)
    private val offeringAltar = Point(2242.0, 92.0, 958.0)

    private val windPillars = mapOf(
        WindDirection.WEST to BlockPoint(2200, 91, 958),
        WindDirection.NORTH to BlockPoint(2207, 91, 952),
        WindDirection.EAST to BlockPoint(2214, 91, 958),
        WindDirection.SOUTH to BlockPoint(2207, 91, 964)
    )

    private val dialogues = listOf(
        "§f能得到玄武洞门锁的认可，诸位果然有过人之处。看来你们已准备好迎接§4§n玄武大人§f最后的考验了。",
        "§f玄武，司掌§e坚守与沉稳§f。此次试炼的核心只有一个——在狂风暴雨中，守住这座玄武庙。",
        "§f稍后这座海岛将掀起滔天风暴。届时你们的抗击退能力将毫无用武之地。不过玄武大人已施下庇佑之力：你们走得越慢，积攒的§e定势§f便越多；若能§c静止不动超过一秒§f，定势积攒的速度便会达到最快。当定势达到§b八成§f，玄武大人的力量便会护住你们，不被海风刮跑。",
        "§f留意身旁那§e四根柱子§f——它们用来辨别风向。哪边的§c火光燃起§f，便是哪边起风了。切记，不可慌乱跑动，应面朝起风的方向稳稳定住身形。若逆势而动，便会被海风无情卷入海中，试炼亦随之失败。",
        "§f待风停之后，速去§e西头石碑下§f将贡品搬运至§e玄武庙前的供奉处§f。每次最多可搬§c三个§f贡品，但背负越重，定势积攒便越慢，也越容易被海风刮跑。这其中如何取舍，需要你们自己掂量。",
        "§f向玄武大人供奉§e九份贡品§f，试炼便告完成，你们也将获得§4§n玄武§f的祝福。玄武大人欣赏脚踏实地之人，此次试炼只需在§c十五分钟§f内完成即可。",
        "§f准备好了吗？风暴将至，祝你们好运。"
    )

    @EventHandler
    fun onTriggerClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (BlockPoint.from(block.location) != triggerPoint || !block.type.name.endsWith("_BUTTON")) return

        event.isCancelled = true
        if (isDungeonActive || isStarting) {
            event.player.sendMessage("§c玄武试炼副本正在进行或准备中，无法重复开启！")
            return
        }

        val candidates = block.world.players
            .filter(::isStandingOnEntryWool)
            .map { EntryCandidate(it.uniqueId, it.name) }
        if (candidates.isEmpty()) return

        isStarting = true
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            var completedPlayerName: String? = null
            var databaseError: String? = null
            val eligibleIds = mutableListOf<UUID>()

            try {
                val dataSource = plugin.databaseManager.dataSource
                    ?: throw IllegalStateException("数据库连接池尚未初始化")
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT xuanwu FROM player_test WHERE uuid = ?").use { statement ->
                        for (candidate in candidates) {
                            statement.setString(1, candidate.uuid.toString())
                            var candidateCompleted = false
                            statement.executeQuery().use { result ->
                                candidateCompleted = result.next() && result.getInt("xuanwu") == 1
                            }
                            if (candidateCompleted) {
                                completedPlayerName = candidate.name
                                break
                            }
                            eligibleIds.add(candidate.uuid)
                        }
                    }
                }
            } catch (exception: Exception) {
                databaseError = exception.message ?: exception.javaClass.simpleName
                plugin.logger.warning("检查玄武试炼完成状态失败：$databaseError")
            }

            Bukkit.getScheduler().runTask(plugin, Runnable mainThread@{
                if (!isStarting) return@mainThread
                if (databaseError != null) {
                    event.player.sendMessage("§c玄武试炼数据读取失败，请稍后再试。")
                    isStarting = false
                    return@mainThread
                }
                if (completedPlayerName != null) {
                    event.player.sendMessage("§c玩家§e$completedPlayerName§c已完成玄武试炼！")
                    isStarting = false
                    return@mainThread
                }

                val selected = eligibleIds
                    .mapNotNull(Bukkit::getPlayer)
                    .filter { it.isOnline && it.world == block.world && isStandingOnEntryWool(it) }
                    .shuffled()
                    .take(MAX_PLAYERS)
                if (selected.isEmpty()) {
                    isStarting = false
                    return@mainThread
                }

                isStarting = false
                startDungeon(selected)
            })
        })
    }

    private fun startDungeon(players: List<Player>) {
        val currentWorld = players.firstOrNull()?.world ?: return
        isDungeonActive = true
        ending = false
        world = currentWorld
        offeredCount = 0
        remainingTicks = TRIAL_SECONDS * 20
        mainTick = 0
        activeWind = null
        windWarningTicks = 0
        windHoldTicks = 0
        nextWindTicks = randomWindDelay()
        playerStates.clear()
        clearWindFire()

        trialBar = Bukkit.createBossBar(
            "§b玄武试炼 §7| §f已供奉 §e0§7/§e$REQUIRED_OFFERINGS §7| §f剩余 15:00",
            BarColor.BLUE,
            BarStyle.SEGMENTED_10
        ).also { it.progress = 0.0 }

        players.forEach { player ->
            player.addScoreboardTag(PLAYER_TAG)
            player.teleport(startLocation.toLocation(currentWorld))

            playerStates[player.uniqueId] = PlayerState(lastLocation = player.location.clone())
            trialBar?.addPlayer(player)
            stabilityBars[player.uniqueId] = Bukkit.createBossBar(
                "§e定势 §f20%",
                BarColor.RED,
                BarStyle.SOLID
            ).also {
                it.progress = 0.20
                it.addPlayer(player)
            }
            cargoBars[player.uniqueId] = Bukkit.createBossBar(
                "§6背负贡品 §f0/$MAX_CARGO",
                BarColor.YELLOW,
                BarStyle.SEGMENTED_6
            ).also {
                it.progress = 0.0
                it.addPlayer(player)
            }
        }
        startBgmLoop()

        val dialogueDurations = intArrayOf(60, 60, 100, 100, 100, 60, 40)
        var dialogueIndex = 0
        var dialogueDelayTicks = 0
        dialogueTask = object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive || ending) {
                    cancel()
                    return
                }

                val trialPlayers = getTrialPlayers()
                if (trialPlayers.isEmpty()) {
                    endDungeon(win = false, killPlayers = false)
                    cancel()
                    return
                }

                if (dialogueDelayTicks > 0) {
                    dialogueDelayTicks--
                    return
                }

                if (dialogueIndex < dialogues.size) {
                    trialPlayers.forEach { player ->
                        player.sendMessage(dialogues[dialogueIndex])
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.55f)
                    }
                    dialogueDelayTicks = dialogueDurations[dialogueIndex] - 1
                    dialogueIndex++
                    return
                }

                trialPlayers.forEach { player ->
                    playerStates[player.uniqueId]?.apply {
                        lastLocation = player.location.clone()
                        speedSamples.clear()
                        stillTicks = 0
                    }
                }
                startMainLoop()
                cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun startBgmLoop() {
        bgmTask?.cancel()
        playBgmForTrialPlayers()
        bgmTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                if (!isDungeonActive || ending) return@Runnable
                playBgmForTrialPlayers()
            },
            BGM_LOOP_TICKS,
            BGM_LOOP_TICKS
        )
    }

    private fun playBgmForTrialPlayers() {
        getTrialPlayers().forEach { player ->
            player.playSound(player.location, BGM_SOUND, SoundCategory.RECORDS, 1.0f, 1.0f)
        }
    }

    private fun stopBgm(player: Player) {
        player.stopSound(BGM_SOUND, SoundCategory.RECORDS)
        player.stopSound(BGM_SOUND)
    }

    private fun startMainLoop() {
        getTrialPlayers().forEach { player ->
            player.setPlayerWeather(WeatherType.DOWNFALL)
            player.setPlayerTime(18_000L, false)
            player.playSound(player.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.65f, 0.75f)
        }

        mainTask = object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive || ending) {
                    cancel()
                    return
                }

                mainTick++
                remainingTicks--
                val players = getTrialPlayers()
                if (players.isEmpty()) {
                    endDungeon(win = false, killPlayers = false)
                    return
                }

                players.toList().forEach { player ->
                    if (!isInsideArena(player.location)) {
                        eliminatePlayer(player, "§c你离开了玄武试炼区域，试炼失败了。")
                        return@forEach
                    }
                    updatePlayerState(player)
                    updateOfferingInteraction(player)
                }

                if (!isDungeonActive || ending) return
                if (offeredCount >= REQUIRED_OFFERINGS) {
                    endDungeon(win = true, killPlayers = false)
                    return
                }

                updateWind()

                if (mainTick % 5 == 0) updateBossBars()
                if (mainTick % 100 == 0) playPrivateStormAmbience()

                if (remainingTicks <= 0) {
                    endDungeon(
                        win = false,
                        killPlayers = true,
                        failureMessage = "§c十五分钟已到，贡品未能备齐，玄武试炼失败了！"
                    )
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun updatePlayerState(player: Player) {
        val state = playerStates[player.uniqueId] ?: return
        val current = player.location
        val dx = current.x - state.lastLocation.x
        val dz = current.z - state.lastLocation.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)

        state.speedSamples.addLast(horizontalDistance)
        if (state.speedSamples.size > 20) state.speedSamples.removeFirst()

        if (horizontalDistance <= STILL_DISTANCE_PER_TICK) {
            state.stillTicks++
        } else {
            state.stillTicks = 0
        }

        val wind = activeWind
        if (wind != null && windHoldTicks > 0) {
            if (horizontalDistance <= STILL_DISTANCE_PER_TICK) {
                state.windStillTicks++
            } else {
                state.windStillTicks = 0
            }
            if (isFacingWind(player, wind)) {
                state.windFacingTicks++
            } else {
                state.windFacingTicks = 0
            }
        }

        val measuredSeconds = state.speedSamples.size / 20.0
        val measuredSpeed = if (measuredSeconds > 0.0) state.speedSamples.sum() / measuredSeconds else 0.0
        val cargoMultiplier = 1.0 - state.cargo * 0.10

        val ratePerSecond = when {
            state.stillTicks >= 20 -> 0.08 * cargoMultiplier
            measuredSpeed <= BASE_WALK_SPEED_BLOCKS_PER_SECOND -> 0.04 * cargoMultiplier
            else -> {
                val excessRatio = (
                    (measuredSpeed - BASE_WALK_SPEED_BLOCKS_PER_SECOND) /
                        BASE_WALK_SPEED_BLOCKS_PER_SECOND
                    ).coerceIn(0.0, 1.0)
                -(0.06 + 0.06 * excessRatio)
            }
        }

        state.lastRatePerSecond = ratePerSecond
        state.stability = (state.stability + ratePerSecond / 20.0).coerceIn(0.0, 1.0)
        state.lastLocation = current.clone()
    }

    private fun updateOfferingInteraction(player: Player) {
        val state = playerStates[player.uniqueId] ?: return
        val inSource = isWithin(player.location, offeringSource, 6.0)
        val inAltar = isWithin(player.location, offeringAltar, 3.0)

        if (inSource && state.cargo < MAX_CARGO) {
            state.acquireTicks++
            state.depositTicks = 0
            if (state.acquireTicks >= INTERACTION_TICKS) {
                state.acquireTicks = 0
                state.cargo++
                player.sendMessage("§e[玄武试炼] §f你取得了一份贡品，当前背负 §6${state.cargo}§7/§6$MAX_CARGO§f。")
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.8f)
            }
            return
        }

        if (inAltar && state.cargo > 0) {
            state.depositTicks++
            state.acquireTicks = 0
            if (state.depositTicks >= INTERACTION_TICKS) {
                state.depositTicks = 0
                val delivered = state.cargo
                state.cargo = 0
                offeredCount = (offeredCount + delivered).coerceAtMost(REQUIRED_OFFERINGS)
                getTrialPlayers().forEach {
                    it.sendMessage(
                        "§a[玄武试炼] §e${player.name}§f供奉了§e$delivered§f份贡品，当前进度 §e$offeredCount§7/§e$REQUIRED_OFFERINGS§f。"
                    )
                    it.playSound(it.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.1f)
                }
            }
            return
        }

        state.acquireTicks = 0
        state.depositTicks = 0
    }

    private fun updateWind() {
        val wind = activeWind
        if (wind == null) {
            nextWindTicks--
            if (nextWindTicks <= 0) beginWindWarning()
            return
        }

        if (windWarningTicks > 0) {
            windWarningTicks--
            if (windWarningTicks > 0) {
                updateWindBar(wind)
            } else {
                beginWindHold(wind)
            }
            return
        }

        if (windHoldTicks > 0) {
            windHoldTicks--
            updateWindHoldBar(wind)
            if (windHoldTicks % 20 == 0) {
                getTrialPlayers().forEach { player ->
                    player.playSound(player.location, Sound.ITEM_ELYTRA_FLYING, 0.85f, 0.50f)
                    val source = player.location.clone()
                        .add(wind.facingVector().multiply(6.0))
                        .add(0.0, 1.2, 0.0)
                    player.spawnParticle(Particle.CLOUD, source, 24, 1.5, 0.8, 1.5, 0.06)
                }
            }
            if (windHoldTicks > 0) return
        }

        windBar?.removeAll()
        windBar = null
        resolveWind(wind)
        clearWindFire()
        activeWind = null
        nextWindTicks = randomWindDelay()
    }

    private fun beginWindWarning() {
        val wind = WindDirection.entries.random()
        activeWind = wind
        windWarningTicks = WIND_WARNING_TICKS
        windHoldTicks = 0
        igniteWindPillar(wind)

        windBar?.removeAll()
        windBar = Bukkit.createBossBar(
            "§c${wind.chineseName}风来袭 §7| §f倒计时 3.0秒",
            BarColor.RED,
            BarStyle.SEGMENTED_6
        ).also { bar ->
            bar.progress = 1.0
            getTrialPlayers().forEach(bar::addPlayer)
        }

        getTrialPlayers().forEach { player ->
            player.sendMessage("§c${wind.chineseName}风将在三秒后起，快面向${wind.chineseName}方静止而立……")
            player.playSound(player.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 0.8f)
        }
    }

    private fun beginWindHold(wind: WindDirection) {
        windHoldTicks = WIND_HOLD_TICKS
        getTrialPlayers().forEach { player ->
            playerStates[player.uniqueId]?.apply {
                windStillTicks = 0
                windFacingTicks = 0
            }
            player.sendMessage("§c${wind.chineseName}风已至！面向${wind.chineseName}方，稳住身形三秒！")
            player.playSound(player.location, Sound.ITEM_ELYTRA_FLYING, 1.0f, 0.45f)
        }
        windBar?.color = BarColor.YELLOW
        windBar?.style = BarStyle.SEGMENTED_6
        windBar?.setTitle("§e${wind.chineseName}风正在呼啸 §7| §f坚守 3.0秒")
        windBar?.progress = 1.0
    }

    private fun updateWindBar(wind: WindDirection) {
        val seconds = windWarningTicks.coerceAtLeast(0) / 20.0
        windBar?.setTitle("§c${wind.chineseName}风来袭 §7| §f倒计时 %.1f秒".format(seconds))
        windBar?.progress = (windWarningTicks / WIND_WARNING_TICKS.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun updateWindHoldBar(wind: WindDirection) {
        val seconds = windHoldTicks.coerceAtLeast(0) / 20.0
        windBar?.setTitle("§e${wind.chineseName}风正在呼啸 §7| §f坚守 %.1f秒".format(seconds))
        windBar?.progress = (windHoldTicks / WIND_HOLD_TICKS.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun resolveWind(wind: WindDirection) {
        getTrialPlayers().toList().forEach { player ->
            val state = playerStates[player.uniqueId] ?: return@forEach
            if (state.windStillTicks < REQUIRED_STILL_TICKS) {
                eliminatePlayer(player, "§c${wind.chineseName}风已起后你未能连续静止三秒，定势被海风打断，试炼失败了……")
                return@forEach
            }
            if (state.windFacingTicks < WIND_HOLD_TICKS) {
                eliminatePlayer(player, "§c${wind.chineseName}风已起后，你没有持续面朝${wind.chineseName}方三秒，试炼失败了……")
                return@forEach
            }

            when {
                state.stability >= 0.80 -> {
                    state.stability = (state.stability - 0.60).coerceAtLeast(0.0)
                    player.sendMessage("§b玄武之力护住了你的身形，你稳稳承下了这阵海风。")
                    player.playSound(player.location, Sound.BLOCK_ANVIL_LAND, 0.55f, 0.65f)
                }

                state.stability >= 0.60 -> {
                    player.sendMessage("§e定势不稳，海风将你刮跑了……")
                    teleportToIslandEdge(player, wind)
                }

                else -> eliminatePlayer(player, "§c强大的海风将你刮飞到玄武庙外，试炼失败了……")
            }
        }

        if (playerStates.isEmpty() && isDungeonActive) {
            endDungeon(win = false, killPlayers = false)
        }
    }

    private fun updateBossBars() {
        val seconds = (remainingTicks.coerceAtLeast(0) + 19) / 20
        val minutesPart = seconds / 60
        val secondsPart = seconds % 60
        trialBar?.setTitle(
            "§b玄武试炼 §7| §f已供奉 §e$offeredCount§7/§e$REQUIRED_OFFERINGS §7| §f剩余 %02d:%02d"
                .format(minutesPart, secondsPart)
        )
        trialBar?.progress = (offeredCount / REQUIRED_OFFERINGS.toDouble()).coerceIn(0.0, 1.0)

        getTrialPlayers().forEach { player ->
            val state = playerStates[player.uniqueId] ?: return@forEach
            val percent = (state.stability * 100.0).toInt()
            val ratePercent = abs(state.lastRatePerSecond * 100.0)
            val rateText = if (state.lastRatePerSecond >= 0.0) {
                "§a+%.1f%%/秒".format(ratePercent)
            } else {
                "§c-%.1f%%/秒".format(ratePercent)
            }

            stabilityBars[player.uniqueId]?.let { bar ->
                bar.setTitle("§e定势 §f$percent% §7| $rateText")
                bar.progress = state.stability.coerceIn(0.0, 1.0)
                bar.color = when {
                    state.stability >= 0.80 -> BarColor.BLUE
                    state.stability >= 0.60 -> BarColor.YELLOW
                    else -> BarColor.RED
                }
            }

            cargoBars[player.uniqueId]?.let { bar ->
                val title = when {
                    state.acquireTicks > 0 && state.cargo < MAX_CARGO ->
                        "§6背负贡品 §f${state.cargo}/$MAX_CARGO §7| §e取得中 ${state.acquireTicks / 20}/5秒"

                    state.depositTicks > 0 && state.cargo > 0 ->
                        "§6背负贡品 §f${state.cargo}/$MAX_CARGO §7| §a供奉中 ${state.depositTicks / 20}/5秒"

                    else -> "§6背负贡品 §f${state.cargo}/$MAX_CARGO"
                }
                bar.setTitle(title)
                bar.progress = (state.cargo / MAX_CARGO.toDouble()).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun playPrivateStormAmbience() {
        getTrialPlayers().forEach { player ->
            player.playSound(player.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35f, 0.65f)
        }
    }

    private fun isFacingWind(player: Player, wind: WindDirection): Boolean {
        val facing = player.location.direction.setY(0.0)
        if (facing.lengthSquared() < 0.0001) return false
        facing.normalize()
        return facing.dot(wind.facingVector()) >= FACING_DOT_THRESHOLD
    }

    private fun teleportToIslandEdge(player: Player, wind: WindDirection) {
        val currentWorld = world ?: return
        val edge = wind.edgePoint
        val destination = findSafeLanding(currentWorld, edge.first, edge.second)
            ?: startLocation.toLocation(currentWorld)
        player.teleport(destination)
        playerStates[player.uniqueId]?.apply {
            lastLocation = player.location.clone()
            speedSamples.clear()
            stillTicks = 0
            windStillTicks = 0
            windFacingTicks = 0
            acquireTicks = 0
            depositTicks = 0
        }
    }

    private fun findSafeLanding(currentWorld: World, centerX: Int, centerZ: Int): Location? {
        for (radius in 0..4) {
            for (x in centerX - radius..centerX + radius) {
                for (z in centerZ - radius..centerZ + radius) {
                    if (radius > 0 && x != centerX - radius && x != centerX + radius &&
                        z != centerZ - radius && z != centerZ + radius
                    ) {
                        continue
                    }
                    for (y in 125 downTo 60) {
                        val ground = currentWorld.getBlockAt(x, y, z)
                        if (!ground.type.isSolid) continue
                        val feet = currentWorld.getBlockAt(x, y + 1, z)
                        val head = currentWorld.getBlockAt(x, y + 2, z)
                        if (feet.isPassable && head.isPassable) {
                            return Location(currentWorld, x + 0.5, y + 1.0, z + 0.5)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun igniteWindPillar(wind: WindDirection) {
        val currentWorld = world ?: return
        val pillar = windPillars[wind] ?: return
        currentWorld.getBlockAt(pillar.x, pillar.y + 1, pillar.z).setType(Material.FIRE, false)
    }

    private fun clearWindFire() {
        val currentWorld = world ?: return
        windPillars.values.forEach { pillar ->
            val fireBlock = currentWorld.getBlockAt(pillar.x, pillar.y + 1, pillar.z)
            if (fireBlock.type == Material.FIRE) fireBlock.setType(Material.AIR, false)
        }
    }

    private fun eliminatePlayer(player: Player, message: String) {
        if (!playerStates.containsKey(player.uniqueId)) return
        player.sendMessage(message)
        removeParticipant(player)
        if (!player.isDead && player.health > 0.0) player.health = 0.0
    }

    private fun removeParticipant(player: Player) {
        playerStates.remove(player.uniqueId)
        player.removeScoreboardTag(PLAYER_TAG)
        restorePlayerEnvironment(player)
        stopBgm(player)
        stabilityBars.remove(player.uniqueId)?.removeAll()
        cargoBars.remove(player.uniqueId)?.removeAll()
        trialBar?.removePlayer(player)
        windBar?.removePlayer(player)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return
        player.sendMessage("§c你在玄武试炼中死亡，试炼失败了。")
        removeParticipant(player)
        if (playerStates.isEmpty() && isDungeonActive && !ending) {
            endDungeon(win = false, killPlayers = false)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return
        removeParticipant(player)
        if (!player.isDead && player.health > 0.0) player.health = 0.0
        if (playerStates.isEmpty() && isDungeonActive && !ending) {
            endDungeon(win = false, killPlayers = false)
        }
    }

    private fun endDungeon(
        win: Boolean,
        killPlayers: Boolean,
        failureMessage: String? = null
    ) {
        if ((!isDungeonActive && !isStarting) || ending) return
        ending = true
        isDungeonActive = false
        isStarting = false

        dialogueTask?.cancel()
        mainTask?.cancel()
        bgmTask?.cancel()
        dialogueTask = null
        mainTask = null
        bgmTask = null
        clearWindFire()

        val participants = getTrialPlayers()
        participants.forEach { player ->
            player.removeScoreboardTag(PLAYER_TAG)
            restorePlayerEnvironment(player)
            stopBgm(player)
        }

        trialBar?.removeAll()
        trialBar = null
        windBar?.removeAll()
        windBar = null
        stabilityBars.values.forEach(BossBar::removeAll)
        stabilityBars.clear()
        cargoBars.values.forEach(BossBar::removeAll)
        cargoBars.clear()

        cleanupArenaMonsters()

        if (win) {
            val keyItem = plugin.resourceManager.getItem("mijingyaoshi")
            participants.forEach { player ->
                player.sendMessage("§a§l玄武分魂：§f风雨未能动摇诸位分毫。脚踏实地，守心如山——玄武试炼，通过了！")
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.85f)
                if (keyItem != null) {
                    player.inventory.addItem(keyItem.clone().also { it.amount = 1 })
                    player.sendMessage("§e[奖励] §a获得 §f秘境钥匙 §ax1！")
                } else {
                    plugin.logger.warning("未能找到 ID 为 mijingyaoshi 的物品，无法发放玄武试炼秘境钥匙奖励。")
                }
                updateDungeonRecord(player)
                updateDbStateAsync(player.uniqueId)
                player.teleport(rewardLocation.toLocation(player.world))
            }
        } else {
            participants.forEach { player ->
                if (failureMessage != null) player.sendMessage(failureMessage)
                if (killPlayers && !player.isDead && player.health > 0.0) {
                    player.health = 0.0
                }
            }
        }

        playerStates.clear()
        offeredCount = 0
        activeWind = null
        windWarningTicks = 0
        windHoldTicks = 0
        world = null
        ending = false
    }

    fun shutdown() {
        endDungeon(win = false, killPlayers = false)
    }

    private fun cleanupArenaMonsters() {
        val currentWorld = world ?: return
        currentWorld.entities.asSequence()
            .filterIsInstance<Monster>()
            .filter { isInsideArena(it.location) }
            .toList()
            .forEach { it.remove() }
    }

    private fun updateDungeonRecord(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val record = data.dungeonRecords.getOrPut("xuanwu_test") { DungeonRecord() }
        record.clears += 1
        record.availableOpens += 1
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.savePlayer(data)
        })
        player.sendMessage("§e[秘境] §a玄武试炼通关记录 +1，金宝箱可开箱次数 +1！")

        if (plugin.questManager.refreshAvailableQuests(player, QuestType.MAIN) > 0) {
            player.sendMessage("§a[系统] 玄武试炼已经通过，新的主线任务已解锁。")
        }
    }

    private fun updateDbStateAsync(uuid: UUID) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.databaseManager.dataSource?.connection?.use { connection ->
                    val sql = """
                        INSERT INTO player_test (uuid, player_name, xuanwu)
                        VALUES (?, ?, 1)
                        ON CONFLICT(uuid) DO UPDATE SET xuanwu = 1
                    """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, uuid.toString())
                        statement.setString(2, Bukkit.getOfflinePlayer(uuid).name ?: "Unknown")
                        statement.executeUpdate()
                    }
                }
            } catch (exception: Exception) {
                plugin.logger.warning("更新玄武试炼数据库状态失败：${exception.message}")
            }
        })
    }

    private fun getTrialPlayers(): List<Player> {
        return playerStates.keys.mapNotNull(Bukkit::getPlayer)
            .filter { it.isOnline && it.scoreboardTags.contains(PLAYER_TAG) }
    }

    private fun restorePlayerEnvironment(player: Player) {
        player.resetPlayerWeather()
        player.resetPlayerTime()
    }

    private fun isStandingOnEntryWool(player: Player): Boolean {
        val below = player.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
        return below.type == Material.BLUE_WOOL &&
            below.x in -115..-113 &&
            below.y == 3 &&
            below.z in -404..-402
    }

    private fun isInsideArena(location: Location): Boolean {
        return location.world == world &&
            location.x in 2073.81..2366.30 &&
            location.y in 6.0..214.50 &&
            location.z in 836.93..1073.60
    }

    private fun isWithin(location: Location, point: Point, radius: Double): Boolean {
        if (location.world != world) return false
        val dx = location.x - point.x
        val dy = location.y - point.y
        val dz = location.z - point.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    private fun randomWindDelay(): Int = Random.nextInt(8 * 20, 15 * 20 + 1)

    private data class PlayerState(
        var stability: Double = 0.20,
        var cargo: Int = 0,
        var acquireTicks: Int = 0,
        var depositTicks: Int = 0,
        var stillTicks: Int = 0,
        var windStillTicks: Int = 0,
        var windFacingTicks: Int = 0,
        var lastRatePerSecond: Double = 0.0,
        var lastLocation: Location,
        val speedSamples: ArrayDeque<Double> = ArrayDeque()
    )

    private data class EntryCandidate(val uuid: UUID, val name: String)

    private data class Point(val x: Double, val y: Double, val z: Double)

    private data class TrialLocation(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float
    ) {
        fun toLocation(world: World): Location = Location(world, x, y, z, yaw, pitch)
    }

    private data class BlockPoint(val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(location: Location): BlockPoint {
                return BlockPoint(floor(location.x).toInt(), floor(location.y).toInt(), floor(location.z).toInt())
            }
        }
    }

    private enum class WindDirection(
        val chineseName: String,
        private val facingX: Double,
        private val facingZ: Double,
        val edgePoint: Pair<Int, Int>
    ) {
        WEST("西", -1.0, 0.0, 2250 to 958),
        NORTH("北", 0.0, -1.0, 2207 to 987),
        EAST("东", 1.0, 0.0, 2177 to 958),
        SOUTH("南", 0.0, 1.0, 2207 to 930);

        fun facingVector(): Vector = Vector(facingX, 0.0, facingZ)
    }
}
