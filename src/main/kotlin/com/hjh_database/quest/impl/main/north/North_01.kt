package com.hjh_database.quest.impl.main.north

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class North_01 : QuestBase("main_north_1", "[主线]洄游祭", QuestType.MAIN, 19) {

    override val raceLimit: Int? = null
    override val requiredCompletedQuestIds = setOf("main_west_4")

    override val description = listOf(
        "§7你已取得青龙、朱雀与白虎的祝福。",
        "§7前往玄水湖泊，寻找最后的玄武祝福。",
        "§7先与水族族长-水渊了解当地情况。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptShuiyuan = listOf(
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f你好啊，异乡人。我是水族的族长，水渊。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f……没听说过水族？那倒也不奇怪。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f我们水族人口稀少，平日生活在玄水湖底，很少与外界往来。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f族人天生能够在水中呼吸，并以§4§n玄武神§f的名义，世代守护着这片水域。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f你能够来到这里，也是恰逢一年一度的§2§o洄游祭§f。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f我们族地的入口不会一直开启，每年只有短短几日能够与外界相通。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f而§2§o洄游祭§f，正是为了庆祝鲑鱼群归来而举行的祭典。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f既然赶上了，也欢迎你留下来共襄盛举。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f不过……你身上的气息，似乎有些不同。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f青龙、朱雀，还有白虎……",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f看来，你已经得到了三位§4§n圣兽§f的祝福。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f如此说来，你来到玄水湖泊，应该不只是为了参加祭典吧？",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f你是想进入§2§o玄武神庙§f，取得最后的§4§n玄武祝福§f？",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f嗯……这件事倒有些难办。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f神庙并不在寻常水域，而是位于一处由§4§n玄武神§f力量守护的秘境之中。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f平日里，就连本族族人也不会随意进入。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f只有举行祭典，或是祭司需要进行占卜时，神庙的入口才会开启。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f况且按照族内的规矩，外乡人在祭典期间也不得擅自进入神庙。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f不过，你既然已经得到三位圣兽的认可，或许不能按照寻常规矩看待。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f这样吧，你去找族里的祭司§4§n洛禾§f。",
        "§e[${StoryNpcs.SHUIZUCUNZHANG.displayName}§e] §f神庙与祭典一向由他负责，他或许有办法让你获得进入神庙的资格。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e水族族长-水渊 §c对话")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHUIZUCUNZHANG.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= scriptShuiyuan.size) return true

        player.sendMessage(scriptShuiyuan[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        talkProgress[player.uniqueId] = index + 1

        if (index == scriptShuiyuan.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val plugin = Hjh_database.instance
            plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
        }
        return true
    }

    override fun giveReward(player: Player) {
        val plugin = Hjh_database.instance
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("  §e[提示] §f寻找水族祭司-洛禾")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
