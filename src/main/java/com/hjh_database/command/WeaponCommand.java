package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class WeaponCommand implements CommandExecutor {
    private final Hjh_database plugin;

    public WeaponCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "你没有权限。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /hjhweapon <reload|get> [武器ID]");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.getPlayerManager().getWeaponManager().reload();
            sender.sendMessage(ChatColor.GREEN + "武器配置已重载！");
            return true;
        }

        if (args[0].equalsIgnoreCase("get")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("控制台不能拿武器。");
                return true;
            }
            if (args.length < 2) {
                // 【修复】这里改用 getAllIds()
                sender.sendMessage(ChatColor.RED + "请输入武器ID。可用ID: " + plugin.getPlayerManager().getWeaponManager().getAllIds());
                return true;
            }

            String id = args[1];
            ItemStack item = plugin.getPlayerManager().getWeaponManager().getItemStack(id);

            if (item == null) {
                sender.sendMessage(ChatColor.RED + "武器 ID 不存在: " + id);
                return true;
            }

            ((Player) sender).getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "已获取武器: " + id);
            return true;
        }

        return true;
    }
}