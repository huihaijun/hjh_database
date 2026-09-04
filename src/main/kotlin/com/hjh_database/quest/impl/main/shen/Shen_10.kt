package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Shen_10 : QuestBase("main_shen_10", "[神族主线]蓬莱会合", QuestType.MAIN, 24) {

    override val raceLimit = 0
    override val requiredCompletedQuestIds = setOf("main_north_5")

    override val description = listOf(
        "§7四圣兽的祝福已经全部汇聚于你一身。",
        "§7返回神族长老大殿，向§e长老§7禀报此行结果。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f……四道祝福都集齐了？",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f很好。看来§4§n四圣兽§f虽然出了问题，留下的力量却还认得你。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f先前连§f§n我族§f都被§2§o圣山§f拒之门外，这件事已经很不正常了。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f我会先去一趟§2§o蓬莱§f。你把东西准备齐全，随后到那里与我会合。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这一次，我要亲眼看看§4§n四圣兽§f究竟出了什么问题。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f怎么去蓬莱？你连这都不知道啊……唉。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f§e皇城西门§f出去，向北走，有个§e渡口§f。让船家载你到海外的§e蓬莱岛§f便是。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f事不宜迟。东西备齐了就动身，到蓬莱等我，别拖后腿。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回长老大殿，与 §e长老 §c对话")
        else -> listOf("§a已与长老约定在蓬莱岛会合")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZHANGLAO.id || currentProgress != 0) return false

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
        npcId == StoryNpcs.SHEN_ZHANGLAO.id && currentProgress == 0

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
