package com.hjh_database.dz.command

import com.hjh_database.Hjh_database
import com.hjh_database.dz.gui.AdminCategoryGui
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*

class DzCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {
    private val stationKey: NamespacedKey = NamespacedKey(plugin, "hjh_forge_station")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        // 1. 获取锻造台指令
        if (args[0].equals("station", ignoreCase = true)) {
            if (sender !is Player) {
                sender.sendMessage("§c只有玩家可以使用此指令。")
                return true
            }
            giveStation(sender)
            return true
        }

        // 2. 编辑配方指令
        if (args[0].equals("edit", ignoreCase = true)) {
            if (sender !is Player) return true
            if (!sender.isOp) {
                sender.sendMessage("§c权限不足。")
                return true
            }
            AdminCategoryGui(plugin, sender).open()
            return true
        }

        // 3. 重载指令
        if (args[0].equals("reload", ignoreCase = true)) {
            if (!sender.isOp) return true
            plugin.dzLevelManager.reload() // 重载等级配置
            plugin.recipeManager.loadAllRecipes() // 重载配方
            sender.sendMessage("§a锻造系统配置已重载 (包含dzlvl.yml)。")
            return true
        }

        // 4. 管理员修改数据指令
        // /hjhdz admin set <player> <type> <value>
        if (args[0].equals("admin", ignoreCase = true)) {
            if (!sender.isOp) {
                sender.sendMessage("§c权限不足。")
                return true
            }
            if (args.size < 5 || !args[1].equals("set", ignoreCase = true)) {
                sender.sendMessage("§c用法: /hjhdz admin set <玩家> <level/exp/license> <数值>")
                return true
            }

            val target = Bukkit.getPlayer(args[2])
            if (target == null) {
                sender.sendMessage("§c玩家不在线。")
                return true
            }

            // 这里假设 PlayerManager 已经有 getDzData 方法 (根据你之前的代码逻辑)
            val data = plugin.playerManager.getDzData(target.uniqueId)
            if (data == null) {
                sender.sendMessage("§c无法获取玩家数据。")
                return true
            }

            val type = args[3].lowercase(Locale.getDefault())
            val value: Int
            try {
                value = args[4].toInt()
            } catch (e: NumberFormatException) {
                sender.sendMessage("§c请输入有效的数字。")
                return true
            }

            when (type) {
                "level", "lv" -> {
                    data.forgeLevel = value // 假设 setForgeLevel 改为了 var forgeLevel
                    sender.sendMessage("§a已将 " + target.name + " 的锻造等级设置为: " + value)
                }
                "exp" -> {
                    data.forgeExp = value
                    sender.sendMessage("§a已将 " + target.name + " 的锻造经验设置为: " + value)
                }
                "license", "job" -> { // 兼容旧习惯
                    data.forgeLicense = value
                    val licName = plugin.dzLevelManager.getLicenseName(value)
                    sender.sendMessage("§a已将 " + target.name + " 的锻造资质设置为: " + licName + " (" + value + ")")
                }
                else -> {
                    sender.sendMessage("§c未知类型，可用: level, exp, license")
                }
            }
            return true
        }

        sendHelp(sender)
        return true
    }

    private fun giveStation(player: Player) {
        val station = ItemStack(Material.DISPENSER)
        val meta = station.itemMeta
        if (meta != null) {
            meta.setDisplayName("${ChatColor.GOLD}== 锻造台 ==")
            meta.lore = listOf("§7放置后右键打开锻造界面", "§e[管理员物品]")
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
            meta.persistentDataContainer.set(stationKey, PersistentDataType.STRING, "true")
            station.itemMeta = meta
        }
        player.inventory.addItem(station)
        player.sendMessage("§a已获得锻造台。")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("${ChatColor.GOLD}=== 锻造系统指令 ===")
        sender.sendMessage("§e/hjhdz station §7- 获取锻造台")
        sender.sendMessage("§e/hjhdz edit §7- 编辑/管理配方")
        if (sender.isOp) {
            sender.sendMessage("§c/hjhdz admin set <玩家> level <数值> §7- 修改锻造等级")
            sender.sendMessage("§c/hjhdz admin set <玩家> exp <数值> §7- 修改锻造经验")
            sender.sendMessage("§c/hjhdz admin set <玩家> license <数值> §7- 修改资质ID")
            sender.sendMessage("§c/hjhdz reload §7- 重载配置文件")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String>? {
        // args[0]
        if (args.size == 1) {
            val list = mutableListOf("station", "edit")
            if (sender.isOp) {
                list.add("admin")
                list.add("reload")
            }
            return list
        }

        // admin
        if (args[0].equals("admin", ignoreCase = true) && sender.isOp) {
            if (args.size == 2) {
                return Collections.singletonList("set")
            }
            if (args.size == 3 && args[1].equals("set", ignoreCase = true)) {
                return null // 显示玩家列表
            }
            if (args.size == 4 && args[1].equals("set", ignoreCase = true)) {
                return listOf("level", "exp", "license")
            }
            if (args.size == 5 && args[1].equals("set", ignoreCase = true)) {
                // 提示一些常用数值
                if (args[3].equals("license", ignoreCase = true)) {
                    return listOf("0", "1", "2", "3", "4", "5")
                }
                return listOf("1", "10", "100", "1000")
            }
        }
        return null
    }
}