package com.hjh_database.resource;

import com.hjh_database.Hjh_database;
import com.hjh_database.dz.gui.AdminRecipeListGui;
import com.hjh_database.dz.gui.PlayerRecipeListGui;
import com.hjh_database.dz.gui.RecipePreviewGui;
import com.hjh_database.skill.medical.gui.MedicalEtchGui;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class ResourceListener implements Listener {
    private final Hjh_database plugin;
    // 本地定义 Key，防止调用其他 Manager 可能出现的空指针或顺序问题
    private final NamespacedKey keyIgnoreRefresh;

    public ResourceListener(Hjh_database plugin) {
        this.plugin = plugin;
        // 这个 Key 字符串必须和 MedicalManager 里的一模一样
        this.keyIgnoreRefresh = new NamespacedKey(plugin, "hjh_ignore_refresh");
    }

    // 1. 玩家进服刷新
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateInventory(event.getPlayer().getInventory());
    }

    // 2. 打开容器刷新
    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();

        // 保持您原有的：黑名单界面不刷新
        if (holder instanceof PlayerRecipeListGui ||
                holder instanceof AdminRecipeListGui ||
                holder instanceof RecipePreviewGui ||
                holder instanceof MedicalEtchGui.EtchHolder ||
                holder instanceof MedicalEtchGui.SeparateHolder ||
                holder instanceof MedicalEtchGui.MainMenuHolder) {
            return;
        }

        // 刷新容器内容
        updateInventory(inv);

        // 顺便刷新玩家自己的背包
        if (event.getPlayer() instanceof Player) {
            updateInventory(((Player) event.getPlayer()).getInventory());
        }
    }

    // === 核心刷新逻辑 ===
    private void updateInventory(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item == null || !item.hasItemMeta()) continue;

            // ================= 改动位置 =================
            // 1. 优先检查：是否有“免刷新锁”
            // 如果有这个标记，直接 continue 跳过，保护它的名字和 Lore 不被还原
            if (item.getItemMeta().getPersistentDataContainer().has(keyIgnoreRefresh, PersistentDataType.INTEGER)) {
                continue;
            }

            // 2. 如果没有锁，才执行您原本的统一刷新逻辑
            plugin.getResourceManager().refreshItem(item);
            // ===========================================
        }
    }
}