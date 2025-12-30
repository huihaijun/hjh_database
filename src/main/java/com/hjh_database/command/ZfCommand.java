package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ZfCommand implements CommandExecutor, TabCompleter {
    private final Hjh_database plugin;

    public ZfCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限检查
        if (!sender.hasPermission("hjh.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令！");
            return true;
        }

        // 帮助信息
        if (args.length < 4 || !args[0].equalsIgnoreCase("setlevel")) {
            sender.sendMessage(ChatColor.RED + "用法: /zfset setlevel <玩家> <元素类型> <等级>");
            return true;
        }

        // 1. 获取目标玩家
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家 " + args[1] + " 不在线！");
            return true;
        }

        // 2. 获取元素类型 (转大写)
        String type = args[2].toUpperCase();
        List<String> validTypes = Arrays.asList("METAL", "WOOD", "WATER", "FIRE", "EARTH");
        if (!validTypes.contains(type)) {
            sender.sendMessage(ChatColor.RED + "未知的元素类型！有效值: METAL, WOOD, WATER, FIRE, EARTH");
            return true;
        }

        // 3. 获取等级
        int level;
        try {
            level = Integer.parseInt(args[3]);
            if (level < 1 || level > 5) { // 假设最大5级
                sender.sendMessage(ChatColor.RED + "等级必须在 1-5 之间！");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "等级必须是数字！");
            return true;
        }

        // 4. 修改数据
        PlayerData data = plugin.getPlayerManager().getData(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "无法加载该玩家数据！");
            return true;
        }

        data.setElementLevel(type, level);
        plugin.getDatabaseManager().savePlayer(data); // 异步保存到数据库

        sender.sendMessage(ChatColor.GREEN + "成功将玩家 " + target.getName() + " 的 " + type + " 等级设置为 " + level);
        target.sendMessage(ChatColor.GREEN + "你的 " + type + " 阵法等级已变更为 " + level + "！");

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission("hjh.admin")) return result;

        if (args.length == 1) {
            result.add("setlevel");
        } else if (args.length == 2) {
            // 补全玩家名
            return null; // 返回 null 会自动补全在线玩家
        } else if (args.length == 3) {
            // 补全元素类型
            String[] types = {"METAL", "WOOD", "WATER", "FIRE", "EARTH"};
            for (String t : types) {
                if (t.toLowerCase().startsWith(args[2].toLowerCase())) {
                    result.add(t);
                }
            }
        } else if (args.length == 4) {
            // 补全等级
            result.addAll(Arrays.asList("1", "2", "3", "4", "5"));
        }

        return result;
    }
}