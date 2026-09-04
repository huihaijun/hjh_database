package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Zhan_06 : QuestBase("main_zhan_6", "[战神族主线]皇城接应", QuestType.MAIN, 6) {

    override val raceLimit = 3

    override val description = listOf(
        "§7族长命你前往人族皇城，与当地眼线接头。",
        "§7前往皇宫后院东边的§e月老小庙§7，寻找管理员§e蚀日§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val shiriScript = listOf(
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f你来了。我是蚀日，皇城这边的接应人。为了掩人耳目，我在月老庙做了管理员，离皇宫近，打听消息也方便。长话短说，近日皇城有几件事值得注意。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f§c其一§f，人皇接见了§4§n神族§f的一名特使。§c其二§f，大内总管和御前带刀侍卫长分别接待了一批冒险者和仙人。这般大动作，以前少有。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f探子回报，诸事皆与§e四圣兽§f的神庙有关。冒险者和仙人已经往§a东部龙鳞森林§f内部去了。结界是否真在减弱，还需继续观察，但皇城在围绕圣兽做事，这一点可以确定。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f我族在§e龙须镇§f也有探子，名叫§e左焰§f。你到那里与他接头，他知道更细的。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f不过走之前，我劝你先去皇城的§e四大导师§f处择师。学一身本领，将来遇事也好应对。想学刀剑弓术，去§e东门北边§f的两座高楼。对术法有兴趣，去§e南门方向东边§f的四合院，找§e秦观星§f。想从医，去§e风华楼东边§f的小屋，有长者传授医术。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f选完职业，再去§e护国殿§f看看。那里有个叫§e法海§f的，鼓捣了些石头，常有人在石头旁站上几秒，然后“咻”地一下不见了。我猜是某种§e传送法术§f，你日后或许用得上。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f我族与人族关系不算太差，别惹事就行。遇到神族，务必小心。",
        "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f路上小心。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往月老小庙，与管理员 §e蚀日 §c接头")
        else -> listOf("§a已取得龙须镇接头情报")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_SHIRI.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in shiriScript.indices) return true

        player.sendMessage(shiriScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == shiriScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        val data = plugin.playerManager.getPlayerData(player)
        plugin.playerManager.giveExp(player, 240)

        if (data != null) {
            if (data.questStatuses[SIDE_CHONGHUA_QUEST_ID] != QuestStatus.COMPLETED) {
                data.questStatuses[SIDE_CHONGHUA_QUEST_ID] = QuestStatus.IN_PROGRESS
                data.questProgress[SIDE_CHONGHUA_QUEST_ID] = 0
                plugin.databaseManager.saveQuestData(
                    player,
                    SIDE_CHONGHUA_QUEST_ID,
                    QuestStatus.IN_PROGRESS,
                    0
                )
            }
            plugin.databaseManager.savePlayerAsync(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +240")
        player.sendMessage("")
        player.sendMessage("  §b[解锁] §f已解锁新支线任务：重华晶的奥秘")
        player.sendMessage("  §e[提示] §f[选择职业]不记入主线流程")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private companion object {
        const val SIDE_CHONGHUA_QUEST_ID = "side_ren_1"
    }
}
