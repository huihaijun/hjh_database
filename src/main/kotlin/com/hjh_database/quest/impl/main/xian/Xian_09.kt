package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Xian_09 : QuestBase("main_xian_9", "[仙族主线]四方祝福", QuestType.MAIN, 9) {

    override val raceLimit = 1

    override val description = listOf(
        "§7青龙祝福仍不足以感应四圣兽的方位。",
        "§7返回皇宫，将青龙神庙的情况告知§e羽罗§7。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val healerCache = HashMap<UUID, Boolean>()
    private val queryingPlayers = mutableSetOf<UUID>()

    private fun getScript(isHealer: Boolean): List<String> {
        val script = mutableListOf(
            "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f原来如此……纵是获得了§e青龙§f的祝福，也未能感应到圣兽的方位么。",
            "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f那便如分魂大人所言，得将§e四圣兽的祝福§f尽数集齐方有转机。其余三座祭坛，依序为——§c南方朱雀祭坛§f、§e西方白虎祭坛§f、§b北方玄武祭坛§f。",
            "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f这四地魔物一处比一处凶险，上仙务请§e按此顺序§f前往，不可乱了先后。",
            "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f待集齐四大祝福，请再回皇城。届时§e护国法师§f大人定有法子相助上仙。他的居所就在§e皇宫东侧§f，与§6重华镜§f同处一殿。上仙取得祝福之后，径直去寻他便是。",
            "§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f此外，皇上命我备了些§e薄礼§f，献给上仙。皆是凡间俗物，恐难入上仙法眼，但终究是我等一片心意，万望上仙收下。"
        )
        if (isHealer) {
            script.add("§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f还有一事，皇上听闻上仙不仅为我族分忧，更以§d医术§f救济天下苍生，特命我备下这批§e医师的补给§f。虽是微薄，也是我人族对上仙的敬仰之心，请上仙一并过目。")
        }
        script.add("§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f此去路途凶险，上仙务必保重。")
        return script
    }

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c通过青龙试炼后，返回皇宫向 §e羽罗 §c汇报")
        else -> listOf("§a已取得前往其余三处圣兽祭坛的指引")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_YULUO.id || currentProgress != 0) return false

        val playerId = player.uniqueId
        val index = talkProgress.getOrDefault(playerId, 0)
        if (index == 0) {
            if (!queryingPlayers.add(playerId)) return true
            val isHealer = plugin.playerManager.getPlayerData(player)?.job == HEALER_JOB_ID

            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                var trialPassed = false
                var queryFailed = false
                try {
                    plugin.databaseManager.dataSource?.connection?.use { connection ->
                        connection.prepareStatement("SELECT qinglong FROM player_test WHERE uuid = ?").use { statement ->
                            statement.setString(1, playerId.toString())
                            statement.executeQuery().use { result ->
                                trialPassed = result.next() && result.getInt("qinglong") == 1
                            }
                        }
                    }
                } catch (exception: Exception) {
                    queryFailed = true
                    plugin.logger.warning("查询仙族玩家青龙试炼状态失败: ${exception.message}")
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    queryingPlayers.remove(playerId)
                    if (!player.isOnline) return@Runnable

                    when {
                        queryFailed -> player.sendMessage("§c[系统] 无法读取青龙试炼状态，请稍后重试或联系管理员。")
                        !trialPassed -> {
                            player.sendMessage("§e[${StoryNpcs.XIAN_YULUO.displayName}§e] §f上仙可曾通过青龙试炼？取得祝福之后，再来将结果告知我吧。")
                            player.sendMessage("§c（尚未通过青龙试炼，请完成试炼后再与羽罗交谈）")
                            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                        }
                        else -> {
                            healerCache[playerId] = isHealer
                            playDialogue(player, isHealer)
                        }
                    }
                })
            })
            return true
        }

        playDialogue(player, healerCache.getOrDefault(playerId, false))
        return true
    }

    override fun giveReward(player: Player) {
        val isHealer = healerCache.getOrDefault(player.uniqueId, false)
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
        healerCache.remove(player.uniqueId)
    }

    private fun playDialogue(player: Player, isHealer: Boolean) {
        val script = getScript(isHealer)
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in script.indices) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            val data = plugin.playerManager.getPlayerData(player) ?: return
            plugin.questManager.completeQuest(player, data, this)
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun addReward(rewards: MutableList<ItemStack>, resourceId: String, amount: Int) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("仙族第九章缺少奖励物品配置: $resourceId")
            return
        }
        item.amount = amount
        rewards.add(item)
    }

    private companion object {
        const val HEALER_JOB_ID = 3
    }
}
