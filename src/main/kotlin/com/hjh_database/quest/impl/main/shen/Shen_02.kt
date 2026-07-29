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

class Shen_02 : QuestBase("main_shen_2", "[神族主线]长老的安排", QuestType.MAIN, 2) {

    override val raceLimit = 0

    override val description = listOf(
        "§7前往长老大殿，将舒灵的推荐函交给 §e长老§7。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val introductionScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f往哪看呢？",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f见了面连声问好都没有……你感应不到我来了么？",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f（长老微微眯起眼，审视了你片刻，随后轻轻一拂袖）",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f也罢。新降世的小神，这次便不追究了。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f舒灵给了你一封推荐函吧。拿来。"
    )

    private val assignmentScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f（长老展开信函扫了一眼）",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f嗯……也好，正好有些事需要人去办。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f我还有事要忙。你先去刚才路过的§e资源中心§f，找那边的§e负责人§f，他会带你尽快熟悉这里。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f快去吧，别打扰我。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往长老大殿寻找 §e长老")
        1 -> listOf("§c主手持有 §b舒灵的推荐函 §c交给 §e长老")
        2 -> listOf("§c继续听 §e长老 §c安排")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZHANGLAO.id) return false

        when (currentProgress) {
            0 -> playDialogue(player, introductionScript) {
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 请主手持有舒灵的推荐函，交给长老。")
            }

            1 -> {
                if (!isHoldingLetter(player.inventory.itemInMainHand)) {
                    player.sendMessage("§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f舒灵的推荐函呢？拿在主手上交给我。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                consumeMainHandLetter(player)
                plugin.questManager.updateProgress(player, id, 2)
                player.sendMessage("§a[任务] -> 已交付舒灵的推荐函。")
            }

            2 -> playDialogue(player, assignmentScript) {
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }

            else -> return false
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private val plugin get() = Hjh_database.instance

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

    private fun isHoldingLetter(item: ItemStack): Boolean =
        item.type == Material.PAPER && item.itemMeta?.displayName == "§b舒灵的推荐函"

    private fun consumeMainHandLetter(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= 1
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
    }
}
