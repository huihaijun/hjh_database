package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.HashMap
import java.util.UUID

class Ren_07 : QuestBase("main_ren_7", "[人族主线]森林危机", QuestType.MAIN, 7) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往龙须镇寻找 §e镇长-王卯")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你已听从李公公的指引。",
        "§7前往龙须镇寻找镇长王卯，",
        "§7了解龙鳞森林野兽侵袭的具体情况。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptWangMao = listOf(
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f喔§2§o皇城§f的勇士们,你们终于来了",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f相信你们也在来的途中遇到了各种野兽和怪物的阻挠",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f没错,§2§o龙鳞森林§f已经快被这些家伙给闹到翻过来了",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f镇上的§4§n祭司§f只会整天念些没作用的咒语和观看星象,根本没有实质的助益",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f你们赶快到§2§o森林中心§f去查看吧,我想一定又是§4§n祭司§f该好好照顾的§2§o祭坛§f出了问题",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f哼哼,如果真的是这样,我就想办法把她给赶出去…在村庄里只会浪费粮食的家伙…",
        "§e[${StoryNpcs.REN_WANGMAO.displayName}§e] §f这些拿在路上吃，别饿坏了肚子"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.REN_WANGMAO.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptWangMao) {
                    // 对话结束，直接完成任务
                    Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +280")
        player.sendMessage("  §e[奖励] §f包子 x10")
        player.sendMessage("§8§m========================================")

        // 1. 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 280)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        // 2. 发放普通物品（包子）
        val baoziItem = ItemStack(Material.BREAD, 10).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§f包子")
            }
        }

        val leftovers = player.inventory.addItem(baoziItem)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
