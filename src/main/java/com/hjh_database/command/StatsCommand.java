package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class StatsCommand implements CommandExecutor {
    private final Hjh_database plugin;
    // 映射表 (与 AdminCommand.java 保持一致)
    private final Map<Integer, String> jobMap = Map.of(0, "战士", 1, "弓箭手", 2, "术士", 3, "医师");
    private final Map<Integer, String> raceMap = Map.of(0, "神", 1, "仙", 2, "人", 3, "战神", 4, "妖");

    private String getJobName(Integer job) {
        return job == null ? "未选择" : jobMap.getOrDefault(job, "未知");
    }

    private String getRaceName(Integer race) {
        return race == null ? "未选择" : raceMap.getOrDefault(race, "未知");
    }
    public StatsCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("控制台无法使用此命令。");
            return true;
        }

        Player player = (Player) sender;
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());

        if (data == null) {
            player.sendMessage(ChatColor.RED + "正在加载数据，请稍后再试...");
            return true;
        }

        player.sendMessage(ChatColor.DARK_GRAY + "============ [ " + ChatColor.GOLD + "个人属性" + ChatColor.DARK_GRAY + " ] ============");
        player.sendMessage(ChatColor.YELLOW + " 姓名: " + ChatColor.WHITE + data.getPlayerName());
        player.sendMessage(ChatColor.YELLOW + " 等级: " + ChatColor.WHITE + data.getLv());
        // 【新增显示职业和种族】
        player.sendMessage(ChatColor.YELLOW + " 职业: " + ChatColor.AQUA + getJobName(data.getJob()));
        player.sendMessage(ChatColor.YELLOW + " 种族: " + ChatColor.LIGHT_PURPLE + getRaceName(data.getRace()));
        player.sendMessage("");
        player.sendMessage(ChatColor.RED + " ❤ 生命: " + String.format("%.1f", player.getHealth()) + " / " + data.getVal(data.getMaxHealth()));
        player.sendMessage(ChatColor.RED + " ⚔ 攻击: " + data.getVal(data.getAttack()));
        player.sendMessage(ChatColor.GREEN + " ➹ 远程: " + data.getVal(data.getArcherDamage()));
// 在 StatsCommand.java 中修改护甲显示行
        double armor = data.getVal(data.getArmor());
        double reducePercent = (1 - (50.0 / (50.0 + armor))) * 100;
        player.sendMessage(ChatColor.BLUE + " 🛡 护甲: " + armor + ChatColor.GRAY + " (物理减伤: " + String.format("%.1f", reducePercent) + "%)");        player.sendMessage(ChatColor.AQUA + " ⚡ 移速: " + String.format("%.1f", data.getVal(data.getSpeed()) * 1000)); // 放大显示便于阅读
        player.sendMessage(ChatColor.LIGHT_PURPLE + " ⚛ 法强: " + data.getVal(data.getZfStr()));
        player.sendMessage(ChatColor.GRAY + " ❈ 韧性: " + String.format("%.0f%%", data.getVal(data.getToughness()) * 100));
        player.sendMessage(ChatColor.GOLD + " $ 财产: " + data.getVal(data.getMoney()));
        player.sendMessage(ChatColor.DARK_GRAY + "======================================");

        return true;
    }
}