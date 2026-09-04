package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Yao_11 : QuestBase("main_yao_11", "[妖族主线]蓬莱故人", QuestType.MAIN, 25) {

    override val raceLimit = 4
    override val requiredCompletedQuestIds = setOf("main_yao_10")

    override val description = listOf(
        "§7大长老提到，当年曾有一批妖族逃往北方。",
        "§7前往蓬莱岛，寻找可能知晓旧事的族人。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val moYuScript = listOf(
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f你真的是从外面来的§2§o妖族§f？",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f……太好了。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f我们当年逃进§e蓬莱§f以后，就再也没能出去。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f这么多年，连外面的族人究竟还剩多少都不知道。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f……",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f所以很多村镇还是没能保下来吗。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f至少§e叶灵谷§f还在……这样就好。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f你说你是来找§4§n四圣兽§f的？",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f百年前它们受了重伤，一路退进旁边的§2§o圣山§f。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f这些年来，一直靠留在各地的力量勉强撑着§e结界§f。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f照理说，§e结界§f弱到现在，它们早该醒了。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f可§2§o圣山§f一直没有任何动静。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f你既然已经得到§e四兽认可§f，就亲自进去看看吧。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f不过小心点。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f能让四只§4§n圣兽§f一百年都没有出来的地方……",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f里面恐怕没有我们想得那么简单。",
        "§e[${StoryNpcs.PENGLAI_MOYU.displayName}§e] §f若是调查出什么来，就回去向§a大长老§f报告一下，务必小心！"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往蓬莱岛，寻找 §e墨雨 §c了解百年前的旧事")
        else -> listOf("§a已从墨雨处得知四圣兽退入圣山的旧事")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.PENGLAI_MOYU.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in moYuScript.indices) return true

        player.sendMessage(moYuScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == moYuScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.PENGLAI_MOYU.id && currentProgress == 0

    override fun giveReward(player: Player) {
        val pills = plugin.resourceManager.getItem(TWIN_PILL_ID)
        if (pills == null) {
            plugin.logger.warning("妖族第十一章缺少奖励物品配置: $TWIN_PILL_ID")
            player.sendMessage("§c[错误] 无法发放高级双生丹，请联系管理员。")
        } else {
            pills.amount = TWIN_PILL_AMOUNT
            val leftovers = player.inventory.addItem(pills)
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            if (leftovers.isNotEmpty()) {
                player.sendMessage("§c[提示] 背包空间不足，部分双生丹已掉落在脚下！")
            }
        }

        plugin.playerManager.giveExp(player, 500)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +500")
        player.sendMessage("  §e[奖励] §f双生丹[高级] x15")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private companion object {
        const val TWIN_PILL_ID = "shuangshengdan2"
        const val TWIN_PILL_AMOUNT = 15
    }
}
