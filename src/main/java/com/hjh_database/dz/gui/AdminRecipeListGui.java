package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import com.hjh_database.dz.data.DzRecipe;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class AdminRecipeListGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final String category;
    private final Inventory inv;
    private int page = 1;

    public AdminRecipeListGui(Hjh_database plugin, Player player, String category) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;
        this.inv = Bukkit.createInventory(this, 54, "配方管理: " + category);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresh();
    }

    private void refresh() {
        inv.clear();
        List<DzRecipe> all = plugin.getRecipeManager().getRecipesByCategory(category);

        int start = (page - 1) * 45;
        int end = Math.min(start + 45, all.size());

        for (int i = start; i < end; i++) {
            DzRecipe r = all.get(i);
            ItemStack item = r.getResult().clone();
            ItemMeta meta = item.getItemMeta();

            List<String> lore = meta.getLore();
            if (lore == null) lore = new ArrayList<>();
            lore.add(" ");
            lore.add("§8----------------");
            lore.add("§7ID: §f" + r.getId());
            lore.add("§7职业要求: §f" + r.getReqJob());
            lore.add("§7等级要求: §f" + r.getReqForgeLevel());
            lore.add(" ");
            lore.add("§a[左键] 编辑");
            lore.add("§c[右键] 删除");
            meta.setLore(lore);
            meta.setLocalizedName(r.getId()); // 存ID
            item.setItemMeta(meta);
            inv.addItem(item);
        }

        if (page > 1) setBtn(45, Material.ARROW, "上一页");
        if (end < all.size()) setBtn(53, Material.ARROW, "下一页");

        // 【核心修改】点击直接打开编辑器，不需要参数
        setBtn(49, Material.ANVIL, "§a§l[+ 新建配方]", "§7点击打开编辑器", "§7放入物品后保存即可");
    }

    private void setBtn(int slot, Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(new ArrayList<>(List.of(lore)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    public void open() { player.openInventory(inv); }
    @Override public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().equals(inv)) HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inv)) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;

        int slot = event.getSlot();
        if (slot == 45 && page > 1) { page--; refresh(); }
        else if (slot == 53) { page++; refresh(); }
        else if (slot == 49) {
            // 新建配方：传入 null ID
            new RecipeEditorGui(plugin, player, category, null).open();
        }
        else if (slot < 45 && event.getCurrentItem().getType() != Material.AIR) {
            String id = event.getCurrentItem().getItemMeta().getLocalizedName();
            if (event.getClick() == ClickType.RIGHT) {
                plugin.getRecipeManager().deleteRecipe(category, id);
                player.sendMessage("§c已删除: " + id);
                refresh();
            } else {
                // 编辑现有配方：传入 ID
                new RecipeEditorGui(plugin, player, category, id).open();
            }
        }
    }
}