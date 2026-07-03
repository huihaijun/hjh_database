package com.hjh_database.farming.listener

import com.hjh_database.farming.manager.FarmingManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class FarmingEntranceListener(private val manager: FarmingManager) : Listener {
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.GLOWSTONE) return

        val loc = block.location
        if (loc.blockX != 168 || loc.blockY != 48 || loc.blockZ != 231) return

        event.isCancelled = true
        manager.openMain(event.player)
    }
}
