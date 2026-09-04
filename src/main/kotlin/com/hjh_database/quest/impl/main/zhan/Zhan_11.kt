package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Zhan_11 : QuestBase("main_zhan_11", "[战神族主线]圣山伏击", QuestType.MAIN, 25) {

    override val raceLimit = 3
    override val requiredCompletedQuestIds = setOf("main_zhan_10")

    override val description = listOf(
        "§7左焰已先行赶往蓬莱探查四圣兽的藏身处。",
        "§7前往蓬莱岛，与§e左焰§7会合。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val zuoYanScript = listOf(
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f你总算来了。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f已经确认过了，§4§n四兽§f就在旁边的§2§o圣山§f里。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f这地方是§e蓬莱§f世代守着的地界，据说也是§4§n盘古§f殒落之处。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f你身上有§e四道祝福§f，圣山的§e结界§f应该拦不住你。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f……等了这么多年。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f当年踏平我族联盟据点的四只畜生，今天总算让咱们摸到§c藏身之处§f了。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f先进去把情况摸实。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f若它们当真只是缩在里头养伤……",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f东西拿好。这次，§c别再让它们跑了§f！"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往蓬莱岛，与 §e左焰 §c会合")
        else -> listOf("§a已与左焰确认四圣兽藏身于圣山")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.PENGLAI_ZUOYAN.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in zuoYanScript.indices) return true

        player.sendMessage(zuoYanScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == zuoYanScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.PENGLAI_ZUOYAN.id && currentProgress == 0

    override fun giveReward(player: Player) {
        val pills = plugin.resourceManager.getItem(TWIN_PILL_ID)
        if (pills == null) {
            plugin.logger.warning("战神族第十一章缺少奖励物品配置: $TWIN_PILL_ID")
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
