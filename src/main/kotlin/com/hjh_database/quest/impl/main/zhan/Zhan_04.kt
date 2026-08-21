package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Zhan_04 : QuestBase("main_zhan_4", "[战神族主线]炼丹补给", QuestType.MAIN, 4) {

    override val raceLimit = 3

    override val description = listOf(
        "§7前往战备资源部对面的§e炼丹室§7，学习冶药法。",
        "§7炼制§e二十枚新手疗愈丹§7，并带回议事厅交给族长。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val alchemyScript = listOf(
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f来了。我是炼丹室的工作人员。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f族长前脚刚来催过丹药补给的事，你后脚就到了。新生的族人刚落地就知道替族里分忧，不错。我族后辈要都像你这样，复仇的事就有着落了。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f材料库里还有些存货。拿着，清点一下——§e一份药引§f，§e两株草药§f。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f拿好了就听我说，冶药不难，就几步。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f丹药分§e初级§f、§e中级§f、§e高级§f三品。冶药法等级越高，能炼的品级越高。高级丹药只有§d医师§f能炼，没那份济世的心，光有手艺也白搭。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f操作很简单：走到炼药锅前，伸手一碰，界面自开。选好丹药和品级，药锅会跟你共鸣，锅面上浮出所需材料的影子。照着影子把材料丢进去，材料齐了丹药自会凝成，落进你行囊里。共鸣期间别跑太远，断了也不打紧，材料不损，回来重开便是。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f还有一桩，记牢了——丹药炼出来就能吃，见效快，这是好事。可§c是药三分毒§f，服下后体内会生出§c药丹疾病§f，一段时间内排拒其他丹药的药力。药丹疾病没消，再好的药也灌不进去。越是猛烈的丹药，这病拖得越久。日后跟人动手，吃药§c掐准时机§f，别傻乎乎地连灌。",
        "§e[${StoryNpcs.ZHAN_LIANDAN.displayName}§e] §f冶药锅就在我手边，你随便挑一口，炼好了直接回去找族长。他应该会收走一部分送医疗室，剩下的你自己留着，日后出去用得上。"
    )

    private val chiefScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f回来了。天机令打得如何？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f……不错。这是丹药？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f你刚来就知道给族里分忧，我族后辈果然有看头。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我不多拿，§e五颗§f我收下，送医疗室去。剩下§e十五颗§f你自己收着，日后外出别伤了自己。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往炼丹室，与工作人员对话")
        1 -> listOf("§a已获得炼丹材料", "§c炼制 20 枚 §b新手疗愈丹[初窥] §c并主手持有后交给族长")
        else -> listOf("§a丹药补给已交给族长")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 2

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.ZHAN_LIANDAN.id && currentProgress == 0 -> {
                val finished = playDialogue(player, alchemyScript) { index ->
                    if (index == MATERIAL_LINE_INDEX) giveAlchemyMaterials(player)
                }
                if (finished) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 请炼制 20 枚新手疗愈丹，随后主手持有丹药返回议事厅找族长。")
                }
                true
            }

            npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 1 -> {
                handleChiefDialogue(player)
                true
            }

            else -> false
        }
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 80)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +80")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun handleChiefDialogue(player: Player) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in chiefScript.indices) return

        if (index >= PILL_CHECK_INDEX && !hasRequiredPillsInMainHand(player)) {
            player.sendMessage("§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f数量不够。把§e二十枚新手疗愈丹§f一起拿在主手，再来找我。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        player.sendMessage(chiefScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == PILL_PROMPT_LINE_INDEX) {
            talkProgress[player.uniqueId] = PILL_CHECK_INDEX
            player.sendMessage("§a[任务提示] §f请将至少 §e20 枚新手疗愈丹[初窥] §f拿在主手，再与族长对话。")
            return
        }

        if (index == chiefScript.lastIndex) {
            consumePillsFromMainHand(player, PILL_TURN_IN_AMOUNT)
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 2)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun playDialogue(player: Player, script: List<String>, onLine: (Int) -> Unit): Boolean {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return false

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        onLine(index)

        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return index == script.lastIndex
    }

    private fun giveAlchemyMaterials(player: Player) {
        val catalyst = plugin.resourceManager.getItem("hjh_xsyy")
        val herb = plugin.resourceManager.getItem("hjh_cy")
        if (catalyst == null || herb == null) {
            player.sendMessage("§c[错误] 无法获取炼丹材料，请联系管理员！")
            return
        }

        catalyst.amount = 1
        herb.amount = 2
        giveItem(player, catalyst, "新手疗愈丹药引")
        giveItem(player, herb, "草药")
        player.sendMessage("§e[系统] 获得 ${catalyst.itemMeta?.displayName ?: "药引"} x1")
        player.sendMessage("§e[系统] 获得 ${herb.itemMeta?.displayName ?: "草药"} x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun giveItem(player: Player, item: ItemStack, displayName: String) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }

    private fun hasRequiredPillsInMainHand(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        return item.amount >= REQUIRED_PILL_AMOUNT &&
            item.type == Material.POTION &&
            item.itemMeta?.displayName?.contains("新手疗愈丹") == true
    }

    private fun consumePillsFromMainHand(player: Player, amount: Int) {
        val item = player.inventory.itemInMainHand
        item.amount -= amount
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
    }

    private companion object {
        const val MATERIAL_LINE_INDEX = 2
        const val PILL_PROMPT_LINE_INDEX = 1
        const val PILL_CHECK_INDEX = 2
        const val REQUIRED_PILL_AMOUNT = 20
        const val PILL_TURN_IN_AMOUNT = 5
    }
}
