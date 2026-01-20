package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class WeaponCommand(private val plugin: Hjh_database) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限。")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW.toString() + "用法: /hjhweapon <reload|get> [武器ID]")
            return true
        }

        if (args[0].equals("reload", ignoreCase = true)) {
            plugin.playerManager.weaponManager.reload()
            sender.sendMessage(ChatColor.GREEN.toString() + "武器配置已重载！")
            return true
        }

        if (args[0].equals("get", ignoreCase = true)) {
            if (sender !is Player) {
                sender.sendMessage("控制台不能拿武器。")
                return true
            }

            // Kotlin 中数组长度用 size
            if (args.size < 2) {
                // 【修复】这里改用 getAllIds()
                // 假设 WeaponManager 已重构为 Kotlin，getXxx() 对应 xxx 属性
                sender.sendMessage(ChatColor.RED.toString() + "请输入武器ID。可用ID: " + plugin.playerManager.weaponManager.allIds)
                return true
            }

            val id = args[1]
            val item = plugin.playerManager.weaponManager.getItemStack(id)

            if (item == null) {
                sender.sendMessage(ChatColor.RED.toString() + "武器 ID 不存在: " + id)
                return true
            }

            // sender 已经智能转换为 Player (因为前面判断了 !is Player)
            (sender as Player).inventory.addItem(item)
            sender.sendMessage(ChatColor.GREEN.toString() + "已获取武器: " + id)
            return true
        }

        return true
    }
}