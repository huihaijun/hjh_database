package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.random.Random

class Side_Warlock_BackflowBook : QuestBase("side_warlock_backflow_book", "[支线]灵力逆生元素之术？", QuestType.SIDE, 1), Listener {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val pendingTrials = mutableSetOf<UUID>()
    private val trials = HashMap<UUID, TrialSession>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val totalRounds = 10
    private val requiredCorrectRounds = 8
    private val roundDurationTicks = 60

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return data.job == 2 && data.lv >= 15
    }

    override val description = listOf(
        "§7溯源真人发现了可以用灵力逆转生成元素的方法？",
        "§7快去皇城的后花园找他一探究竟！"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往皇城后花园寻找 §e溯源真人")
            1 -> listOf("§c敲响五行阵旁的钟，开始五行共鸣试炼")
            2 -> listOf("§c回去找 §e溯源真人 §c重新调整气息")
            3 -> listOf("§c回去找 §e溯源真人 §c领取奖励")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != "suyuanzhenren") return false

        when (currentProgress) {
            0 -> {
                playDialogue(player, "intro", introScript) {
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            1 -> {
                player.sendMessage("§e[§a§l溯源真人§e] §f去吧，准备好了，便去敲一下阵眼旁那口§c钟§f。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                return true
            }
            2 -> {
                playDialogue(player, "fail", failScript) {
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            3 -> {
                playDialogue(player, "success", successScript) {
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }

        return true
    }

    @EventHandler
    fun onBellInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!isTrialBell(block.location)) return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses[id] != QuestStatus.IN_PROGRESS || data.questProgress[id] != 1) return

        event.isCancelled = true
        if (trials.containsKey(player.uniqueId) || pendingTrials.contains(player.uniqueId)) {
            player.sendMessage("§c[五行试炼] §7试炼已经开始，凝神看阵眼！")
            return
        }

        pendingTrials.add(player.uniqueId)
        block.world.playSound(block.location, Sound.BLOCK_BELL_USE, 1f, 1f)
        player.sendMessage("§a[五行试炼] §7钟声已响，2秒后开始。")
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingTrials.remove(player.uniqueId)
            val currentData = plugin.playerManager.getPlayerData(player)
            if (player.isOnline && currentData?.questStatuses?.get(id) == QuestStatus.IN_PROGRESS && currentData.questProgress[id] == 1) {
                startTrial(player)
            }
        }, 40L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingTrials.remove(event.player.uniqueId)
        endTrial(event.player.uniqueId, saveProgress = false)
    }

    private fun startTrial(player: Player) {
        val bar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10)
        bar.addPlayer(player)

        val focusLoc = trialFocusLocation(player)
        val session = TrialSession(player.uniqueId, bar, focusLoc, focusLoc.block.blockData)
        trials[player.uniqueId] = session

        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.25f)
        rollPrompt(player, session)

        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || trials[player.uniqueId] !== session) {
                    endTrial(player.uniqueId, saveProgress = false)
                    cancel()
                    return
                }

                session.elapsedTicks += 1
                session.roundTicks += 1

                val roundRemaining = (roundDurationTicks - session.roundTicks).coerceAtLeast(0)
                bar.progress = ((totalRounds - session.completedRounds) / totalRounds.toDouble()).coerceIn(0.0, 1.0)
                bar.setTitle("§d五行共鸣 §7| §f第 ${session.completedRounds + 1}/$totalRounds 轮 ${"%.1f".format(roundRemaining / 20.0)}s §7| §a正确 ${session.correctRounds}/$requiredCorrectRounds §7| §e${session.promptText()}")
                spawnFocusParticles(session)

                if (session.roundTicks >= roundDurationTicks) {
                    judgeRound(player, session)

                    if (session.completedRounds >= totalRounds) {
                        val passed = session.correctRounds >= requiredCorrectRounds
                        endTrial(player.uniqueId, saveProgress = true, passed = passed)
                        cancel()
                        return
                    }

                    rollPrompt(player, session)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun judgeRound(player: Player, session: TrialSession) {
        session.completedRounds++
        if (isHoldingRequiredElement(player, session)) {
            session.correctRounds++
            player.sendMessage("§a[五行试炼] §7第 ${session.completedRounds} 轮共鸣成功！正确 §e${session.correctRounds}§7/$requiredCorrectRounds")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f)
        } else {
            player.sendMessage("§c[五行试炼] §7第 ${session.completedRounds} 轮共鸣偏差。正确 §e${session.correctRounds}§7/$requiredCorrectRounds")
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f)
        }
    }

    private fun rollPrompt(player: Player, session: TrialSession) {
        session.element = FiveElement.entries.random()
        session.relation = if (Random.nextBoolean()) Relation.GENERATE else Relation.RESTRAIN
        session.roundTicks = 0

        player.sendBlockChange(session.focusLoc, session.element.material.createBlockData())
        player.sendMessage("§e[§a§l溯源真人§e] §f${session.relation.commandText}！阵眼显化：${session.element.displayName}§f。")

        val sound = if (session.relation == Relation.GENERATE) Sound.BLOCK_AMETHYST_BLOCK_CHIME else Sound.ENTITY_ILLUSIONER_CAST_SPELL
        player.playSound(player.location, sound, 1f, if (session.relation == Relation.GENERATE) 1.4f else 0.8f)
        session.focusLoc.world?.spawnParticle(Particle.DUST, session.focusLoc.clone().add(0.5, 0.5, 0.5), 24, 0.35, 0.35, 0.35, Particle.DustOptions(session.element.color, 1.4f))
    }

    private fun isHoldingRequiredElement(player: Player, session: TrialSession): Boolean {
        return getResourceId(player.inventory.itemInMainHand) == session.requiredElement().resourceId
    }

    private fun spawnFocusParticles(session: TrialSession) {
        session.focusLoc.world?.spawnParticle(
            Particle.DUST,
            session.focusLoc.clone().add(0.5, 0.55, 0.5),
            3,
            0.22,
            0.22,
            0.22,
            Particle.DustOptions(session.element.color, 1.0f)
        )
    }

    private fun endTrial(uuid: UUID, saveProgress: Boolean, passed: Boolean = false) {
        val session = trials.remove(uuid) ?: return
        session.task?.cancel()
        session.bar.removeAll()

        val player = Bukkit.getPlayer(uuid)
        if (player != null) {
            player.sendBlockChange(session.focusLoc, session.originalBlockData)
        }

        if (!saveProgress || player == null) return

        if (passed) {
            player.sendMessage("§a[五行试炼] §7试炼结束，正确 §e${session.correctRounds}§7/$totalRounds，阵眼完成足够共鸣。回去找溯源真人吧。")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
            plugin.questManager.updateProgress(player, id, 3)
        } else {
            player.sendMessage("§c[五行试炼] §7试炼结束，正确 §e${session.correctRounds}§7/$totalRounds，还差一点。回去找溯源真人吧。")
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)
            plugin.questManager.updateProgress(player, id, 2)
        }
    }

    private fun playDialogue(player: Player, dialogueId: String, scripts: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player.uniqueId, dialogueId)
        val index = talkProgress.getOrDefault(key, 0)
        if (index < scripts.size) {
            player.sendMessage("§e[§a§l溯源真人§e] ${scripts[index]}")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[key] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(key)
                onFinish()
            }
        }
    }

    private fun dialogueKey(uuid: UUID, dialogueId: String): UUID {
        return UUID.nameUUIDFromBytes((uuid.toString() + ":$dialogueId").toByteArray())
    }

    private fun giveResource(player: Player, id: String, amount: Int) {
        val item = plugin.resourceManager.getItem(id) ?: ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] $id") }
        }
        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§e获得 ${item.itemMeta?.displayName ?: id} §fx$amount")
    }

    private fun getResourceId(item: ItemStack?): String? {
        val meta = item?.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    private fun isTrialBell(loc: Location): Boolean {
        return loc.blockX == 193 && loc.blockY == 49 && loc.blockZ == -278
    }

    private fun trialFocusLocation(player: Player): Location {
        return Location(player.world, 193.0, 51.0, -278.0)
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f[回流仪]制作书 x1")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 100)
        giveResource(player, "huiliuyizhizuoshu", 1)
    }

    private data class TrialSession(
        val playerId: UUID,
        val bar: org.bukkit.boss.BossBar,
        val focusLoc: Location,
        val originalBlockData: BlockData,
        var element: FiveElement = FiveElement.METAL,
        var relation: Relation = Relation.GENERATE,
        var elapsedTicks: Int = 0,
        var roundTicks: Int = 0,
        var completedRounds: Int = 0,
        var correctRounds: Int = 0,
        var task: BukkitTask? = null
    ) {
        fun requiredElement(): FiveElement {
            return when (relation) {
                Relation.GENERATE -> element.generatedBy
                Relation.RESTRAIN -> element.restrainedBy
            }
        }

        fun promptText(): String = "${element.shortName}${relation.commandText}"
    }

    private enum class Relation(val commandText: String) {
        GENERATE("生"),
        RESTRAIN("克")
    }

    private enum class FiveElement(
        val resourceId: String,
        val displayName: String,
        val shortName: String,
        val material: Material,
        val color: Color
    ) {
        METAL("metal", "§e金元素", "金", Material.YELLOW_WOOL, Color.YELLOW),
        WOOD("wood", "§a木元素", "木", Material.LIME_WOOL, Color.LIME),
        WATER("water", "§b水元素", "水", Material.BLUE_WOOL, Color.AQUA),
        FIRE("fire", "§c火元素", "火", Material.RED_WOOL, Color.RED),
        EARTH("earth", "§6土元素", "土", Material.BROWN_WOOL, Color.fromRGB(130, 82, 38));

        val generatedBy: FiveElement
            get() = when (this) {
                METAL -> EARTH
                WOOD -> WATER
                WATER -> METAL
                FIRE -> WOOD
                EARTH -> FIRE
            }

        val restrainedBy: FiveElement
            get() = when (this) {
                METAL -> FIRE
                WOOD -> METAL
                WATER -> EARTH
                FIRE -> WATER
                EARTH -> WOOD
            }
    }

    private val introScript = listOf(
        "§f小术士，你也是来这后花园赏花的？",
        "§f你问我怎么一眼瞧出你是术士？呵呵，咱们§b术士§f身上，自有一股其他职业学不来的优雅。而你眉宇之间隐隐流转着§e五行灵力§f，老夫更是一望便知。",
        "§f你可知，§e元素§f与§d灵力§f，本是一体两面，相生相成。可惜如今多数医师只会单向从元素中汲取灵力，却鲜有人能逆而行之——将灵力反转为元素。一旦摸透这个门道，对咱们术士而言，无异于一步登天的造化。",
        "§f你看，这便是老夫近来研制的§6回流仪§f，它可从施法者体内汲取灵力，逆转五行，凭空凝练出元素来。你与我有缘，若你能通过一个小小考验，这制作之法，老夫便传给你。",
        "§f瞧见旁边这座简易§b五行阵§f了吗？待会儿阵眼之上会浮现一枚晶块，代表§c金§f、§a木§f、§b水§f、§c火§f、§e土§f其中之一。与此同时，老夫会喊出“§c生§f”或“§c克§f”的口令。",
        "§f我若喊“§c生§f”，你便速速手持能§e相生§f此晶块的元素；我若喊“§c克§f”，你便速速手持能§e相克§f此晶块的元素。记住，每轮留给你的反应时间只有§c三秒§f，若手中元素拿错，阵眼便无法与你共鸣。",
        "§f试炼一共§c十轮§f，只要正确率达到§c八成§f以上，让阵眼完成足够多的共鸣，这制作书，便是你的了。",
        "§f五行相生相克——金生水、水生木、木生火、火生土、土生金；金克木、木克土、土克水、水克火、火克金。这不用老夫多教你吧？",
        "§f去吧，准备好了，便去敲一下阵眼旁那口§c钟§f。"
    )

    private val failScript = listOf(
        "§f无妨，五行之道本就千变万化。一次不成，便试千百次，继续去敲钟，胜利近在咫尺！"
    )

    private val successScript = listOf(
        "§f不错不错！反应迅捷，五行于心，这§6回流仪§f的制作书，归你了！"
    )
}
