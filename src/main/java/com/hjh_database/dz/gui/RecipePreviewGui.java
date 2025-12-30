package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
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

import java.util.Arrays;
import java.util.List;

public class RecipePreviewGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final String category;
    private final DzRecipe recipe;
    private final Inventory inv;

    private final int[] INPUT_SLOTS = {11, 12, 13, 14, 15};
    private final int RESULT_SLOT = 24;
    private final int START_BUTTON_SLOT = 49;
    private final int BACK_BUTTON_SLOT = 45;

    public RecipePreviewGui(Hjh_database plugin, Player player, String category, String recipeId) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;

        // 获取配方
        this.recipe = plugin.getRecipeManager().getRecipe(category, recipeId);

        if (this.recipe == null) {
            // 如果 ID 传过来了但配方找不到 (比如文件被删了)
            player.sendMessage(ChatColor.RED + "无法加载配方预览: " + recipeId + " (数据可能已丢失)");
            this.inv = Bukkit.createInventory(this, 9, "错误");
            return;
        }

        this.inv = Bukkit.createInventory(this, 54, "配方预览: " + recipeId);
        setupGui();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void setupGui() {
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bg.getItemMeta();
        meta.setDisplayName(" ");
        bg.setItemMeta(meta);
        for (int i = 0; i < 54; i++) inv.setItem(i, bg);

        if (recipe != null) {
            // 1. 展示材料
            List<ItemStack> ingredients = recipe.getIngredients();
            if (ingredients != null) {
                for (int i = 0; i < ingredients.size(); i++) {
                    if (i < INPUT_SLOTS.length) {
                        inv.setItem(INPUT_SLOTS[i], ingredients.get(i));
                    }
                }
            }
            // 2. 展示成品
            inv.setItem(RESULT_SLOT, recipe.getResult());

            // 3. 展示详情
            ItemStack info = new ItemStack(Material.PAPER);
            ItemMeta im = info.getItemMeta();
            im.setDisplayName("§e§l配方要求");
            im.setLore(Arrays.asList(
                    "§7职业: " + DzUtil.getJobName(recipe.getReqJob()),
                    "§7等级: " + recipe.getReqForgeLevel(),
                    "§7执照: " + recipe.getReqLicense(),
                    "§7锻造成功奖励经验: " + recipe.getExpReward()
            ));
            info.setItemMeta(im);
            inv.setItem(23, info);
        }

        // 返回按钮
        ItemStack back = new ItemStack(Material.RED_BED);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("§c返回列表");
        back.setItemMeta(bm);
        inv.setItem(BACK_BUTTON_SLOT, back);

        // 开始锻造按钮
        ItemStack start = new ItemStack(Material.ANVIL);
        ItemMeta sm = start.getItemMeta();
        sm.setDisplayName("§a§l[开始锻造]");
        sm.setLore(Arrays.asList("§7点击进入材料投放界面", "§7开始制作物品"));
        start.setItemMeta(sm);
        inv.setItem(START_BUTTON_SLOT, start);
    }

    public void open() {
        if (recipe == null) return; // 如果配方为空，不打开界面
        player.openInventory(inv);
    }

    @Override public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().equals(inv)) HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inv)) return;
        event.setCancelled(true);

        int slot = event.getSlot();
        if (slot == BACK_BUTTON_SLOT) {
            player.closeInventory();
            new PlayerRecipeListGui(plugin, player, category).open();
        }
        else if (slot == START_BUTTON_SLOT) {
            if (recipe != null) {
                player.closeInventory();
                // 跳转到锻造台
                new RecipeCraftingGui(plugin, player, recipe).open();
            }
        }
    }
}