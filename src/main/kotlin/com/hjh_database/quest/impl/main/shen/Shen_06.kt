package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Shen_06 : QuestBase("main_shen_6", "[神族主线]圣山异动", QuestType.MAIN, 6) {

    override val raceLimit = 0

    override val description = listOf(
        "§7返回资源中心，听取负责人的安排。",
        "§7随后前往长老大殿，查明圣山异动的缘由。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val resourceCenterScript = listOf(
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f嗯，时候差不多了。你在这儿转了几圈，应该也熟悉了。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f长老方才§e传音§f过来，说待你准备妥当，便回去见他。听这意思，应当是要给你安排正事了。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f这些包子你拿着，不知道长老要给你安排什么事情，路上别饿着。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f快去吧。这次切记§e提前问好§f，莫再惹他老人家动气了。"
    )

    private val elderScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这次倒是懂事了……",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f东西都拿好了？那便去§e下界§f走一趟。§e圣山§f近来有些不对。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f圣山是什么？你连这都不知道。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f圣山乃我族起源，§4§n盘古神§f的殒落之地。如今由§4§n四圣兽§f镇守，这股力量维持着天地间的平衡与运转。近来我感应到圣山的能量有些紊乱——你去查清楚，究竟发生了什么。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f到了下界，直接去人族皇城找他们的§e皇§f。他会接待你。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这个羽毛拿着，赶路的时候方便些……"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e资源中心负责人 §c对话")
        1 -> listOf("§c前往 §e长老大殿 §c与长老对话")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean = when (npcId) {
        StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.id -> handleResourceCenterDialogue(player, currentProgress)
        StoryNpcs.SHEN_ZHANGLAO.id -> handleElderDialogue(player, currentProgress)
        else -> false
    }

    private fun handleResourceCenterDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            0 -> playDialogue(player, resourceCenterScript) {
                giveBaozi(player)
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 前往长老大殿，向长老问好。")
            }

            1 -> player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f长老正在大殿等你，快去吧，别忘了提前问好。")
            else -> return false
        }
        return true
    }

    private fun handleElderDialogue(player: Player, currentProgress: Int): Boolean {
        if (currentProgress != 1) return false

        playDialogue(player, elderScript) {
            plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
        }
        return true
    }

    override fun giveReward(player: Player) {
        giveFeather(player)
        plugin.playerManager.giveExp(player, 10)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("  §e[奖励] §f新芽之羽 x1")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveBaozi(player: Player) {
        val baozi = org.bukkit.inventory.ItemStack(Material.BREAD, 20).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }
        giveItem(player, baozi)
        player.sendMessage("§e[系统] 获得 §f包子 x20")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun giveFeather(player: Player) {
        val feather = plugin.resourceManager.getItem("hjh_xyzy") ?: org.bukkit.inventory.ItemStack(Material.FEATHER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§e新芽之羽") }
        }
        feather.amount = 1
        giveItem(player, feather)
        player.sendMessage("§e[系统] 获得 ${feather.itemMeta?.displayName ?: "新芽之羽"} x1")
    }

    private fun giveItem(player: Player, item: org.bukkit.inventory.ItemStack) {
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，物品已掉落在脚下！")
        }
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
}
