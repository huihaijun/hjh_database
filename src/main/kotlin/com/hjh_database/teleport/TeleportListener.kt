package com.hjh_database.teleport

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType

class TeleportListener(private val plugin: Hjh_database) : Listener {

    private val tpKey = NamespacedKey(plugin, "hjh_tp_point_id")

    // === 1. 放置方块时，注册传送点 ===
    @EventHandler
    fun onPlace(e: BlockPlaceEvent) {
        val item = e.itemInHand
        if (!item.hasItemMeta()) return

        val pdc = item.itemMeta!!.persistentDataContainer
        if (pdc.has(tpKey, PersistentDataType.STRING)) {
            val pointId = pdc.get(tpKey, PersistentDataType.STRING) ?: return

            // 检查该 ID 是否在配置中存在
            if (!plugin.teleportManager.points.containsKey(pointId)) {
                e.player.sendMessage("§c[警告] 传送点ID [$pointId] 在配置中不存在！")
            }

            // 记录方块位置
            plugin.teleportManager.addBlock(e.block.location, pointId)
            e.player.sendMessage("§a[HJH] 已成功放置传送点触发器！绑定ID: $pointId")
        }
    }

    // === 2. 破坏方块时，移除数据 ===
    @EventHandler
    fun onBreak(e: BlockBreakEvent) {
        val loc = e.block.location
        // 检查这个位置是不是传送点
        if (plugin.teleportManager.getPointIdByBlock(loc) != null) {
            if (!e.player.isOp) {
                e.player.sendMessage("§c你不能破坏传送触发器。")
                e.isCancelled = true
                return
            }
            plugin.teleportManager.removeBlock(loc)
            e.player.sendMessage("§e[HJH] 已移除该位置的传送触发器数据。")
        }
    }

    // === 3. 玩家交互（踩踏/右键） ===
    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        val block = e.clickedBlock ?: return
        // 只响应 物理接触(踩压力板) 和 右键方块
        if (e.action != Action.PHYSICAL && e.action != Action.RIGHT_CLICK_BLOCK) return
        val pointId = plugin.teleportManager.getPointIdByBlock(block.location) ?: return
        // 压力板触发时，不要取消事件，否则压力板按不下去看着很怪
        if (e.action == Action.RIGHT_CLICK_BLOCK && !isInteractable(block.type)) {
            e.isCancelled = true
        }
        // ★★★ 修复点：延迟 1 tick 执行传送，防止 CME 崩溃 ★★★
        // 如果是物理触发(压力板)，必须延迟；右键其实可以直接传，但统一延迟最安全
        plugin.server.scheduler.runTask(plugin, Runnable {
            // 再次检查玩家是否在线（防止极端情况）
            if (e.player.isOnline) {
                plugin.teleportManager.tryTeleport(e.player, pointId)
            }
        })
    }

    // 简单的辅助判断：是否是原版可交互方块(按钮/拉杆/门等)
    private fun isInteractable(mat: Material): Boolean {
        return mat.name.contains("BUTTON") ||
                mat.name.contains("LEVER") ||
                mat.name.contains("PLATE") ||
                mat.name.contains("DOOR")
    }
}