package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Ren_12 : QuestBase("main_ren_12", "[人族主线]尘封旧案", QuestType.MAIN, 26) {

    override val raceLimit = 2
    override val requiredCompletedQuestIds = setOf("main_ren_11")

    override val description = listOf(
        "§7进入圣山，查明四圣兽未归神庙的缘由。",
        "§7将圣山中发生的一切带回皇城复命。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val liGonggongScript = listOf(
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f哎哟喂，你可算回来了！瞧这一身风尘，路上没少遭罪吧？",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f怎么样，§4§n四圣兽§f的事，查清楚了？",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f……什么？四圣兽被困在§e圣山§f，还从一颗蛋里生出个§4§n盘古§f？",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f这、这都什么跟什么呀！光听着咱家腿肚子都打转了。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f这事可轮不到咱家拿主意。赶紧的，去§e皇上§f那儿走一遭，把来龙去脉一五一十禀报清楚。",
        "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f这些天皇上正为神族特使的事愁得吃不下饭，你带回的消息，说不定正解了他的疑惑。快去！"
    )

    private val emperorScript = listOf(
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f你方才说，蓬莱之人将百年前那桩事称作§e“灾难”§f？",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f……这倒奇了。朕调阅过皇室旧档，其中记载，并非如此。",
        "§7（轩辕氏示意，近侍捧来一卷落满灰尘的旧档案）",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f咳咳……这灰厚得能种菜了。还有一股子§a草药味§f，也不知当年§c经了谁的手§f。罢了，说正事。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f旧档所载，只寥寥数行：§c“南方来者分袭四座神庙”§f，§c“四圣兽皆负重伤，向北遁去”§f，§c“神上随后传令，各方无需再追查此事”§f。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f还有一事，你方才说，那具破茧而出的躯壳，§4§n神族长老§f当时也在场？他还亲口说那是§e仿冒品§f？",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f若真如此……他所知之事，远比我们多得多。这桩旧案，怕是得慢慢挖掘了。",
        "§e[${StoryNpcs.RENHUANGXUANYUANSHI.displayName}§e] §f这些§6奖赏§f你且收下，好好犒劳自己一番。日后若有新的消息，朕会再召你入宫。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c至少通过一次圣山副本，再找 §e李公公 §c复命")
        1 -> listOf("§c前往皇宫，向 §e人皇-轩辕氏 §c禀报圣山见闻")
        else -> listOf(
            "§a已向人皇禀明圣山见闻",
            "",
            "§7§o四圣兽的谜团暂时结束了",
            "§7§o可你总觉得事情的并非如此简单",
            "§7§o仿冒品?大灾难?到底是怎么一回事?",
            "§7§o那份带着§b§o草药味§7§o的档案又经了谁人之手?",
            "§7§o或许是知道的§6§l§o真相§7§o还不够多的缘故……"
        )
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean = when {
        npcId == StoryNpcs.REN_LIGONGGONG.id && currentProgress == 0 -> handleLiGonggong(player)
        npcId == StoryNpcs.RENHUANGXUANYUANSHI.id && currentProgress == 1 -> handleEmperor(player)
        else -> false
    }

    private fun handleLiGonggong(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if ((data.dungeonRecords[ShengShanDungeonManager.DUNGEON_RECORD_ID]?.clears ?: 0) <= 0) {
            player.sendMessage("§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f圣山里的情况可查清了？等你带回确切消息，咱家再替你禀报皇上。")
            player.sendMessage("§c（请先至少通过一次圣山副本）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        playDialogue(player, liGonggongScript) {
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 前往皇宫，将圣山见闻禀报给人皇-轩辕氏。")
        }
        return true
    }

    private fun handleEmperor(player: Player): Boolean {
        playDialogue(player, emperorScript) {
            val data = plugin.playerManager.getPlayerData(player) ?: return@playDialogue
            plugin.questManager.completeQuest(player, data, this)
        }
        return true
    }

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
            plugin.logger.warning("人族第十二章缺少奖励物品配置: $resourceId")
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
