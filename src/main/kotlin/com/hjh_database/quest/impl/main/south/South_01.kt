package com.hjh_database.quest.impl.main.south

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

/**
 * 南方主线任务第一章 - 绿洲传闻
 * 该任务为五种族通用主线，去除了种族限制。
 */
class South_01 : QuestBase("main_south_1", "[主线]绿洲传闻", QuestType.MAIN, 10) {

    // 嫁接准备：设置为 null，代表无种族限制，后续各族前置完成后均可跳转至此
    override val raceLimit = null

    // 南方区域主线承接人族与妖族各自完成的四方结界章节。
    override val requiredCompletedQuestIds = setOf("main_ren_9", "main_yao_9", "main_shen_9")

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c与 §e绿洲小镇镇长 §c交谈")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你来到了焱砂大漠中唯一的绿洲。",
        "§7这里的气氛似乎有些不同寻常，",
        "§7去见见镇长，打听关于圣兽朱雀的消息。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptMayor = listOf(
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f你好啊旅行者，难得难得，欢迎来到这儿，此处可是§2§o焱砂大漠§f中唯一的一片§2§o绿洲§f了",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f不瞒你说，这么多年下来，能横穿这茫茫大漠走到这儿的旅人，咱两个巴掌都数得过来",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f那些个半路打了退堂鼓灰溜溜跑回皇城的也就算了，还有不少愣头青撞上了那帮杀千刀的马贼团，被劫得落花流水，能保住小命就算造化了",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f所以啊，能安然无恙站在这儿的，必定是智勇双全之辈",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f…啊？你问我怎么瞧出来的？",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f呵呵，你身上那块配饰正泛着蓝盈盈的光呢，咱要是没看走眼，那光泽跟§4§n龙鳞§f倒有几分神似",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f镇上的人都说，但凡能通过§4§n青龙大人§f试炼的，身上便会多出一件如龙鳞般的信物，想必就是你腰间这块了吧",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f啊…？觉得镇上气氛很奇怪？",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f唉…这你就有所不知了",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f自从§4§n朱雀神上§f离开§2§o南方沙漠§f之后，这里的气候变得越来越不稳定",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f近几年小镇旁的§2§o火山口§f似乎又有了要喷发的迹象…",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f镇上的§4§n巫者§f说这是因为§4§n火山之神§f发怒了",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f所以我们要准备一名祭品，献给§4§n火山神§f",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f如果你想询问关于§4§n圣兽朱雀§f或是§2§o火山§f的事情的话",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f可以去找镇里的§4§n巫者§f，她叫做§4§n红鸾§f，应该在镇里的炼丹铺内",
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptMayor) {
                    // 对话结束，完成任务
                    val plugin = Hjh_database.instance
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return false
    }

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
        player.sendMessage("  §e[奖励] §f经验 +50")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 50)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
