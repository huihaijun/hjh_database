package com.hjh_database.listener;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.PlayerInventory;

public class PlayerListener implements Listener {
    private final Hjh_database plugin;

    public PlayerListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getPlayerManager().loadAndCache(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data != null) {
            data.setCurrentHealth(player.getHealth());
        }
        plugin.getPlayerManager().unloadAndSave(player.getUniqueId());
    }

    // =================================================================
    //  ⚡️ 核心：全方位状态同步监听
    //  任何可能导致物品栏变动的事件，都会触发 refreshPlayerStatus
    // =================================================================

    // 1. 切换快捷栏 (滚轮/数字键)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        refreshPlayerStatus(event.getPlayer());
    }

    // 2. 交换双手物品 (按F)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        refreshPlayerStatus(event.getPlayer());
    }

    // 3. 丢弃物品 (按Q)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        refreshPlayerStatus(event.getPlayer());
    }

    // 4. 捡起物品
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refreshPlayerStatus(player);
        }
    }

    // 5. 点击背包 (移动/穿戴/丢弃)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // 只处理玩家自己的背包，或者涉及到装备栏的操作
            refreshPlayerStatus(player);
        }
    }

    // 6. 关闭背包 (作为兜底检查)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshPlayerStatus(player);
        }
    }

    // 7. 玩家复活 (防止死亡后属性未重置)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        refreshPlayerStatus(event.getPlayer());
    }

    /**
     * 统一刷新方法
     * 延迟 1 Tick 执行，确保事件已经处理完毕，物品已经在新位置上
     */
    private void refreshPlayerStatus(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // 1. 刷新所有物品的 Lore (视觉反馈)
            plugin.getPlayerManager().getWeaponManager().refreshPlayerWeapons(player);

            // 2. 【新增】刷新护甲 Lore (状态显示)
            plugin.getPlayerManager().getArmorManager().refreshPlayerArmors(player);

            // 2. 重新计算所有属性 (数值反馈)
            plugin.getPlayerManager().updateStats(player);

            // 3. (可选) 强制客户端刷新背包显示，解决偶尔的 Lore 显示延迟
            // 注意：频繁调用 updateInventory 在高版本通常没问题，但在极旧版本可能有性能损耗
            // 如果你发现 Lore 还是偶尔不刷新，取消下面这行的注释
            // player.updateInventory();

        }, 1L);
    }
}