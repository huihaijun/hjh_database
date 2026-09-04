package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_12 : QuestBase("main_xian_12", "[仙族主线]八卦疑阵", QuestType.MAIN, 26) {

    override val raceLimit = 1
    override val requiredCompletedQuestIds = setOf("main_xian_11")

    override val description = listOf(
        "§7通过圣山秘境，查明四圣兽与盘古肉身的异变。",
        "§7先向法海说明情况，再返回蜀山禀报仙族盟主。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val fahaiScript = listOf(
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f上仙，你回来了。此行可还安好？",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f圣山那边的情况，不知上仙是否有所收获？",
        "§7（你简单告诉了法海一些情况。）",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f这……竟有此事？",
        "§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f此事已然超出贫道所能参详的范畴。上仙还是§c尽快回蜀山§f，向§e盟主§f禀告为妙。莫要耽搁。"
    )

    private val leaderScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f道友，总算回来了。§e圣山§f究竟发生了什么？",
        "§7（你将圣山的经过告诉了盟主。）",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f……",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f§4§n四圣兽§f围着一座§e阵法§f……",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f最后竟然从里面生出了一具§c盘古肉身§f？",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这可比我原先猜的麻烦多了。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f等等。你刚才说什么？",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f§e八卦§f？",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f§e乾坤震巽，坎离艮兑§f……一卦不少？",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f……这就奇怪了。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f§4§n神族§f确实强大，可他们向来§c不擅长§f这类繁复的§e阵术演算§f。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f反倒是我们§e仙族§f，自古以来最喜欢研究这些东西。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f当然，天下会八卦的人多得是。单凭八个卦象，还不能说明什么。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f可你说那八卦并非临时施展的术法……",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f而是和§4§n四圣兽§f本身连在一起的阵法。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这就值得§c查一查§f了。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这样吧，我会让§e藏书阁§f的人把过去与八卦阵法和四象有关的§e旧卷§f重新查一遍。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这么精细复杂的阵法，我族不可能一点§c记载的痕迹§f都没有。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f你先休息。这些§6奖励§f你且收下，这趟路辛苦了，好好犒劳自己一番。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f有消息的话，我会让人找你。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c至少通过一次圣山副本，再找 §e护国法师-法海 §c复命")
        1 -> listOf("§c尽快返回蜀山，向 §e仙族盟主 §c禀报圣山异变")
        else -> listOf(
            "§a已将圣山异变告知仙族盟主",
            "",
            "§7§o四圣兽的谜团暂时结束了",
            "§7§o可你总觉得事情的并非如此简单",
            "§7§o为何明明是由§b§o神族§7§o引领的四圣兽",
            "§7§o却偏偏用着类似§b§o我族§7§o的八卦阵法",
            "§7§o在那§e§o藏书阁§7§o里，真的会有相关的记载吗？",
            "§7§o或许是知道的§6§l§o真相§7§o还不够多的缘故……"
        )
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean = when {
        npcId == StoryNpcs.REN_FAHAI.id && currentProgress == 0 -> handleFahai(player)
        npcId == StoryNpcs.XIAN_XIANZUMENGZHU.id && currentProgress == 1 -> handleLeader(player)
        else -> false
    }

    private fun handleFahai(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if ((data.dungeonRecords[ShengShanDungeonManager.DUNGEON_RECORD_ID]?.clears ?: 0) <= 0) {
            player.sendMessage("§e[${StoryNpcs.REN_FAHAI.displayName}§e] §f圣山之行尚无结果么？待上仙查明其中异变，再来告知贫道吧。")
            player.sendMessage("§c（请先至少通过一次圣山副本）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        playDialogue(player, fahaiScript) {
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 尽快返回蜀山，向仙族盟主禀报圣山异变。")
        }
        return true
    }

    private fun handleLeader(player: Player): Boolean {
        playDialogue(player, leaderScript) {
            val data = plugin.playerManager.getPlayerData(player) ?: return@playDialogue
            plugin.questManager.completeQuest(player, data, this)
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        (npcId == StoryNpcs.REN_FAHAI.id && currentProgress == 0) ||
            (npcId == StoryNpcs.XIAN_XIANZUMENGZHU.id && currentProgress == 1)

    override fun giveReward(player: Player) {
        val rewards = mutableListOf<ItemStack>()
        addReward(rewards, "yinpiao", 15, "银票")
        addReward(rewards, "mijingyaoshi", 5, "秘境之钥")
        addReward(rewards, "yuansuduihuanquan", 64, "元素兑换券")
        addReward(rewards, "access_book_5", 1, "五阶饰品制作书")
        addReward(rewards, "lingyujian", 1, "灵玉简")

        val leftovers = player.inventory.addItem(*rewards.toTypedArray())
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分奖励已掉落在脚下！")
        }

        plugin.playerManager.giveExp(player, 5000)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +5000")
        player.sendMessage("  §e[奖励] §f银票 x15")
        player.sendMessage("  §e[奖励] §f秘境之钥 x5")
        player.sendMessage("  §e[奖励] §f元素兑换券 x64")
        player.sendMessage("  §e[奖励] §f五阶饰品制作书 x1")
        player.sendMessage("  §e[奖励] §f灵玉简 x1")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun addReward(rewards: MutableList<ItemStack>, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("仙族第十二章缺少奖励物品配置: $resourceId ($displayName)")
            return
        }
        item.amount = amount
        rewards += item
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }
}
