package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_01 : QuestBase("main_xian_1", "[仙族主线]飞升接引", QuestType.MAIN, 1) {

    override val raceLimit = 1

    override val description = listOf(
        "§7你已飞升仙境。",
        "§7与§e新手引导员-小飞§7对话，了解仙族的规矩。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val script = listOf(
        "§e[${StoryNpcs.XIAN_XIAOFEI.displayName}§e] §f道友留步。恭喜飞升，我是负责接引的引导员，小飞。",
        "§e[${StoryNpcs.XIAN_XIAOFEI.displayName}§e] §f我族规矩，新飞升的道友需先面见§e盟主§f。这封§b推荐函§f请收好，面见时呈上即可。",
        "§e[${StoryNpcs.XIAN_XIAOFEI.displayName}§e] §f路径不难——沿§e环山栈道§f下行，至大石碑处左转直走，再沿§e之字形的山路§f上行，便能望见§e盟主大殿§f。沿途有§e指路告示牌§f，迷了路便瞧上一眼。",
        "§e[${StoryNpcs.XIAN_XIAOFEI.displayName}§e] §f仙路漫漫，自今日始。道友一路且行，不必匆忙。"
    )

    override fun getProgressText(progress: Int): List<String> = if (progress >= 1) {
        listOf("§a已取得小飞的引荐信")
    } else {
        listOf("§c与新手引导员-小飞对话 (0/1)")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 1

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_XIAOFEI.id || checkComplete(currentProgress)) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return true

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 1)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        val letter = ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§b小飞的引荐信")
                lore = listOf(
                    "§7§o这是小飞写给盟主的引荐信。",
                    "§7§o请前往盟主大殿，将它呈交给盟主。",
                    "§e[任务物品]"
                )
            }
        }

        player.inventory.addItem(letter).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，小飞的引荐信已掉落在脚下。")
        }

        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f小飞的引荐信 x1")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
