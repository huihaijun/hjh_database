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

class Ren_06 : QuestBase("main_ren_6", "[人族主线]皇城指引", QuestType.MAIN, 6) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往皇宫寻找 §e李公公")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你已离开人族峡谷，抵达皇城。",
        "§7按照村长的指引，前往皇宫。",
        "§7找到大内总管李公公，询问接下来的安排。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptLiGongGong = listOf(
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f啊，新面孔！从峡谷里走出来的族人，都是好样的。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f想当年，我也是通过层层考验，才得以进宫做官呢。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f怎么样，有没有意愿也到宫里来当差啊？长那么俊俏去当探险者，真是可惜了…",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f好啦，不玩了，说正事。近来皇城周遭村庄屡遭野兽侵袭，陛下为此甚是忧心。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f朝廷决意派遣可靠之人前往查探。你来得正是时候。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f可如今这些畜生越发凶悍，寻常村民已难抵挡。你虽有些底子，但真要应对，还需一门正经的职业修行。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f咱们皇城里有四位顶尖的导师，分授战士、弓箭手、术士、医师之道。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f你初来皇城，不如先去拜访他们，择一适合你的门道，好好磨练一番本事。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f待你选定了职业，有了更扎实的根基，再去龙须镇不迟。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f届时王卯镇长见你是正经的职业冒险者，也更能放心将详情相告。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f啊对了，你初来乍到，皇城地图近来又紧缺，老夫便与你口述一番方位，你记仔细了。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f皇宫在城北；城西是藏经阁；城东则是护国法师的炼丹房。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f丹药铺在东南；西南方则有铁匠铺与集市。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f先前有位与你一般的年轻人，曾将四位导师的方位信息略作笔记，我这便给你。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f你可在城中转转，选定职业后，便前往龙须镇寻镇长王卯吧。"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        // 场景: 找李公公
        // 注意：这里假设 StoryNpcs 枚举中有名为 REN_LIGONGGONG 的定义
        if (npcId == StoryNpcs.REN_LIGONGGONG.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptLiGongGong) {
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

        // 生成前辈笔记 (id: hjh_zybj)
        val itemNote = rm.getItem("hjh_zybj") ?: ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e前辈笔记(配置缺失)")
                lore = listOf("§7缺少 hjh_zybj 配置", "§7请联系管理员")
            }
        }

        // 发放物品
        val leftovers = player.inventory.addItem(itemNote)

        // 掉落处理
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${itemNote.itemMeta?.displayName}")
        }

        // 完成任务
        Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this)
    }

    /**
     * 简化的对话播放逻辑 (复用自 Ren_05)
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
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f请阅读笔记，寻找职业导师")
        player.sendMessage("  §e[提示] §f[选择职业]不记入主线流程")
        player.sendMessage("§8§m========================================")

        // 经验奖励
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            Hjh_database.instance.databaseManager.savePlayer(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}