package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Zhan_09 : QuestBase("main_zhan_9", "[战神族主线]四兽追踪", QuestType.MAIN, 9) {

    override val raceLimit = 3

    override val description = listOf(
        "§7青龙祝福无法直接感应四圣兽的下落。",
        "§7返回皇城月老小庙，将结界异动汇报给§e蚀日§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private fun getScript(isHealer: Boolean): List<String> {
        val script = mutableListOf(
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f好，情况我已知晓。你和左焰此行辛苦了。",
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f收集祝福之力并非无用之功。刚接到线报——人族冒险团一回到皇城，便马不停蹄地赶往§c南方沙漠§f了，那里正是§e朱雀祭坛§f的所在。",
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f我要你将四兽祝福尽数集齐。§e朱雀§f在南方沙漠，§e白虎§f在西边深山，§e玄武§f在北边玄水湖。加上东方的青龙，四处一个都不能少。",
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f这四地的魔物一处比一处凶险，务必按我说的顺序前去，不可乱了阵脚。",
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f集齐之后再来找我。到那时，我手中应该会有更多情报。",
            "§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f这些你拿着。是我在此地当差攒下的§e俸禄§f，族长吩咐过，若有族人经过，便分他一些。东西不多，够你路上用度。"
        )
        if (isHealer) {
            script.add("§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f还有——我知你选了§d医师§f。这条路，你走得注定比其他三门更艰难。这些是§e额外留给你§f的，收好，务必别推辞。")
        }
        script.add("§e[${StoryNpcs.ZHAN_SHIRI.displayName}§e] §f前路艰难，我族复仇大计，便系于你一身了。§c保重§f。")
        return script
    }

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回皇城月老小庙，向 §e蚀日 §c汇报青龙结界的情况")
        else -> listOf("§a已取得追踪其余三处圣兽祭坛的命令")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_SHIRI.id || currentProgress != 0) return false

        val isHealer = plugin.playerManager.getPlayerData(player)?.job == HEALER_JOB_ID
        val script = getScript(isHealer)
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return true

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return true
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
        return true
    }

    override fun giveReward(player: Player) {
        val isHealer = plugin.playerManager.getPlayerData(player)?.job == HEALER_JOB_ID
        val rewards = mutableListOf<ItemStack>()

        addReward(rewards, "jinyuanbao", 2)
        addReward(rewards, "yuansuduihuanquan", 8)
        addReward(rewards, "yuhedan0", 10)
        if (isHealer) {
            addReward(rewards, "armor_core_3", 1)
            addReward(rewards, "chitongding", 1)
        }

        val leftovers = player.inventory.addItem(*rewards.toTypedArray())
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分奖励已掉落在脚下！")
        }

        plugin.playerManager.giveExp(player, 360)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +360")
        player.sendMessage("  §e[奖励] §f金元宝 x2")
        player.sendMessage("  §e[奖励] §f元素兑换券 x8")
        player.sendMessage("  §e[奖励] §f初级愈合丹 x10")
        if (isHealer) {
            player.sendMessage("  §d[额外] §f三阶防具核心 x1")
            player.sendMessage("  §d[额外] §f赤铜锭 x1")
        }
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f下一站：南方沙漠的朱雀祭坛")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun addReward(rewards: MutableList<ItemStack>, resourceId: String, amount: Int) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("战神族第九章缺少奖励物品配置: $resourceId")
            return
        }
        item.amount = amount
        rewards.add(item)
    }

    private companion object {
        const val HEALER_JOB_ID = 3
    }
}
