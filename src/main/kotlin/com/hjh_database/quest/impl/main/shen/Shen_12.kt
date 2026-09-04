package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Shen_12 : QuestBase("main_shen_12", "[神族主线]圣山疑云", QuestType.MAIN, 26) {

    override val raceLimit = 0
    override val requiredCompletedQuestIds = setOf("main_shen_11")

    override val description = listOf(
        "§7通过圣山秘境，查明四圣兽与盘古肉身的异变。",
        "§7回神族大殿，将圣山中的见闻告知长老。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f圣山一行，辛苦了。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f四圣兽体内竟然藏着这种规模的阵法……",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f仙族当年将它们送来的时候，从未提过此事。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f还有那具所谓的“盘古”肉身。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f一出现便只知道吞噬周围的一切……",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f看来这件事情比我预想得还要麻烦。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f我会派人继续调查四圣兽。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f尤其是当年§f§n仙族§f究竟为什么把它们送到我族手中。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f你这一路做得不错。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这些奖励拿去，暂且休息吧。",
        "§7（长老走来时，你发现屋顶的灯突然熄灭了一下，又重新亮起）",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这灯又灭了？三天两头出毛病……看来回头得找那个负责§4修灯§f的来看看了。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f你先回去吧，若有新的发现，我会再召你回来。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c至少通过一次圣山副本，再找 §e神族长老 §c复命")
        else -> listOf(
            "§a已将圣山见闻告知神族长老",
            "",
            "§7§o四圣兽的谜团暂时结束了",
            "§7§o可你总觉得事情的并非如此简单",
            "§7§o仙族为何要供奉\"四圣兽\"，那具\"盘古\"为何会掌握一些奇怪的阵法",
            "§7§o大殿的那盏灯……，§4§o修灯§7§o的人会知道些什么吗",
            "",
            "§7§o或许是知道的§6§l§o真相§7§o还不够多的缘故……"
        )
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZHANGLAO.id || currentProgress != 0) return false

        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if ((data.dungeonRecords[ShengShanDungeonManager.DUNGEON_RECORD_ID]?.clears ?: 0) <= 0) {
            player.sendMessage("§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f先去圣山查清异变，再回来见我。")
            player.sendMessage("§c（请先至少通过一次圣山副本）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in elderScript.indices) return true

        player.sendMessage(elderScript[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == elderScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun canHandleNpcDialogue(npcId: String, currentProgress: Int): Boolean =
        npcId == StoryNpcs.SHEN_ZHANGLAO.id && currentProgress == 0

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
            plugin.logger.warning("神族第十二章缺少奖励物品配置: $resourceId ($displayName)")
            return
        }
        item.amount = amount
        rewards += item
    }
}
