package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ResourceReloadCommand implements CommandExecutor, TabCompleter {
    private final Hjh_database plugin;

    public ResourceReloadCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("resourcereload")) {
            if (!sender.hasPermission("hjh.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令。");
                return true;
            }

            sender.sendMessage(ChatColor.YELLOW + "正在重载资源配置并刷新全服物品...");
            plugin.getResourceManager().reload();
            sender.sendMessage(ChatColor.GREEN + "重载完成！在线玩家的物品已更新。");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "未知指令。请尝试: /hjh resourcereload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            if ("resourcereload".startsWith(args[0].toLowerCase()) && sender.hasPermission("hjh.admin")) {
                list.add("resourcereload");
            }
            return list;
        }
        return Collections.emptyList();
    }
}