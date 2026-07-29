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

class Shen_05 : QuestBase("main_shen_5", "[神族主线]丹塔问药", QuestType.MAIN, 5) {

    override val raceLimit = 0

    override val description = listOf(
        "§7白木虫害需要丹药治疗。",
        "§7前往丹塔，向负责人寻求帮助。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val resourceCenterScript = listOf(
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f嗯，品质合乎标准。这样你日后的修行，我们便放心了。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f刚瞧见丹塔的负责人回来了。你去寻他一趟，看看有没有法子治这棵树的病——再这么下去，非变成一棵白树不可。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f丹塔就在你来时的路上，高塔有门牌，一眼便能看到。去吧。"
    )

    private val danTaScript = listOf(
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f哦？那棵树的病竟已重到这个地步了么……",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f不必慌张。老夫虽不通树木之理，但这树久居神境，早已通了神性。给族人疗伤的丹药，用在它身上同样奏效。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f拿着，这是§e药引§f和§a草药§f。去旁边的炼药锅，炼几炉丹药出来，回头交给资源中心的负责人便是。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f不会用？无妨，听一遍。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f丹药分§e初级§f、§e中级§f、§e高级§f三品。冶药法等级越高，能炼的品级越高。不过高级丹药唯有§d医师§f方可炼制——那不仅是手艺，还需一颗济世之心。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f操作不难：走到炼药锅前，伸手即触，界面自开。选好丹药与品级，药锅便与你共鸣，锅面会浮现所需材料的影子。照着影子将材料逐一投入，材料齐备，丹药自会凝成，落入你行囊中。记住——共鸣期间勿离锅太远，否则会中断。不过断了也无妨，材料不损，回来重新开炉便是。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f还有一桩要事。丹药炼成即可服用，见效极快——这是好处。但§c是药三分毒§f，服下丹药后体内会生出一种§c药丹疾病§f。此症不痛不痒，却会在一定时间内排斥其他丹药的药力。药丹疾病尚在，别的丹药便灌不进去。越是猛烈的丹药，持续越久。日后对敌时，吃药须掐准时机，切勿连灌。",
        "§e[${StoryNpcs.SHEN_DANTAFUZEREN.displayName}§e] §f今日便从简单的练起——去炼§e二十枚§f初窥丹药。够治那棵树了，还能余下不少留给你自己。去吧。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e资源中心负责人 §c对话")
        1 -> listOf("§c前往 §e丹塔 §c寻找负责人")
        2 -> listOf("§a已获得炼药材料", "§c炼制 20 枚 §b新手疗愈丹[初窥] §c并主手持有一枚交给负责人")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        when {
            npcId == StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.id -> {
                return handleResourceCenterDialogue(player, currentProgress)
            }

            npcId == StoryNpcs.SHEN_DANTAFUZEREN.id -> {
                return handleDanTaDialogue(player, currentProgress)
            }
        }
        return false
    }

    private fun handleResourceCenterDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            0 -> playDialogue(player, resourceCenterScript) {
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 前往丹塔寻找负责人。")
            }

            1 -> player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f丹塔就在来时的路上，找到负责人问问治疗白木的法子。")

            2 -> {
                if (!isHoldingNoviceHealingPill(player.inventory.itemInMainHand)) {
                    player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f丹药炼好了吗？主手拿一枚§e新手疗愈丹§f给我看看。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                consumeMainHandItem(player)
                player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f太好了，这下这些树有救了。你且留§e一枚丹药§f在此即可，其余的便自己收好，日后修行路上用得上。")
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }

            else -> return false
        }
        return true
    }

    private fun handleDanTaDialogue(player: Player, currentProgress: Int): Boolean {
        if (currentProgress != 1) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= danTaScript.size) return true

        player.sendMessage(danTaScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == 2) {
            giveMaterials(player)
        }

        talkProgress[player.uniqueId] = index + 1
        if (index == danTaScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 2)
            player.sendMessage("§a[任务] -> 已获得材料，请在炼药锅炼制 20 枚新手疗愈丹。")
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
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

    private fun giveMaterials(player: Player) {
        val yaoyin = plugin.resourceManager.getItem("hjh_xsyy")
        val caoyao = plugin.resourceManager.getItem("hjh_cy")
        if (yaoyin == null || caoyao == null) {
            player.sendMessage("§c[错误] 无法获取炼丹材料配置，请联系管理员！")
            return
        }

        yaoyin.amount = 1
        caoyao.amount = 2
        val leftovers = player.inventory.addItem(yaoyin, caoyao)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
        }

        player.sendMessage("§e[系统] 获得 ${yaoyin.itemMeta?.displayName ?: "药引"} x1")
        player.sendMessage("§e[系统] 获得 ${caoyao.itemMeta?.displayName ?: "草药"} x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun isHoldingNoviceHealingPill(item: ItemStack): Boolean =
        item.type == Material.POTION && item.itemMeta?.displayName?.contains("新手疗愈丹") == true

    private fun consumeMainHandItem(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= 1
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
    }
}
