package com.hjh_database.dz.gui;

import com.hjh_database.Hjh_database;
import com.hjh_database.dz.data.DzRecipe;
import com.hjh_database.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecipeEditorGui implements InventoryHolder, Listener {
    private final Hjh_database plugin;
    private final Player player;
    private final String category;
    private final String editId;
    private final Inventory inv;

    private boolean awaitingChat = false;
    private ItemStack cachedResult;
    private List<ItemStack> cachedIngredients;
    private String cachedId;

    private final int[] INPUT_SLOTS = {11, 12, 13, 14, 15};
    private final int RESULT_SLOT = 24;
    private final int SAVE_BUTTON_SLOT = 49;

    public RecipeEditorGui(Hjh_database plugin, Player player, String category, String editId) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;
        this.editId = editId;

        String title = (editId == null) ? "新建配方" : "编辑: " + editId;
        this.inv = Bukkit.createInventory(this, 54, title);

        setupGui();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void setupGui() {
        ItemStack bg = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta bgm = bg.getItemMeta();
        bgm.setDisplayName("§7");
        bg.setItemMeta(bgm);
        for(int i=0; i<54; i++) {
            if(!isInputOrResult(i)) inv.setItem(i, bg);
        }

        ItemStack save = new ItemStack(Material.LIME_WOOL);
        ItemMeta sm = save.getItemMeta();
        sm.setDisplayName("§a§l[保存并设置参数]");
        sm.setLore(Arrays.asList(
                "§71. 放入成品和材料",
                "§72. 点击此按钮",
                "§73. 在聊天栏输入等级职业等参数"
        ));
        save.setItemMeta(sm);
        inv.setItem(SAVE_BUTTON_SLOT, save);

        if (editId != null) {
            DzRecipe r = plugin.getRecipeManager().getRecipe(category, editId);
            if (r != null) {
                inv.setItem(RESULT_SLOT, r.getResult());
                List<ItemStack> ings = r.getIngredients();
                for(int i=0; i<ings.size() && i<INPUT_SLOTS.length; i++) {
                    inv.setItem(INPUT_SLOTS[i], ings.get(i));
                }
            }
        }
    }

    private boolean isInputOrResult(int slot) {
        if (slot == RESULT_SLOT) return true;
        for(int s : INPUT_SLOTS) if(s == slot) return true;
        return false;
    }

    public void open() { player.openInventory(inv); }
    @Override public Inventory getInventory() { return inv; }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if(event.getInventory().equals(inv)) {
            if (!awaitingChat) {
                HandlerList.unregisterAll(this);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if(!event.getInventory().equals(inv)) return;
        int slot = event.getRawSlot();
        if(!isInputOrResult(slot) && slot < 54) event.setCancelled(true);

        if(slot == SAVE_BUTTON_SLOT) {
            event.setCancelled(true);
            initiateSaveProcess();
        }
    }

    private void initiateSaveProcess() {
        ItemStack result = inv.getItem(RESULT_SLOT);
        if (result == null || result.getType() == Material.AIR) {
            player.sendMessage("§c错误：结果槽位为空！");
            return;
        }

        // 核心：识别ID
        String id = (editId != null) ? editId : ItemUtil.getPublicId(result);

        // 如果识别出来是 AIR，说明这既不是 RPG物品，也不是原版物品，这几乎不可能发生，除非 ItemUtil 逻辑有误
        if (id.equals("AIR")) {
            player.sendMessage("§c错误：无法识别结果物品的ID。");
            return;
        }

        List<ItemStack> ings = new ArrayList<>();
        for(int s : INPUT_SLOTS) {
            ItemStack it = inv.getItem(s);
            ings.add(it != null ? it : new ItemStack(Material.AIR));
        }

        this.cachedResult = result;
        this.cachedIngredients = ings;
        this.cachedId = id;
        this.awaitingChat = true;

        player.closeInventory();

        player.sendMessage("§a========================================");
        player.sendMessage("§a正在保存配方，ID识别为: §e" + id);
        if (id.equals(result.getType().name())) {
            player.sendMessage("§7(提示: 这是一个原版物品配方)");
        } else {
            player.sendMessage("§7(提示: 这是一个自定义RPG物品配方)");
        }
        player.sendMessage("§a请在聊天栏输入 4 个参数 (空格分隔):");
        player.sendMessage("§7格式: <职业> <等级> <经验奖励> <执照要求>");
        player.sendMessage("§7示例: 1 10 50 0  (代表:弓手 10级 50经验 0执照)");
        player.sendMessage("§e输入 'cancel' 取消保存");
        player.sendMessage("§a========================================");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!event.getPlayer().equals(player) || !awaitingChat) return;

        event.setCancelled(true);
        String msg = event.getMessage().trim();

        if (msg.equalsIgnoreCase("cancel")) {
            player.sendMessage("§c已取消保存。");
            awaitingChat = false;
            HandlerList.unregisterAll(this);
            return;
        }

        String[] args = msg.split("\\s+");
        try {
            int job = args.length > 0 ? Integer.parseInt(args[0]) : -1;
            int lv  = args.length > 1 ? Integer.parseInt(args[1]) : 1;
            int exp = args.length > 2 ? Integer.parseInt(args[2]) : 10;
            int lic = args.length > 3 ? Integer.parseInt(args[3]) : 0;

            DzRecipe recipe = new DzRecipe(
                    cachedId, category, cachedResult, cachedIngredients,
                    job, lv, lic, exp
            );
            plugin.getRecipeManager().saveRecipe(recipe);

            player.sendMessage("§a✔ 配方保存成功！");

            awaitingChat = false;
            HandlerList.unregisterAll(this);

            Bukkit.getScheduler().runTask(plugin, () ->
                    new AdminRecipeListGui(plugin, player, category).open()
            );

        } catch (NumberFormatException e) {
            player.sendMessage("§c格式错误！请输入数字。例如: -1 1 10 0");
        }
    }
}