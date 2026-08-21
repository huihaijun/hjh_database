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

class Zhan_03 : QuestBase("main_zhan_3", "[战神族主线]战备锻造", QuestType.MAIN, 3) {

    override val raceLimit = 3

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    override val description = listOf(
        "§7族长还有别的事情要交代，先别急着离开议事厅。",
        "§7前往§e战备资源部§7学习锻造，并亲手打造一枚§6天机令§7。"
    )

    private val chiefScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f不错，没让这石头累着。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f刚巧盟里送饭的来过，这几个§e包子§f拿去垫垫，吃完再办事。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我族行事向来讲究协同，可战场上落单也是常事。到时消息不通、行囊沉重、不知下一步该做什么——都是麻烦。总得有个东西替你分担。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f族里早前有人在§d天机阁§f干过下手，摸清了那口钟——§6天机令§f的锻造工艺。此令轻巧便携，能解你后顾之忧。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f去§e战备资源部§f找负责人，他那儿还有材料，会教你如何锻造。"
    )

    private val resourceOfficerScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f来了。我是这儿的负责人。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f锻造房？就在我身后。没一眼认出来吧——故意的。这种地方，总要留点遮蔽。真到神族那帮混账再打过来，也不至于把家底全亮出去。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f族长让你打的§6天机令§f，就在里面锻造。材料我给你备好了，拿着。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f记住，先站到锻造台前，伸手即碰，界面自开。台上分§e武器§f、§e防具§f、§e法宝§f、§e杂项§f四类，想要什么先找准门类。每件器物都有配方，点开就能看清所需材料，缺什么补什么。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f锻造手艺越纯熟，能造的东西越多。每成一器，经验便涨一分，等级跟着往上升。日后若完成特殊委托或锻出珍品，还能提升§e锻造资质§f——传说级的兵刃，没这资质碰都别想碰。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f§6天机令§f在§e杂项§f栏里。自己去锻，锻好了拿来我过目。",
        "§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f没问题的话，别急着回去。你这趟路不近，能办完的事一趟办完——对面就是§e炼丹室§f，去那儿炼些丹药再走。族长前些日子也催过，你顺手一起带回去，他看你办事利落，后面也敢把更要紧的差事交给你。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c继续与族长对话")
        1 -> listOf("§c前往战备资源部，与负责人对话")
        2 -> listOf("§c在杂项中锻造天机令，主手持有后交给负责人过目")
        else -> listOf("§a天机令已通过战备资源部验收")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 3

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 0 -> {
                val finished = playDialogue(player, chiefScript) { index ->
                    if (index == BAOZI_LINE_INDEX) giveBaozi(player)
                }
                if (finished) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 前往战备资源部寻找负责人。")
                }
                true
            }

            npcId == StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.id && currentProgress == 1 -> {
                val finished = playDialogue(player, resourceOfficerScript) { index ->
                    if (index == MATERIAL_LINE_INDEX) giveForgeMaterials(player)
                }
                if (finished) {
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] -> 已获得材料，请在锻造台的杂项中制作天机令。")
                }
                true
            }

            npcId == StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.id && currentProgress == 2 -> {
                if (!hasTianjiTokenInMainHand(player)) {
                    player.sendMessage("§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f天机令还没锻好？做好后拿在主手，再来找我过目。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }
                player.sendMessage("§e[${StoryNpcs.ZHAN_ZHANBEIZIYUANBUFUZEREN.displayName}§e] §f成色不错，拿稳了。天机令不必交给我，往后的路你自己用得上。")
                plugin.questManager.updateProgress(player, id, 3)
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

    private fun giveBaozi(player: Player) {
        val baozi = ItemStack(Material.BREAD, 15).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }
        giveItem(player, baozi, "包子")
        player.sendMessage("§e[系统] 获得 §f包子 x15")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun giveForgeMaterials(player: Player) {
        val core = plugin.resourceManager.getItem("tianjihe")
        val needle = plugin.resourceManager.getItem("tiezhen")
        if (core == null || needle == null) {
            player.sendMessage("§c[错误] 无法获取天机令锻造材料，请联系管理员！")
            return
        }

        needle.amount = 2
        giveItem(player, core, "天机核")
        giveItem(player, needle, "铁针")
        player.sendMessage("§e[系统] 获得 §b天机核 x1")
        player.sendMessage("§e[系统] 获得 §b铁针 x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun giveItem(player: Player, item: ItemStack, displayName: String) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }

    private fun hasTianjiTokenInMainHand(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (plugin.menuManager.isTianjiToken(item)) return true
        val meta = item.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName.contains("天机令")
    }

    private companion object {
        const val BAOZI_LINE_INDEX = 1
        const val MATERIAL_LINE_INDEX = 2
    }
}
