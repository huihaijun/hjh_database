package com.hjh_database.dz.command;

import com.hjh_database.Hjh_database;
import com.hjh_database.dz.data.DzPlayerData;
import com.hjh_database.dz.gui.AdminCategoryGui;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class DzCommand implements CommandExecutor, TabCompleter {
    private final Hjh_database plugin;
    private final NamespacedKey stationKey;

    public DzCommand(Hjh_database plugin) {
        this.plugin = plugin;
        this.stationKey = new NamespacedKey(plugin, "hjh_forge_station");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此指令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "station":
                return giveStation(sender);

            case "player":
                return handlePlayerCommand(sender, args);

            case "edit":
                // 之前的 edit 逻辑...
                if (!(sender instanceof Player player)) return true;
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /hjhdz edit <category> <ID>");
                    return true;
                }
                // 这里应该调用你的 RecipeEditorGui
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    // === 处理 /hjhdz player ... ===
    private boolean handlePlayerCommand(CommandSender sender, String[] args) {
        // args: [player, <targetName>, <action>, (key), (value)]
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /hjhdz player <玩家名> <check|set> ...");
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家不在线或不存在。");
            return true;
        }

        // 获取玩家锻造数据
        // 假设 PlayerManager 有个 getDzData 方法，如果没有请根据你的架构调整
        DzPlayerData data = plugin.getPlayerManager().getDzData(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "无法获取该玩家的锻造数据（可能未加载）。");
            return true;
        }

        String action = args[2].toLowerCase();

        // 1. 查看数据 check
        if (action.equals("check")) {
            sender.sendMessage(ChatColor.GOLD + "=== " + targetName + " 的锻造数据 ===");
            sender.sendMessage(ChatColor.GRAY + "等级 (Level): " + ChatColor.WHITE + data.getForgeLevel());
            sender.sendMessage(ChatColor.GRAY + "经验 (Exp): " + ChatColor.WHITE + data.getForgeExp());
            sender.sendMessage(ChatColor.GRAY + "执照 (License): " + ChatColor.WHITE + data.getForgeLicense());
            return true;
        }

        // 2. 修改数据 set
        if (action.equals("set")) {
            if (args.length < 5) {
                sender.sendMessage(ChatColor.RED + "用法: /hjhdz player <玩家> set <level/exp/license> <数值>");
                return true;
            }
            String key = args[3].toLowerCase();
            int value;
            try {
                value = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "数值必须是整数！");
                return true;
            }

            switch (key) {
                case "level":
                    data.setForgeLevel(value);
                    sender.sendMessage(ChatColor.GREEN + "已设置锻造等级为: " + value);
                    break;
                case "exp":
                    data.setForgeExp(value);
                    sender.sendMessage(ChatColor.GREEN + "已设置锻造经验为: " + value);
                    break;
                case "license":
                    data.setForgeLicense(value);
                    sender.sendMessage(ChatColor.GREEN + "已设置锻造执照为: " + value);
                    break;
                default:
                    sender.sendMessage(ChatColor.RED + "未知属性，可用: level, exp, license");
                    break;
            }
            // 这里可能需要一个 save 方法
            // plugin.getPlayerManager().saveDzData(target.getUniqueId());
            return true;
        }

        return true;
    }

    private boolean giveStation(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        ItemStack station = new ItemStack(Material.DISPENSER);
        ItemMeta meta = station.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "== 锻造台 ==");
        meta.setLore(Arrays.asList("§7放置后右键打开锻造界面", "§e[管理员物品]"));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, "true");
        station.setItemMeta(meta);
        player.getInventory().addItem(station);
        player.sendMessage("§a已获得锻造台。");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 锻造系统指令 ===");
        sender.sendMessage("§e/hjhdz station §7- 获取锻造台");
        sender.sendMessage("§e/hjhdz player <玩家> check §7- 查看玩家数据");
        sender.sendMessage("§e/hjhdz player <玩家> set <key> <val> §7- 修改数据");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("station", "player", "edit");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return null; // 返回 null 默认显示在线玩家列表
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("player")) {
            return Arrays.asList("check", "set");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("player") && args[2].equalsIgnoreCase("set")) {
            return Arrays.asList("level", "exp", "license");
        }
        return Collections.emptyList();
    }
}