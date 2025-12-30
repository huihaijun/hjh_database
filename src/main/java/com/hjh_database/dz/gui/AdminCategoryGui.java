package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class AdminCategoryGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final Inventory inv;

    public AdminCategoryGui(Hjh_database plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv = Bukkit.createInventory(this, 27, "§c[管理员] 选择配方分类");

        setItem(10, Material.IRON_SWORD, "weapon", "§c武器配方管理");
        setItem(12, Material.DIAMOND_CHESTPLATE, "armor", "§b防具配方管理");
        setItem(14, Material.TOTEM_OF_UNDYING, "artifact", "§6神器配方管理");
        setItem(16, Material.APPLE, "misc", "§a杂项配方管理");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void setItem(int slot, Material mat, String key, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLocalizedName(key);
        meta.setLore(Arrays.asList("§7点击进入管理界面", "§7可新建、编辑、删除"));
        // 修复: 隐藏Flag
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    public void open() { player.openInventory(inv); }

    @Override
    public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inv)) return;
        event.setCancelled(true);

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        String category = item.getItemMeta().getLocalizedName();
        // 进入列表
        new AdminRecipeListGui(plugin, player, category).open();
    }
}