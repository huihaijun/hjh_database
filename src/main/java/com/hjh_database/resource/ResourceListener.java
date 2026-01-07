package com.hjh_database.resource;

import com.hjh_database.Hjh_database;
// 记得导入你的GUI类
import com.hjh_database.dz.gui.AdminRecipeListGui;
import com.hjh_database.dz.gui.PlayerRecipeListGui;
import com.hjh_database.dz.gui.RecipePreviewGui;
// 如果有其他不想被刷新的界面也加在这里

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder; // 导入这个
import org.bukkit.inventory.ItemStack;

public class ResourceListener implements Listener {
    private final Hjh_database plugin;

    public ResourceListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    // 1. 玩家进服时，扫描并刷新他背包里所有的物品 (这个没问题，保留)
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateInventory(event.getPlayer().getInventory());
    }

    // 2. 玩家打开任何箱子/背包时 (这里是问题所在！)
    @EventHandler
    public void onOpenInventory(InventoryOpenEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();

        // === 【核心修复】 黑名单豁免 ===
        // 如果打开的是配方列表、预览界面或管理员管理界面，直接返回，不要刷新！
        // 因为这些界面里的物品是"展示用"的，带有特殊的Lore和NBT数据，不能被重置。
        if (holder instanceof PlayerRecipeListGui ||
                holder instanceof AdminRecipeListGui ||
                holder instanceof RecipePreviewGui) {
            return;
        }
        // =============================

        updateInventory(inv);

        // 刷新玩家自己的背包是没问题的，因为那是他身上的装备，应该保持最新
        if (event.getPlayer() instanceof Player) {
            updateInventory(((Player) event.getPlayer()).getInventory());
        }
    }

    // 批量刷新逻辑 (保持不变)
    private void updateInventory(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null) {
                plugin.getResourceManager().refreshItem(item);
            }
        }
    }
}