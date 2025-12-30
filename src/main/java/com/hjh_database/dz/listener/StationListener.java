package com.hjh_database.dz.listener;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.dz.gui.AdminCategoryGui;
import com.hjh_database.dz.gui.CategoryGui;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class StationListener implements Listener {
    private final Hjh_database plugin;
    private final NamespacedKey stationKey;

    public StationListener(Hjh_database plugin) {
        this.plugin = plugin;
        this.stationKey = new NamespacedKey(plugin, "hjh_forge_station");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 只处理主手交互，防止触发两次
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.DISPENSER) return;

        // 检查是否为锻造台 (检查 NBT)
        if (!(block.getState() instanceof TileState tileState)) return;
        if (!tileState.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) return;

        event.setCancelled(true); // 阻止打开原版发射器
        Player player = event.getPlayer();
        ItemStack handItem = player.getInventory().getItemInMainHand();

        // === 1. 管理员逻辑：手持木锄右键 ===
        if (player.isOp() && handItem.getType() == Material.WOODEN_HOE) {
            player.sendMessage(ChatColor.GREEN + "[管理员] 已打开配方管理界面");
            new AdminCategoryGui(plugin, player).open();
            return;
        }

        // === 2. 普通玩家逻辑：打开锻造界面 ===
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        Integer job = (data != null) ? data.getJob() : null;
        new CategoryGui(plugin, player, job).open();
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() == Material.DISPENSER) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) {
                Block block = event.getBlockPlaced();
                if (block.getState() instanceof TileState tileState) {
                    PersistentDataContainer data = tileState.getPersistentDataContainer();
                    data.set(stationKey, PersistentDataType.STRING, "true");
                    tileState.update();
                    event.getPlayer().sendMessage(ChatColor.GREEN + "成功放置锻造台 (发射器)！");
                }
            }
        }
    }
}