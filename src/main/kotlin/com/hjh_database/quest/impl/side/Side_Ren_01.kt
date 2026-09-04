package com.hjh_database.quest.impl.side.ren

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.HashMap
import java.util.UUID

class Side_Ren_01 : QuestBase("side_ren_1", "[支线]重华晶的奥秘", QuestType.SIDE, 1) {

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return super.canAccept(player, data) &&
                (
                        data.completedQuests.contains("main_ren_6") ||
                                data.questStatuses["main_ren_6"] == QuestStatus.COMPLETED ||
                                data.completedQuests.contains("main_yao_7") ||
                                data.questStatuses["main_yao_7"] == QuestStatus.COMPLETED ||
                                data.completedQuests.contains("main_zhan_6") ||
                                data.questStatuses["main_zhan_6"] == QuestStatus.COMPLETED
                        )
    }

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往护国殿寻找 §e护国法师-法海")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7据说护国法师法海研究出了一种神奇的传送阵法。",
        "§7前往护国殿一探究竟吧。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptFahai = listOf(
        "§e[护国法师-法海] §f小伙子，快过来看看老夫新捣鼓出来的宝贝！",
        "§e[护国法师-法海] §f此物，名曰【重华晶】。",
        "§e[护国法师-法海] §f这晶体玄妙得很——往地上一放，便能吸纳四周的灵气精华，将其刻入晶脉之中，再与你的灵识共鸣，让你与这片天地建立一种神奇的联系。",
        "§e[护国法师-法海] §f再配上这块刻着“命”字的绿石，只需轻轻一触，便能瞬间抵达你此前建立过联系的区域。",
        "§e[护国法师-法海] §f真可谓：缩地成寸，日行千里！",
        "§e[护国法师-法海] §f不过这“命”石有个规矩——它只认同一方天地里的【重华晶】。咱们这片大陆分作四域：东方森林、西方山脉、南方沙漠、北方湖泊。东边的“命”石，可传不到南边去。这四块“命”石，就立在四域的入口处。",
        "§e[护国法师-法海] §f再说了，传送这事儿，着实耗费【重华晶】的灵力。每用完一次，这方天地就得歇上五分钟，好在别处不受影响。",
        "§e[护国法师-法海] §f日后你在外闯荡，遇着别的【重华晶】，别忘了顺手触碰一下，便可与其共鸣。结下的缘分越多，赶路便越省心。",
        "§e[护国法师-法海] §f这十块重生石你且收好。在外厮杀，毙命在所难免。用此石复生，可免天道责罚，重获新生。"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        // 判定 NPC 的 ID 是否为 ren_fahai
        if (npcId == "ren_fahai") {
            if (currentProgress == 0) {
                playDialogue(player, scriptFahai) {
                    // 对话结束回调：发放物品并完成任务
                    giveItemsAndFinish(player)
                }
                return true
            }
        }
        return false
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == "ren_fahai" && currentProgress == 0

    // ==========================================
    // 辅助方法
    // ==========================================

    /**
     * 发放剧情物品并完成任务
     */
    private fun giveItemsAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        // 1. 生成重生石 (id: relive_stone)
        val itemStone = rm.getItem("relive_stone") ?: ItemStack(Material.EMERALD).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§c[配置缺失] relive_stone")
            }
        }

        // 数量设置为 10
        itemStone.amount = 10

        // 发放物品
        val leftovers = player.inventory.addItem(itemStone)

        // 掉落处理
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${itemStone.itemMeta?.displayName} §fx10")
        }

        // 完成任务
        Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this)
    }

    /**
     * 简化的对话播放逻辑
     */
    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            // 播放村民声音或其他音效
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            talkProgress[player.uniqueId] = index + 1

            // 播放完毕检测
            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +20")
        player.sendMessage("  §e[奖励] §f重生石 x10")
        player.sendMessage("§8§m========================================")

        // 经验奖励 +20
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 20)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
