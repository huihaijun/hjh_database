package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Lightable
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitRunnable
import kotlin.random.Random

class BaiGuJingTrial(
    private val plugin: Hjh_database,
    override val player: Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "baigujing"

    private enum class Phase {
        STORY,
        WAIT_BELL,
        RUNNING,
        ENDED
    }

    private data class LampSnapshot(
        val location: Location,
        val blockData: BlockData
    )

    private var phase = Phase.STORY
    private var tick = 0
    private var ticksLeft = TRIAL_TICKS
    private var moves = 0
    private var hintsUsed = 0
    private var hintColumn = -1
    private var hintRow = -1
    private var hintHighlightTicks = 0
    private var lastLampClickMillis = 0L
    private var lastBellClickMillis = 0L
    private val lampStates = Array(BOARD_SIZE) { BooleanArray(BOARD_SIZE) }
    private val solutionPresses = Array(BOARD_SIZE) { BooleanArray(BOARD_SIZE) }
    private val lampSnapshots = mutableListOf<LampSnapshot>()

    private val startLocation =
        Location(player.world, 108.30, 53.00, -501.04, 0f, 0f)
    private val exitLocation =
        Location(player.world, 158.70, 54.00, -545.60, 0f, 0f)
    private val bellLocation =
        Location(player.world, 105.0, 54.0, -501.0)

    private val countdownBar: BossBar = Bukkit.createBossBar(
        "§d墓园白骨精的医术试炼",
        BarColor.PURPLE,
        BarStyle.SOLID
    )

    private val storyMessages = listOf(
        "§f哟，活人身上的药香……隔着半座墓园都闻得见。小医师，莫盯着本座这副骨头架子瞧，今日请你来，可不是为了拆你的骨。",
        "§f你脚下这片墓园，早些年还是一处乱葬岗。边关兵乱、疫病横行时，死者一车一车运来，草席一卷便埋进土里，连姓名都无人过问。",
        "§f后来人族在此立碑修坟，便自称功德圆满。哼，碑上刻的是太平盛世，泥土下面却尽是连一句告别都没等到的冤魂。",
        "§f本座虽是妖，却也知道亡者该有归处。这些年守在墓园，不是贪图他们那点阴气，而是压着这满地§c怨念§f，免得他们迷了心智，变成只知害人的厉鬼。",
        "§f可怨气能压，心结却化不开。每逢月色转暗，坟中的亡灵便循着生前最后一点执念回来，哭的哭，喊的喊，搅得整片墓园不得安宁。",
        "§f墙上这二十五盏§e魂灯§f，是上一任守墓人以灵脉相连布成的§6引渡阵§f。每亮一盏，便代表还有亡魂被执念困住，不肯踏上归途。",
        "§f医者辨阴阳、察生死，看的不只是活人的血肉，也该看得见魂魄郁结之处。本座要借你这份心细，将魂灯一盏不剩地全部熄灭。",
        "§f不过这些灯彼此牵连。你右键触碰其中一盏，它自己与§e上、下、左、右§f相邻的魂灯都会同时明灭；位于边角的，自然只牵动身旁几盏。",
        "§f莫见哪盏亮便只顾着点哪盏。你这一手落下，旧火虽灭，新火也可能重燃。先看清整面灯阵，再决定从何处下手。",
        "§f阵法一旦发动，怨气只会给你§c五分钟§f。五分钟内让二十五盏魂灯尽数归暗，亡灵便能循阴路离去；若拖得太久，它们又会被怨念拽回来。",
        "§f若一时看不清阵势，试炼中再敲那口§e钟§f，本座可替你指出一盏该动的魂灯。不过本座也不能时时插手，一局至多替你指路§c四次§f。",
        "§f办成此事，本座便将一门§d魂灵游§f赠你。它本是妖族观魂行气之术，落在你这医师手里，倒比给那些只会挥刀的人合适。听完之后，敲响身旁的§e钟§f，我们便开始。"
    )

    override fun start() {
        player.teleport(startLocation)
        if (!captureLampWall() || bellLocation.block.type != Material.BELL) {
            player.sendMessage("§c引渡阵的二十五盏红石灯或启阵钟并未完整布置，试炼无法开始。")
            fail()
            return
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        when (phase) {
            Phase.STORY -> runStory()
            Phase.WAIT_BELL -> Unit
            Phase.RUNNING -> runTrial()
            Phase.ENDED -> Unit
        }
        tick++
    }

    @EventHandler
    fun onLampInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val indices = lampIndices(block.location) ?: return

        event.isCancelled = true
        if (phase != Phase.RUNNING) {
            player.sendMessage("§7先听白骨精把引渡之法说完。")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLampClickMillis < CLICK_DEBOUNCE_MILLIS) return
        lastLampClickMillis = now

        toggleCross(indices.first, indices.second)
        solutionPresses[indices.first][indices.second] =
            !solutionPresses[indices.first][indices.second]
        if (indices.first == hintColumn && indices.second == hintRow) {
            clearHintHighlight()
        }
        moves++
        player.playSound(
            block.location.clone().add(0.5, 0.5, 0.5),
            Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
            0.75f,
            1.35f
        )
        updateActionBar()

        if (countLitLamps() == 0) {
            win()
        }
    }

    @EventHandler
    fun onBellInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!sameBlock(block.location, bellLocation) || block.type != Material.BELL) return

        event.isCancelled = true
        val now = System.currentTimeMillis()
        if (now - lastBellClickMillis < BELL_DEBOUNCE_MILLIS) return
        lastBellClickMillis = now
        player.playSound(bellLocation, Sound.BLOCK_BELL_USE, 1f, 0.9f)

        when (phase) {
            Phase.STORY -> player.sendMessage("§7先听白骨精把话说完，再敲钟启阵。")
            Phase.WAIT_BELL -> beginTrial()
            Phase.RUNNING -> giveHint()
            Phase.ENDED -> Unit
        }
    }

    private fun runStory() {
        if (tick % STORY_MESSAGE_TICKS != 0) return
        val messageIndex = tick / STORY_MESSAGE_TICKS
        if (messageIndex < storyMessages.size) {
            player.sendMessage("§d§l墓园-白骨精 §f: ${storyMessages[messageIndex]}")
            player.playSound(player.location, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.8f, 0.8f)
            return
        }

        phase = Phase.WAIT_BELL
        tick = 0
        player.sendMessage("§e[医术试炼] §f准备妥当后，敲响身旁的钟发动引渡阵。")
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f)
    }

    private fun beginTrial() {
        phase = Phase.RUNNING
        tick = 0
        ticksLeft = TRIAL_TICKS
        moves = 0
        hintsUsed = 0
        clearHintHighlight()
        generateSolvableBoard()
        countdownBar.addPlayer(player)
        updateCountdownBar()
        updateActionBar()
        player.sendMessage("§d[医术试炼] §f引渡阵已经发动。右键魂灯，令二十五盏灯全部熄灭！")
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.7f)
    }

    private fun runTrial() {
        ticksLeft--
        if (ticksLeft <= 0) {
            player.sendMessage("§c五分钟已过，墓园怨气重新缠住了亡灵……")
            fail()
            return
        }
        if (tick % 10 == 0) updateCountdownBar()
        if (tick % 5 == 0) updateActionBar()
        if (tick % 20 == 0) {
            spawnSoulParticles()
        }
        updateHintHighlight()
    }

    private fun captureLampWall(): Boolean {
        lampSnapshots.clear()
        for (x in MIN_X..MAX_X) {
            for (y in MIN_Y..MAX_Y) {
                val block = player.world.getBlockAt(x, y, BOARD_Z)
                if (block.type != Material.REDSTONE_LAMP || block.blockData !is Lightable) {
                    lampSnapshots.clear()
                    return false
                }
                lampSnapshots += LampSnapshot(block.location.clone(), block.blockData.clone())
            }
        }
        return lampSnapshots.size == BOARD_SIZE * BOARD_SIZE
    }

    private fun generateSolvableBoard() {
        repeat(100) {
            clearGeneratedBoard()
            var chosenPresses = 0
            for (column in 0 until BOARD_SIZE) {
                for (row in 0 until BOARD_SIZE) {
                    if (Random.nextDouble() < 0.45) {
                        solutionPresses[column][row] = true
                        toggleStateCross(column, row)
                        chosenPresses++
                    }
                }
            }
            val lit = countLitLamps()
            if (chosenPresses in 8..17 && lit in 8..18) {
                applyBoardState()
                return
            }
        }

        clearGeneratedBoard()
        FALLBACK_PRESSES.forEach { (column, row) ->
            solutionPresses[column][row] = true
            toggleStateCross(column, row)
        }
        applyBoardState()
    }

    private fun clearGeneratedBoard() {
        lampStates.forEach { column -> column.fill(false) }
        solutionPresses.forEach { column -> column.fill(false) }
    }

    private fun toggleCross(column: Int, row: Int) {
        toggleStateCross(column, row)
        applyBoardState()
    }

    private fun toggleStateCross(column: Int, row: Int) {
        CROSS_OFFSETS.forEach { (dx, dy) ->
            val targetColumn = column + dx
            val targetRow = row + dy
            if (targetColumn in 0 until BOARD_SIZE && targetRow in 0 until BOARD_SIZE) {
                lampStates[targetColumn][targetRow] = !lampStates[targetColumn][targetRow]
            }
        }
    }

    private fun applyBoardState() {
        for (column in 0 until BOARD_SIZE) {
            for (row in 0 until BOARD_SIZE) {
                val block = player.world.getBlockAt(MIN_X + column, MIN_Y + row, BOARD_Z)
                val data = block.blockData as? Lightable ?: continue
                data.isLit = lampStates[column][row]
                block.setBlockData(data, false)
            }
        }
    }

    private fun lampIndices(location: Location): Pair<Int, Int>? {
        if (location.world != player.world ||
            location.blockZ != BOARD_Z ||
            location.blockX !in MIN_X..MAX_X ||
            location.blockY !in MIN_Y..MAX_Y
        ) {
            return null
        }
        return (location.blockX - MIN_X) to (location.blockY - MIN_Y)
    }

    private fun sameBlock(first: Location, second: Location): Boolean =
        first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ

    private fun countLitLamps(): Int =
        lampStates.sumOf { column -> column.count { lit -> lit } }

    private fun updateCountdownBar() {
        val secondsLeft = ((ticksLeft + 19) / 20).coerceAtLeast(0)
        val minutes = secondsLeft / 60
        val seconds = secondsLeft % 60
        countdownBar.progress = (ticksLeft.toDouble() / TRIAL_TICKS).coerceIn(0.0, 1.0)
        countdownBar.setTitle(
            "§d墓园白骨精的医术试炼 §7- §f%02d:%02d".format(minutes, seconds)
        )
    }

    private fun updateActionBar() {
        if (!player.isOnline || phase != Phase.RUNNING) return
        val lit = countLitLamps()
        player.sendActionBar(
            LegacyComponentSerializer.legacySection().deserialize(
                "§e尚燃魂灯：§f$lit/25 §7| §b已落子：§f$moves"
                    + " §7| §d剩余提示：§f${MAX_HINTS - hintsUsed}"
            )
        )
    }

    private fun giveHint() {
        if (hintsUsed >= MAX_HINTS) {
            player.sendMessage("§c本座已经替你指过四次路，余下的阵势须由你自己看破。")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f)
            return
        }

        val candidates = buildList {
            for (column in 0 until BOARD_SIZE) {
                for (row in 0 until BOARD_SIZE) {
                    if (solutionPresses[column][row]) add(column to row)
                }
            }
        }
        val target = candidates.randomOrNull()
        if (target == null) {
            if (countLitLamps() == 0) win()
            return
        }

        hintsUsed++
        hintColumn = target.first
        hintRow = target.second
        hintHighlightTicks = HINT_HIGHLIGHT_TICKS
        player.sendMessage(
            "§d§l墓园-白骨精 §f: §e看仔细了，那盏泛起魂光的灯，便是此刻可以落子之处。§7（剩余提示：${MAX_HINTS - hintsUsed}次）"
        )
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.35f)
        spawnHintParticles()
        updateActionBar()
    }

    private fun updateHintHighlight() {
        if (hintHighlightTicks <= 0) return
        hintHighlightTicks--
        if (hintHighlightTicks % 4 == 0) spawnHintParticles()
    }

    private fun spawnHintParticles() {
        if (hintColumn !in 0 until BOARD_SIZE || hintRow !in 0 until BOARD_SIZE) return
        val center = Location(
            player.world,
            MIN_X + hintColumn + 0.5,
            MIN_Y + hintRow + 0.5,
            BOARD_Z - 0.18
        )
        player.world.spawnParticle(Particle.END_ROD, center, 7, 0.28, 0.28, 0.04, 0.01)
        player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 4, 0.2, 0.2, 0.03, 0.005)
    }

    private fun clearHintHighlight() {
        hintColumn = -1
        hintRow = -1
        hintHighlightTicks = 0
    }

    private fun spawnSoulParticles() {
        val litLocations = buildList {
            for (column in 0 until BOARD_SIZE) {
                for (row in 0 until BOARD_SIZE) {
                    if (lampStates[column][row]) {
                        add(
                            Location(
                                player.world,
                                MIN_X + column + 0.5,
                                MIN_Y + row + 0.5,
                                BOARD_Z - 0.15
                            )
                        )
                    }
                }
            }
        }
        litLocations.randomOrNull()?.let { location ->
            player.world.spawnParticle(Particle.SOUL, location, 2, 0.12, 0.12, 0.05, 0.01)
        }
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        player.teleport(exitLocation)
        player.sendMessage(
            "§d§l墓园-白骨精 §f: §a呵……二十五盏魂灯尽数归暗，哭声也停了。小医师，你替这些无名亡魂补上了迟来多年的送别。§d魂灵游§a的法门，本座说到做到，传你便是。"
        )
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f)
        player.world.spawnParticle(
            Particle.SOUL,
            player.location.clone().add(0.0, 1.0, 0.0),
            35,
            0.7,
            0.8,
            0.7,
            0.03
        )

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 200)
            data.learnMedicalSkill("hunlingyou")
            data.completedMedicalTrials.add(trialId)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { connection ->
                        plugin.databaseManager.saveMedicalData(connection, data)
                        plugin.databaseManager.saveCompletedMedicalTrials(connection, data)
                    }
                } catch (exception: Exception) {
                    plugin.logger.severe("保存白骨精医术试炼完成记录失败: ${exception.message}")
                }
            })
        }

        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        cleanUp()
        if (player.isOnline && !player.isDead) player.teleport(exitLocation)
        if (shouldMessage) {
            player.sendMessage("§c墓园-白骨精的医术试炼失败！")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        countdownBar.removeAll()
        clearHintHighlight()
        restoreLampWall()
        if (player.isOnline) {
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""))
        }
    }

    private fun restoreLampWall() {
        lampSnapshots.forEach { snapshot ->
            snapshot.location.block.setBlockData(snapshot.blockData.clone(), false)
        }
        lampSnapshots.clear()
    }

    companion object {
        private const val BOARD_SIZE = 5
        private const val MIN_X = 106
        private const val MAX_X = 110
        private const val MIN_Y = 53
        private const val MAX_Y = 57
        private const val BOARD_Z = -498
        private const val STORY_MESSAGE_TICKS = 5 * 20
        private const val TRIAL_TICKS = 5 * 60 * 20
        private const val CLICK_DEBOUNCE_MILLIS = 120L
        private const val BELL_DEBOUNCE_MILLIS = 500L
        private const val MAX_HINTS = 4
        private const val HINT_HIGHLIGHT_TICKS = 4 * 20

        private val CROSS_OFFSETS = listOf(
            0 to 0,
            -1 to 0,
            1 to 0,
            0 to -1,
            0 to 1
        )
        private val FALLBACK_PRESSES = listOf(
            0 to 0,
            0 to 2,
            0 to 4,
            2 to 4,
            1 to 2,
            3 to 0,
            4 to 0,
            4 to 1,
            4 to 3,
            4 to 4
        )
    }
}
