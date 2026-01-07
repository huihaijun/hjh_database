package com.hjh_database.kaiwu;

import com.hjh_database.Hjh_database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KaiWuEditor {
    private final Hjh_database plugin;
    private final KaiWuManager manager;

    public KaiWuEditor(Hjh_database plugin, KaiWuManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openEditor(Player player, String locKey) {
        // 创建 54 格 GUI
        Inventory inv = Bukkit.createInventory(null, 54, "§0开物点编辑: " + locKey);

        // 获取现有配置（如果有）
        KaiWuManager.NodeConfig node = manager.getNode(locKey);

        // 读取全局默认值作为初始显示
        int defaultDepleted = plugin.getConfig().getInt("kaiwu.mining.depleted_duration", 300);

        // --- 1. 放置掉落物 (如果有) ---
        if (node != null && node.drops != null) {
            for (int i = 0; i < node.drops.size() && i < 45; i++) {
                inv.setItem(i, node.drops.get(i));
            }
        }

        // --- 2. 底部功能栏 (45-53) ---
        fillBorder(inv);

        // [46] 采集耗时
        inv.setItem(46, createSettingIcon(Material.CLOCK, "§e采集耗时",
                node != null ? node.timeSeconds : 2.0, "秒", "左键+0.5 / 右键-0.5"));

        // [47] 精力消耗
        inv.setItem(47, createSettingIcon(Material.COOKED_BEEF, "§c精力消耗",
                node != null ? node.energyCost : 5.0, "点", "左键+1 / 右键-1"));

        // [48] 经验产出
        inv.setItem(48, createSettingIcon(Material.EXPERIENCE_BOTTLE, "§b获得经验",
                (double)(node != null ? node.exp : 10), "点", "左键+5 / 右键-5"));

        // [49] 需求等级
        inv.setItem(49, createSettingIcon(Material.LADDER, "§6需求等级",
                (double)(node != null ? node.reqLevel : 1), "级", "左键+1 / 右键-1"));

        // [50] 彻底冷却 (重生时间)
        inv.setItem(50, createSettingIcon(Material.COMPASS, "§d重生冷却 (挖空后)",
                (double)(node != null ? node.cooldownSec : 60), "秒", "左键+10 / 右键-10"));

        // [51] 【新增】枯竭恢复 (自然回满时间)
        inv.setItem(51, createSettingIcon(Material.ENCHANTED_BOOK, "§a自然恢复 (枯竭后)",
                (double)(node != null ? node.depletedSec : defaultDepleted), "秒", "左键+10 / 右键-10"));

        // [53] 删除按钮
        ItemStack delete = new ItemStack(Material.BARRIER);
        ItemMeta dm = delete.getItemMeta();
        dm.setDisplayName("§c§l删除此资源点");
        delete.setItemMeta(dm);
        inv.setItem(53, delete);

        player.openInventory(inv);
    }

    // --- GUI 点击处理 ---
    public void handleClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!title.startsWith("§0开物点编辑: ")) return;

        e.setCancelled(false);
        int slot = e.getRawSlot();
        if (slot >= 45 && slot <= 53) {
            e.setCancelled(true);

            ItemStack item = e.getCurrentItem();
            if (item == null) return;

            Player p = (Player) e.getWhoClicked();
            boolean isLeft = e.isLeftClick();

            if (item.getType() == Material.CLOCK) updateVal(item, isLeft ? 0.5 : -0.5, 0.5, 60.0, "秒");
            else if (item.getType() == Material.COOKED_BEEF) updateVal(item, isLeft ? 1.0 : -1.0, 0.0, 1000.0, "点");
            else if (item.getType() == Material.EXPERIENCE_BOTTLE) updateVal(item, isLeft ? 5.0 : -5.0, 0.0, 10000.0, "点");
            else if (item.getType() == Material.LADDER) updateVal(item, isLeft ? 1.0 : -1.0, 1.0, 100.0, "级");
            else if (item.getType() == Material.COMPASS) updateVal(item, isLeft ? 10.0 : -10.0, 10.0, 3600.0, "秒");
                // 【新增】处理 51 格点击
            else if (item.getType() == Material.ENCHANTED_BOOK) updateVal(item, isLeft ? 10.0 : -10.0, 5.0, 3600.0, "秒");

            else if (item.getType() == Material.BARRIER) {
                String locKey = title.replace("§0开物点编辑: ", "");
                manager.removeNode(p, locKey);
                p.closeInventory();
            }
        }
    }

    // --- GUI 关闭处理 (保存) ---
    public void handleClose(InventoryCloseEvent e) {
        String title = e.getView().getTitle();
        if (!title.startsWith("§0开物点编辑: ")) return;

        String locKey = title.replace("§0开物点编辑: ", "");
        Inventory inv = e.getInventory();

        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) {
                drops.add(it);
            }
        }

        double time = parseVal(inv.getItem(46));
        double energy = parseVal(inv.getItem(47));
        int exp = (int) parseVal(inv.getItem(48));
        int reqLv = (int) parseVal(inv.getItem(49));
        int cooldown = (int) parseVal(inv.getItem(50));
        // 【新增】读取第 51 格
        int depleted = (int) parseVal(inv.getItem(51));

        manager.saveNodeFromEditor(locKey, drops, time, energy, exp, reqLv, cooldown, depleted);
        e.getPlayer().sendMessage("§a[开物术] 资源点配置已保存！");
    }

    // --- 辅助方法 ---
    private void fillBorder(Inventory inv) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.setDisplayName(" ");
        glass.setItemMeta(meta);
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }

    private ItemStack createSettingIcon(Material mat, String name, double val, String unit, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(
                "§f当前值: §a" + String.format("%.1f", val) + unit,
                "§7" + desc
        ));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE, val);
        item.setItemMeta(meta);
        return item;
    }

    private void updateVal(ItemStack item, double delta, double min, double max, String unit) {
        ItemMeta meta = item.getItemMeta();
        Double current = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE);
        if (current == null) current = 0.0;

        double next = current + delta;
        if (next < min) next = min;
        if (next > max) next = max;

        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE, next);
        List<String> lore = meta.getLore();
        lore.set(0, "§f当前值: §a" + String.format("%.1f", next) + unit);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private double parseVal(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Double d = item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE);
        return d == null ? 0 : d;
    }
}