package com.hjh_database.quest.impl.main.south

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

/**
 * 南方主线任务第二章 - 祭品少女
 */
class South_02 : QuestBase("main_south_2", "[主线]祭品少女", QuestType.MAIN, 11) {

    // 无种族限制，五族通用
    override val raceLimit = null

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往镇里的丹药铺寻找 §e巫者-红鸾")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7火山之神竟会发怒？",
        "§7还需要献祭少女来平息怒火？",
        "§7前往丹药铺，向巫者红鸾打听详情。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptHongLuan = listOf(
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f…你想知道有关于§4§n朱雀§f的事情？",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f§4§n朱雀§f不会回来了，这里是§4§n火山之神§f管辖的范围，任何事情都需要经过神的指示",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f如果违背神的命令，§2§o火山§f便会喷发，将我们所有的人瞬间杀死…",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f§4§n火山之神§f这次需要一名少女当作祭品",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f你可别用那种眼神看我，这可不是什么残忍的事儿",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f§4§n火山之神§f说了，少女的心魂最是纯净，唯有这般纯洁无瑕的灵魄投入火山口，才能平息神的怒火",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f上回那个小子…唉，就是因为不够纯粹，火山的震动反而更剧烈了，神说，他在底下不够安分",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f这一次，非得要一个心思干净、尚未被世俗玷污的姑娘不可",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f嗯…村庄里好像没剩几位少女可以选了…",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f啊，我想起来了，那位老是喜欢待在§2§o旧村庄废墟§f里的§4§n那丫头§f",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f那丫头整天就知道躲在那翻她那些破纸堆，从不来神殿敬拜，我看火山之神早就盯上她了",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f神跟我说了，神就缺一个不信神的人在身边伺候，好让她见识见识什么叫做神威浩荡",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f帮我一个忙吧，你沿着路继续走，到§2§o旧的废弃村庄§f去",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f找一位叫做§4§n莲心§f的女孩，叫她过来见我",
        "§e[${StoryNpcs.WUZHEHONGLUAN.displayName}§e] §f就说…神选中了她，这是她的福气，让她别不知好歹"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.WUZHEHONGLUAN.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptHongLuan) {
                    // 对话结束，完成任务
                    val plugin = Hjh_database.instance
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return false
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
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
        player.sendMessage("  §e[奖励] §f经验 +50")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 50
            Hjh_database.instance.databaseManager.savePlayer(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}