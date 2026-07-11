package com.hjh_database.kaiwu

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class KaiWuListener(private val plugin: Hjh_database) : Listener {
    // 假设 Hjh_database 中有 getKaiWuManager() 方法，Kotlin 中调用为 plugin.kaiWuManager
    private val editor: KaiWuEditor = KaiWuEditor(plugin, plugin.kaiWuManager)

    // 1. 聊天确认监听 (删除资源点)
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val manager = plugin.kaiWuManager
        val p = event.player

        // deleteConfirmations 是 MutableMap，可以直接 containsKey
        if (manager.deleteConfirmations.containsKey(p.uniqueId)) {
            event.isCancelled = true // 拦截聊天
            val msg = event.message.trim()

            if (msg == "1") {
                // 必须同步执行删除操作
                // Runnable Lambda 简化写法
                plugin.server.scheduler.runTask(plugin, Runnable { manager.confirmDeleteNode(p) })
            } else {
                manager.deleteConfirmations.remove(p.uniqueId)
                p.sendMessage("§7操作已取消。")
            }
        }
    }

    // 2. 受伤打断开采
    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        if (event.entity is Player) {
            val p = event.entity as Player
            if (plugin.kaiWuManager.isMining(p)) {
                plugin.kaiWuManager.cancelMining(p, true)
            }
        }
    }

    // 3. 破坏方块 (触发删除确认)
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val manager = plugin.kaiWuManager
        if (manager.isNode(event.block.location)) {
            event.isCancelled = true // 无论如何先取消

            val p = event.player
            if (p.hasPermission("hjh.kaiwu.op")) {
                // 触发确认流程
                manager.requestDeleteNode(p, manager.serializeLoc(event.block.location))
            } else {
                p.sendMessage("§c这是资源点，请右键开采！")
            }
        }
    }

    // 4. 交互 (开采 & 编辑)
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.clickedBlock == null) return

        val player = event.player
        val manager = plugin.kaiWuManager
        // 这里的 !! 是安全的，因为上面检查了 null 并 return
        val locKey = manager.serializeLoc(event.clickedBlock!!.location)

        // 左键查看资源信息；同时取消原版方块破坏行为。
        if (event.action == Action.LEFT_CLICK_BLOCK && manager.isNode(event.clickedBlock!!.location)) {
            event.isCancelled = true
            manager.showNodeInfo(player, event.clickedBlock!!.location)
            return
        }

        // 右键负责开采与管理员编辑。
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        // OP 编辑模式 (金锄头)
        if (player.hasPermission("hjh.kaiwu.op") &&
            player.inventory.itemInMainHand.type == Material.GOLDEN_HOE
        ) {
            event.isCancelled = true
            editor.openEditor(player, locKey)
            return
        }

        // 玩家开采模式
        if (manager.isNode(event.clickedBlock!!.location)) {
            event.isCancelled = true
            manager.startMining(player, event.clickedBlock!!.location)
        }
    }

    @EventHandler
    fun onInvClick(event: InventoryClickEvent) {
        editor.handleClick(event)
    }

    @EventHandler
    fun onInvClose(event: InventoryCloseEvent) {
        editor.handleClose(event)
    }
}
