package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

class Yao_08 : QuestBase("main_yao_8", "[妖族主线]青龙神庙", QuestType.MAIN, 8) {

    override val raceLimit = 4

    override val description = listOf(
        "§7按照华夭与大长老的指引，",
        "§7前往 §a龙鳞森林§7 深处的 §e青龙神庙§7，",
        "§7寻找 §e青龙分魂§7 打探结界减弱的消息。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptQingLong = listOf(
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f你好，此地乃§2§o青龙神庙§f，吾乃§4§n青龙大人§f的一缕分魂。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f结界减弱？果真如此么……难怪近来神庙中的龙气确是大不如前了。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f你既为此而来，便往神庙深处走一趟吧。青龙大人司掌§e智慧与灵识§f，留下的试炼考的并非蛮力，而是你对§e这方天地的了解§f。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f神庙二层有青龙大人的雕像，试炼便在其中。通过之后，便能感应到青龙的力量了。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f去吧，待你完成试炼，我会在§e出口§f等候你的消息。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往 §a龙鳞森林§c深处，寻找 §e青龙分魂")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.QINGLONGFENHUN.id || currentProgress != 0) return false

        playDialogue(player, scriptQingLong) {
            val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return@playDialogue
            Hjh_database.instance.questManager.completeQuest(player, data, this)
        }
        return true
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +320")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 320)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
