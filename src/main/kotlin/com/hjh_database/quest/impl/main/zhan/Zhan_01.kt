package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Zhan_01 : QuestBase("main_zhan_1", "[战神族主线]族中急令", QuestType.MAIN, 1) {

    override val raceLimit = 3

    override val description = listOf(
        "§7请找到 §e新手引导员-飞虎 §7并与他对话。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val script = listOf(
        "§e[${StoryNpcs.ZHAN_FEIHU.displayName}§e] §f新的族人，我是引导员飞虎。欢迎。",
        "§e[${StoryNpcs.ZHAN_FEIHU.displayName}§e] §f如今外头形势§c紧急§f，族里正§c缺人手§f。你刚降世就要替族里办事，难为你了。",
        "§e[${StoryNpcs.ZHAN_FEIHU.displayName}§e] §f这份§e介绍信§f拿好，去右前方的§e议事厅§f交给§e族长§f。他自会安排。",
        "§e[${StoryNpcs.ZHAN_FEIHU.displayName}§e] §f路不熟就参考路边的§e指路告示牌§f。动作快些，别让族长等。"
    )

    override fun getProgressText(progress: Int): List<String> = if (progress >= 1) {
        listOf("§a已完成对话")
    } else {
        listOf("§c与新手引导员-飞虎对话 (0/1)")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 1

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_FEIHU.id || checkComplete(currentProgress)) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return true

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
                setDisplayName("§e飞虎的介绍信")
                lore = listOf(
                    "§7§o这是飞虎写给族长的介绍信。",
                    "§7§o请前往议事厅交给族长",
                    "§e[任务物品]"
                )
            }
        }

        val leftovers = player.inventory.addItem(letter)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§c[提示] 背包已满，飞虎的介绍信已掉落在脚下。")
        }

        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f飞虎的介绍信 x1")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
