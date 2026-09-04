package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Shen_11 : QuestBase("main_shen_11", "[神族主线]圣兽旧事", QuestType.MAIN, 25) {

    override val raceLimit = 0
    override val requiredCompletedQuestIds = setOf("main_shen_10")

    override val description = listOf(
        "§7神族长老已经先行前往蓬莱岛。",
        "§7在蓬莱与长老会合，了解四圣兽不为人知的来历。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f你来了。东西都准备好了？",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f圣山里面现在是什么情况，连我也无法确定。进去以后小心点。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f……你似乎很在意§4§n四圣兽§f的来历？",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f也罢。反正到了这一步，有些事让你知道也无妨。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f世人总觉得四圣兽是§f§n我族§f创造出来的。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f其实不是。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f很多年前，它们是由当时的§f§n仙族§f盟主，以“贡礼”的名义送到§f§n我族§f手中的。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f四圣兽力量强大，又能镇守四方，所以我族后来一直借助它们维持世界秩序。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f可现在四只圣兽同时出现异常……",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f我自然先去问了§f§n仙族§f。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f可现任盟主只说，“那是上一代留下的东西，我们无能为力”。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f是真的无能为力……还是有什么东西，连他们自己都不愿意提，那就不好说了。",
        "§e[${StoryNpcs.PENGLAI_ZHANGLAO.displayName}§e] §f走吧。到了§2§o圣山§f，答案自然会自己出现。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往蓬莱岛，与 §e长老 §c会合")
        else -> listOf("§a已从长老处得知四圣兽的旧事")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.PENGLAI_ZHANGLAO.id || currentProgress != 0) return false

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
        npcId == StoryNpcs.PENGLAI_ZHANGLAO.id && currentProgress == 0

    override fun giveReward(player: Player) {
        val pills = plugin.resourceManager.getItem(TWIN_PILL_ID)
        if (pills == null) {
            plugin.logger.warning("神族第十一章缺少奖励物品配置: $TWIN_PILL_ID")
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
