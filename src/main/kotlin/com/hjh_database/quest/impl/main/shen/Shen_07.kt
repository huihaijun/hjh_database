package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Shen_07 : QuestBase("main_shen_7", "[神族主线]圣山受阻", QuestType.MAIN, 7) {

    override val raceLimit = 0

    override val description = listOf(
        "§7前往皇城，向人皇与护国法师询问圣山异动。",
        "§7前往圣山入口，查明无法进入的原因。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val emperorScript = listOf(
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上，您总算来了。朕恭候多时了。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f不瞒您说，近来皇城四周的野兽不知为何变得异常狂暴，朕正为此事焦头烂额……",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f……啊？您此行并非为此而来？也罢也罢，朕再另寻他人相助便是。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f神上若是为§e圣山§f一事而来，不妨去询问§4§n护国法师——法海§f。圣山之事，他知道的远比我多。法海大师就在§e皇宫东侧的护国殿§f，神上一去便知。"
    )

    private val fahaiFirstScript = listOf(
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f神上驾到，有失远迎，还请神上恕罪。不知神上此番前来，所为何事？",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f原来如此……是为了§e圣山§f之事。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f说来惭愧，关于圣山，我所知怕是远不及诸位神上。不过既蒙垂询，我自当尽己所能。据我所知，圣山乃§4§n盘古大神§f开天辟地之后力尽殒落之地，灵气充沛，由§e蓬莱仙岛§f的蓬莱一族世代守护。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f近来我也感应到圣山方向有异常气息传来，只是相隔千里，详情无从得知。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f这样——我这里有一面§6重华镜§f。此镜表面看来是仙人留在此处的法宝，实则乃§e上古神器§f。传闻若由神族亲自使用，可化千里于一隙，瞬息之间抵达任意去处。神上不妨以此镜前往北方圣山，一探究竟。"
    )

    private val fahaiReturnScript = listOf(
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f原来如此……看来是圣山的自我防卫机制将神上挡了回来。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f但这与圣兽的祝福之间究竟有何关联，请恕我学识浅薄，无法解答。此事，恐怕还需神上回族中向§e长老大人§f请教。他对圣山与圣兽的了解远胜于我，或许能告知神上眼下的缘由。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f不过，虽无法以重华镜缩地成寸，我这里倒另有一件东西，想请神上过目。此物名为§6重华晶§f。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f这晶石颇有些玄妙——往地上一放，便能自行吸纳四方灵气精华，将其刻入晶脉之中，再与你的灵识共鸣，让你与这片天地建立起一种奇妙的联系。再配上这块刻着“§e命§f”字的绿石，只需轻轻一触，便能瞬间抵达你此前建立过联系的区域。可谓缩地成寸，日行千里。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f不过这“命”石有个规矩——它只认同一方天地里的§6重华晶§f。这片大陆共分四域：§a东方森林§f、§e西方山脉§f、§c南方沙漠§f、§b北方湖泊§f。东边的“命”石，可传不到南边去。四块“命”石，便立在四域入口处。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f再者，传送一事颇为消耗§6重华晶§f的灵力。每用过一次，这一方天地便需歇上片刻才能再次发动，好在其他区域不受影响。日后神上在外闯荡，若遇着别的§6重华晶§f，记得顺手触碰一下，便可与之共鸣。结下的缘分越多，日后赶路便越省心。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f这十枚§e重生石§f也请神上一并收好。在外厮杀，毙命在所难免。用此石复生，可免天道责罚，重获新生。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f我便不再耽搁了。长老那边，想必也在等神上的消息。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e人皇-轩辕氏 §c对话")
        1 -> listOf("§c前往护国殿，与 §e护国法师-法海 §c对话")
        2 -> listOf("§c前往北方圣山入口，尝试使用重华镜")
        3 -> listOf("§c返回护国殿，向 §e护国法师-法海 §c回报")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean = when (npcId) {
        StoryNpcs.RENHUANGXUANYUANSHI.id -> handleEmperorDialogue(player, currentProgress)
        StoryNpcs.REN_FAHAI.id -> handleFahaiDialogue(player, currentProgress)
        else -> false
    }

    private fun handleEmperorDialogue(player: Player, currentProgress: Int): Boolean {
        if (currentProgress != 0) return false
        playDialogue(player, emperorScript) {
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 前往皇宫东侧的护国殿，寻找护国法师-法海。")
        }
        return true
    }

    private fun handleFahaiDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            1 -> playDialogue(player, fahaiFirstScript) {
                plugin.questManager.updateProgress(player, id, 2)
                player.sendMessage("§a[任务] -> 前往北方圣山入口，尝试使用重华镜。")
            }

            2 -> player.sendMessage("§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f神上请先前往北方圣山入口，看看重华镜能否带您进入圣山。")

            3 -> playDialogue(player, fahaiReturnScript) {
                giveRespawnStones(player)
                completeChonghuaSideQuest(player)
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }

            else -> return false
        }
        return true
    }

    private fun giveRespawnStones(player: Player) {
        val stones = plugin.resourceManager.getItem("relive_stone") ?: ItemStack(Material.EMERALD).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§e重生石") }
        }
        stones.amount = 10
        giveItem(player, stones)
        player.sendMessage("§e[系统] 获得 ${stones.itemMeta?.displayName ?: "重生石"} x10")
    }

    private fun completeChonghuaSideQuest(player: Player) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses["side_ren_1"] == QuestStatus.COMPLETED) return

        val sideQuest = plugin.questManager.getQuest("side_ren_1") ?: return
        plugin.questManager.completeQuest(player, data, sideQuest)
    }

    private fun giveItem(player: Player, item: ItemStack) {
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，物品已掉落在脚下！")
        }
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 280)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +280")
        player.sendMessage("  §b[同步] §f重华晶的奥秘已完成")
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
}
