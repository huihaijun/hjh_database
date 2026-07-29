package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Shen_01 : QuestBase("main_shen_1", "[神族主线]初临神域", QuestType.MAIN, 1) {

    override val raceLimit = 0

    override val description = listOf(
        "§7请找到 §e新手引导员-舒灵 §7并与她对话。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val script = listOf(
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f新生的族人，欢迎。我是舒灵，负责接引你的引导员。",
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f这封§b推荐函§f收好了，到了大殿交给§e长老§f便是。",
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f路线的话——穿过山洞往上走，经过§e商业区§f后方的大桥，直行就能望见§e长老大殿§f了。路程不算短，不过正好趁这个机会熟悉一下我族的生活环境，沿途也有§e指路告示牌§f，迷路了随时看一眼。",
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f对了，有件事得提前嘱咐你——§e长老§f脾气不大好，见到他一定要§e早早问好§f，礼数周全些。他老人家若是觉得你怠慢了，嘴上不说，脸色可够你受的。",
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f旁边这座传送法阵本可直达大殿，只是你刚降世不久，血脉尚不稳定，暂且还无法使用。日后成熟了，它自会为你开启。这一点还望见谅。",
        "§e[${StoryNpcs.SHEN_SHULING.displayName}§e] §f这离大殿还是挺远的，这几个§6包子§f你拿着路上垫一垫。去吧，莫让长老久等。"
    )

    override fun getProgressText(progress: Int): List<String> = if (progress >= 1) {
        listOf("§a已完成对话")
    } else {
        listOf("§c与新手引导员-舒灵对话 (0/1)")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 1

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_SHULING.id || checkComplete(currentProgress)) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= script.size) return true

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        talkProgress[player.uniqueId] = index + 1

        if (index == script.lastIndex) {
            player.sendMessage("§a[任务] -> 对话结束。")
            Hjh_database.instance.questManager.updateProgress(player, id, 1)
        }
        return true
    }

    override fun giveReward(player: Player) {
        val plugin = Hjh_database.instance

        val letter = ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§b舒灵的推荐函")
                lore = listOf(
                    "§7§o这是舒灵写给长老的推荐函。",
                    "§7§o请穿过山洞前往长老大殿，将它交给长老。",
                    "§e[任务物品]"
                )
            }
        }
        val baozi = ItemStack(Material.BREAD, 10).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }

        giveItem(player, letter, "舒灵的推荐函")
        giveItem(player, baozi, "包子")
        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f舒灵的推荐函 x1")
        player.sendMessage("  §e[奖励] §f包子 x10")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveItem(player: Player, item: ItemStack, displayName: String) {
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }
}
