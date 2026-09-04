package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestStatus
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class ShenJobTicketListener(private val plugin: Hjh_database) : Listener {

    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val destinations = mapOf(
        "shen_job_ticket_warrior" to Location(Bukkit.getWorld("world"), 292.54, 46.00, 27.12, 3152.26f, -1.65f),
        "shen_job_ticket_archer" to Location(Bukkit.getWorld("world"), 292.74, 47.00, -1.01, 3150.31f, 0.45f),
        "shen_job_ticket_warlock" to Location(Bukkit.getWorld("world"), 183.27, 47.00, 118.98, 3509.25f, -1.50f),
        "shen_job_ticket_doctor" to Location(Bukkit.getWorld("world"), 95.66, 48.00, 33.11, 3419.10f, 1.65f)
    )

    @EventHandler
    fun onUseTicket(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return

        val item = event.item ?: return
        val resourceId = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING) ?: return
        val destination = destinations[resourceId] ?: return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.race != 0 || data.questStatuses["main_shen_9"] != QuestStatus.IN_PROGRESS ||
            (data.questProgress["main_shen_9"] ?: 0) < 1
        ) {
            player.sendMessage("§c[提示] 这张传送券暂时无法响应你的神力。")
            return
        }

        val world = destination.world ?: run {
            player.sendMessage("§c[错误] 传送目标世界未加载。")
            return
        }

        event.isCancelled = true
        consumeMainHandTicket(player)
        player.teleport(destination.clone().apply { this.world = world })
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
    }

    private fun consumeMainHandTicket(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= 1
        }
    }
}
