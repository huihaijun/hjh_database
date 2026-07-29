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

class Ren_04 : QuestBase("main_ren_4", "[人族主线]初识丹道", QuestType.MAIN, 4) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e村长 §c对话")
            1 -> listOf("§a已接受教导", "§c前往 §e炼丹房 §c寻找掌柜")
            2 -> listOf("§a已获得丹方材料", "§c炼制 5颗 §b[新手疗愈丹] §c并交给村长")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7通过了坚韧与团结的考验。",
        "§7村长认为你已准备好接触更深奥的技艺。",
        "§7前往炼丹房，学习人族的生存之本。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    // 1. 村长对话
    private val scriptChiefStart = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f看来，你已具备了人族应有的品质。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f坚韧是我们的生存之道，团结是我们的发展之基。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f你通过了这两项考验，已是一位成熟的人族族员了。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f但若想外出冒险，有些事你必须知晓。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f这座峡谷是温暖的桃花源，受人族守护神——女娲庇护，我们才得以在此安心成长。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f可峡谷之外，危机四伏。一是野外残暴的怪物，它们不仅侵扰峡谷，还会破坏皇城。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f二则最可憎——那些妖族。前些年我们与仙族共建镇妖塔，才将他们镇压。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f可他们贼心不死，日夜觊觎此塔，妄图救出同族。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f一旦放出，对我人族将是灭顶之灾。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f好在皇城传来消息，妖族内丹是炼丹良方，可加快伤愈。这也是我族适应力强的原因之一。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f现在，你去峡谷炼丹房，为我炼五颗“新手疗愈丹”练练手。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f炼丹房在我房间的上面一层，出门右转，过吊桥即是。路上也有路牌指引。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f掌柜会在那里等你，他会教你炼丹之法。"
    )

    // 2. 炼丹房掌柜对话
    // 注意：请确保 StoryNpcs 中包含 REN_ALCHEMIST，如果没有，请替换为正确的 NPC ID 引用
    private val scriptAlchemist = listOf(
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f嗯？年轻人，看你眉宇间透着灵气，莫非对我们人族的炼丹之术有兴趣？",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f原来是村长让你来的。好啊，好啊，能走到这一步的年轻人不多，你能通过那两道考验，说明你确有为人族分忧的决心。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f我们人族的炼丹之术，称作【冶药法】。这和锻造术一样，是我们立足于世的根本手艺。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f但炼丹与锻造不同，不是拿到材料就能开工。你必须先学习【丹方】——那是丹药的配方与炼制要诀。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f日后的冒险中，你会在各处遇到丹方，或是购买，或是机缘所得。只有学会了丹方，才能炼制对应的丹药。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f丹药分初级、中级、高级三品。你的冶药法等级越高，能炼制的品级也就越高。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f不过，高级丹药唯有【医师】职业方可炼制。你若真想深研此道，日后可去皇城寻访医师导师。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f具体如何操作呢？你且听好：轻触这炼药锅，界面便会展开，选定丹药与品级后，药锅会与你共鸣，在上方浮现所需材料。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f此时，将对应的材料投入锅中即可。若材料无误，丹药即成，自动收入你的行囊。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f需注意的是，共鸣期间不可离锅过远，否则会中断炼制。但放心，材料不会损耗，你可重新开始。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f对了，还有一事需谨记。丹药炼成后即可服用，见效迅速，此乃一大便利。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f然而，是药三分毒。服下丹药后，你会进入一种名为【药丹疾病】的状态。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f此疾期间，你体内会排斥其他丹药的药力。只要【药丹疾病】未消，便无法再服下任何其他丹药。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f此状态持续时间长短，依丹药效力而定。越是高级猛烈的丹药，【药丹疾病】便持续越久，有时甚至可达半分钟！日后在外冒险，服用丹药时定要注意时机！",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f来，这是“新手疗愈丹”的丹方与材料。此丹方简单，无需用到那些妖族的肮脏内丹——说来可恨，他们虽可憎，其内丹却是疗伤良药。近来妖族内丹稀少，这初级丹药正好用不上。",
        "§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f你且去试试手，炼成五颗，交给村长。他会明白，你已经准备好离开峡谷，为人族的未来而战了。"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        // --- 场景 1: 找村长 (接任务 或 交任务) ---
        if (npcId == StoryNpcs.REN_CHIEF.id) {

            // 阶段 0: 听村长讲世界观
            if (currentProgress == 0) {
                playDialogue(player, scriptChiefStart) {
                    // 对话结束回调
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 请前往炼丹房寻找掌柜。")
                }
                return true
            }

            // 阶段 1: 还没找掌柜
            if (currentProgress == 1) {
                player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f炼丹房就在出门右转过桥处，去吧。")
                return true
            }

            // 阶段 2: 交丹药 (需要5颗)
            if (currentProgress == 2) {
                // 检查背包里有没有 5个 [新手疗愈丹] (Potion)
                if (checkAndRemovePotions(player, "新手疗愈丹", 5)) {
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f很好，成色不错。")
                    // 完成任务
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                } else {
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f丹药炼好了吗？我需要 5颗【新手疗愈丹】。")
                    player.sendMessage("§7(提示：请使用炼药锅进行炼制)")
                }
                return true
            }
        }

        // --- 场景 2: 找炼丹房掌柜 (学炼丹) ---
        if (npcId == StoryNpcs.REN_ALCHEMIST.id) {

            // 阶段 1: 听掌柜教学
            if (currentProgress == 1) {
                playDialogue(player, scriptAlchemist) {
                    // 对话结束回调：给材料，进下一阶段
                    giveMaterials(player)
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] -> 已获得材料，请点击附近的炼药锅炼制 5颗 新手疗愈丹。")
                }
                return true
            }

            // 阶段 2: 补领材料提醒
            if (currentProgress == 2) {
                player.sendMessage("§e[${StoryNpcs.REN_ALCHEMIST.displayName}§e] §f炼药需心静。炼好了，就去找村长报告吧！")
                return true
            }
        }

        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /**
     * 发放任务材料 (使用 ResourceManager)
     * 发放 5份 药引 和 5份 草药
     */
    private fun giveMaterials(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        // 从资源管理器获取物品
        val yaoyin = rm.getItem("hjh_xsyy") // 药引
        val caoyao = rm.getItem("hjh_cy") // 草药

        if (yaoyin == null || caoyao == null) {
            player.sendMessage("§c[错误] 无法获取炼丹材料配置，请联系管理员！")
            return
        }

        // 设置数量为 5
        yaoyin.amount = 1
        caoyao.amount = 3

        // 发送给玩家
        val inv = player.inventory
        val leftovers = inv.addItem(yaoyin, caoyao)

        // 掉落处理
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        }

        player.sendMessage("§e[系统] 获得 ${yaoyin.itemMeta?.displayName ?: "药引[新手疗愈丹]"} x1")
        player.sendMessage("§e[系统] 获得 ${caoyao.itemMeta?.displayName ?: "人族草药"} x3")
        // 如果有丹方物品，也可以在这里发放，暂且略过

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    /**
     * 检查并扣除指定名称和数量的药水
     * @param player 玩家
     * @param namePart 药水名称包含的部分
     * @param amountRequired 需要的总数量
     */
    private fun checkAndRemovePotions(player: Player, namePart: String, amountRequired: Int): Boolean {
        // 1. 统计背包里的符合条件的药水数量
        var totalFound = 0
        val slotsToRemove = HashMap<Int, Int>() // Slot -> Amount to remove from that slot

        for ((index, item) in player.inventory.contents.withIndex()) {
            if (item == null || item.type != Material.POTION) continue

            val meta = item.itemMeta ?: continue
            if (meta.displayName.contains(namePart)) {
                totalFound += item.amount
            }
        }

        // 2. 如果数量不足，返回 false
        if (totalFound < amountRequired) {
            return false
        }

        // 3. 数量足够，开始扣除
        var leftToRemove = amountRequired
        for ((index, item) in player.inventory.contents.withIndex()) {
            if (item == null || item.type != Material.POTION) continue
            val meta = item.itemMeta ?: continue

            if (meta.displayName.contains(namePart)) {
                val amountInSlot = item.amount
                val toRemove = Math.min(amountInSlot, leftToRemove)

                item.amount = amountInSlot - toRemove
                leftToRemove -= toRemove

                if (leftToRemove <= 0) break
            }
        }

        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
        return true
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
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +80")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 80)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 4 // 只要比当前最大流程数大即可
    }
}
