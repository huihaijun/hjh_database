package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Yao_12 : QuestBase("main_yao_12", "[妖族主线]内丹疑云", QuestType.MAIN, 26) {

    override val raceLimit = 4
    override val requiredCompletedQuestIds = setOf("main_yao_11")

    override val description = listOf(
        "§7通过圣山秘境，查明四圣兽百年未归的缘由。",
        "§7返回镇妖塔顶，将圣山见闻告知§e妖族大长老-蚩尤§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f回来了？",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f圣山那边到底出了什么事，从头给我说一遍。",
        "§7（你将经过告诉了蚩尤…………）",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f……§4§n四圣兽§f体内，居然还藏着这种东西。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f难怪§4§n百年前那场仗§f之后，它们宁可躲进§e圣山§f，也一直没再出来。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f不过现在看来，§4§n四圣兽§f本身恐怕也只是§c别人手里的一件工具§f。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f这件事我会继续查。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f你先回去歇着，能从§e圣山§f活着回来，已经够你吹很久了。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f这些拿着，这些可是塔底下华夭那丫头好不容易拿到的，赏你了。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f对了，你这一身伤也别硬扛。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f回§e叶灵谷§f以后去§e丹药铺§f拿点药。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f用咱们自己的丹，别再去人族那个破炼丹房拿那些乱七八糟的丹药。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f他们的方子里动不动就塞§c我族的内丹§f，看着就晦气。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f人仙二族一个个自诩正道，炼丹却离不得我族的内丹。这“良方”究竟是哪个混账传下来的？若有一日老子出了这破塔，头一件事就是去查个明白。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f哼，用别的药引就不行么？偏要拿我族人的性命入药……总觉得这里头，没他们说得那么轻巧。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c至少通过一次圣山副本，再找 §e妖族大长老-蚩尤 §c复命")
        else -> listOf(
            "§a已将圣山见闻告知妖族大长老",
            "",
            "§7§o四圣兽的谜团暂时结束了",
            "§7§o可你总觉得事情的并非如此简单",
            "§7§o为何人仙二族这么依赖§c§o我族内丹§7§o去作为药引炼药？",
            "§7§o这份丹方到底是谁传出来的？",
            "§7§o为何在还没出谷时，族内的§b§o炼丹掌柜§7§o就说根本不需要§c§o我族内丹§7§o？",
            "§7§o或许是知道的§6§l§o真相§7§o还不足够多的缘故……"
        )
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_DAZHANGLAO.id || currentProgress != 0) return false

        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if ((data.dungeonRecords[ShengShanDungeonManager.DUNGEON_RECORD_ID]?.clears ?: 0) <= 0) {
            player.sendMessage("§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f圣山那边还没查明白？先弄清楚里面出了什么事，再回来见我。")
            player.sendMessage("§c（请先至少通过一次圣山副本）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in elderScript.indices) return true

        player.sendMessage(elderScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == elderScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.YAO_DAZHANGLAO.id && currentProgress == 0

    override fun giveReward(player: Player) {
        val rewards = mutableListOf<ItemStack>()
        addReward(rewards, "yinpiao", 15, "银票")
        addReward(rewards, "mijingyaoshi", 5, "秘境之钥")
        addReward(rewards, "yuansuduihuanquan", 64, "元素兑换券")
        addReward(rewards, "access_book_5", 1, "五阶饰品制作书")
        addReward(rewards, "lingyujian", 1, "灵玉简")

        val leftovers = player.inventory.addItem(*rewards.toTypedArray())
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分奖励已掉落在脚下！")
        }

        plugin.playerManager.giveExp(player, 5000)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +5000")
        player.sendMessage("  §e[奖励] §f银票 x15")
        player.sendMessage("  §e[奖励] §f秘境之钥 x5")
        player.sendMessage("  §e[奖励] §f元素兑换券 x64")
        player.sendMessage("  §e[奖励] §f五阶饰品制作书 x1")
        player.sendMessage("  §e[奖励] §f灵玉简 x1")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun addReward(rewards: MutableList<ItemStack>, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("妖族第十二章缺少奖励物品配置: $resourceId ($displayName)")
            return
        }
        item.amount = amount
        rewards += item
    }
}
