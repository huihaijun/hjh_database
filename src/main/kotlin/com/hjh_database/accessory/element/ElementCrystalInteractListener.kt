package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.admin.AdminInteractionBlocks.Kind
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class ElementCrystalInteractListener(private val plugin: Hjh_database) : Listener {
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val block = event.clickedBlock ?: return
            if (plugin.adminInteractionBlocks.typeAt(block) != Kind.ELEMENT_CRYSTAL) return
            event.isCancelled = true // 两只手都阻止原版充能/爆炸/物品使用，仅主手开 GUI。
            if (event.hand != EquipmentSlot.HAND) return
            plugin.elementCrystalGui.openGui(event.player)
        }
    }
}
