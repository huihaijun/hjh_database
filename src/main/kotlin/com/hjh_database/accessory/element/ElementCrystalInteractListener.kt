package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class ElementCrystalInteractListener(private val plugin: Hjh_database) : Listener {
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val block = event.clickedBlock ?: return
            if (block.type == Material.RESPAWN_ANCHOR) {
                if (block.x == 124 && block.y == 60 && block.z == -19) {
                    event.isCancelled = true
                    plugin.elementCrystalGui.openGui(event.player)
                }
            }
        }
    }
}
