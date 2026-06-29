package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.ArrayList
import java.util.Collections

class ResourceReloadCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("resourcereload", ignoreCase = true)) {
            if (!sender.hasPermission("hjh.admin")) {
                sender.sendMessage(ChatColor.RED.toString() + "你没有权限执行此命令。")
                return true
            }

            sender.sendMessage(ChatColor.YELLOW.toString() + "正在重载资源配置并刷新全服物品...")
            // 调用 ResourceManager 的 reload 方法 (Kotlin 属性访问)
            plugin.resourceManager.reload()
            plugin.jianghuXindeManager.reload()
            plugin.jianghuXindeManager.ensureStationBlock()
            plugin.bgmManager.reload()
            sender.sendMessage(ChatColor.GREEN.toString() + "重载完成！在线玩家的物品已更新。")
            return true
        }

        sender.sendMessage(ChatColor.RED.toString() + "未知指令。请尝试: /hjh resourcereload")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (args.size == 1) {
            val list = ArrayList<String>()
            // Kotlin 推荐使用 lowercase() 替代 toLowerCase()
            if ("resourcereload".startsWith(args[0].lowercase()) && sender.hasPermission("hjh.admin")) {
                list.add("resourcereload")
            }
            return list
        }
        return Collections.emptyList()
    }
}
