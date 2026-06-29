package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.block.TileState
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class BaihuDzStationListener(private val plugin: Hjh_database) : Listener {
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.DISPENSER) return
        val state = block.state as? TileState ?: return
        if (!state.persistentDataContainer.has(plugin.baihuDzManager.stationKey, PersistentDataType.STRING)) return

        event.isCancelled = true
        BaihuDzCategoryGui(plugin, event.player).open()
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type != Material.DISPENSER) return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(plugin.baihuDzManager.stationKey, PersistentDataType.STRING)) return
        val state = event.blockPlaced.state as? TileState ?: return
        state.persistentDataContainer.set(plugin.baihuDzManager.stationKey, PersistentDataType.STRING, "true")
        state.update()
        event.player.sendMessage("§a已放置虎瘴锻造台。")
    }
}
