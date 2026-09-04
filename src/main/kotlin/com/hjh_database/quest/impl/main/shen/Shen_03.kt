package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class Shen_03 : QuestBase("main_shen_3", "[神族主线]白木样本", QuestType.MAIN, 3) {

    override val raceLimit = 0

    override val description = listOf(
        "§7资源中心旁的白木似乎染上了虫害。",
        "§7去向负责人学习开物术，并采集一块样本。"
    )

    private val plugin get() = Hjh_database.instance
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()

    private val introductionScript = listOf(
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f来了啊。我是这儿的负责人，老早就感应到你要到了。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f你说什么是§e感应§f？我们神族的血脉皆源自创世神§4§n盘古大人§f，族人之间自可凭此§e血脉共鸣§f彼此感应。你刚降世不久，对这些还不熟悉，日后自会体会。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f你说方才没感应到长老的血脉，没提前问好才被他凶了……可能是长老神力深不可测，以你现在的能力，感应不到也并非不可能。别往心里去。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f他方才传了信，让我带你熟悉一下这里。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f正好，旁边那棵树的树干不知怎么发白了，我疑心是虫害。你去替我取一块下来看看。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f斧头？用不着。瞧见那白木头上冒的§a绿光§f了么——这便是天地灵气汇聚的征兆，可以用§e开物术§f来取。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f把目光聚在你想要的东西上，右手轻轻一碰，便能引动周遭灵气帮你把它“请”出来。不过施展时须专注，别乱动，分心中断了白白浪费时间。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f万物有灵，这些能采的东西也一样。下手前仔细看：§a绿光盈盈§f便是§a富饶§f，此时采集最快也最丰厚。若是光变成了§e暗黄色§f，便是枯竭了——还能采，但费时费力，说不定还一无所获。若是§7灰蒙蒙一片§f，那便是正在休养，暂时动不得。等它歇够了，绿光自会回来。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f每次施展开物术都会消耗§b精力§f，不过精力会随时间慢慢回复。开物术每提升一级，精力上限都会提高，§b开采速度也会增加§f。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f好了，去试试吧。取到样本之后，就快来交给我。"
    )

    private val completionScript = listOf(
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f果不其然，又是这虫子作祟。这么多年了还没整治明白，看来得去拜托§e丹塔§f那边的人了。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f辛苦你跑这一趟，这点§6辛苦钱§f你拿着，算是我的一点心意，不过先别走，待会还有事嘱咐你。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往资源中心寻找 §e负责人")
        1 -> listOf("§c采集 §e白化的木头 §c并交给负责人 (0/1)")
        2 -> listOf("§c继续听 §e资源中心负责人 §c安排")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.id) return false

        when (currentProgress) {
            0 -> playDialogue(player, introductionScript) {
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 请使用开物术采集一块白化的木头，带回来交给负责人。")
            }

            1 -> {
                if (countResource(player, "baihuademutou") < 1) {
                    player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f样本还没取到？去旁边那棵泛着绿光的白木头上试试开物术。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                removeResource(player, "baihuademutou", 1)
                plugin.questManager.updateProgress(player, id, 2)
                player.sendMessage("§a[任务] -> 已交付白化的木头。")
            }

            2 -> playDialogue(player, completionScript) {
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }

            else -> return false
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 80)
        giveResource(player, "hjh_tongqian", 10, "铜钱")
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +80")
        player.sendMessage("  §e[奖励] §f铜钱 x10")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= script.size) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        talkProgress[player.uniqueId] = index + 1

        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        }
    }

    private fun countResource(player: Player, resourceId: String): Int =
        player.inventory.contents
            .filter { getResourceId(it) == resourceId }
            .sumOf { it?.amount ?: 0 }

    private fun removeResource(player: Player, resourceId: String, amount: Int) {
        var remaining = amount
        for (item in player.inventory.contents) {
            if (remaining <= 0) break
            if (getResourceId(item) != resourceId) continue

            val removed = minOf(remaining, item!!.amount)
            item.amount -= removed
            remaining -= removed
        }
    }

    private fun getResourceId(item: ItemStack?): String? {
        val meta = item?.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    private fun giveResource(player: Player, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            player.sendMessage("§c[错误] 缺少任务奖励配置：$resourceId")
            return
        }

        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§e[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }
}
