package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Yao_05 : QuestBase("main_yao_5", "[妖族主线]妖族证明", QuestType.MAIN, 5) {

    override val raceLimit = 4
    override val description = listOf(
        "§7你已掌握叶灵谷传授的基础技艺。",
        "§7谷主有最后的话要嘱托。",
        "§7领取妖族证明，踏上前往皇城与镇妖塔的旅途。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptGuzhu = listOf(
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f这五颗丹药，是你亲手炼出来的。能在这么短的时间里掌握§e开物术§f、锻造§6天机令§f、学会§d冶药法§f——你心中那份与自然相通的灵性，已经醒了。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f现在，我将这枚§e妖族证明§f授予你。从今往后，无论你漂泊多远，遭逢何等险境，它都会为你指引回家的方向。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f在§c人族皇城§f的东门外，有一棵花树。能看见树上隐隐透出的§a绿光§f——那就是回家的门。记住，那地方藏在树冠深处，需仔细寻找。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f这枚证明之中，还承载着我族与§e自然共鸣§f的天赋之力。持此证者，开采资源时速度提升两成有余，且不受资源枯竭之限；精力回复也比寻常快上两分，更能§e感应到更远范围内的资源§f所在。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f等你修为精进、有所顿悟之后，这枚证明还会与你体内的妖力共鸣，觉醒更深层的力量。不过那都是后话了，先记在心里便是。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f时候不早，你既已长大，有些事，老夫也到了该托付给你的时候。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f其实，咱们谷里的§4大长老§f已经失踪一个多月了。他老人家临走前说要去探查镇妖塔的动静，之后便音讯全无……我们怀疑，他恐怕是被§c人仙二族§f抓进那座§4镇妖塔§f里了。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f谷里一直找不到合适的人手去打探消息。大长老妖力深厚，寻常族人进去便是送死；可妖力太强又会被塔中阵法感应到，反倒打草惊蛇。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f但你不同。你初生不久，妖气尚薄，只要低调行事，混进人族皇城并非难事。老夫想请你走这一趟——你愿意吗？",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f好，好！不愧是老夫看中的孩子。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f§4镇妖塔§f就在皇城西门外，塔中有我们卧底的探子，化名叫做§e华夭§f，是个混血的花妖。想办法跟她接头，问问看她知不知道大长老的下落。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f这包盘缠你带上，出门总需用钱。这些干粮也拿着，路上别饿着自己。还有这根§e翎羽§f，随身带着可身轻如燕，赶路省下不少力气。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f记住，万一身份败露，什么都别管，优先保住自己的命。活着回来，比什么都强。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f从叶灵谷离开后，沿着大路向东，很快就能望见皇城的城墙。大长老的安危，就托付给你了。一路小心。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e谷主 §c领取妖族证明")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_GUZHU.id) return false
        if (currentProgress != 0) return false

        playDialogue(player, scriptGuzhu) {
            giveItemsAndFinish(player)
        }
        return true
    }

    private fun giveItemsAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        val itemProof = rm.getItem("yao_zm_begin") ?: ItemStack(Material.PURPLE_DYE).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§e妖族证明(配置缺失)") }
        }

        val itemMoney = rm.getItem("hjh_tongqian") ?: ItemStack(Material.SUNFLOWER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§6铜钱") }
        }
        itemMoney.amount = 20

        val itemFood = ItemStack(Material.BREAD, 20)
        itemFood.itemMeta = itemFood.itemMeta?.apply { setDisplayName("§f包子") }

        val itemFeather = rm.getItem("hjh_xyzy") ?: ItemStack(Material.FEATHER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] hjh_xyzy") }
        }

        val leftovers = player.inventory.addItem(itemProof, itemMoney, itemFood, itemFeather)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，部分物品掉落在脚下！")
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        }

        player.sendMessage("§e[系统] 获得 ${itemProof.itemMeta?.displayName}")
        player.sendMessage("§e[系统] 获得 ${itemMoney.itemMeta?.displayName} x${itemMoney.amount}")
        player.sendMessage("§e[系统] 获得 ${itemFood.itemMeta?.displayName} x${itemFood.amount}")
        player.sendMessage("§e[系统] 获得 ${itemFeather.itemMeta?.displayName}")

        Hjh_database.instance.questManager.completeQuest(
            player,
            Hjh_database.instance.playerManager.getPlayerData(player)!!,
            this
        )
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

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f请前往皇城西门外的镇妖塔寻找华夭")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 10)
            data.status = 3
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
