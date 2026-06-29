package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.HashMap
import java.util.UUID

class Yao_07 : QuestBase("main_yao_7", "[妖族主线]皇城潜行", QuestType.MAIN, 7) {

    override val raceLimit = 4

    override val description = listOf(
        "§7大长老确认被困镇妖塔塔顶。",
        "§7回去寻找 §e华夭§7，商议下一步行动。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptHuayao = listOf(
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f大长老真的被关在§4塔顶§f！？",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f可恶，那群该死的§c人类§f……",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f虽说一时半刻大长老还撑得住，但困在塔中一日，阵法便会削弱他一分妖力。什么时候会撑不住倒下，谁也说不准。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f看来只能尽快摸清结界的情况，再想办法搬救兵来救大长老了。你就按大长老说的，先去§a东边的龙鳞森林§f，§e青龙的祭坛§f就在森林深处。一般这种大阵都有个管事的，到了那边仔细找找，看看能不能套出些有用的消息。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f不过在你动身之前，有件更要紧的事得跟你交代清楚。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f最近魔物动荡得厉害，皇城里§c戒备森严§f，到处是巡逻的卫兵。你现在这副模样——没身份、没营生，在他们眼里就是个“§c无业游民§f”，一旦被盘查，妖族的身份暴露是迟早的事。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f好在皇城里设了四处§e导师堂§f，有四位师傅分别传授§e剑技§f、§e射术§f、§e术法§f和§e医术§f。你随便拜入一人门下，便算是有了§e正当职业§f。不光能让那些多疑的人族对你放下戒备，学来的本事在外面也大有用处，遇上危险不至于手忙脚乱。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f给，这是一位前辈当年留下的§b笔记§f，里头记了四位导师的大致方位。不过到底是在人族地盘上偷偷记的，写得不太详尽，到了皇城还得你自己多转转、多打听。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f对了，那位前辈还提到，皇宫后面的§e护国殿§f里，有个老头儿鼓捣出一件叫§6重华晶§f的宝贝。那东西可了不得，能让你瞬息之间远遁千里，日后往返各大祭坛能省下不知多少脚程。那老头眼神不济，压根分不清你是妖族还是人族，你大摇大摆进去便是。嘴甜些，客气些，说不定还能从他那儿摸到些别的好东西。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f好了，该说的都说了。先去皇城把§e导师§f找好、把§6重华晶§f的门路摸到手，再动身去§a龙鳞森林§f。万事小心，别让大长老等太久。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c返回 §4镇妖塔 §c寻找 §e华夭")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_HUAYAO.id || currentProgress != 0) return false

        playDialogue(player, scriptHuayao) {
            giveItemsAndFinish(player)
        }
        return true
    }

    private fun giveItemsAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        val itemNote = rm.getItem("yao_zybj") ?: ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e妖族前辈的笔记(配置缺失)")
                lore = listOf("§7缺少 yao_zybj 配置", "§7请联系管理员")
            }
        }

        val leftovers = player.inventory.addItem(itemNote)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${itemNote.itemMeta?.displayName}")
        }

        val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return
        Hjh_database.instance.questManager.completeQuest(player, data, this)
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
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
        player.sendMessage("  §e[提示] §f请阅读笔记，寻找职业导师")
        player.sendMessage("  §e[提示] §f[选择职业]不记入主线流程")
        player.sendMessage("  §b[解锁] §f已解锁新支线任务：重华晶的奥秘")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 10)

            if (data.questStatuses["side_ren_1"] != QuestStatus.COMPLETED) {
                data.questStatuses["side_ren_1"] = QuestStatus.IN_PROGRESS
                data.questProgress["side_ren_1"] = 0
            }

            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
