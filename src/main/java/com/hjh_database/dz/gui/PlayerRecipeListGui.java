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
        inv.clear(); // 清空当前页
        slotMap.clear(); // 清空点击映射

        // 1. 设置背景填充物 (保持原逻辑)
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        // 2. 设置翻页按钮 (保持原逻辑)
        if (page > 1) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pm = prev.getItemMeta();
            pm.setDisplayName("§a上一页");
            prev.setItemMeta(pm);
            inv.setItem(45, prev);
        }
        if ((page * 45) < displayRecipes.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nm = next.getItemMeta();
            nm.setDisplayName("§a下一页");
            next.setItemMeta(nm);
            inv.setItem(53, next);
        }

        // 3. 返回按钮 (保持原逻辑)
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("§c返回分类");
        back.setItemMeta(bm);
        inv.setItem(49, back);

        // ====================================================
        // 【核心修改区域】 配方列表渲染
        // ====================================================

        int startIndex = (page - 1) * 45;
        int endIndex = Math.min(startIndex + 45, displayRecipes.size());

        // A. 预先获取玩家数据 (用于显示 ✔/✘ 状态，不用于拦截)
        // 获取锻造数据
        com.hjh_database.dz.data.DzPlayerData dzData = plugin.getPlayerManager().getDzData(player.getUniqueId());
        int myForgeLv = (dzData != null) ? dzData.getForgeLevel() : 1;
        int myLicense = (dzData != null) ? dzData.getForgeLicense() : 0;
        // 获取RPG职业数据
        com.hjh_database.data.PlayerData rpgData = plugin.getPlayerManager().getData(player.getUniqueId());
        int myJob = (rpgData != null) ? rpgData.getJob() : 0;

        for (int i = startIndex; i < endIndex; i++) {
            DzRecipe recipe = displayRecipes.get(i);
            int slot = i - startIndex;

            // 记录槽位 -> 配方ID 的映射
            slotMap.put(slot, recipe.getId());

            // B. 克隆结果物品 (关键：使用 clone 保留 WeaponManager 生成的原始属性)
            ItemStack icon = recipe.getResult().clone();
            ItemMeta meta = icon.getItemMeta();

            // C. 获取物品现有的 Lore (如果有的话，比如武器的攻击力)
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();

            // --- 在原有属性下方追加锻造信息 ---
            lore.add("");
            lore.add("§8§m------------------");

            // 1. 职业需求
            String jobName = DzUtil.getJobName(recipe.getReqJob());
            boolean jobOk = (recipe.getReqJob() == 0) || (myJob == recipe.getReqJob());
            String jobStatus = jobOk ? "§a✔" : "§c✘";

            if (recipe.getReqJob() > 0) {
                lore.add("§7职业: §f" + jobName + " " + jobStatus);
            } else {
                lore.add("§7职业: §f通用");
            }

            // 2. 锻造等级需求
            boolean lvOk = myForgeLv >= recipe.getReqForgeLevel();
            String lvStatus = lvOk ? "§a✔" : "§c✘";
            lore.add("§7等级: §fLv." + recipe.getReqForgeLevel() + " " + lvStatus);

            // 3. 锻造资质/执照需求 (新增)
            if (recipe.getReqLicense() > 0) {
                boolean licOk = myLicense >= recipe.getReqLicense();
                String licStatus = licOk ? "§a✔" : "§c✘";
                lore.add("§7资质: §f" + recipe.getReqLicense() + "级执照 " + licStatus);
            }

            // 4. 经验奖励
            if (recipe.getExpReward() > 0) {
                lore.add("§7经验: §e+" + recipe.getExpReward());
            }

            // 5. 底部提示 (无论条件是否满足，都显示可点击)
            lore.add("");
            lore.add("§e▶ 点击查看配方详情");

            meta.setLore(lore);
            icon.setItemMeta(meta);

            inv.setItem(slot, icon);
        }
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