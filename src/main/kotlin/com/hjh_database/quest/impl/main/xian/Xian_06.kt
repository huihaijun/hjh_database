package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_06 : QuestBase("main_xian_6", "[仙族主线]皇城受命", QuestType.MAIN, 6) {

    override val raceLimit = 1

    override val description = listOf(
        "§7仙族盟主命你前往人族皇城协助调查魔物异动。",
        "§7前往皇宫寻找§e御前带刀侍卫长——羽罗§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val yuluoScript = listOf(
        "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f恭迎上仙驾临。在下御前带刀侍卫长——羽罗，已提前获知上仙将至，特在此恭候。",
        "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f近日各方魔物暴起伤人，来势颇不寻常。我族虽已派遣§e特遣队§f前往查探，但皇上仍放心不下，故而恳请上仙出手相助，一同调查此事的§c根源§f。",
        "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f烦请上仙先往§e皇城东方§f的§e龙鳞之森§f走一遭，与§e龙须镇§f的祭司接洽。那片林地，她比谁都熟悉。",
        "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f另外，皇城中有§e四位导师§f，分授§e剑§f、§e弓§f、§e术§f、§e医§f四道。上仙虽已仙体大成，但若多一门防身技艺，应对未知险境时也能更从容。我这儿有一份§e笔记§f，记着四位导师的居所，上仙可作参考。",
        "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f待选定职业，再去§e护国殿§f寻§e护国法师——法海§f。他所研的§6重华晶§f颇有些门道，或能与上仙的仙族证明§e共鸣§f，说不定于上仙有用。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往皇宫，与 §e御前带刀侍卫长——羽罗 §c对话")
        else -> listOf("§a已取得羽罗的笔记与龙须镇情报")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_YULUO.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in yuluoScript.indices) return true

        player.sendMessage(yuluoScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == yuluoScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        giveNotebook(player)
        plugin.playerManager.giveExp(player, 240)

        plugin.playerManager.getPlayerData(player)?.let { data ->
            if (data.questStatuses[SIDE_CHONGHUA_QUEST_ID] != QuestStatus.COMPLETED) {
                data.questStatuses[SIDE_CHONGHUA_QUEST_ID] = QuestStatus.IN_PROGRESS
                data.questProgress[SIDE_CHONGHUA_QUEST_ID] = 0
                plugin.databaseManager.saveQuestData(
                    player,
                    SIDE_CHONGHUA_QUEST_ID,
                    QuestStatus.IN_PROGRESS,
                    0
                )
            }
            plugin.databaseManager.savePlayerAsync(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +240")
        player.sendMessage("  §e[奖励] §f羽罗的笔记 x1")
        player.sendMessage("")
        player.sendMessage("  §b[解锁] §f已解锁新支线任务：重华晶的奥秘")
        player.sendMessage("  §e[提示] §f[选择职业]不记入主线流程")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveNotebook(player: Player) {
        val notebook = plugin.resourceManager.getItem("xian_zybj") ?: ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] xian_zybj") }
        }
        notebook.amount = 1

        player.inventory.addItem(notebook).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，羽罗的笔记已掉落在脚下。")
        }
        player.sendMessage("§e[系统] 获得 ${notebook.itemMeta?.displayName ?: "羽罗的笔记"} x1")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private companion object {
        const val SIDE_CHONGHUA_QUEST_ID = "side_ren_1"
    }
}
