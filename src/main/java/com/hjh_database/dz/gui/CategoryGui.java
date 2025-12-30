package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CategoryGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final Inventory inv;
    private final FileConfiguration settings; // 改名为 settings

    public CategoryGui(Hjh_database plugin, Player player, Integer job) {
        this.plugin = plugin;
        this.player = player;

        // 1. 【核心修复】读取 forge_settings.yml
        File file = new File(plugin.getDataFolder(), "forge_settings.yml");
        if (file.exists()) {
            this.settings = YamlConfiguration.loadConfiguration(file);
        } else {
            this.settings = new YamlConfiguration(); // 空配置，将触发兜底逻辑
        }

        String title = settings.getString("gui.title", "锻造台 - 选择分类");
        this.inv = Bukkit.createInventory(this, 27, title);

        setupGui();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void setupGui() {
        // 背景
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = bg.getItemMeta();
        m.setDisplayName(" ");
        bg.setItemMeta(m);
        for(int i=0; i<27; i++) inv.setItem(i, bg);

        // 2. 读取分类配置
        ConfigurationSection catSec = settings.getConfigurationSection("categories");

        // 如果配置文件没生成或者没写对，使用【硬编码兜底】，保证不出现屏障
        if (catSec == null || catSec.getKeys(false).isEmpty()) {
            addCategoryItem(10, Material.IRON_SWORD, "weapon", "§c§l[武器锻造]", "§7打造各种神兵利器");
            addCategoryItem(12, Material.IRON_CHESTPLATE, "armor", "§9§l[防具锻造]", "§7打造坚固的盔甲");
            addCategoryItem(14, Material.NETHER_STAR, "artifact", "§6§l[法宝锻造]", "§7打造特殊的法宝");
            addCategoryItem(16, Material.CHEST, "misc", "§e§l[杂项锻造]", "§7打造材料与其他物品");
        } else {
            // 正常读取配置 (槽位如果不配，自己算一个简单的排列)
            int[] slots = {10, 12, 14, 16, 11, 13, 15};
            int index = 0;

            for (String key : catSec.getKeys(false)) {
                if (index >= slots.length) break;
                String name = catSec.getString(key + ".name", key);
                String iconMat = catSec.getString(key + ".icon", "BARRIER");
                List<String> lore = catSec.getStringList(key + ".lore");
                Material mat = Material.getMaterial(iconMat);
                if (mat == null) mat = Material.BARRIER;

                // 这里把 lore 转成 string 数组传进去，或者改下 helper 方法
                // 为了简单，直接这里构建
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(name);
                meta.setLore(lore);
                meta.setLocalizedName(key); // 存 ID
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                item.setItemMeta(meta);

                inv.setItem(slots[index++], item);
            }
        }
    }

    private void addCategoryItem(int slot, Material mat, String id, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore, "", "§e点击进入"));
        meta.setLocalizedName(id);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    public void open() { player.openInventory(inv); }
    @Override public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if(event.getInventory().equals(inv)) HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if(!event.getInventory().equals(inv)) return;
        event.setCancelled(true);
        if(event.getCurrentItem() == null) return;

        String catId = event.getCurrentItem().getItemMeta().getLocalizedName();
        if(catId != null && !catId.isEmpty()) {
            player.closeInventory();
            if (player.isOp() && player.getInventory().getItemInMainHand().getType() == Material.WOODEN_HOE) {
                new AdminRecipeListGui(plugin, player, catId).open();
            } else {
                new PlayerRecipeListGui(plugin, player, catId).open();
            }
        }
    }
}