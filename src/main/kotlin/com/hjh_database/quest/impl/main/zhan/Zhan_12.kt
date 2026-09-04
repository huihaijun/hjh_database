package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Zhan_12 : QuestBase("main_zhan_12", "[战神族主线]旧敌真相", QuestType.MAIN, 26) {

    override val raceLimit = 3
    override val requiredCompletedQuestIds = setOf("main_zhan_11")

    override val description = listOf(
        "§7通过圣山秘境，查明四圣兽藏身其中的真相。",
        "§7将这份绝密情报亲自带回战神族族地。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val zuoYanScript = listOf(
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f圣山那边的动静，我已经看见了。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f里面到底发生了什么，回来以后再说。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f这种级别的情报，不能走普通渠道。",
        "§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f§c直接回族地§f，亲自向族长汇报。我留在这继续盯着。"
    )

    private val chiefScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f回来了？坐。把圣山里看到的东西，一件一件说清楚。",
        "§7（你将圣山中的经过一五一十告诉了族长。）",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f……",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f你确定？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f那四只§4§n圣兽§f当时并非在§e守护§f什么东西，而是被§c固定在阵中§f，力量还一直往那具肉身上送？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f圣兽没有攻击你们，反倒从阵里钻出一坨§c烂肉§f要吞噬世界？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f有意思。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我们追了§4§n四圣兽§f这么多年。百年前赔上那么多族人的命，才把它们从§e神庙§f逼走。一直以来，我们都把它们当作§c血洗联盟的仇敌§f。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f可照你带回来的消息看——",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f它们不像是敌人，倒像是§c被人当了枪使§f。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f若圣山里的真相真如这般……那当年联盟的覆灭，恐怕还有§e另一种可能§f。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我会让下面的人§c重新翻查旧档§f。这趟辛苦你了，先休息。这些拿着，算这次行动的§e奖赏§f。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f对了，我族在§e神族领地§f也安插了眼线。如今看来，是时候让他§c活动活动§f了。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f你先调整几日，待我查出值得你再跑一趟的事，自然会找你。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c至少通过一次圣山副本，再找蓬莱的 §e左焰 §c接头")
        1 -> listOf("§c直接返回战神族族地，亲自向 §e族长 §c汇报")
        else -> listOf(
            "§a已将圣山中的绝密情报汇报给战神族族长",
            "",
            "§7§o四圣兽的谜团暂时结束了",
            "§7§o可你总觉得事情的并非如此简单",
            "§7§o族长说的§c§o眼线§7§o，究竟是谁？他又知道些什么",
            "§7§o莫非，四圣兽§4§o从未§7§o是我们的敌人",
            "§7§o真正的幕后黑手§c§o另有他人§7§o？",
            "§7§o或许是知道的§6§l§o真相§7§o还不够多的缘故……"
        )
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean = when {
        npcId == StoryNpcs.PENGLAI_ZUOYAN.id && currentProgress == 0 -> handleZuoYan(player)
        npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 1 -> handleChief(player)
        else -> false
    }

    private fun handleZuoYan(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if ((data.dungeonRecords[ShengShanDungeonManager.DUNGEON_RECORD_ID]?.clears ?: 0) <= 0) {
            player.sendMessage("§e[${StoryNpcs.PENGLAI_ZUOYAN.displayName}§e] §f先进入圣山把情况摸清，再回来找我。")
            player.sendMessage("§c（请先至少通过一次圣山副本）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        playDialogue(player, zuoYanScript) {
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 直接返回战神族族地，亲自向族长汇报圣山情报。")
        }
        return true
    }

    private fun handleChief(player: Player): Boolean {
        playDialogue(player, chiefScript) {
            val data = plugin.playerManager.getPlayerData(player) ?: return@playDialogue
            plugin.questManager.completeQuest(player, data, this)
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        (npcId == StoryNpcs.PENGLAI_ZUOYAN.id && currentProgress == 0) ||
            (npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 1)

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
            plugin.logger.warning("战神族第十二章缺少奖励物品配置: $resourceId ($displayName)")
            return
        }
        item.amount = amount
        rewards += item
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }
}
