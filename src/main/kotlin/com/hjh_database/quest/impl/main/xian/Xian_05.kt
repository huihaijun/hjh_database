package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import com.hjh_database.race.impl.XianRace
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_05 : QuestBase("main_xian_5", "[仙族主线]仙族证明", QuestType.MAIN, 5) {

    override val raceLimit = 1

    override val description = listOf(
        "§7你已掌握仙族传授的基础技艺。",
        "§7返回盟主大殿，领取§e仙族证明§7与下界任务。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val leaderScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f不错，看来你已适应了这具新飞升的躯体，气息沉稳，灵光内敛。既如此，便去替族里办些事吧。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f拿好，这是§e仙族证明§f。我族乃世间最富§e创造力§f的一族，聪慧如天高、似海深。你虽初入仙途，但凭我族血脉，在锻造等技艺的磨砺中，定会比其他种族更快领悟其中§e关窍§f，手艺精进之速亦非常人可及。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f此外，我族飞升之后，于这蜀山之地练就了一身仙风道骨，行动力非凡，真有§e缩地成寸、日行千里§f之能。持此证明，你便可与大陆上一些§e灵晶§f共鸣，将其绑定。日后只需以右手轻抚证明，便能借仙力飞梭至所绑之处。不过你修为尚浅，暂只能与§e四枚§f灵晶结契。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这些§e物资§f且收好，这根§e翎羽§f也带上，赶路时能省些脚力。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f从大殿下去，一路§e向西§f，有一座§e传送阵§f，可直达人族皇城。去皇宫寻§e御前带刀侍卫头领——羽罗§f。他会告知你更多详情。到了那边随机应变，务必协助人族查清此事的§c背后缘由§f。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f太平的日子，怕是维持不了太久了。去吧，万事留心。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回盟主大殿，与 §e仙族盟主 §c对话")
        else -> listOf("§a已获得仙族证明，准备前往人族皇城")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_XIANZUMENGZHU.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in leaderScript.indices) return true

        player.sendMessage(leaderScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == leaderScript.lastIndex) {
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
        (plugin.raceModule.getRace(1) as? XianRace)?.ensureInitialForgeLevel(player)
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("  §e[奖励] §f仙族证明 x1、包子 x20、铜钱 x20、新芽之羽 x1")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveQuestItems(player: Player) {
        val proof = plugin.resourceManager.getItem("xian_zm_begin") ?: ItemStack(Material.LAPIS_LAZULI).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] xian_zm_begin") }
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
        giveItem(player, proof, "仙族证明")
        giveItem(player, buns, "包子")
        giveItem(player, copper, "铜钱")
        giveItem(player, feather, "新芽之羽")
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
