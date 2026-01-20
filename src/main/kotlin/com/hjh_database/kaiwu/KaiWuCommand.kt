package com.hjh_database.kaiwu

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class KaiWuCommand(private val plugin: Hjh_database) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("hjh.kaiwu.op")) {
            sender.sendMessage("§c权限不足。")
            return true
        }

        // /hjhkw setlevel <player> <level>
        // /hjhkw setenergy <player> <amount>
        // /hjhkw reload

        if (args.isNotEmpty()) {
            if (args[0].equals("reload", ignoreCase = true)) {
                plugin.kaiWuManager.loadNodes()
                sender.sendMessage("§a配置已重载。")
                return true
            }

            if (args.size >= 3) {
                val target = Bukkit.getPlayer(args[1])
                if (target == null) {
                    sender.sendMessage("§c玩家不在线。")
                    return true
                }

                if (args[0].equals("setlevel", ignoreCase = true)) {
                    try {
                        val lv = args[2].toInt()
                        plugin.kaiWuManager.setPlayerLevel(target, lv)
                        sender.sendMessage("§a已设置 " + target.name + " 等级为 " + lv)
                    } catch (e: NumberFormatException) {
                        sender.sendMessage("§c数字格式错误")
                    }
                    return true
                }

                if (args[0].equals("setenergy", ignoreCase = true)) {
                    try {
                        // val 是 Kotlin 关键字，使用反引号转义以保持原变量名不变
                        val `val` = args[2].toDouble()
                        plugin.kaiWuManager.setPlayerEnergy(target, `val`)
                        sender.sendMessage("§a已设置 " + target.name + " 精力为 " + `val`)
                    } catch (e: NumberFormatException) {
                        sender.sendMessage("§c数字格式错误")
                    }
                    return true
                }
            }
        }

        sender.sendMessage("§6=== 开物术管理 ===")
        sender.sendMessage("§7手持金锄头右键方块打开编辑器")
        sender.sendMessage("§7/hjhkw setlevel <玩家> <等级>")
        sender.sendMessage("§7/hjhkw setenergy <玩家> <数值>")
        return true
    }
}