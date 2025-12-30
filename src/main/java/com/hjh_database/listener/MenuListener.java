package com.hjh_database.listener;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.ui.MenuManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class MenuListener implements Listener {
    private final Hjh_database plugin;

    public MenuListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    // 1. 监听玩家右键 (打开菜单)
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            MenuManager menuManager = plugin.getMenuManager();
            if (menuManager.isTianjiToken(item)) {
                menuManager.openMainMenu(event.getPlayer());
                event.setCancelled(true);
            }
        }
    }

    // 2. 监听背包点击 (核心修复部分)
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // 获取标题
        String title = event.getView().getTitle();

        // 安全检查
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // =======================================================
        // 【修复重点】使用 contains 而不是 equals
        // 这样可以忽略颜色代码(§/&)的细微差异，防止菜单失效变成箱子
        // =======================================================

        // 1. 处理主菜单
        if (title.contains("天机")) {
            event.setCancelled(true); // 禁止拿取物品
            // 检测点击了“道天图录” (Slot 31)
            // 注意：Slot 编号是从 0 开始的
            if (event.getRawSlot() == 31) {
                plugin.getMenuManager().openDaoTianMenu(player);
            }
        }

        // 2. 处理道天图录菜单
        else if (title.contains("道天图录") && title.contains("元素仓库")) {
            event.setCancelled(true); // 禁止拿取物品

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || !clickedItem.hasItemMeta()) return;

            // 返回按钮 (Slot 49)
            if (event.getRawSlot() == 49) {
                plugin.getMenuManager().openMainMenu(player);
                return;
            }

            // 获取按钮对应的元素类型
            ItemMeta meta = clickedItem.getItemMeta();
            NamespacedKey btnKey = new NamespacedKey(plugin, "btn_element");

            // 只有带有我们特定 NBT 标记的物品才触发逻辑
            if (meta.getPersistentDataContainer().has(btnKey, PersistentDataType.STRING)) {
                String typeName = meta.getPersistentDataContainer().get(btnKey, PersistentDataType.STRING);
                try {
                    MenuManager.ElementType type = MenuManager.ElementType.valueOf(typeName);
                    handleElementClick(player, type, event.getClick());
                } catch (IllegalArgumentException e) {
                    // 忽略无效类型
                }
            }
        }
    }

    private void handleElementClick(Player player, MenuManager.ElementType type, ClickType click) {
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        // === 存入逻辑 (左键) ===
        if (click.isLeftClick()) {
            int count = 0;
            // 遍历背包
            for (ItemStack item : player.getInventory().getContents()) {
                if (plugin.getMenuManager().isPanlingItem(item, type)) {
                    count += item.getAmount();
                    player.getInventory().remove(item); // 移除物品
                }
            }

            if (count > 0) {
                addBankAmount(data, type, count);
                player.sendMessage("§a已存入 " + count + " 个 " + type.name);
                plugin.getDatabaseManager().savePlayer(data); // 异步保存
                plugin.getMenuManager().openDaoTianMenu(player); // 刷新界面
            } else {
                player.sendMessage("§c你的背包里没有 " + type.name);
            }
        }
        // === 取出逻辑 (右键 / Shift右键) ===
        else if (click.isRightClick()) {
            int currentStock = getBankAmount(data, type);
            if (currentStock <= 0) {
                player.sendMessage("§c仓库里没有 " + type.name + " 了");
                return;
            }

            int amountToTake = 1;
            if (click.isShiftClick()) {
                amountToTake = 64;
            }

            // 如果库存不足一组，则全部取出
            if (currentStock < amountToTake) {
                amountToTake = currentStock;
            }

            // 检查背包空间
            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage("§c背包已满！");
                return;
            }

            // 扣除库存
            addBankAmount(data, type, -amountToTake);

            // 发放物品
            ItemStack item = plugin.getMenuManager().getPanlingItem(type, amountToTake);
            player.getInventory().addItem(item);

            player.sendMessage("§e已取出 " + amountToTake + " 个 " + type.name);
            plugin.getDatabaseManager().savePlayer(data);
            plugin.getMenuManager().openDaoTianMenu(player);
        }
    }

    private void addBankAmount(PlayerData data, MenuManager.ElementType type, int amount) {
        switch (type) {
            case METAL -> data.setMetal(data.getMetal() + amount);
            case WOOD -> data.setWood(data.getWood() + amount);
            case WATER -> data.setWater(data.getWater() + amount);
            case FIRE -> data.setFire(data.getFire() + amount);
            case EARTH -> data.setEarth(data.getEarth() + amount);
            case RELIVE -> data.setReliveStone(data.getReliveStone() + amount);
        }
    }

    private int getBankAmount(PlayerData data, MenuManager.ElementType type) {
        return switch (type) {
            case METAL -> data.getMetal();
            case WOOD -> data.getWood();
            case WATER -> data.getWater();
            case FIRE -> data.getFire();
            case EARTH -> data.getEarth();
            case RELIVE -> data.getReliveStone();
        };
    }
}