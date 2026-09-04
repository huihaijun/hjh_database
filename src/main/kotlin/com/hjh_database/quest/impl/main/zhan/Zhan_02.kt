package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class Zhan_02 : QuestBase("main_zhan_2", "[战神族主线]引导之石", QuestType.MAIN, 2) {

    override val raceLimit = 3

    private val plugin get() = Hjh_database.instance
    private val resourceKey by lazy { NamespacedKey(plugin, "resource_id") }
    private val talkProgress = HashMap<UUID, Int>()

    override val description = listOf(
        "§7将飞虎的介绍信交给族长，并向祁龙学习战神族的开采技巧。",
        "§7采集两块§e引导碎石§7，带回议事厅交给族长。"
    )

    private val chiefBeforeLetter = listOf(
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f好生疏的面孔。你是我族的人？",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f你手上那封信的印章，是飞虎的。让我看看。"
    )

    private val chiefAfterLetter = listOf(
        "§7（你递上飞虎的介绍信）",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f原来是新生的族人。欢迎你。眼下族里条件不好，拿不出什么像样的东西给你，先将就着吧。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我们落到这般境地，都拜那帮§c自以为是的神族§f所赐。他们空有力量，却用来为非作歹，妄图掌控整个世界。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f我们这族，本是为了反抗他们而组建的联盟。后来§4§n四兽§f镇压，联盟中人大多阵亡。剩下的躲到这片神族力量也触及不到的世界边缘，勉强存续至今。这就是我们战神族的由来。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f在这块贫瘠之地谋生，不容易。我们学会了不少§e开采资源§f的窍门——与其说开采，不如说是§c精打细算§f。一块铁，恨不能掰成两半用。这些本事，你早晚也要学。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f洞外第一个路标下面有块石头，刻着§e“引导之石”§f，据传是当年盟主留下的。我们每隔一段时间就从上面取样，借它判断气候的变化。具体缘由我也不甚清楚。石头旁有个族人叫§e祁龙§f，研究这块石头有些年头了。",
        "§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f想学开采技巧，就去找他。路上自己当心。"
    )

    private val qilongScript = listOf(
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f族人，你也是来看§e引导之石§f的？",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f这地方被那帮§c神族§f逼得酷热难耐，恨不能把咱蒸干了。可这块石头摸上去跟冰一样，凉得稀奇。依我看，这种寒气只有§b西北方的高岭§f上才会有。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f石头里残着盟主的旧息。可西北那片地界……我族的人已经很多年没能踏足了。盟主当年一去不返，只留下这块石头，凉得像是还在替谁守着什么。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f算了，都是些没头绪的事。你刚说奉族长之命来采石做检验的？",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f正好。在这片荒地上开采资源，早成了我族的基本功。我现在就把§e开物术§f教给你。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f万物有灵，这些资源也一样。靠近时仔细看：冒§a绿莹莹的光§f，就是§a富饶§f，这时候采最快也最丰厚。采过之后变§e暗黄§f，就是§e枯竭§f，再硬来费时费力，说不定什么也捞不着。若是§7灰蒙蒙一片§f，那是它在休养，等绿光回来再动手不迟。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f用你的§b右手§f轻触，等待一会，资源便到你手上了。§b左手§f轻触，你还能驱动灵识，看到这个资源的一些信息。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f施展开物术要耗§b精力§f，精力会随时间慢慢回复。开物术等级越高，精力上限越高，§b开采速度也会越快§f。",
        "§e[${StoryNpcs.ZHAN_QILONG.displayName}§e] §f去，拿这块石头练练手。采上§e两块§f，应该够族长那边用了。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c主手持有飞虎的介绍信，与族长对话")
        1 -> listOf("§c前往引导之石旁，与祁龙对话")
        2 -> listOf("§c主手持有两块引导碎石，返回议事厅交给族长")
        else -> listOf("§a已将引导碎石交给族长")
    }

    override fun checkComplete(progress: Int): Boolean = progress >= 3

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 0 -> handleChiefIntroduction(player)
            npcId == StoryNpcs.ZHAN_QILONG.id && currentProgress == 1 -> handleQilongDialogue(player)
            npcId == StoryNpcs.ZHAN_ZUZHANG.id && currentProgress == 2 -> handleStoneDelivery(player)
            else -> false
        }
    }

    private fun handleChiefIntroduction(player: Player): Boolean {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index < chiefBeforeLetter.size) {
            say(player, chiefBeforeLetter[index])
            talkProgress[player.uniqueId] = index + 1
            return true
        }

        if (index == chiefBeforeLetter.size) {
            if (!isHoldingFeihuLetter(player.inventory.itemInMainHand)) {
                player.sendMessage("§c[任务] -> 请将【飞虎的介绍信】拿在主手，再左键族长。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return true
            }
            consumeMainHand(player, 1)
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1
        }

        val afterIndex = talkProgress.getValue(player.uniqueId) - chiefBeforeLetter.size - 1
        if (afterIndex in chiefAfterLetter.indices) {
            say(player, chiefAfterLetter[afterIndex])
            talkProgress[player.uniqueId] = talkProgress.getValue(player.uniqueId) + 1
            if (afterIndex == chiefAfterLetter.lastIndex) {
                talkProgress.remove(player.uniqueId)
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 前往引导之石旁寻找祁龙。")
            }
        }
        return true
    }

    private fun handleQilongDialogue(player: Player): Boolean {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in qilongScript.indices) return true
        say(player, qilongScript[index])
        talkProgress[player.uniqueId] = index + 1
        if (index == qilongScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 2)
            player.sendMessage("§a[任务] -> 采集两块引导碎石，主手持有后返回族长处。")
        }
        return true
    }

    private fun handleStoneDelivery(player: Player): Boolean {
        val hand = player.inventory.itemInMainHand
        if (getResourceId(hand) != STONE_RESOURCE_ID || hand.amount < REQUIRED_STONES) {
            player.sendMessage("§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f样本还不够。主手拿好§e两块引导碎石§f再交给我。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        consumeMainHand(player, REQUIRED_STONES)
        player.sendMessage("§e[系统] 你将两块引导碎石交给了族长。")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
        player.sendMessage("§e[${StoryNpcs.ZHAN_ZUZHANG.displayName}§e] §f待会先别急着走，我还有别的事告诉你。")
        plugin.questManager.updateProgress(player, id, 3)
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private fun isHoldingFeihuLetter(item: ItemStack): Boolean {
        return item.type == Material.PAPER && item.itemMeta?.displayName == "§e飞虎的介绍信"
    }

    private fun getResourceId(item: ItemStack): String? {
        return item.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)
    }

    private fun consumeMainHand(player: Player, amount: Int) {
        val hand = player.inventory.itemInMainHand
        if (hand.amount <= amount) {
            player.inventory.setItemInMainHand(null)
        } else {
            hand.amount -= amount
        }
    }

    private companion object {
        const val STONE_RESOURCE_ID = "yindaosuishi"
        const val REQUIRED_STONES = 2
    }
}
