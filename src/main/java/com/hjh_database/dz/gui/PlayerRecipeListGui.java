package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.dz.data.DzRecipe;
import com.hjh_database.util.DzUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerRecipeListGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final String category;
    private final Inventory inv;
    private int page = 1;
    private final List<DzRecipe> displayRecipes = new ArrayList<>();

    // 【终极方案】不再依赖物品NBT，而是直接记录 槽位 -> 配方ID 的映射
    // 这样无论物品是否被刷新、Lore是否被清洗，都不会影响点击判定
    private final Map<Integer, String> slotMap = new HashMap<>();

    public PlayerRecipeListGui(Hjh_database plugin, Player player, String category) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;

        this.inv = Bukkit.createInventory(this, 54, "锻造列表: " + category);

        loadRecipes();
        setupPage();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadRecipes() {
        displayRecipes.clear();
        List<DzRecipe> all = plugin.getRecipeManager().getRecipesByCategory(category);

        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        int myJob = (data != null && data.getJob() != null) ? data.getJob() : -1;

        for (DzRecipe r : all) {
            // 职业过滤
            if (r.getReqJob() != -1 && r.getReqJob() != myJob) {
                continue;
            }
            displayRecipes.add(r);
        }
    }

    private void setupPage() {
        inv.clear();
        slotMap.clear(); // 翻页时清空映射

        int start = (page - 1) * 45;
        int end = Math.min(start + 45, displayRecipes.size());

//        System.out.println("[GUI调试] 构建页面: " + page + " 总配方数: " + displayRecipes.size());

        for (int i = start; i < end; i++) {
            DzRecipe recipe = displayRecipes.get(i);
            if (recipe.getResult() == null) continue;

            // 1. 准备显示物品
            ItemStack display = recipe.getResult().clone();
            ItemMeta meta = display.getItemMeta();

            List<String> lore = meta.getLore();
            if (lore == null) lore = new ArrayList<>();
            lore.add(" ");
            lore.add("§8-----------------");
            lore.add("§7配方ID: " + recipe.getId());
            lore.add("§7职业: " + DzUtil.getJobName(recipe.getReqJob()));
            lore.add("§7等级: " + recipe.getReqForgeLevel());
            lore.add(" ");
            lore.add("§e点击查看详情");
            meta.setLore(lore);
            display.setItemMeta(meta);

            // 2. 放入界面
            int slotIndex = i - start;
            inv.setItem(slotIndex, display);

            // 3. 【核心】记录槽位映射
            // 不管物品上面的NBT会不会被清洗，这个Map是存在内存里的，绝对安全
            slotMap.put(slotIndex, recipe.getId());

//            System.out.println("[GUI映射] Slot:" + slotIndex + " -> ID:" + recipe.getId());
        }

        // 底部按钮
        if (page > 1) setBtn(45, Material.ARROW, "§e上一页");
        if (end < displayRecipes.size()) setBtn(53, Material.ARROW, "§e下一页");
        setBtn(49, Material.BARRIER, "§c返回分类");
    }

    private void setBtn(int slot, Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
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
        if (event.getCurrentItem() == null) return; // 允许点空位，反正做了判断

        int slot = event.getSlot();

        // 1. 功能按钮区
        if (slot == 45) { if (page > 1) { page--; setupPage(); } return; }
        if (slot == 53) { if ((page * 45) < displayRecipes.size()) { page++; setupPage(); } return; }
        if (slot == 49) { player.closeInventory(); new CategoryGui(plugin, player, null).open(); return; }

        // 2. 配方区 (使用 Map 查找)
        if (slot < 45) {
            // 直接从 Map 里查 ID，不再读取物品 NBT
            String recipeId = slotMap.get(slot);

            if (recipeId != null) {
//                System.out.println("[GUI命中] Slot:" + slot + " -> ID:" + recipeId);
                player.closeInventory();
                new RecipePreviewGui(plugin, player, category, recipeId).open();
            } else {
                // 如果点了有物品的格子但 Map 里没 ID，说明这是异常情况
                if (event.getCurrentItem().getType() != Material.AIR) {
//                    System.out.println("[GUI未命中] Slot:" + slot + " 有物品但无映射!");
                }
            }
        }
    }
}