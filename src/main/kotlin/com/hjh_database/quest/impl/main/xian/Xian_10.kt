package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Xian_10 : QuestBase("main_xian_10", "[仙族主线]圣兽踪迹", QuestType.MAIN, 24) {

    override val raceLimit = 1
    override val requiredCompletedQuestIds = setOf("main_north_5")

    override val description = listOf(
        "§7四圣兽的祝福已经全部汇聚于你一身。",
        "§7前往皇宫东侧的护国殿，寻找§e护国法师-法海§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val fahaiScript = listOf(
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f上仙，你身上这些§e五彩斑斓的饰品§f……若贫道所感不差，便是§e四圣兽祝福§f凝结的产物吧。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f它们身上确有四圣兽的气息。这么多年了，贫道总算是等到了一线眉目。且稍候，容我施法一探。",
        "§f〔法海周身金光渐盛，如经文流转，凝而不散〕",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f……居然——居然如此之近？也难怪贫道从未察觉，原来是有§e结界§f护着。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f§4§n四圣兽§f沉睡之处，就在北方的§e圣山§f之中。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f此去务须小心。那座山由一群§e避世之人§f守护，他们聚居之地，名唤§e蓬莱§f。北边的§e港口§f有船可往。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f但有一桩须谨记——这§e圣兽祝福§f，必须由上仙§c亲手集齐§f。若这些饰品是自他人之手夺来，蓬莱的结界可不会认你，去了也是白搭。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f圣兽沉眠之地，吉凶难料，内中究竟潜伏着什么，贫道也说不上来。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f贫道早年云游四方时，曾有幸结识一位居住蓬莱的仙友，名唤§e凯歌§f。上仙若上得蓬莱，便去寻他，就说是§e法海§f托你去的。以我与他昔日的交情，他应当能予上仙几分照应。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f若无他事，便早些动身吧。§c时辰不等人§f。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往皇宫东侧护国殿，与 §e护国法师-法海 §c对话")
        else -> listOf("§a已得知四圣兽沉睡于北方圣山")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.REN_FAHAI.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in fahaiScript.indices) return true

        player.sendMessage(fahaiScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == fahaiScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.REN_FAHAI.id && currentProgress == 0

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 400)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +400")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f北方港口有船可前往蓬莱岛")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
