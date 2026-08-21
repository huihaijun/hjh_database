package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_04 : QuestBase("main_xian_4", "[仙族主线]灵丹初成", QuestType.MAIN, 4) {

    override val raceLimit = 1

    override val description = listOf(
        "§7前往仙器铺旁的§e灵丹铺§7，向掌柜学习炼丹之法。",
        "§7炼制新手疗愈丹，并向§e仙族盟主§7提交五枚。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val shopkeeperScript = listOf(
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f哦…原来是刚飞升的朋友，欢迎光临。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f我和仙器铺那位可不一样。那位吝啬又卑鄙、见钱眼开、无节操无廉耻、不知道飞升上来做什么的、整天只想着偷人油水、没肩膀没担当没人性不是男……啊，抱歉，情绪有些激动，道友别往心里去。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f……什么？你说我与他有过节？没有的事，真没有。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f不过就是初飞升时被那家伙哄着用五张兑换券换了一把破匕首，此后天天被妖兽追着咬。于是一气之下弃了武道，改修丹道，勤炼猎妖之术与炼丹之法，成了如今仙界无人不知、前无古人后无来者的§e第一灵丹铺§f大老板。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f我绝对没有对那家伙店里来的人加收百分之一千的手续费，绝对没有。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f……咳，扯远了。盟主前几日差人要一批丹药，我正愁没人搭手。你来得正好，这些材料拿着，足够炼§e二十枚新手疗愈丹§f。我来教你。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f丹药分§e初级§f、§e中级§f、§e高级§f三品。炼丹之术越精，能炼的品级越高。不过高级丹药唯有§d医师§f方可驾驭——那不是手巧就够的，还需一颗济世之心。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f操作不难：站到炼药锅前，伸手一触，界面自开。选好丹药与品级，锅身便会与你共鸣，锅面浮现所需材料的§e影子§f。照影投料，材料齐备，丹药自会凝成，落入你的行囊。共鸣时别离锅太远，断了也无妨，材料不损，回来重开便是。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f还有一桩须牢记——丹药炼成即可服用，见效极快，这是利处。但§c是药三分毒§f，服下后体内会生出§c药丹疾病§f，一段时日内排斥他药之力。此症未消，再好的灵丹也灌不进去。丹药越猛，此症拖得越久。日后临敌，服药务必§c掐准时机§f，切莫连灌。",
        "§e[${StoryNpcs.XIAN_LINGDANPUZHANGGUI.displayName}§e] §f去炼吧。炼好了，就送去给盟主。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往 §e灵丹铺 §c与掌柜对话")
        1 -> listOf("§a已获得炼丹材料", "§c炼制新手疗愈丹，主手持有至少五枚后交给仙族盟主")
        else -> listOf("§a已将五枚新手疗愈丹交给仙族盟主")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.XIAN_LINGDANPUZHANGGUI.id && currentProgress == 0) {
            playShopkeeperDialogue(player)
            return true
        }

        if (npcId == StoryNpcs.XIAN_XIANZUMENGZHU.id && currentProgress == 1) {
            turnInPills(player)
            return true
        }

        return false
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

    private fun playShopkeeperDialogue(player: Player) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in shopkeeperScript.indices) return

        say(player, shopkeeperScript[index])
        if (index == MATERIAL_LINE_INDEX && !giveAlchemyMaterials(player)) return

        if (index == shopkeeperScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 请炼制新手疗愈丹，主手持有至少五枚后交给仙族盟主。")
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun turnInPills(player: Player) {
        val hand = player.inventory.itemInMainHand
        if (!isNoviceHealingPill(hand) || hand.amount < TURN_IN_AMOUNT) {
            player.sendMessage("§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f丹药尚未备齐么？将至少§e五枚新手疗愈丹§f拿在主手，再交给我吧。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        hand.amount -= TURN_IN_AMOUNT
        player.sendMessage("§7（你将五枚新手疗愈丹交给了仙族盟主。）")
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
        val data = plugin.playerManager.getPlayerData(player) ?: return
        plugin.questManager.completeQuest(player, data, this)
    }

    private fun giveAlchemyMaterials(player: Player): Boolean {
        val catalyst = plugin.resourceManager.getItem("hjh_xsyy")
        val herb = plugin.resourceManager.getItem("hjh_cy")
        if (catalyst == null || herb == null) {
            player.sendMessage("§c[错误] 无法获取炼丹材料，请联系管理员！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return false
        }

        catalyst.amount = 1
        herb.amount = 2
        giveItem(player, catalyst, "新手疗愈丹药引")
        giveItem(player, herb, "草药")
        player.sendMessage("§e[系统] 获得 ${catalyst.itemMeta?.displayName ?: "药引"} x1")
        player.sendMessage("§e[系统] 获得 ${herb.itemMeta?.displayName ?: "草药"} x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
        return true
    }

    private fun giveItem(player: Player, item: ItemStack, displayName: String) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }

    private fun isNoviceHealingPill(item: ItemStack): Boolean {
        return item.type == Material.POTION &&
            item.itemMeta?.displayName?.contains("新手疗愈丹") == true
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private companion object {
        const val MATERIAL_LINE_INDEX = 5
        const val TURN_IN_AMOUNT = 5
    }
}
