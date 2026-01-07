package com.hjh_database.kaiwu;

import com.hjh_database.Hjh_database;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class KaiWuCommand implements CommandExecutor {
    private final Hjh_database plugin;

    public KaiWuCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hjh.kaiwu.op")) {
            sender.sendMessage("§c权限不足。");
            return true;
        }

        // /hjhkw setlevel <player> <level>
        // /hjhkw setenergy <player> <amount>
        // /hjhkw reload

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("reload")) {
                plugin.getKaiWuManager().loadNodes();
                sender.sendMessage("§a配置已重载。");
                return true;
            }

            if (args.length >= 3) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§c玩家不在线。");
                    return true;
                }

                if (args[0].equalsIgnoreCase("setlevel")) {
                    try {
                        int lv = Integer.parseInt(args[2]);
                        plugin.getKaiWuManager().setPlayerLevel(target, lv);
                        sender.sendMessage("§a已设置 " + target.getName() + " 等级为 " + lv);
                    } catch (NumberFormatException e) { sender.sendMessage("§c数字格式错误"); }
                    return true;
                }

                if (args[0].equalsIgnoreCase("setenergy")) {
                    try {
                        double val = Double.parseDouble(args[2]);
                        plugin.getKaiWuManager().setPlayerEnergy(target, val);
                        sender.sendMessage("§a已设置 " + target.getName() + " 精力为 " + val);
                    } catch (NumberFormatException e) { sender.sendMessage("§c数字格式错误"); }
                    return true;
                }
            }
        }

        sender.sendMessage("§6=== 开物术管理 ===");
        sender.sendMessage("§7手持金锄头右键方块打开编辑器");
        sender.sendMessage("§7/hjhkw setlevel <玩家> <等级>");
        sender.sendMessage("§7/hjhkw setenergy <玩家> <数值>");
        return true;
    }
}