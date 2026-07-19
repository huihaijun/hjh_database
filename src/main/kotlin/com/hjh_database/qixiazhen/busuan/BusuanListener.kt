package com.hjh_database.qixiazhen.busuan

import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class BusuanListener(private val manager: BusuanManager) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val block = event.clickedBlock
        val seeking = block?.let(manager::isSeekingBlock) == true
        val pot = block?.let(manager::isPotBlock) == true
        val ritualBlock = seeking || pot

        // 无论玩家手持什么、使用哪只手，都禁止仪式方块的原版交互。
        if (ritualBlock) denyInteraction(event)
        if (event.hand != EquipmentSlot.HAND) return

        if (manager.handleFortuneUse(event.player)) {
            denyInteraction(event)
            return
        }
        if (!ritualBlock) return
        if (seeking) manager.handleSeeking(event.player) else manager.handlePot(event.player)
    }

    private fun denyInteraction(event: PlayerInteractEvent) {
        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
    }
}
