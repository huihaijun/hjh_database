package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.ArrayList

class ZfCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 权限检查
        if (!sender.hasPermission("hjh.admin")) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限执行此命令！")
            return true
        }

        // 帮助信息
        if (args.size < 4 || !args[0].equals("setlevel", ignoreCase = true)) {
            sender.sendMessage(ChatColor.RED.toString() + "用法: /zfset setlevel <玩家> <元素类型> <等级>")
            return true
        }

        // 1. 获取目标玩家
        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            sender.sendMessage(ChatColor.RED.toString() + "玩家 " + args[1] + " 不在线！")
            return true
        }

        // 2. 获取元素类型 (转大写)
        val type = args[2].uppercase()
        val validTypes = listOf("METAL", "WOOD", "WATER", "FIRE", "EARTH")
        if (!validTypes.contains(type)) {
            sender.sendMessage(ChatColor.RED.toString() + "未知的元素类型！有效值: METAL, WOOD, WATER, FIRE, EARTH")
            return true
        }

        // 3. 获取等级
        val level: Int
        try {
            level = args[3].toInt()
            if (level < 1 || level > 5) { // 假设最大5级
                sender.sendMessage(ChatColor.RED.toString() + "等级必须在 1-5 之间！")
                return true
            }
        } catch (e: NumberFormatException) {
            sender.sendMessage(ChatColor.RED.toString() + "等级必须是数字！")
            return true
        }

        // 4. 修改数据
        val data = plugin.playerManager.getData(target.uniqueId)
        if (data == null) {
            sender.sendMessage(ChatColor.RED.toString() + "无法加载该玩家数据！")
            return true
        }

        data.setElementLevel(type, level)
        plugin.databaseManager.savePlayer(data) // 异步保存到数据库

        sender.sendMessage(ChatColor.GREEN.toString() + "成功将玩家 " + target.name + " 的 " + type + " 等级设置为 " + level)
        target.sendMessage(ChatColor.GREEN.toString() + "你的 " + type + " 阵法等级已变更为 " + level + "！")

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        val result = ArrayList<String>()
        if (!sender.hasPermission("hjh.admin")) return result

        if (args.size == 1) {
            result.add("setlevel")
        } else if (args.size == 2) {
            // 补全玩家名
            return null // 返回 null 会自动补全在线玩家
        } else if (args.size == 3) {
            // 补全元素类型
            val types = arrayOf("METAL", "WOOD", "WATER", "FIRE", "EARTH")
            for (t in types) {
                if (t.lowercase().startsWith(args[2].lowercase())) {
                    result.add(t)
                }
            }
        } else if (args.size == 4) {
            // 补全等级
            result.addAll(listOf("1", "2", "3", "4", "5"))
        }

        return result
    }
}