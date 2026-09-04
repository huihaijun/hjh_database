package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Yao_03 : QuestBase("main_yao_3", "[妖族主线]天机入手", QuestType.MAIN, 3) {

    override val raceLimit = 4
    override val description = listOf(
        "§7谷主似乎要教你更多关于妖族与自然共鸣的事。",
        "§7去听听他的安排吧。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptGuzhuStart = listOf(
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f哦？这么快就回来了？两颗§e启灵果§f，一颗不少——看来小蔓那丫头教得不错。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f孩子，你可知我们§2§o妖族§f为何能在这片大陆上存活至今？人仙二族视我等为异类，见则诛之，可我们的血脉，从未断绝。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f靠的不只是自身的本事，更是这§e大自然的恩赐§f。一山一水，一花一树，在我们妖族眼里，都不是死物，而是可以§e心意相通的伙伴§f。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f你方才用开物术摘下启灵果，便已经尝到这份共鸣的滋味了——不是强取，而是感应；不是掠夺，而是呼应。这便是我们妖族的立足之本。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f不过，想与自然共鸣，先要学会§e感应自身§f。连自己都看不清，又怎能看清这天地万物？",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f你去咱们叶灵谷的§e铁匠铺§f走一趟，请掌柜帮你打一口§e钟§f出来。别小看这钟，到了那儿掌柜自会告诉你其中的奥妙。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f从我身后这棵大树的楼梯上去，走二楼吊桥，沿途有路牌给你指方向。快去吧。"
    )

    private val scriptSmith = listOf(
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f哟，新面孔！这谷里多久没见着新生的崽儿了——你是谷主派来的？",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f谷主让你来打一口§e钟§f？嘿嘿，老头子说话就是爱绕弯子。他要的不是什么报时的玩意儿，是§6天机令§f！",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f这§6天机令§f，原本是§c人族§f在皇城里头那天机阁折腾出来的东西。持此令者，可§b通晓三界之事§f，§a规划下一步方向§f，甚至能§d随时存取物件§f——确实是个好宝贝。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f不过嘛，他们人族能造，咱们妖族就不能学？当年咱们有几个机灵的族人混进皇城，在天机阁里卧底了好些年，愣是把这打造之法给摸透了，悄悄带回了谷里。这事儿可别到处嚷嚷，咱们自己心里清楚就行。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f正巧我这儿还剩些材料——也是那几个族人当年顺手从皇城§c捎回来§f的，攒了这些年也没舍得用。你可得仔细着使，弄坏了可没处补去，皇城那地方不是随便就能再溜进去一趟的。锻造台就在旁边，跟我来。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f什么，不会锻造？不打紧不打紧，咱们妖族学东西向来靠的是心传，我演示一遍，你跟着做，准能成！",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f瞧好了——先站到锻造台前，伸手一碰，界面自然就开了。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f台上分四大类：§e武器§f、§e防具§f、§e法宝§f、§e杂项§f。想造什么，先找准门类。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f每一件器物都有它的配方，点进去就能瞧见需要哪些材料。缺什么，记下来，回头去四处搜罗便是。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f你这锻造的手艺越是纯熟，能打造的东西就越多。每成一器，经验便涨一分，等级自然往上升。",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f若有机缘完成特殊委托，或亲手锻出珍品，还能提升你的§e锻造资质§f——日后那些传说中的神兵利器，可都得靠这份资质才能碰呢！",
        "§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f好啦，材料给你备齐了。§6天机令§f在§e杂项§f那一栏里，找出来，亲手锻一枚。打好之后，带去给谷主过目，他自有话说。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e谷主 §c对话")
            1 -> listOf("§a已与谷主对话", "§c前往 §e铁匠铺 §c寻找掌柜")
            2 -> listOf("§a已获得材料", "§c制作 §6[天机令] §c并主手持有左键谷主")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 3
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +80")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 80)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        if (npcId == StoryNpcs.YAO_GUZHU.id) {
            when (currentProgress) {
                0 -> {
                    playDialogue(player, scriptGuzhuStart) {
                        plugin.questManager.updateProgress(player, id, 1)
                        player.sendMessage("§a[任务] -> 请前往叶灵谷铁匠铺寻找掌柜。")
                    }
                    return true
                }

                1 -> {
                    player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f铁匠铺在古树二楼吊桥那边，沿着路牌走便能找到。")
                    return true
                }

                2 -> {
                    if (hasTianjiTokenInMainHand(player)) {
                        player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f嗯，这便是天机令了。能亲手铸成此物，说明你已经开始懂得感应自身了。")
                        plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                    } else {
                        player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f天机令可铸好了？做好后主手持有它，再来左键找老夫。")
                    }
                    return true
                }
            }
        }

        if (npcId == StoryNpcs.YAO_TIEJIANGPUZHANGGUI.id) {
            when (currentProgress) {
                1 -> {
                    playDialogue(player, scriptSmith) {
                        giveMaterials(player)
                        plugin.questManager.updateProgress(player, id, 2)
                        player.sendMessage("§a[任务] -> 已获得材料，请在锻造台的杂项中制作天机令。")
                    }
                    return true
                }

                2 -> {
                    player.sendMessage("§e[${StoryNpcs.YAO_TIEJIANGPUZHANGGUI.displayName}§e] §f快去试试锻造台吧，§6天机令§f就在§e杂项§f里。")
                    return true
                }
            }
        }

        return false
    }

    private fun giveMaterials(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val core = rm.getItem("tianjihe")
        val needle = rm.getItem("tiezhen")

        if (core == null || needle == null) {
            player.sendMessage("§c[错误] 无法获取任务物品配置，请联系管理员！")
            return
        }

        needle.amount = 2
        val leftovers = player.inventory.addItem(core, needle)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        }

        player.sendMessage("§e[系统] 获得 ${core.itemMeta?.displayName ?: "天机核"} x1")
        player.sendMessage("§e[系统] 获得 ${needle.itemMeta?.displayName ?: "铁针"} x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun hasTianjiTokenInMainHand(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (Hjh_database.instance.menuManager.isTianjiToken(item)) return true

        val meta = item.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName.contains("天机令")
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index >= scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }
}
