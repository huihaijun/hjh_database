package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Ren_10 : QuestBase("main_ren_10", "[人族主线]圣兽踪迹", QuestType.MAIN, 24) {

    override val raceLimit = 2
    override val requiredCompletedQuestIds = setOf("main_north_5")

    override val description = listOf(
        "§7四圣兽的祝福已经全部汇聚于你一身。",
        "§7返回皇宫东侧的护国殿，寻找§e护国法师-法海§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val fahaiScript = listOf(
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f年轻人，你身上这些§e五彩斑斓的饰品§f……若我所感不差，这便是§e四圣兽祝福§f凝结的产物吧。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f时隔多年，终于有机会窥见§4§n圣兽§f去向了。且稍候，容我施法一探。",
        "§f〔法海周身金光渐盛，如细密经文般流转不息〕",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f……居然——居然这么近么？也难怪我从未察觉，原来是被§e结界§f护住了。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f§4§n四圣兽§f沉睡之处，就在北方的§e圣山§f之中。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f去那里，务必万事小心。那座山由一群§e避世之人§f守护，他们居住的岛屿，名唤§e蓬莱§f。北方的§e港口§f有船可往。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f但记住——这些§e圣兽祝福§f，必须由你§c亲手集齐§f。若是从他人手中夺来，蓬莱的结界不会认你，去了也是徒劳。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f圣兽沉眠之地，谁也不知其中潜伏着怎样的凶险。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f我早年云游时，有幸结识一位住在蓬莱的仙友，名唤§e凯歌§f。你若能登上蓬莱岛，便去找他。就说是我法海请你去的，他看在我的薄面上，应当能予你几分方便。",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f去吧，把该准备的都准备妥当，再动身不迟。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往皇宫东侧护国殿，与 §e护国法师-法海 §c对话")
        else -> listOf("§a已得知四圣兽沉睡于北方圣山")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.REN_FAHAI.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in fahaiScript.indices) return true

        player.sendMessage(fahaiScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == fahaiScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.REN_FAHAI.id && currentProgress == 0

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 400)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +400")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f北方港口有船可前往蓬莱岛")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }
}
