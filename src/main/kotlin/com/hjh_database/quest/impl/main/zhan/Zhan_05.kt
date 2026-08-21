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

class Zhan_05 : QuestBase("main_zhan_5", "[战神族主线]战神族证明", QuestType.MAIN, 5) {

    override val raceLimit = 3

    override val description = listOf(
        "§7你已掌握战神族传授的基础技艺。",
        "§7返回议事厅，接受族长最后的嘱托。",
        "§7领取§e战神族证明§7，调查四方圣兽结界的异动。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val chiefScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f咱们相处虽不算久，但我在你身上看到了希望——看到了我族§e复仇成功§f的希望。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我之前与你说过，我们被赶到这荒芜之地，全拜那帮§c不知好歹的神族§f所赐。可真要说我们当年为何节节败退，连盟主都下落不明，其实根源不在神族那帮废物自己。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f他们不知从哪里弄来了§4§n四头圣兽§f。那东西根本就是§c杀戮机器§f，如大浪淘沙般推平了我们一座座基地，残酷地镇压了我们。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我们也曾试图§e直捣黄龙§f，擒贼先擒王，想先宰了这几头畜生。为此牺牲了数名§e精英杀手§f，结果也不过是让四兽受了伤，躲回了祭坛。受制于四兽的§c结界§f，我族这些年一直不敢有大动作。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f不过最近，四兽的力量似乎有了§e减弱§f的迹象。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f对我族来说，这是§c千载难逢§f的时机。而这些天对你的观察，也让我确信——你已经够格成为我族的§e正式一员§f。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f这枚§e战神族证明§f，现在授予你。我族常年备战，对战斗的体悟比别族更深。日后你在外作战便会发现，你的§e战斗经验§f增长得比其他人更快，对§e心得体悟§f的运用也自有不同。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f拿好证明。现在，去世界各地的§e圣兽祭坛§f，查清结界的情况，顺便看看那几头畜生到底是死是活。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f不必担心孤立无援。我族在四大区域都安插了§e眼线§f，日后你抵达各地居民区，凭这份证明，或许能获得一些有用的情报。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f先去人族皇城，§e皇宫后院东边§f的§e月老小庙§f。那里有人会告诉你更多。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f这些§e盘缠和物资§f带上。族里不宽裕，给不了你太多，只望你平安归来。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f有你在，我族的出头之日，便§e指日可待§f。去吧。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回议事厅，与族长对话")
        else -> listOf("§a已获得战神族证明")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_ZUZHANG.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in chiefScript.indices) return true

        player.sendMessage(chiefScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == chiefScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        giveQuestItems(player)
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let { data ->
            data.status = 3
            plugin.databaseManager.savePlayerAsync(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("  §e[奖励] §f战神族证明 x1、包子 x20、铜钱 x20、新芽之羽 x1")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f前往皇城皇宫后院东边的月老小庙")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveQuestItems(player: Player) {
        val proof = plugin.resourceManager.getItem("zhan_zm_begin") ?: ItemStack(Material.GREEN_DYE).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] zhan_zm_begin") }
        }
        val copper = plugin.resourceManager.getItem("hjh_tongqian") ?: ItemStack(Material.SUNFLOWER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§6铜钱") }
        }
        val feather = plugin.resourceManager.getItem("hjh_xyzy") ?: ItemStack(Material.FEATHER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] hjh_xyzy") }
        }
        val buns = ItemStack(Material.BREAD, BUN_AMOUNT).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }

        proof.amount = 1
        copper.amount = COPPER_AMOUNT
        feather.amount = 1
        giveItem(player, proof, "战神族证明")
        giveItem(player, buns, "包子")
        giveItem(player, copper, "铜钱")
        giveItem(player, feather, "新芽之羽")

        player.sendMessage("§e[系统] 获得 ${proof.itemMeta?.displayName ?: "战神族证明"} x1")
        player.sendMessage("§e[系统] 获得 §f包子 x$BUN_AMOUNT")
        player.sendMessage("§e[系统] 获得 ${copper.itemMeta?.displayName ?: "铜钱"} x$COPPER_AMOUNT")
        player.sendMessage("§e[系统] 获得 ${feather.itemMeta?.displayName ?: "新芽之羽"} x1")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun giveItem(player: Player, item: ItemStack, displayName: String) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }

    private companion object {
        const val BUN_AMOUNT = 20
        const val COPPER_AMOUNT = 20
    }
}
