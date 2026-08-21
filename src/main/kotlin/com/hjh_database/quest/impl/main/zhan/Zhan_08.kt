package com.hjh_database.quest.impl.main.zhan

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Zhan_08 : QuestBase("main_zhan_8", "[战神族主线]青龙试炼", QuestType.MAIN, 8) {

    override val raceLimit = 3

    override val description = listOf(
        "§7左焰已潜入青龙神庙，并掌握了人仙二族的行动。",
        "§7与神庙内的左焰接头，参加青龙试炼并确认结界状态。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<DialogueKey, Int>()
    private val trialVerifiedPlayers = HashSet<UUID>()
    private val queryingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    private val briefingScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f战友。方才人仙二族与§e青龙分魂§f的对话，我都听到了。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f那分魂不过是§4§n青龙§f的一缕意识。青龙当年被我族重创后离开神庙，至今未归。这是个机会——四兽应该还在沉睡休整。若能§c先发制人§f，一举歼灭它们并非不可能。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f可就连那分魂也不清楚四兽的下落。所以他们让人仙二族去参加§e青龙试炼§f，获取祝福之力，借此感应四兽的方位。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f试炼向所有人开放。那我们也去——拿到§e青龙祝福§f，就能顺藤摸瓜，找到那几头畜生的藏身之处。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f神庙§e二楼§f便是试炼入口。跟紧我。"
    )

    private val reportScript = listOf(
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f结果即使获得了青龙的祝福，也没办法得知四兽现在的位置吗……",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f不过，至少感应得到，确定结界的效力正在减弱。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f如果四兽不及时回到神庙，结界很有可能就会崩解。",
        "§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f事不宜迟，这件事情，请你务必准确地转达给蚀日大人。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c在青龙神庙内与 §e左焰 §c接头")
        1 -> listOf("§c前往神庙二楼完成 §a青龙试炼§c，之后再与左焰对话")
        else -> listOf("§a已确认青龙结界正在减弱")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.ZHAN_ZUOYAN2.id) return false

        return when (currentProgress) {
            0 -> {
                playDialogue(player, briefingScript, currentProgress) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 前往青龙神庙二楼完成青龙试炼，之后再与左焰对话。")
                }
                true
            }

            1 -> {
                handlePostTrialDialogue(player)
                true
            }

            else -> false
        }
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 320)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +320")
        player.sendMessage("")
        player.sendMessage("  §e[提示] §f返回皇城月老小庙，将消息转达给蚀日")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        clearPlayerState(player.uniqueId)
    }

    private fun handlePostTrialDialogue(player: Player) {
        val playerId = player.uniqueId
        if (playerId in trialVerifiedPlayers) {
            playReportDialogue(player)
            return
        }

        val data = plugin.playerManager.getPlayerData(player)
        if ((data?.dungeonRecords?.get(QINGLONG_RECORD_ID)?.clears ?: 0) > 0) {
            trialVerifiedPlayers.add(playerId)
            playReportDialogue(player)
            return
        }

        if (!queryingPlayers.add(playerId)) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            var passed = false
            var queryFailed = false
            try {
                plugin.databaseManager.dataSource?.connection?.use { connection ->
                    connection.prepareStatement("SELECT qinglong FROM player_test WHERE uuid = ?").use { statement ->
                        statement.setString(1, playerId.toString())
                        statement.executeQuery().use { result ->
                            passed = result.next() && result.getInt("qinglong") == 1
                        }
                    }
                }
            } catch (exception: Exception) {
                queryFailed = true
                plugin.logger.warning("查询玩家 $playerId 的青龙试炼状态失败: ${exception.message}")
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                queryingPlayers.remove(playerId)
                if (!player.isOnline) return@Runnable

                when {
                    queryFailed -> player.sendMessage("§c[系统] 无法读取青龙试炼记录，请稍后重试。")
                    passed -> {
                        trialVerifiedPlayers.add(playerId)
                        playReportDialogue(player)
                    }
                    else -> {
                        player.sendMessage("§e[${StoryNpcs.ZHAN_ZUOYAN2.displayName}§e] §f试炼还没结束。先去二楼取得§e青龙祝福§f，再回来找我。")
                        player.sendMessage("§c（尚未通过青龙试炼）")
                        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    }
                }
            })
        })
    }

    private fun playReportDialogue(player: Player) {
        playDialogue(player, reportScript, 1) {
            val data = plugin.playerManager.getPlayerData(player) ?: return@playDialogue
            plugin.questManager.completeQuest(player, data, this)
        }
    }

    private fun playDialogue(player: Player, script: List<String>, phase: Int, onFinish: () -> Unit) {
        val key = DialogueKey(player.uniqueId, phase)
        val index = talkProgress.getOrDefault(key, 0)
        if (index !in script.indices) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(key)
            onFinish()
        } else {
            talkProgress[key] = index + 1
        }
    }

    private fun clearPlayerState(playerId: UUID) {
        talkProgress.keys.removeIf { it.playerId == playerId }
        trialVerifiedPlayers.remove(playerId)
        queryingPlayers.remove(playerId)
    }

    private data class DialogueKey(val playerId: UUID, val phase: Int)

    private companion object {
        const val QINGLONG_RECORD_ID = "dragon_test"
    }
}
