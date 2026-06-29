package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Yao_04 : QuestBase("main_yao_4", "[妖族主线]初识冶药", QuestType.MAIN, 4) {

    override val raceLimit = 4
    override val description = listOf(
        "§7谷主认为你已经是叶灵谷合格的妖族族人。",
        "§7但离开山谷之前，你还需要学习冶药法。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptGuzhuStart = listOf(
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f好，好，这枚§6天机令§f锻得漂亮——看来铁匠铺那家伙把看家本事都教给你了。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f你通过了老夫的两道考验：§e开物术§f让你学会与自然共鸣，§6天机令§f让你学会感应自身。能做到这两点，你已是叶灵谷一名合格的§2§o妖族§f族人了。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f不过孩子，你若想走出这山谷去闯荡，有些事，老夫必须让你知道。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f这座叶灵谷，是我们妖族仅存的几处净土之一。谷中有§e智慧之树§f的庇荫，千百年来，人仙二族的爪牙始终无法伸到这里。族人们在此繁衍生息，虽谈不上富足，倒也安宁。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f可一旦踏出谷口，便是两重险境。野外那些§c无主无识的凶暴魔物§f倒还算其次，最可恨的是那§c人族§f与§c仙族§f——他们在皇城边建起一座§4镇妖塔§f，将我无数族人囚禁其中，日日以阵法削弱他们的妖力，求生不得求死不能。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f好了，眼下便有一个机会，让你学会保护自己。你去谷里的§e炼丹铺§f走一趟，替老夫炼五颗§e初窥丹药§f出来。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f炼丹铺就在铁匠铺旁边，顺着吊桥上的路牌一眼就能瞧见，离得不远。掌柜是谷里最精通§d冶药法§f的师傅，他会教你如何以天地万物入药——那是咱们妖族安身立命的另一门手艺，你须好好学。"
    )

    private val scriptAlchemist = listOf(
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f请坐，不必拘谨。谷主让你来学§d冶药法§f？看来他是真把你放在心上了。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f咱们叶灵谷每来一个新生的族人，谷主都这般郑重其事。这份心意，你可莫要辜负。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f咱们妖族的§d冶药法§f，和锻造术一样，是立足天地的根基。但这门手艺和锻造不同——不是拿了材料就能开炉。你得先学会§e丹方§f，那上头记载着每种丹药的调配要诀。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f日后你四处闯荡，总能在各地寻到各种丹方——有的藏在深山古洞里，有的被那些魔物当宝贝守着，也有好心人愿意传授。学会了丹方，才能炼制对应的丹药。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f不过在你动手之前，有件事，我应当让你知道。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f你可知那§c人族§f是如何炼丹的？他们四处猎杀我们的族人，取其内丹入药，说什么我族内丹能令伤愈倍增。呵，用他人的性命换自己的苟活，这等下作手段，也配叫炼丹？",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f我们妖族不同。一草一木，一石一水，天地万物自有灵性。用自然的馈赠炼出的丹药，干干净净，胜过人族那些沾满鲜血的丹丸百倍千倍。这便是正道。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f好了，说回正事。丹药分§e初级§f、§e中级§f、§e高级§f三品。你的冶药法等级越高，能炼的品级就越高。不过高级丹药唯有§d医师§f方可炼制——那可不是光有手艺就行的，还得有一颗济世之心。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f具体怎么操作？你听好：走到炼药锅前，伸手一触，界面自开。选好丹药与品级，药锅便会与你共鸣，锅面上会浮现所需材料的影子。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f你就照着影子，把对应的材料丢进去。材料齐了，丹药自会凝成，乖乖落入你行囊里。不过记住——共鸣期间别跑太远，离锅远了就断了。放心，断了也不打紧，材料不会少你的，回来重新开炉便是。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f还有一桩要紧事。丹药炼出来就能吃，见效极快——这是好处。可§c是药三分毒§f，吞下丹药后，体内便会生出一种§c药丹疾病§f。",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f这毛病不痛不痒，但会叫你在一段时间内排斥其他丹药的药力。只要§c药丹疾病§f还在，别的丹药就灌不进去。越是猛烈的丹药，这病就拖得越久，有时候能拖上小半盏茶的功夫。日后在野外跟人动手时，吃药可得掐准时机，别傻乎乎地连着灌！",
        "§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f来，这是§e新人丹药§f的丹方和材料——全是谷里采的草药和打理好的药引，干干净净。你拿去试试手，炼丹房就在这儿的二楼，炼成§e五颗§f，交给谷主就好了。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e谷主 §c对话")
            1 -> listOf("§a已接受教导", "§c前往 §e丹药铺 §c寻找掌柜")
            2 -> listOf("§a已获得丹方材料", "§c炼制 5颗 §b[新手疗愈丹] §c并交给谷主")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 4
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 10)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        if (npcId == StoryNpcs.YAO_GUZHU.id) {
            when (currentProgress) {
                0 -> {
                    playDialogue(player, scriptGuzhuStart) {
                        plugin.questManager.updateProgress(player, id, 1)
                        player.sendMessage("§a[任务] -> 请前往叶灵谷丹药铺寻找掌柜。")
                    }
                    return true
                }

                1 -> {
                    player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f丹药铺就在铁匠铺旁边，顺着吊桥上的路牌去吧。")
                    return true
                }

                2 -> {
                    if (checkAndRemovePotions(player, "新手疗愈丹", 5)) {
                        player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f很好，丹香清正，没有半分血腥气。你这冶药法，算是入门了。")
                        plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                    } else {
                        player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f丹药炼好了吗？老夫要的是 5颗§e初窥丹药§f。")
                        player.sendMessage("§7(提示：请使用炼药锅炼制 5颗 新手疗愈丹[初窥])")
                    }
                    return true
                }
            }
        }

        if (npcId == StoryNpcs.YAO_DANYAOPUZHANGGUI.id) {
            when (currentProgress) {
                1 -> {
                    playDialogue(player, scriptAlchemist) {
                        giveMaterials(player)
                        plugin.questManager.updateProgress(player, id, 2)
                        player.sendMessage("§a[任务] -> 已获得材料，请点击附近的炼药锅炼制 5颗 新手疗愈丹。")
                    }
                    return true
                }

                2 -> {
                    player.sendMessage("§e[${StoryNpcs.YAO_DANYAOPUZHANGGUI.displayName}§e] §f炼药需心静。炼好了，就去交给谷主吧。")
                    return true
                }
            }
        }

        return false
    }

    private fun giveMaterials(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val yaoyin = rm.getItem("hjh_xsyy")
        val caoyao = rm.getItem("hjh_cy")

        if (yaoyin == null || caoyao == null) {
            player.sendMessage("§c[错误] 无法获取炼丹材料配置，请联系管理员！")
            return
        }

        yaoyin.amount = 1
        caoyao.amount = 3

        val leftovers = player.inventory.addItem(yaoyin, caoyao)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        }

        player.sendMessage("§e[系统] 获得 ${yaoyin.itemMeta?.displayName ?: "药引[新手疗愈丹]"} x1")
        player.sendMessage("§e[系统] 获得 ${caoyao.itemMeta?.displayName ?: "草药"} x3")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun checkAndRemovePotions(player: Player, namePart: String, amountRequired: Int): Boolean {
        var totalFound = 0

        for (item in player.inventory.contents) {
            if (item == null || item.type != Material.POTION) continue
            val meta = item.itemMeta ?: continue
            if (meta.displayName.contains(namePart)) {
                totalFound += item.amount
            }
        }

        if (totalFound < amountRequired) return false

        var leftToRemove = amountRequired
        for (item in player.inventory.contents) {
            if (item == null || item.type != Material.POTION) continue
            val meta = item.itemMeta ?: continue
            if (!meta.displayName.contains(namePart)) continue

            val toRemove = minOf(item.amount, leftToRemove)
            item.amount -= toRemove
            leftToRemove -= toRemove
            if (leftToRemove <= 0) break
        }

        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
        return true
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
}
