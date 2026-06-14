package com.hjh_database.jianghu

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class JianghuXindeListener(private val plugin: Hjh_database) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val mainHand = player.inventory.itemInMainHand
        if (plugin.jianghuXindeManager.getXindeValue(mainHand) > 0) {
            event.isCancelled = true
            event.setUseItemInHand(Event.Result.DENY)
            event.setUseInteractedBlock(Event.Result.DENY)
            plugin.jianghuXindeManager.consumeXindeItem(player, mainHand)
            return
        }

        val clickedBlock = event.clickedBlock ?: return
        if (clickedBlock.type != Material.ENCHANTING_TABLE) return
        if (!plugin.jianghuXindeManager.isStation(clickedBlock.location)) return

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        plugin.jianghuXindeManager.openGui(player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title != plugin.jianghuXindeManager.guiTitle) return

        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val topSize = event.view.topInventory.size

        if (event.rawSlot >= topSize) {
            if (event.isShiftClick) {
                event.isCancelled = true
            }
            return
        }

        event.isCancelled = true
        plugin.jianghuXindeManager.handleGuiClick(player, event.rawSlot, event.click)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title != plugin.jianghuXindeManager.guiTitle) return
        val topSize = event.view.topInventory.size
        if (event.rawSlots.any { it < topSize }) {
            event.isCancelled = true
        }
    }
}
