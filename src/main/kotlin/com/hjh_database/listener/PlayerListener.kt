package com.hjh_database.listener

import com.hjh_database.Hjh_database
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.*

class PlayerListener(private val plugin: Hjh_database) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.playerManager.loadAndCache(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            data.currentHealth = player.health
        }
        plugin.playerManager.unloadAndSave(player.uniqueId)
    }

    // =================================================================
    //  ⚡️ 核心：全方位状态同步监听
    //  任何可能导致物品栏变动的事件，都会触发 refreshPlayerStatus
    // =================================================================

    // 1. 切换快捷栏 (滚轮/数字键)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        refreshPlayerStatus(event.player)
    }

    // 2. 交换双手物品 (按F)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        refreshPlayerStatus(event.player)
    }

    // 3. 丢弃物品 (按Q)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        refreshPlayerStatus(event.player)
    }

    // 4. 捡起物品
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        if (event.entity is Player) {
            refreshPlayerStatus(event.entity as Player)
        }
    }

    // 5. 点击背包 (移动/穿戴/丢弃)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val who = event.whoClicked
        if (who is Player) {
            // 只处理玩家自己的背包，或者涉及到装备栏的操作
            refreshPlayerStatus(who)
        }
    }

    // 6. 关闭背包 (作为兜底检查)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player
        if (player is Player) {
            refreshPlayerStatus(player)
        }
    }

    // 7. 玩家复活 (防止死亡后属性未重置)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        refreshPlayerStatus(event.player)
    }

    /**
     * 统一刷新方法
     * 延迟 1 Tick 执行，确保事件已经处理完毕，物品已经在新位置上
     */
    private fun refreshPlayerStatus(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            // 1. 刷新所有物品的 Lore (视觉反馈)
            // 注意：这里调用的是属性 weaponManager (对应 Java 的 getWeaponManager())
            plugin.playerManager.weaponManager.refreshPlayerWeapons(player)

            // 2. 【新增】刷新护甲 Lore (状态显示)
            plugin.playerManager.armorManager.refreshPlayerArmors(player)

            // 2. 重新计算所有属性 (数值反馈)
            plugin.playerManager.updateStats(player)

            // 3. (可选) 强制客户端刷新背包显示，解决偶尔的 Lore 显示延迟
            // 注意：频繁调用 updateInventory 在高版本通常没问题，但在极旧版本可能有性能损耗
            // 如果你发现 Lore 还是偶尔不刷新，取消下面这行的注释
            // player.updateInventory()

        }, 1L)
    }
}