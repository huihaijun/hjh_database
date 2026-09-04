package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Zhan_10 : QuestBase("main_zhan_10", "[战神族主线]蓬莱接应", QuestType.MAIN, 24) {

    override val raceLimit = 3
    override val requiredCompletedQuestIds = setOf("main_north_5")

    override val description = listOf(
        "§7四圣兽祝福已经全部汇聚于你一身。",
        "§7返回皇城月老庙，向§e蚀日§7汇报此行结果。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val shiRiScript = listOf(
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f战友！你总算回来了！",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f§e四道祝福§f都拿齐了？好。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f这几天皇城里的动静越来越大。人族和仙族的人已经先一步往北去了，目的地都是§2§o蓬莱§f。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f看来他们已经找到了§4§n四圣兽§f的藏身处。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f我让§e左焰§f先过去探路了。你立刻赶去§2§o蓬莱§f和他会合。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f记住，我们等这个机会§c太久了§f。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f但在确认情况之前，§c别急着动手§f。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回皇城月老庙，与 §e蚀日 §c对话")
        else -> listOf("§a已受命前往蓬莱与左焰会合")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_SHIRI.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in shiRiScript.indices) return true

        player.sendMessage(shiRiScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == shiRiScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.ZHAN_SHIRI.id && currentProgress == 0

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
