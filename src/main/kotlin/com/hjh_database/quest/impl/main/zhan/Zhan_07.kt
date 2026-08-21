package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class Zhan_07 : QuestBase("main_zhan_7", "[战神族主线]龙须接头", QuestType.MAIN, 7) {

    override val raceLimit = 3

    override val description = listOf(
        "§7蚀日提供了战神族在龙须镇的接应人情报。",
        "§7前往§e龙须镇§7寻找§e左焰§7，并凭战神族证明与他接头。"
    )

    private val plugin get() = Hjh_database.instance
    private val resourceKey by lazy { NamespacedKey(plugin, "resource_id") }
    private val talkProgress = HashMap<UUID, Int>()

    private val verifiedScript = listOf(
        "§7（左焰接过你的种族证明，仔细核对后点了点头）",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f没问题。战友，我是左焰。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f蚀日队长应该已经跟你说了不少。时间紧迫，我只挑重点。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f§e仙族§f和§e人族§f都已进入森林深处，探查§e青龙祭坛§f。听他们谈话，结界确有减弱迹象，根源就在§c祭坛与神庙内部§f。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f找到祭坛，便能进入神庙查清真相。事不宜迟，我们现在就出发，我会随你一同进入祭坛。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f进森林前，有些情报你必须知道：",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f森林里的§e石头和木头§f，可用族里教你的§e开物术§f开采，是锻造武器的基础材料。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f路边那些§a杂草§f，一部分可用开物术转化为§e布匹§f，日后制衣；另一部分收好，留着§e冶炼丹药§f。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f深入后你会遇到一座§c诡异祭坛§f，那是§c蜘蛛女王§f的老巢；森林最深处的废弃村庄也有它的踪迹。它速度极快，会吐§c蛛网§f减速，务必小心。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f离废弃村庄不远，有一棵极高的“§c流血§f”之树，当地人叫它§e龙鳞神木§f。树下有一名§c强大看守者§f，会召唤藤蔓破土而出，禁锢并伤害你。遇到它，加倍小心。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f击杀这两个强敌，或许能从它们身上找到§e珍贵之物§f，对你日后锻造武器大有帮助。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f情报就这些。进了神庙之后，再找我接头。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往龙须镇寻找 §e左焰", "§c首次接头时需主手持有 §b战神族证明")
        else -> listOf("§a已取得龙鳞森林与青龙祭坛情报")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_ZUOYAN1.id || currentProgress != 0) return false

        when (val index = talkProgress.getOrDefault(player.uniqueId, INTRODUCTION_INDEX)) {
            INTRODUCTION_INDEX -> {
                say(player, "§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f你是何人？")
                player.sendMessage("§a[任务提示] §f请将§b战神族证明§f拿在主手，再与左焰对话。")
                talkProgress[player.uniqueId] = PROOF_CHECK_INDEX
            }

            PROOF_CHECK_INDEX -> {
                if (!isHoldingWarGodProof(player.inventory.itemInMainHand)) {
                    player.sendMessage("§e[${StoryNpcs.ZHAN_ZUOYAN1.displayName}§e] §f空口无凭。把你的§b战神族证明§f拿在主手，让我核对身份。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                say(player, verifiedScript.first())
                player.sendMessage("§a[任务提示] §f身份已核验，后续对话无需继续手持证明。")
                talkProgress[player.uniqueId] = FIRST_VERIFIED_DIALOGUE_INDEX
            }

            else -> {
                val scriptIndex = index - VERIFIED_STATE_OFFSET
                if (scriptIndex !in 1..verifiedScript.lastIndex) return true

                say(player, verifiedScript[scriptIndex])
                if (scriptIndex == verifiedScript.lastIndex) {
                    talkProgress.remove(player.uniqueId)
                    val data = plugin.playerManager.getPlayerData(player) ?: return true
                    plugin.questManager.completeQuest(player, data, this)
                } else {
                    talkProgress[player.uniqueId] = index + 1
                }
            }
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 280)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +280")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f深入龙鳞森林，寻找青龙祭坛")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun isHoldingWarGodProof(item: ItemStack): Boolean {
        return item.itemMeta?.persistentDataContainer
            ?.get(resourceKey, PersistentDataType.STRING) == WAR_GOD_PROOF_ID
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private companion object {
        const val WAR_GOD_PROOF_ID = "zhan_zm_begin"
        const val INTRODUCTION_INDEX = 0
        const val PROOF_CHECK_INDEX = 1
        const val VERIFIED_STATE_OFFSET = 1
        const val FIRST_VERIFIED_DIALOGUE_INDEX = 2
    }
}
