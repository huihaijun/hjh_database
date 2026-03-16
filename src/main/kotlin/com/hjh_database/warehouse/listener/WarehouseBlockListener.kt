package com.hjh_database.warehouse.listener

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType

class WarehouseBlockListener(private val plugin: Hjh_database) : Listener {

    private val blockKey = NamespacedKey(plugin, "is_warehouse_block")

    @EventHandler
    fun onPlace(e: BlockPlaceEvent) {
        val item = e.itemInHand
        if (item.itemMeta?.persistentDataContainer?.has(blockKey, PersistentDataType.BYTE) == true) {
            val loc = e.blockPlaced.location.add(0.5, 1.2, 0.5)
            // 在 1.21.3 中推荐使用 TextDisplay
            val display = loc.world.spawnEntity(loc, EntityType.TEXT_DISPLAY) as TextDisplay
            display.text = "§a§l个人仓库\n§b[右键打开]"
            display.billboard = org.bukkit.entity.Display.Billboard.CENTER

            // 给方块所在的 Chunk 或 直接使用配置文件记录该坐标是仓库
            // 简单实现：将该坐标存入配置或使用 PDC 绑在周围实体上
            display.persistentDataContainer.set(blockKey, PersistentDataType.BYTE, 1)
        }
    }

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action == Action.RIGHT_CLICK_BLOCK && e.clickedBlock?.type == Material.CHEST) {
            // 检查方块上方是否有我们的悬浮字（以此判定为仓库方块）
            val isWarehouse = e.clickedBlock!!.location.add(0.5, 1.2, 0.5).getNearbyEntities(0.1, 0.1, 0.1)
                .any { it is TextDisplay && it.persistentDataContainer.has(blockKey, PersistentDataType.BYTE) }

            if (isWarehouse) {
                e.isCancelled = true
                // 打开 GUI
                plugin.warehouseManager.openMainMenu(e.player, e.player)
            }
        }
    }
    @EventHandler
    fun onBreak(e: BlockBreakEvent) {
        if (e.block.type == Material.CHEST) {
            val loc = e.block.location.add(0.5, 1.2, 0.5)
            // 寻找方块上方的 TextDisplay 实体
            val displays = loc.getNearbyEntities(0.1, 0.1, 0.1).filterIsInstance<TextDisplay>()
            val isWarehouse = displays.any { it.persistentDataContainer.has(blockKey, PersistentDataType.BYTE) }

            if (isWarehouse) {
                // 如果不是 OP，禁止破坏（防止普通玩家乱敲）
                if (!e.player.isOp) {
                    e.isCancelled = true
                    e.player.sendMessage("§c这是个人仓库，无法破坏！")
                    return
                }
                // 是 OP，允许破坏，并清理悬浮字
                displays.forEach {
                    if (it.persistentDataContainer.has(blockKey, PersistentDataType.BYTE)) {
                        it.remove()
                    }
                }
                e.player.sendMessage("§a已成功拆除个人仓库方块！")
            }
        }
    }
}