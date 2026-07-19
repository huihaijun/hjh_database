package com.hjh_database.qixiazhen.farming.listener

import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot

class FarmingListener(private val manager: FarmingManager) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        manager.loadAndCache(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        manager.saveAndRemove(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val id = manager.resourceId(event.itemInHand) ?: return
        if (id != FarmingManager.FIELD_RESOURCE_ID && id != FarmingManager.CONTROLLER_RESOURCE_ID) return
        if (!event.player.isOp) {
            event.isCancelled = true
            event.player.sendMessage("§c只有管理员可以放置灵田设施。")
            return
        }
        val expected = if (id == FarmingManager.FIELD_RESOURCE_ID) Material.FARMLAND else Material.BELL
        if (event.blockPlaced.type != expected || !manager.registerPlacedBlock(event.player, event.blockPlaced, id)) {
            event.isCancelled = true
            event.player.sendMessage("§c灵田设施登记失败。")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (manager.plot(event.block) == null && manager.controller(event.block) == null) return
        event.isCancelled = true
        if (!event.player.isOp) {
            event.player.sendMessage("§c灵田设施只能由管理员拆除。")
            return
        }
        event.isDropItems = false
        manager.removeRegisteredBlock(event.player, event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> {
                manager.controller(block)?.let {
                    event.isCancelled = true
                    manager.showController(event.player, it)
                    return
                }
                manager.plot(block)?.let {
                    event.isCancelled = true
                    manager.interactPlot(event.player, it)
                }
            }
            Action.LEFT_CLICK_BLOCK -> {
                manager.plot(block)?.let {
                    event.isCancelled = true
                    if (event.player.isOp && event.player.inventory.itemInMainHand.type == Material.DIAMOND_SHOVEL) {
                        manager.removeRegisteredBlock(event.player, block)
                    } else {
                        manager.confirmCropRemoval(event.player, it)
                    }
                }
            }
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFarmlandTrample(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        if (event.clickedBlock?.type != Material.FARMLAND) return
        event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (manager.isFarmBlock(event.block)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        if (manager.isFarmBlock(event.block) ||
            (event.entity is Player && event.block.type == Material.FARMLAND && event.to == Material.DIRT)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf(manager::isFarmBlock)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf(manager::isFarmBlock)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any(manager::isFarmBlock)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any(manager::isFarmBlock)) event.isCancelled = true
    }
}
