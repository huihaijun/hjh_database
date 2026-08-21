package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Xian_07 : QuestBase("main_xian_7", "[仙族主线]森林异变", QuestType.MAIN, 7) {

    override val raceLimit = 1

    override val description = listOf(
        "§7羽罗请你调查龙鳞之森魔物暴起的根源。",
        "§7前往§e龙须镇§7，与祭司§e楚兰§7接洽。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val chulanScript = listOf(
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f哦……上仙大人，您总算是到了。这林中的情形，怕是比您路上所见还要糟上几分。",
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f不知您来时可有察觉，林子里冒出了许多§c古怪的孽物§f，见着活物便咬，凶狠异常。镇上已有好几位§c猎户失踪§f，余下的人白日都不敢独自出镇了。",
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f我这几日思来想去，此事恐怕与§e森林深处祭坛的结界§f衰弱脱不开干系。我巫女一族世代侍奉这片森林的守护神——§4§n青龙§f。如今青龙大人虽已久离神庙，但留下的力量仍勉力维持着结界运转。只是近来，这股力量正§c一点一点地流失§f。",
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f结界一弱，原本被镇于林中的§c黑暗之气§f便寻隙涌出，那些孽物多半也是因此而生。",
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f上仙大人，我知道自己身为凡躯，法力低微，做不了什么。但若是上仙肯代为前往§e青龙祭坛§f一探究竟，或许能寻到破局的线索。传说在危难之际，代表神明的§e分魂§f会于祭坛现世，给予指引。",
        "§e[${StoryNpcs.XIAN_CHULAN.displayName}§e] §f一切，便仰仗上仙大人了。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往龙须镇，与祭司 §e楚兰 §c对话")
        else -> listOf("§a已了解龙鳞之森与青龙祭坛的异变")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_CHULAN.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in chulanScript.indices) return true

        player.sendMessage(chulanScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == chulanScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 280)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +280")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
