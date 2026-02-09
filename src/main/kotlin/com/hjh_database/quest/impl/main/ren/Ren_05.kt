package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.HashMap

class Ren_05 : QuestBase("main_ren_5", "[人族主线]人族证明", QuestType.MAIN, 5) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e村长 §c领取证明")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你已通过所有考验。",
        "§7村长有最后的话要嘱托。",
        "§7领取人族证明，踏上前往皇城的旅途。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptChief = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f你能连过三关，已是一位真正的人族族人了。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f现在，我将授予你这枚人族证明。今后无论在外遭遇何等困境，都可凭此证，借皇城中心的传送法阵归来。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f记住，人族峡谷永远是你的家。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f此证不单是归家的钥匙，更承载着女娲娘娘的祝福。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f受此祝福，即便在外风餐露宿、身处绝境，你的饱食度也永不会低于三成——女娲娘娘绝不会让你饿死。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f我族人亦讲求同心共济。在皇城，向部分人族商人出示此证，交易时可享折扣。出门在外，族人当互相照应。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f此外，待你经历成长、有所领悟后，此证还会与你共鸣，觉醒更深层的庇护之力。不过，那都是后话了。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f时候不早，你既已成人，又通过考验，是时候出去闯荡了。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f你可先去皇城皇宫，寻大内总管李公公。他会告知你当下该做什么。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f这包盘缠你带上，出门总需用钱。这些干粮也拿着，虽有祝福在身，也别饿着自己。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f还有这根翎羽，带在身上可身轻如燕、赶路省力。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f去吧，出村右转，沿路直行即可抵达皇城，沿途皆有路牌指引。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f日后闯出了名堂，别忘了回来，让我也高兴高兴！"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        // 场景: 找村长
        if (npcId == StoryNpcs.REN_CHIEF.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptChief) {
                    // 对话结束回调：发放物品并完成任务
                    giveItemsAndFinish(player)
                }
                return true
            }
        }
        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /**
     * 发放剧情物品并完成任务
     */
    private fun giveItemsAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val inv = player.inventory

        // 1. 人族证明
        val itemProof = rm.getItem("ren_zm_begin") ?: ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§e人族证明(配置缺失)") }
        }

        // 2. 铜钱
        val itemMoney = rm.getItem("hjh_tongqian") ?: ItemStack(Material.SUNFLOWER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§6铜钱") }
        }
        itemMoney.amount = 20

        // 3. 【修改】包子：直接生成面包，命名为“包子”
        val itemFood = ItemStack(Material.BREAD, 20)
        val foodMeta = itemFood.itemMeta
        foodMeta?.setDisplayName("§f包子")
        itemFood.itemMeta = foodMeta

        // 4. 【修改】翎羽：改为从 resource 读取 hjh_xyzy
        val itemFeather = rm.getItem("hjh_xyzy") ?: ItemStack(Material.FEATHER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] hjh_xyzy") }
        }

        // 5. 发放
        val leftovers = inv.addItem(itemProof, itemMoney, itemFood, itemFeather)

        // 6. 掉落处理
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，部分物品掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        }

        // 7. 提示信息
        player.sendMessage("§e[系统] 获得 ${itemProof.itemMeta?.displayName}")
        player.sendMessage("§e[系统] 获得 ${itemMoney.itemMeta?.displayName} x${itemMoney.amount}")
        player.sendMessage("§e[系统] 获得 ${itemFood.itemMeta?.displayName} x${itemFood.amount}")
        player.sendMessage("§e[系统] 获得 ${itemFeather.itemMeta?.displayName}")

        // 8. 完成任务
        Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this)
    }

    /**
     * 简化的对话播放逻辑
     */
    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
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
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f请前往皇城寻找李公公")
        player.sendMessage("§8§m========================================")

        // 经验奖励
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            data.status = 3
            Hjh_database.instance.databaseManager.savePlayer(data)
        }


        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        // 本任务只要触发了完成逻辑即完成，不需要额外的进度检查
        return false
    }
}