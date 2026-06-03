package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID
import java.util.HashMap

class Ren_03 : QuestBase("main_ren_3", "[人族主线]工欲善其事", QuestType.MAIN, 3) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e村长 §c对话")
            1 -> listOf("§a已与村长对话", "§c前往 §e铁匠铺 §c寻找掌柜")
            2 -> listOf("§a已获得材料", "§c制作 §6[天机令] §c并主手持有左键村长")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7村长似乎对你有新的教导。",
        "§7去听听关于人族更多的故事吧。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    // 1. 村长第一阶段对话
    private val scriptChiefStart = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f这么重的山泉水一口气扛来，倒真有几分力气。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f我们人族便是如此——再难的环境，靠一股韧劲总能活下去。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f但人族能走到今天，不光靠自己，更靠“团结”二字。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f万众一心，山海可平。五族之中，唯我们最懂齐心之力。所以寿命虽短，却能创造诸多奇迹。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f想学团结，先学助人。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f你去村里铁匠铺，请掌柜打一口§e钟表§f来。屋里正好缺个看时辰的物件。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f铺子就在你来时的路边，招牌显眼。怎么打造，掌柜自会教你。"
    )

    // 2. 铁匠对话
    private val scriptSmith = listOf(
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f§e钟表§f？没听说最近有安排打造钟表啊……",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f哦——！村长说的“钟”，莫不是指§6天机令§f？",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f这东西可了不得！是咱们在§d天机阁§f当差的族人亲手炼制的宝贝。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f持此令者，可§b通晓三界之事§f，§a规划下一步方向§f，甚至能§d随时存取物件§f——可谓是每个族人行走天下必备的至宝！",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f正好，我这儿还剩些材料，你来得巧。锻造台在那边。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f不会？锻造可是咱人族吃饭的手艺！我只教一次，以后可别说不会啊。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f首先，找到锻造台，伸手一触，界面自开。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f台上分四类：武器、防具、法宝、杂项。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f每件物品都有配方，点进去就能看到所需材料。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f你锻造手艺越高，能造的东西就越多。每次成功，都会涨经验、提等级。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f完成特定任务或造出珍品，还能提升“锻造资质”——这可是将来打造神器的重要凭证！",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f材料给你，§6天机令§f就在“杂项”里。打好后，送去给村长吧。"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        // --- 场景 1: 找村长 (接任务 或 交任务) ---
        if (npcId == StoryNpcs.REN_CHIEF.id) {

            // 阶段 0: 听村长讲道理
            if (currentProgress == 0) {
                playDialogue(player, scriptChiefStart) {
                    // 对话结束回调：进入下一阶段
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 请前往铁匠铺寻找掌柜。")
                }
                return true
            }

            // 阶段 1: 还没找铁匠
            if (currentProgress == 1) {
                player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f铁匠铺就在路边，快去吧。")
                return true
            }

            // 阶段 2: 交天机令
            if (currentProgress == 2) {
                // 只检查主手，不扣除天机令，避免和天机令右键菜单产生交付后的道具丢失问题。
                if (hasTianjiTokenInMainHand(player)) {

                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f做得好！天机令已成，往后行走天下，也算多了一份凭依。")

                    // 完成任务
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                } else {
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f天机令打好了吗？做好后主手持有它，再来左键找我。")
//                    player.sendMessage("§7(提示：如果你弄丢了材料，可以找铁匠铺掌柜再要一份)")
                }
                return true
            }
        }

        // --- 场景 2: 找铁匠 (学锻造) ---
        if (npcId == StoryNpcs.REN_SMITH.id) {

            // 阶段 1: 听铁匠教学
            if (currentProgress == 1) {
                playDialogue(player, scriptSmith) {
                    // 对话结束回调：给材料，进下一阶段
                    giveMaterials(player)
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] -> 已获得材料，请寻找附近的锻造台制作天机令。")
                }
                return true
            }

            // 阶段 2: 补领材料 (人性化设计)
            if (currentProgress == 2) {
                player.sendMessage("§e[${StoryNpcs.REN_SMITH.displayName}§e] §f快去试试锻造台吧，§6天机令§f就在“杂项”里。")
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
     */
    private fun giveMaterials(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        // 从资源管理器获取物品
        val core = rm.getItem("tianjihe") // 天机核
        val needle = rm.getItem("tiezhen") // 铁针

        if (core == null || needle == null) {
            player.sendMessage("§c[错误] 无法获取任务物品配置，请联系管理员！")
            return
        }

        needle.amount = 2

        // 发送给玩家
        val inv = player.inventory

        // 尝试添加物品，addItem 会返回装不下的物品 Map (自动处理堆叠)
        val leftovers = inv.addItem(core, needle)

        // 如果有装不下的，丢在脚下
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
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

    /**
     * 简化的对话播放逻辑
     */
    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            talkProgress[player.uniqueId] = index + 1

            // 提示下一句
            if (index < scripts.size - 1) {
//                player.sendMessage("§a[任务] -> 点击左键继续...")
            } else {
                // 播放完毕
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            Hjh_database.instance.databaseManager.savePlayer(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    // 任务完成后不再触发对话逻辑
    override fun checkComplete(progress: Int): Boolean {
        return progress >= 3
    }
}
