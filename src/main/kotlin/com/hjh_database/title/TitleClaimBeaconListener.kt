package com.hjh_database.title

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestStatus
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class TitleClaimBeaconListener(private val plugin: Hjh_database) : Listener {
    companion object {
        private const val BEACON_X = 204
        private const val BEACON_Y = 47
        private const val BEACON_Z = 92
    }

    private val claimingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    @EventHandler
    fun onBeaconInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (
            block.type != Material.BEACON ||
            block.x != BEACON_X ||
            block.y != BEACON_Y ||
            block.z != BEACON_Z
        ) {
            return
        }

        event.isCancelled = true
        val player = event.player
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            player.sendMessage("§c[称号] 玩家数据尚未加载完成，请稍后再试。")
            return
        }
        if (!claimingPlayers.add(player.uniqueId)) {
            player.sendMessage("§7[称号] 正在检查可补领的主线称号，请稍候。")
            return
        }

        val rewards = buildList {
            QuestTitleRewards.forRace(data.race)?.takeIf { hasCompleted(data, it.questId) }?.let(::add)
            addAll(QuestTitleRewards.regional.filter { hasCompleted(data, it.questId) })
        }
        if (rewards.isEmpty()) {
            claimingPlayers.remove(player.uniqueId)
            if (QuestTitleRewards.forRace(data.race) == null) {
                player.sendMessage("§c[称号] 当前种族无法对应人、神、妖主线称号。")
            }
            player.sendMessage("§e[称号] 暂无可补领的主线称号，请先完成对应主线终章。")
            return
        }

        val grants = rewards.map {
            plugin.titleManager.grantQuestCompletionTitle(player, it.titleId, it.questId)
        }
        CompletableFuture.allOf(*grants.toTypedArray()).whenComplete { _, _ ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                claimingPlayers.remove(player.uniqueId)
                if (!player.isOnline) return@Runnable
                val grantedCount = grants.count { it.getNow(false) }
                if (grantedCount > 0) {
                    player.sendMessage("§a[称号] 补领完成，共获得 $grantedCount 个主线称号。")
                } else {
                    player.sendMessage("§e[称号] 对应的主线称号均已领取，无需重复补领。")
                }
            })
        }
    }

    private fun hasCompleted(data: PlayerData, questId: String): Boolean {
        return data.questStatuses[questId] == QuestStatus.COMPLETED || questId in data.completedQuests
    }
}
