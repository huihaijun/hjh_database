package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.dz.data.DzPlayerData;
import com.hjh_database.dz.data.DzRecipe;
import com.hjh_database.util.DzUtil;
import com.hjh_database.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import java.util.Arrays;
import java.util.List;

public class RecipeCraftingGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final DzRecipe recipe;
    private final Inventory inv;

    private final int[] INPUT_SLOTS = {11, 12, 13, 14, 15};
    private final int PREVIEW_SLOT = 24;
    private final int BUTTON_SLOT = 49;

    public RecipeCraftingGui(Hjh_database plugin, Player player, DzRecipe recipe) {
        this.plugin = plugin;
        this.player = player;
        this.recipe = recipe;

        if (recipe == null) {
            player.sendMessage(ChatColor.RED + "配方数据异常！");
            this.inv = null;
            return;
        }

        this.inv = Bukkit.createInventory(this, 54, "锻造: " + recipe.getId());
        setupGui();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void setupGui() {
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = bg.getItemMeta();
        m.setDisplayName(" ");
        bg.setItemMeta(m);
        for (int i = 0; i < 54; i++) {
            if (!isInputSlot(i)) inv.setItem(i, bg);
        }

        // 显示成品预览
        inv.setItem(PREVIEW_SLOT, recipe.getResult());

        updateButtonState();
    }

    private boolean isInputSlot(int slot) {
        for (int s : INPUT_SLOTS) if (s == slot) return true;
        return false;
    }

    /**
     * 核心逻辑：检查玩家是否满足锻造的所有条件
     * @return 错误原因列表，如果为空则表示满足条件
     */
    private List<String> checkRequirements() {
        List<String> errors = new ArrayList<>();

        // 1. 获取玩家数据
        PlayerData rpgData = plugin.getPlayerManager().getData(player.getUniqueId());
        DzPlayerData forgeData = plugin.getPlayerManager().getDzData(player.getUniqueId());

        if (rpgData == null || forgeData == null) {
            errors.add("§c数据加载中...");
            return errors;
        }

        // 2. 检查职业 (reqJob != -1 才检查)
        if (recipe.getReqJob() != -1) {
            // 这里假设 PlayerData.getJob() 返回 int，且可能为 null
            Integer myJob = rpgData.getJob();
            if (myJob == null || myJob != recipe.getReqJob()) {
                errors.add("§c职业不符 (需要: " + DzUtil.getJobName(recipe.getReqJob()) + ")");
            }
        }

        // 3. 检查锻造等级
        if (forgeData.getForgeLevel() < recipe.getReqForgeLevel()) {
            errors.add("§c锻造等级不足 (需要: Lv." + recipe.getReqForgeLevel() + ")");
        }

        // 4. 检查执照
        if (forgeData.getForgeLicense() < recipe.getReqLicense()) {
            errors.add("§c执照等级不足 (需要: " + recipe.getReqLicense() + "级)");
        }

        // 5. 检查材料 (ItemUtil ID对比)
        List<ItemStack> required = recipe.getIngredients();
        boolean ingredientsOk = true;
        for (int i = 0; i < required.size(); i++) {
            if (i >= INPUT_SLOTS.length) break;
            ItemStack reqItem = required.get(i);
            ItemStack inputItem = inv.getItem(INPUT_SLOTS[i]);

            // 配方空则空，配方有则有
            if (reqItem == null || reqItem.getType() == Material.AIR) {
                if (inputItem != null && inputItem.getType() != Material.AIR) ingredientsOk = false;
            } else {
                if (inputItem == null || inputItem.getType() == Material.AIR) ingredientsOk = false;
                else if (!ItemUtil.isMatch(reqItem, inputItem)) ingredientsOk = false;
                else if (inputItem.getAmount() < reqItem.getAmount()) ingredientsOk = false;
            }
        }
        if (!ingredientsOk) {
            errors.add("§c材料不足或不匹配");
        }

        return errors;
    }

    private void updateButtonState() {
        List<String> errors = checkRequirements();
        ItemStack btn;

        if (errors.isEmpty()) {
            // 条件满足
            btn = new ItemStack(Material.ANVIL);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§a§l[点击开始锻造]");
            meta.setLore(Arrays.asList(
                    "§7材料充足，条件符合",
                    "§e成功奖励经验: " + recipe.getExpReward()
            ));
            btn.setItemMeta(meta);
        } else {
            // 条件不满足
            btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c§l无法锻造");
            meta.setLore(errors); // 直接把错误原因显示在 Lore 里
            btn.setItemMeta(meta);
        }
        inv.setItem(BUTTON_SLOT, btn);
    }

    public void open() { if(inv!=null) player.openInventory(inv); }
    @Override public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().equals(inv)) {
            // 退还材料
            for (int slot : INPUT_SLOTS) {
                ItemStack item = inv.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    player.getInventory().addItem(item);
                }
            }
            HandlerList.unregisterAll(this);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inv)) return;
        int slot = event.getRawSlot();

        // 允许操作输入槽
        if (isInputSlot(slot)) {
            plugin.getServer().getScheduler().runTask(plugin, this::updateButtonState);
            return;
        } else if (slot < 54) {
            event.setCancelled(true);
        }

        if (slot == BUTTON_SLOT) {
            List<String> errors = checkRequirements();
            if (errors.isEmpty()) {
                doCraft();
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
                player.sendMessage("§c无法锻造：");
                for(String err : errors) player.sendMessage(err);
            }
        }
    }

    private void doCraft() {
        // 1. 消耗材料
        List<ItemStack> required = recipe.getIngredients();
        for (int i = 0; i < required.size(); i++) {
            if (i >= INPUT_SLOTS.length) break;
            ItemStack req = required.get(i);
            if (req != null && req.getType() != Material.AIR) {
                ItemStack input = inv.getItem(INPUT_SLOTS[i]);
                if (input != null) {
                    input.setAmount(input.getAmount() - req.getAmount());
                    inv.setItem(INPUT_SLOTS[i], input);
                }
            }
        }

        // 2. 给予成品
        player.getInventory().addItem(recipe.getResult().clone());

        // 3. 增加经验 (修复为奖励)
        // 在 RecipeCraftingGui.java 的 doCraft 方法底部

// 3. 增加经验
        DzPlayerData forgeData = plugin.getPlayerManager().getDzData(player.getUniqueId());
        if (forgeData != null && recipe.getExpReward() > 0) {
            // 【修改后】传入 plugin 和 player 以触发升级特效和保存
            forgeData.addExp(recipe.getExpReward(), plugin, player);
            // 这一行原本的 sendMessage 可以保留也可以去掉，因为 addExp 里已经有了升级提示
            player.sendMessage("§a锻造成功！获得 " + recipe.getExpReward() + " 点锻造经验。");
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1, 1);
        updateButtonState();
    }
}