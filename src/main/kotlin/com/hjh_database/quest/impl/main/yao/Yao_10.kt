package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Yao_10 : QuestBase("main_yao_10", "[妖族主线]蓬莱旧踪", QuestType.MAIN, 24) {

    override val raceLimit = 4
    override val requiredCompletedQuestIds = setOf("main_north_5")

    override val description = listOf(
        "§7四圣兽祝福已经全部汇聚于你一身。",
        "§7返回镇妖塔顶，将结界的情况告知§e妖族大长老-蚩尤§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f四道祝福都拿到了？",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f……好。看来§4§n四兽§f确实还活着。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f§e结界§f已经弱成这样，按理说对§f§n我族§f是件好事。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f可我不喜欢把族人的命押在§c“按理说”§f三个字上。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f§4§n四兽§f只是离开了§e神庙§f，不是死了。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f一旦它们回来，那道压了我们这么多年的§e结界§f随时都可能重新升起来。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f先弄清楚它们到底在哪，出了什么事。",
        "§7（蚩尤借助你身上的四圣兽祝福感应片刻……）",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f……§e北边§f。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f如果我没记错，那里是§2§o蓬莱§f。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f很多年前我们和人仙两族打起来的时候，有一批族人就是往§e北边§f逃的。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f去找找他们。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f活了这么多年的人，总该知道点我们不知道的事。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回镇妖塔顶，与 §e妖族大长老-蚩尤 §c对话")
        else -> listOf("§a已从大长老处得知蓬莱可能留有妖族旧人的踪迹")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_DAZHANGLAO.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in elderScript.indices) return true

        player.sendMessage(elderScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == elderScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.YAO_DAZHANGLAO.id && currentProgress == 0

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
