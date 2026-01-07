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
import java.util.stream.Collectors;

public class DzCommand implements CommandExecutor, TabCompleter {
    private final Hjh_database plugin;
    private final NamespacedKey stationKey;

    public DzCommand(Hjh_database plugin) {
        this.plugin = plugin;
        this.stationKey = new NamespacedKey(plugin, "hjh_forge_station");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        // 1. 获取锻造台指令
        if (args[0].equalsIgnoreCase("station")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c只有玩家可以使用此指令。");
                return true;
            }
            giveStation((Player) sender);
            return true;
        }

        // 2. 编辑配方指令
        if (args[0].equalsIgnoreCase("edit")) {
            if (!(sender instanceof Player)) return true;
            if (!sender.isOp()) {
                sender.sendMessage("§c权限不足。");
                return true;
            }
            new AdminCategoryGui(plugin, (Player) sender).open();
            return true;
        }

        // 3. 重载指令
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp()) return true;
            plugin.getDzLevelManager().reload(); // 重载等级配置
            plugin.getRecipeManager().loadAllRecipes(); // 重载配方
            sender.sendMessage("§a锻造系统配置已重载 (包含dzlvl.yml)。");
            return true;
        }

        // 4. 管理员修改数据指令
        // /hjhdz admin set <player> <type> <value>
        if (args[0].equalsIgnoreCase("admin")) {
            if (!sender.isOp()) {
                sender.sendMessage("§c权限不足。");
                return true;
            }
            if (args.length < 5 || !args[1].equalsIgnoreCase("set")) {
                sender.sendMessage("§c用法: /hjhdz admin set <玩家> <level/exp/license> <数值>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§c玩家不在线。");
                return true;
            }

            DzPlayerData data = plugin.getPlayerManager().getDzData(target.getUniqueId());
            if (data == null) {
                sender.sendMessage("§c无法获取玩家数据。");
                return true;
            }

            String type = args[3].toLowerCase();
            int value;
            try {
                value = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c请输入有效的数字。");
                return true;
            }

            switch (type) {
                case "level":
                case "lv":
                    data.setForgeLevel(value);
                    sender.sendMessage("§a已将 " + target.getName() + " 的锻造等级设置为: " + value);
                    break;
                case "exp":
                    data.setForgeExp(value);
                    sender.sendMessage("§a已将 " + target.getName() + " 的锻造经验设置为: " + value);
                    break;
                case "license":
                case "job": // 兼容旧习惯
                    data.setForgeLicense(value);
                    String licName = plugin.getDzLevelManager().getLicenseName(value);
                    sender.sendMessage("§a已将 " + target.getName() + " 的锻造资质设置为: " + licName + " (" + value + ")");
                    break;
                default:
                    sender.sendMessage("§c未知类型，可用: level, exp, license");
                    break;
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void giveStation(Player player) {
        ItemStack station = new ItemStack(Material.ANVIL);
        ItemMeta meta = station.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "== 锻造台 ==");
        meta.setLore(Arrays.asList("§7放置后右键打开锻造界面", "§e[管理员物品]"));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, "true");
        station.setItemMeta(meta);
        player.getInventory().addItem(station);
        player.sendMessage("§a已获得锻造台。");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 锻造系统指令 ===");
        sender.sendMessage("§e/hjhdz station §7- 获取锻造台");
        sender.sendMessage("§e/hjhdz edit §7- 编辑/管理配方");
        if (sender.isOp()) {
            sender.sendMessage("§c/hjhdz admin set <玩家> level <数值> §7- 修改锻造等级");
            sender.sendMessage("§c/hjhdz admin set <玩家> exp <数值> §7- 修改锻造经验");
            sender.sendMessage("§c/hjhdz admin set <玩家> license <数值> §7- 修改资质ID");
            sender.sendMessage("§c/hjhdz reload §7- 重载配置文件");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // args[0]
        if (args.length == 1) {
            List<String> list = new ArrayList<>(Arrays.asList("station", "edit"));
            if (sender.isOp()) {
                list.add("admin");
                list.add("reload");
            }
            return list;
        }

        // admin
        if (args[0].equalsIgnoreCase("admin") && sender.isOp()) {
            if (args.length == 2) {
                return Collections.singletonList("set");
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
                return null; // 显示玩家列表
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                return Arrays.asList("level", "exp", "license");
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("set")) {
                // 提示一些常用数值
                if (args[3].equalsIgnoreCase("license")) {
                    return Arrays.asList("0", "1", "2", "3", "4", "5");
                }
                return Arrays.asList("1", "10", "100", "1000");
            }
        }
        return null;
    }
}