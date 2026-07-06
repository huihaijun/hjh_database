package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Collections

class ResourceReloadCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {
    private val reloadCommands = setOf("resourceload", "resourcereload")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && reloadCommands.contains(args[0].lowercase())) {
            if (!sender.hasPermission("hjh.admin")) {
                sender.sendMessage(ChatColor.RED.toString() + "你没有权限执行此命令。")
                return true
            }

            sender.sendMessage(ChatColor.YELLOW.toString() + "正在重载 Resource 物品与相关配方...")
            plugin.resourceManager.reload()
            plugin.recipeManager.loadAllRecipes()
            if (plugin.isBaihuDzManagerInitialized()) {
                plugin.baihuDzManager.reload()
            }
            val refreshedAlchemyItems = plugin.alchemyManager.reloadRecipes()
            plugin.jianghuXindeManager.reload()
            plugin.jianghuXindeManager.ensureStationBlock()
            plugin.bgmManager.reload()

            sender.sendMessage(ChatColor.GREEN.toString() + "资源重载完成，在线玩家物品已刷新。")
            sender.sendMessage(ChatColor.GREEN.toString() + "普通锻造、白虎锻造、炼药配方已同步刷新。炼药物品快照刷新 $refreshedAlchemyItems 个。")
            return true
        }

        sender.sendMessage(ChatColor.RED.toString() + "未知指令。请尝试: /hjh resourceload")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (args.size == 1) {
            if (!sender.hasPermission("hjh.admin")) return Collections.emptyList()
            val prefix = args[0].lowercase()
            return reloadCommands.filter { it.startsWith(prefix) }.sorted()
        }
        return Collections.emptyList()
    }
}
