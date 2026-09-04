package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Xian_11 : QuestBase("main_xian_11", "[仙族主线]圣山之托", QuestType.MAIN, 25) {

    override val raceLimit = 1
    override val requiredCompletedQuestIds = setOf("main_xian_10")

    override val description = listOf(
        "§7法海请你前往蓬莱岛，寻找他的仙友§e凯歌§7。",
        "§7向凯歌询问四圣兽与圣山的往事。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val kaigeScript = listOf(
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f你好，道友。找我何事？",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f……啊，是§e法海§f让你来的。原来如此。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f不过他也太过多虑了。你既是§e圣兽所认定§f之人，我等又怎会为难于你。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f嗯……你想知道§4§n四圣兽§f与§e圣山§f的事？说来话长，你且当个故事听吧。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f世人所知，§4§n四圣兽§f乃§e神族§f所御之神兽，协助维系天地秩序。然百年之前一场§c大灾变§f，四圣兽力量大损，便离了各自镇守的神庙，来此§e圣山§f沉眠休养。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f这一睡，便是§c百年§f。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f按理说，圣兽既已苏醒，便该各归其位，继续维持四方结界的稳固。可我们察觉，四位圣兽虽有§e苏醒之兆§f，却§c无一离开圣山§f。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f其中缘由，我等也不得而知。我们的职责只是§e守护与等待§f，况且我们未得圣兽祝福，也无法踏入圣山一探。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f如今道友既持祝福而来，这§e重责大任§f，便托付与你了。前往§e圣山§f吧，想办法让§4§n四圣兽§f重归本位，令天地秩序回到正轨。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f待你调查完圣山，便回皇城寻§e法海§f复命。他一直在等你的消息。",
        "§e[${StoryNpcs.KAIGE.displayName}§e] §f这些§6丹药§f你带上。圣山之内究竟是何光景，我亦知之不深，带着，总能安心几分。去吧。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c登上蓬莱岛，寻找 §e凯歌 §c对话")
        else -> listOf("§a已接受凯歌的托付，准备进入圣山")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.KAIGE.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in kaigeScript.indices) return true

        player.sendMessage(kaigeScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == kaigeScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.KAIGE.id && currentProgress == 0

    override fun giveReward(player: Player) {
        val pills = plugin.resourceManager.getItem(TWIN_PILL_ID)
        if (pills == null) {
            plugin.logger.warning("仙族第十一章缺少奖励物品配置: $TWIN_PILL_ID")
            player.sendMessage("§c[错误] 无法发放高级双生丹，请联系管理员。")
        } else {
            pills.amount = TWIN_PILL_AMOUNT
            val leftovers = player.inventory.addItem(pills)
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            if (leftovers.isNotEmpty()) {
                player.sendMessage("§c[提示] 背包空间不足，部分双生丹已掉落在脚下！")
            }
        }

        plugin.playerManager.giveExp(player, 500)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +500")
        player.sendMessage("  §e[奖励] §f双生丹[高级] x15")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private companion object {
        const val TWIN_PILL_ID = "shuangshengdan2"
        const val TWIN_PILL_AMOUNT = 15
    }
}
