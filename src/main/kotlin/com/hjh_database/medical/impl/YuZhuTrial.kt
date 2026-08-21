package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
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
import java.time.Duration
import kotlin.math.min
import kotlin.math.roundToInt

class YuZhuTrial(
    private val plugin: Hjh_database,
    override val player: Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "yuzhu"

    private enum class Phase {
        STORY,
        WAIT_NOTE_BLOCK,
        PREPARE,
        DEMO,
        INPUT,
        ENDED
    }

    private enum class Pad {
        LEFT,
        RIGHT
    }

    private data class MusicNote(
        val beat: Int,
        val pad: Pad,
        val pitch: Float
    )

    private data class Score(
        val totalBeats: Int,
        val beatTicks: Int,
        val notes: List<MusicNote>
    )

    private var phase = Phase.STORY
    private var phaseTick = 0
    private var inputNoteIndex = 0
    private var correctNotes = 0
    private var extraNotes = 0
    private var currentScore: Score? = null
    private var lastNoteInteractMillis = 0L

    private val progressBar: BossBar = Bukkit.createBossBar(
        "§d雨竹的医术试炼 §7| §f演奏进度：0%",
        BarColor.PURPLE,
        BarStyle.SOLID
    )

    private val startLocation = Location(player.world, -330.75, 97.00, -445.85, 7292.17f, 4.35f)
    private val returnLocation = Location(player.world, -318.53, 115.00, -423.51)
    private val leftNoteBlock = Location(player.world, -336.0, 98.0, -446.0)
    private val rightNoteBlock = Location(player.world, -336.0, 98.0, -447.0)
    private val measureCount = 14
    private val demoBeatCount = 16
    private val earlyInputToleranceTicks = 2
    private val lateInputToleranceTicks = 3
    private val requiredAccuracy = 0.80
    private val scalePitches = mapOf(
        "low5" to 0.5297f,
        "low6" to 0.5946f,
        "1" to 0.7071f,
        "2" to 0.7937f,
        "3" to 0.8909f,
        "5" to 1.0595f,
        "6" to 1.1892f,
        "high1" to 1.4142f
    )

    private val storyMessages = listOf(
        "§f小友，行医辛苦了。终日奔波劳碌，何不暂且歇下，与我一同§e鸣音奏乐§f，放松片刻？",
        "§f说来惭愧，你方才想必也瞧见了——那些§c山魅§f将我这庙宇糟蹋得不成样子。我平日的灵力全用来抵御他们，早已是杯水车薪，这才让庙破败至此。",
        "§f不过我倒发现了一桩趣事：这些山魅对§e音律§f极为敏感。某些§e特定节奏的音调§f，竟能让他们暂且收敛戾气，减缓破坏的举动。",
        "§f四职之中，唯有§d医师§f心细如发。对音律一道，想必你也有自己的见解。你瞧，我在此处放了两个§e音符盒§f——稍后我先示范开头一段，你再从头照着谱子将整支曲子复刻出来。左右两边的音符盒音调不同，待会儿看谱子的标记：§c红色敲右边§f，§9蓝色敲左边§f。",
        "§f务必跟上我的节奏，曲终至少要有§e八成准确§f方能奏效。偶有错音尚可挽回，但空拍时切莫乱敲，否则同样会扰乱旋律，让山魅愈发狂躁。",
        "§f这支曲子共有十四个小节。我会先奏一段开头，你循着谱子将整支曲子完整复刻，便是帮了我天大的忙。事成之后，我有一门§d医术§f相赠，对你日后的修行定有助益。",
        "§f准备好了，便随意挑一个音符盒§e敲一下§f，我们就开始。"
    )

    override fun start() {
        player.teleport(startLocation)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        val phaseAtStart = phase
        when (phase) {
            Phase.STORY -> runStory()
            Phase.PREPARE -> runPrepare()
            Phase.DEMO -> runDemo()
            Phase.INPUT -> runInput()
            else -> Unit
        }
        if (phase == phaseAtStart) phaseTick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.NOTE_BLOCK) return

        val pad = when {
            sameBlock(block.location, leftNoteBlock) -> Pad.LEFT
            sameBlock(block.location, rightNoteBlock) -> Pad.RIGHT
            else -> return
        }
        event.isCancelled = true

        val now = System.currentTimeMillis()
        if (now - lastNoteInteractMillis < 100) return
        lastNoteInteractMillis = now

        when (phase) {
            Phase.WAIT_NOTE_BLOCK -> {
                progressBar.addPlayer(player)
                updateProgressBar()
                startPerformancePreparation()
            }
            Phase.INPUT -> handlePlayerNote(pad)
            else -> Unit
        }
    }

    private fun runStory() {
        if (phaseTick % 60 != 0) return
        val messageIndex = phaseTick / 60
        if (messageIndex < storyMessages.size) {
            player.sendMessage("§a§l雨竹 §f: ${storyMessages[messageIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            return
        }

        setPhase(Phase.WAIT_NOTE_BLOCK)
        player.sendMessage("§e[医术试炼] §f随意敲响左、右任意一个音符盒开始试炼。")
    }

    private fun startPerformancePreparation() {
        if (currentScore == null) currentScore = generateScore()
        inputNoteIndex = 0
        correctNotes = 0
        extraNotes = 0
        setPhase(Phase.PREPARE)
    }

    private fun runPrepare() {
        val score = currentScore ?: return
        showScoreTitle(score, null, "§e准备")
        val seconds = ((60 - phaseTick + 19) / 20).coerceIn(1, 3)
        progressBar.progress = ((60 - phaseTick) / 60.0).coerceIn(0.0, 1.0)
        progressBar.setTitle(
            "§e整曲即将开始 §7| §f倒计时：§c$seconds 秒"
        )

        if (phaseTick == 0 || phaseTick == 20 || phaseTick == 40) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 0.8f + (3 - seconds) * 0.15f)
        }
        if (phaseTick >= 60) {
            setPhase(Phase.DEMO)
            updateProgressBar()
        }
    }

    private fun runDemo() {
        val score = currentScore ?: return
        if (phaseTick >= demoBeatCount * score.beatTicks) {
            setPhase(Phase.INPUT)
            phaseTick = -40
            inputNoteIndex = 0
            correctNotes = 0
            extraNotes = 0
            updateProgressBar()
            return
        }

        val beat = phaseTick / score.beatTicks
        showScoreTitle(score, beat, "§d雨竹示范")

        if (phaseTick % score.beatTicks == 0) {
            score.notes.firstOrNull { note -> note.beat == beat }?.let { note -> playPad(note.pad, note.pitch) }
        }

        val demoProgress = ((phaseTick + 1).toDouble() / (demoBeatCount * score.beatTicks))
            .coerceIn(0.0, 1.0)
        progressBar.progress = demoProgress
        progressBar.setTitle(
            "§d雨竹示范 §7| §f开头旋律：§e${(demoProgress * 100).roundToInt()}%"
        )
    }

    private fun runInput() {
        val score = currentScore ?: return
        if (phaseTick < 0) {
            showScoreTitle(score, null, "§a轮到你")
            return
        }

        val beat = phaseTick / score.beatTicks
        showScoreTitle(score, beat, "§a轮到你")
        judgeExpiredNotes(score)
    }

    private fun handlePlayerNote(pad: Pad) {
        val score = currentScore ?: return
        if (phaseTick < 0) {
            recordExtraNote()
            return
        }
        if (judgeExpiredNotes(score)) return
        val expected = score.notes.getOrNull(inputNoteIndex) ?: return
        val windowStart = expected.beat * score.beatTicks - earlyInputToleranceTicks
        val windowEnd = (expected.beat + 1) * score.beatTicks - 1 + lateInputToleranceTicks
        if (phaseTick !in windowStart..windowEnd) {
            recordExtraNote()
            return
        }

        if (pad == expected.pad) {
            correctNotes++
            playPad(pad, expected.pitch)
        } else {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.1f)
        }
        inputNoteIndex++
        updateProgressBar()
        if (inputNoteIndex >= score.notes.size) finishPerformance(score)
    }

    private fun recordExtraNote() {
        extraNotes++
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.55f, 1.25f)
        updateProgressBar()
    }

    private fun judgeExpiredNotes(score: Score): Boolean {
        var changed = false
        while (inputNoteIndex < score.notes.size) {
            val expected = score.notes[inputNoteIndex]
            val windowEnd = (expected.beat + 1) * score.beatTicks - 1 + lateInputToleranceTicks
            if (phaseTick <= windowEnd) break
            inputNoteIndex++
            changed = true
        }
        if (changed) updateProgressBar()

        if (inputNoteIndex < score.notes.size) return false
        finishPerformance(score)
        return true
    }

    private fun finishPerformance(score: Score) {
        if (phase != Phase.INPUT) return
        val accuracy = correctNotes.toDouble() / (score.notes.size + extraNotes)
        val accuracyPercent = (accuracy * 100).toInt()
        if (accuracy >= requiredAccuracy) {
            player.sendMessage("§a演奏完成，准确率：§e$accuracyPercent%")
            win()
        } else {
            player.sendMessage("§c演奏准确率仅有 $accuracyPercent%，未能安抚躁动的山魅。")
            fail()
        }
    }

    private fun generateScore(): Score {
        val notes = mutableListOf<MusicNote>()

        fun melody(measure: Int, slot: Int, degree: String) {
            notes += MusicNote(measure * 8 + slot, Pad.RIGHT, scalePitches.getValue(degree))
        }

        melody(0, 0, "3")
        melody(0, 2, "3")
        melody(0, 3, "5")
        melody(0, 4, "6")
        melody(0, 5, "high1")
        melody(0, 6, "high1")
        melody(0, 7, "6")
        melody(1, 0, "5")
        melody(1, 2, "5")
        melody(1, 3, "6")
        melody(1, 4, "5")

        melody(2, 0, "3")
        melody(2, 2, "3")
        melody(2, 3, "5")
        melody(2, 4, "6")
        melody(2, 5, "high1")
        melody(2, 6, "high1")
        melody(2, 7, "6")
        melody(3, 0, "5")
        melody(3, 2, "5")
        melody(3, 3, "6")
        melody(3, 4, "5")

        melody(4, 0, "5")
        melody(4, 2, "5")
        melody(4, 4, "5")
        melody(4, 6, "3")
        melody(4, 7, "5")
        melody(5, 0, "6")
        melody(5, 2, "6")
        melody(5, 4, "5")

        melody(6, 0, "3")
        melody(6, 2, "2")
        melody(6, 3, "3")
        melody(6, 4, "5")
        melody(6, 6, "3")
        melody(6, 7, "2")
        melody(7, 0, "1")
        melody(7, 2, "1")
        melody(7, 3, "2")
        melody(7, 4, "1")

        melody(8, 0, "3")
        melody(8, 1, "2")
        melody(8, 2, "1")
        melody(8, 3, "3")
        melody(8, 4, "2")
        melody(8, 7, "3")
        melody(9, 0, "5")
        melody(9, 2, "6")
        melody(9, 3, "high1")
        melody(9, 4, "5")

        melody(10, 0, "2")
        melody(10, 2, "3")
        melody(10, 3, "5")
        melody(10, 4, "2")
        melody(10, 5, "3")
        melody(10, 6, "1")
        melody(10, 7, "low6")
        melody(11, 0, "low5")
        melody(11, 4, "low6")
        melody(11, 6, "1")

        melody(12, 0, "2")
        melody(12, 3, "3")
        melody(12, 4, "1")
        melody(12, 5, "2")
        melody(12, 6, "1")
        melody(12, 7, "low6")
        melody(13, 0, "low5")

        for (measure in 0 until measureCount) {
            val usedSlots = notes.asSequence()
                .filter { note -> note.beat / 8 == measure }
                .map { note -> note.beat % 8 }
                .toSet()
            val drumSlot = listOf(4, 1, 6, 5, 7, 3, 2)
                .firstOrNull { slot -> slot !in usedSlots }
                ?: continue
            notes += MusicNote(measure * 8 + drumSlot, Pad.LEFT, 0.9f)
        }

        return Score(
            totalBeats = measureCount * 8,
            beatTicks = 7,
            notes = notes.sortedBy { note -> note.beat }
        )
    }

    private fun showScoreTitle(score: Score, cursorBeat: Int?, mode: String) {
        val noteByBeat = score.notes.associateBy { note -> note.beat }
        val visibleBeat = (cursorBeat ?: 0).coerceIn(0, score.totalBeats - 1)
        val measure = visibleBeat / 8
        val measureStart = measure * 8
        val measureEnd = min(measureStart + 8, score.totalBeats)
        val symbols = buildString {
            for (beat in measureStart until measureEnd) {
                val note = noteByBeat[beat]
                val symbol = when {
                    cursorBeat != null && beat < cursorBeat -> when (note?.pad) {
                        null -> "§8·"
                        else -> "§8◆"
                    }
                    note?.pad == Pad.LEFT -> "§9◆"
                    note?.pad == Pad.RIGHT -> "§c◆"
                    else -> "§8·"
                }
                if (cursorBeat == beat) {
                    append("§e§l【").append(symbol).append("§e§l】")
                } else {
                    append(symbol)
                }
            }
        }
        val serializer = LegacyComponentSerializer.legacySection()
        player.showTitle(
            Title.title(
                serializer.deserialize("§6${measure + 1}/$measureCount §7[$symbols§7]"),
                serializer.deserialize(mode),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(300), Duration.ZERO)
            )
        )
    }

    private fun playPad(pad: Pad, pitch: Float) {
        when (pad) {
            Pad.LEFT -> player.playSound(leftNoteBlock, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 0.9f)
            Pad.RIGHT -> player.playSound(rightNoteBlock, Sound.BLOCK_NOTE_BLOCK_HARP, 1f, pitch)
        }
    }

    private fun updateProgressBar() {
        val score = currentScore
        val progress = if (score == null || score.notes.isEmpty()) {
            0.0
        } else {
            (inputNoteIndex.toDouble() / score.notes.size).coerceIn(0.0, 1.0)
        }
        val judgedActions = inputNoteIndex + extraNotes
        val accuracy = if (judgedActions == 0) {
            "§7--"
        } else {
            "§a${(correctNotes.toDouble() / judgedActions * 100).roundToInt()}%"
        }
        progressBar.progress = progress
        progressBar.setTitle(
            "§d雨竹的医术试炼 §7| §f进度：§e${(progress * 100).roundToInt()}% §7| §f准确率：$accuracy"
        )
    }

    private fun setPhase(next: Phase) {
        phase = next
        phaseTick = 0
    }

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world && first.blockX == second.blockX && first.blockY == second.blockY && first.blockZ == second.blockZ
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        player.teleport(returnLocation)
        player.sendMessage("§a雨竹 §f: §a愿你往后行医济世，心有清音，手有春风；纵经风雨，也总有天光相随。")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 200)
            data.learnMedicalSkill("jiangtianguang")
            data.completedMedicalTrials.add(trialId)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { connection ->
                        plugin.databaseManager.saveMedicalData(connection, data)
                        plugin.databaseManager.saveCompletedMedicalTrials(connection, data)
                    }
                } catch (exception: Exception) {
                    plugin.logger.severe("保存雨竹医术试炼完成记录失败: ${exception.message}")
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
        if (player.isOnline && !player.isDead) player.teleport(returnLocation)
        if (shouldMessage) player.sendMessage("§c雨竹的医术试炼失败！")
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        progressBar.removeAll()
        if (player.isOnline) player.clearTitle()
    }
}
