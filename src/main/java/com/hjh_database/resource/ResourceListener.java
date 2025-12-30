package com.hjh_database.resource;

import com.hjh_database.Hjh_database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ResourceListener implements Listener {
    private final Hjh_database plugin;

    public ResourceListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    // 1. 玩家进服时，扫描并刷新他背包里所有的物品
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateInventory(event.getPlayer().getInventory());
    }

    // 2. 玩家打开任何箱子/背包时，扫描并刷新里面的物品
    @EventHandler
    public void onOpenInventory(InventoryOpenEvent event) {
        updateInventory(event.getInventory());
        if (event.getPlayer() instanceof Player) {
            updateInventory(((Player) event.getPlayer()).getInventory());
        }
    }

    // 批量刷新逻辑
    private void updateInventory(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null) {
                // 只有属于我们系统的物品会被刷新
                plugin.getResourceManager().refreshItem(item);
            }
        }
    }
}