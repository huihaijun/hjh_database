package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Xian_08 : QuestBase("main_xian_8", "[仙族主线]青龙试炼", QuestType.MAIN, 8) {

    override val raceLimit = 1

    override val description = listOf(
        "§7楚兰请你前往森林深处调查祭坛结界。",
        "§7进入§e青龙神庙§7，寻找§e青龙分魂§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val qinglongScript = listOf(
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f你……是仙族之人？我确能感知到你身上流转的修真之力。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f我？不过是§4§n青龙大人§f所遗的一缕分魂，谈不上什么大人物。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f你的来意，我已然明了。实不相瞒，你所查之事，我无力解决。神庙所维系的§e结界§f确实在衰退，若§4§n青龙大人§f与其他三位圣兽再不归位驻守，结界消散只是时日问题。到那时，天地秩序将被迫重塑——而这过程之中，§c死伤无数§f怕是难以避免。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f以我这点微末之能，既探知不到圣兽们如今的下落，更遑论将他们请回神庙。但若是你的话……或许会有办法。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f§4§n青龙大人§f离去之前，曾以圣力开辟一处§e试炼空间§f，专为考验那些对自身学识抱有信心的求道者。你若能通过§4§n青龙§f的考验，便能获得祂的祝福，或许便能借此感知圣兽们的方位。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f若仍无所获……那便将§e四圣兽的祝福§f尽数集齐，届时自有分晓。",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f神庙§e二楼§f便是试炼入口。去吧，祝你好运。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c进入青龙神庙，寻找 §e青龙分魂 §c对话")
        else -> listOf("§a已获知青龙试炼与四圣兽祝福的线索")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.QINGLONGFENHUN.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in qinglongScript.indices) return true

        player.sendMessage(qinglongScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == qinglongScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 320)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +320")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
