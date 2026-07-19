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

class Shen_09 : QuestBase("main_shen_9", "[神族主线]职业启程", QuestType.MAIN, 9) {

    override val raceLimit = 0

    override val description = listOf(
        "§7人皇已为你准备了四位导师的传送券。",
        "§7选择一门职业后，返回人皇处领取启程物资。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val firstScript = listOf(
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上，又见面了。圣山之事可有进展？",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f原来如此……朕未能多为神上分忧，实在惭愧。长老方才已将§e职业一事§f告知于朕，神上请听——",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f皇城之中共有四位导师，分别传授§e剑道§f、§e箭术§f、§e术法§f与§e医术§f，神上可逐一体验后再做决断。至于具体方位，神上不必费心寻找——朕这里备了四份§6传送券§f，可直达各导师门前。此券只对身怀§e神力§f的神族生效，旁人用不得。不过每份仅能使用§c一次§f，还望神上见谅。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上选定职业之后，还请再回此处一趟。朕另有要事需向神上嘱咐。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e人皇-轩辕氏 §c对话")
        1 -> listOf("§c选择一门职业后，返回 §e人皇-轩辕氏 §c处")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.RENHUANGXUANYUANSHI.id) return false

        when (currentProgress) {
            0 -> playDialogue(player, firstScript) {
                giveJobTickets(player)
                updateStatusToMainland(player)
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 使用传送券体验职业，选定后返回人皇处。")
            }

            1 -> handleSelectedJobDialogue(player)
            else -> return false
        }
        return true
    }

    private fun handleSelectedJobDialogue(player: Player) {
        val job = plugin.playerManager.getPlayerData(player)?.job
        if (job == null || job !in 0..3) {
            player.sendMessage("§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上尚未选定职业。可使用朕交予的传送券前往四位导师处体验。")
            return
        }

        val script = mutableListOf(
            "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f恭喜神上选定职业。",
            jobBlessing(job),
            "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f关于圣兽祝福一事，神上可直接前往§a东方龙鳞森林§f中的§e青龙祭坛§f。天机令或会指引神上前去南方——这一点还望神上见谅。天机令乃人仙二族合力所创，其蕴含的力量与§e神力§f之间偶有冲突，并非有意误导神上，实在抱歉。",
            "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f待神上取得东方森林的§a青龙祝福§f，后续便可继续前行了。"
        )
        if (job == 3) {
            script.add("§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上既选择了§d医师§f，必是心怀救济苍生之志。朕甚为感动。这里有一些专门供给医师的§e物资§f，还望神上收下。")
        }

        playDialogue(player, script) {
            plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
        }
    }

    private fun jobBlessing(job: Int): String = when (job) {
        0 -> "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f剑锋所向，万魔辟易。愿神上之剑，守天地之正。"
        1 -> "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f弓开如满月，箭去似流星。愿神上之箭，洞穿万里阴霾。"
        2 -> "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f术法随心，星辰为引。愿神上之术，通晓天地玄机。"
        else -> "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f仁心济世，妙手回春。愿神上之医，挽苍生于危厄。"
    }

    private fun giveJobTickets(player: Player) {
        val ticketIds = listOf(
            "shen_job_ticket_warrior",
            "shen_job_ticket_archer",
            "shen_job_ticket_warlock",
            "shen_job_ticket_doctor"
        )
        val tickets = ticketIds.mapNotNull { id ->
            plugin.resourceManager.getItem(id)?.apply { amount = 1 } ?: run {
                plugin.logger.warning("缺少神族职业传送券配置: $id")
                null
            }
        }
        val leftovers = player.inventory.addItem(*tickets.toTypedArray())
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分传送券已掉落在脚下！")
        }
        player.sendMessage("§e[系统] 获得四份神谕职业传送券。")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun updateStatusToMainland(player: Player) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        data.updateStatus(3)
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.databaseManager.savePlayer(data)
                plugin.databaseManager.dataSource?.connection?.use { connection ->
                    plugin.databaseManager.savePlayerStatus(connection, data)
                }
            } catch (exception: Exception) {
                plugin.logger.warning("保存神族职业任务状态失败: ${exception.message}")
            }
        })
    }

    override fun giveReward(player: Player) {
        val isHealer = plugin.playerManager.getPlayerData(player)?.job == 3
        val rewards = mutableListOf<ItemStack>()
        val resourceManager = plugin.resourceManager

        resourceManager.getItem("jinyuanbao")?.apply { amount = 2 }?.let(rewards::add)
        resourceManager.getItem("yuansuduihuanquan")?.apply { amount = 8 }?.let(rewards::add)
        resourceManager.getItem("yuhedan0")?.apply { amount = 10 }?.let(rewards::add)
        if (isHealer) {
            resourceManager.getItem("armor_core_3")?.apply { amount = 1 }?.let(rewards::add)
            resourceManager.getItem("chitongding")?.apply { amount = 1 }?.let(rewards::add)
        }

        val leftovers = player.inventory.addItem(*rewards.toTypedArray())
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分奖励已掉落在脚下！")
        }

        plugin.playerManager.giveExp(player, 100)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f金元宝 x2")
        player.sendMessage("  §e[奖励] §f元素兑换券 x8")
        player.sendMessage("  §e[奖励] §f初级愈合丹 x10")
        if (isHealer) {
            player.sendMessage("  §d[额外] §f三阶防具核心 x1")
            player.sendMessage("  §d[额外] §f赤铜锭 x1")
        }
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
}
