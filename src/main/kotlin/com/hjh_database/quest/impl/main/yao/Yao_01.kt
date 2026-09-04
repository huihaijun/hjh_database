package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Yao_01 : QuestBase("main_yao_1", "[妖族主线]初入叶灵谷", QuestType.MAIN, 1) {

    override val raceLimit = 4
    override val description = listOf(
        "§7请找到 §e新手引导员-小花 §7并与她对话。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val script = listOf(
        "§e[${StoryNpcs.YAO_XIAOHUA.displayName}§e] §f呀！是新生的同胞诶！太好了！",
        "§e[${StoryNpcs.YAO_XIAOHUA.displayName}§e] §f你好呀，欢迎来到§2§o妖族§f大家庭！别怕别怕，这儿是§e叶灵谷§f，我们妖族的家园，安全得很呢！",
        "§e[${StoryNpcs.YAO_XIAOHUA.displayName}§e] §f咦，你看起来好像有些迷茫？不要紧，新生的族人刚来到这世上，难免晕头转向。来，拿着这封§b推荐函§f，顺着这条小路往前走，谷主就在古树下的水池边等着你。",
        "§e[${StoryNpcs.YAO_XIAOHUA.displayName}§e] §f谷主可是我们族里最慈祥的长辈，他会告诉你接下来该怎么做的。不管迷了路还是遇到什么麻烦，随时回来找我就好～"
    )

    override fun getProgressText(progress: Int): List<String> {
        return if (progress >= 1) {
            listOf("§a已完成对话")
        } else {
            listOf("§c与小花对话 (0/1)")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 1
    }

    override fun giveReward(player: Player) {
        val plugin = Hjh_database.instance
        val letter = ItemStack(Material.PAPER)
        val meta = letter.itemMeta
        if (meta != null) {
            meta.setDisplayName("§b小花的推荐函")
            meta.lore = listOf(
                "§7§o这是小花写给谷主的推荐函。",
                "§7§o请顺着小路，将它交给古树水池边的谷主。",
                "§e[任务物品]"
            )
            letter.itemMeta = meta
        }

        val leftovers = player.inventory.addItem(letter)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品已掉落在脚下。")
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        }

        plugin.playerManager.getPlayerData(player)?.let { data ->
            plugin.playerManager.giveExp(player, 60)
            plugin.databaseManager.savePlayerAsync(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f小花的推荐函 x1")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_XIAOHUA.id) return false
        if (checkComplete(currentProgress)) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index < script.size) {
            player.sendMessage(script[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index >= script.size - 1) {
                player.sendMessage("§a[任务] -> 对话结束。")
                Hjh_database.instance.questManager.updateProgress(player, id, 1)
            }
        }

        return true
    }
}
