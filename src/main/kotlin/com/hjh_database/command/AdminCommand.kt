package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.ArrayList

class AdminCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {

    // 映射表
    private val jobMap = mapOf(
        0 to "战士", 1 to "弓箭手", 2 to "术士", 3 to "医师"
    )
    private val raceMap = mapOf(
        0 to "神", 1 to "仙", 2 to "人", 3 to "战神", 4 to "妖"
    )

    // 反向查找 (使用 Kotlin 的 associate 替代 Stream)
    private val jobReverseMap = jobMap.entries.associate { (k, v) -> v to k }
    private val raceReverseMap = raceMap.entries.associate { (k, v) -> v to k }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限使用此管理命令。")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW.toString() + "=== HJH 管理员指令 ===")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin reload - 重载配置")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin get <物品ID/名称> [数量] - 获取Resource物品")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin gettestgear <玩家> - 获取测试装备")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin givetoken <玩家> - 给予天机令")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin job <玩家> <职业> - 设置职业")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin race <玩家> <种族> - 设置种族")
            // 【新增提示】
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin medical <技能ID> - 获取医术秘籍")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin getstation - 获取医术绘制台")
            return true
        }

        val subCommand = args[0].lowercase()

        // === reload (重载) ===
        if (subCommand == "reload") {
            plugin.reloadConfig()
            plugin.menuManager.reload()
            plugin.playerManager.weaponManager.reload()
            plugin.playerManager.armorManager.reload()

            // 重载 Resource 物品
            if (plugin.resourceManager != null) {
                plugin.resourceManager.reload()
            }
            // 【新增】重载医术配置
            if (plugin.medicalManager != null) {
                plugin.medicalManager.loadSkillBooks() // 假设你在 Manager 里有这个加载方法
            }

            sender.sendMessage(ChatColor.GREEN.toString() + "所有配置文件(含Resource/Medical)已重载！")
            return true
        }

        // === get (获取 Resource 物品) ===
        if (subCommand == "get") {
            if (sender !is Player) {
                sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此命令。")
                return true
            }
            val player = sender

            if (args.size < 2) return error(sender, "用法: /hjhadmin get <物品ID或名字> [数量]")

            val itemName = args[1]
            // 从 ResourceManager 获取物品
            // 注意：这里调用的是 plugin.resourceManager
            val item = plugin.resourceManager.getItem(itemName)

            if (item == null) {
                return error(sender, "未找到名为 [$itemName] 的物品！请检查 resources 文件夹。")
            }

            var amount = 1
            if (args.size >= 3) {
                try {
                    amount = args[2].toInt()
                } catch (e: NumberFormatException) {
                    return error(sender, "数量必须是数字。")
                }
            }

            item.amount = amount
            player.inventory.addItem(item)
            sender.sendMessage(ChatColor.GREEN.toString() + "已获得物品: " + itemName + " x" + amount)
            return true
        }

        // === medical (获取医术秘籍) 【新增部分】 ===
        if (subCommand == "medical") {
            if (sender !is Player) {
                sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此命令。")
                return true
            }
            val player = sender

            if (args.size < 2) return error(sender, "用法: /hjhadmin medical <技能ID>")

            val skillId = args[1]
            // 调用 MedicalManager 获取秘籍
            if (plugin.medicalManager != null) {
                val book = plugin.medicalManager.getSkillBook(skillId) // 之前写的方法叫 getSkillBook
                if (book != null) {
                    player.inventory.addItem(book)
                    sender.sendMessage(ChatColor.GREEN.toString() + "已获得医术秘籍: " + skillId)
                } else {
                    sender.sendMessage(ChatColor.RED.toString() + "未找到技能ID为 [" + skillId + "] 的秘籍配置。")
                }
            }
            return true
        }

        if (args.size == 1 && args[0].equals("getstation", ignoreCase = true)) {
            if (plugin.medicalManager != null) {
                val p = sender as Player
                p.inventory.addItem(plugin.medicalManager.getMedicalStationItem())
                p.sendMessage("§a已获取医术绘制台！")
                return true
            }
        }

        // === givetoken (给予天机令) ===
        if (subCommand == "givetoken") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin givetoken <玩家>")
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")
            target.inventory.addItem(plugin.menuManager.getTianjiToken())
            sender.sendMessage(ChatColor.GREEN.toString() + "给予天机令成功。")
            return true
        }

        // === gettestgear (获取测试装备) ===
        if (subCommand == "gettestgear") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin gettestgear <玩家>")
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")

            // 获取物品
            val sword = plugin.playerManager.weaponManager.getItemStack("novice_sword")
            val helm = plugin.playerManager.armorManager.getItemStack("novice_helmet")
            val chest = plugin.playerManager.armorManager.getItemStack("novice_chestplate")
            val leg = plugin.playerManager.armorManager.getItemStack("novice_leggings")
            val boot = plugin.playerManager.armorManager.getItemStack("novice_boots")

            if (sword != null) target.inventory.addItem(sword)
            if (helm != null) target.inventory.helmet = helm
            if (chest != null) target.inventory.chestplate = chest
            if (leg != null) target.inventory.leggings = leg
            if (boot != null) target.inventory.boots = boot

            // 刷新属性
            plugin.playerManager.updateStats(target)
            sender.sendMessage(ChatColor.GREEN.toString() + "已发放全套测试装备给 " + target.name)
            return true
        }

        // === job / race (设置职业/种族) ===
        if (args.size < 3) return error(sender, "用法: /hjhadmin <job|race> <玩家> <值>")

        val target = Bukkit.getPlayerExact(args[1])
        if (target == null) return error(sender, "玩家不在线")
        val data = plugin.playerManager.getData(target.uniqueId)
        if (data == null) return error(sender, "数据加载中...")

        val valStr = args[2]
        if (subCommand == "job") {
            val job = jobReverseMap[valStr]
            if (job == null) return error(sender, "无效职业 (战士/弓箭手/术士/医师)")
            data.job = job
            sender.sendMessage(ChatColor.GREEN.toString() + "职业已设为: " + valStr)
        } else if (subCommand == "race") {
            val race = raceReverseMap[valStr]
            if (race == null) return error(sender, "无效种族 (神/仙/人/战神/妖)")
            data.race = race
            sender.sendMessage(ChatColor.GREEN.toString() + "种族已设为: " + valStr)
        } else {
            return error(sender, "未知指令")
        }

        plugin.playerManager.updateStats(target)
        return true
    }

    // 简化的错误提示
    private fun error(sender: CommandSender, msg: String): Boolean {
        sender.sendMessage(ChatColor.RED.toString() + msg)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        // 【修改】添加 medical 到一级补全
        if (args.size == 1) return listOf("job", "race", "givetoken", "reload", "gettestgear", "get", "medical", "getstation")

        // 如果是 get 指令，第二个参数提示所有物品的ID和名字
        if (args.size == 2 && args[0].equals("get", ignoreCase = true)) {
            if (plugin.resourceManager != null) {
                // 注意：这里使用了 getAllNames() 匹配 ResourceManager.kt 中的方法名
                // 如果你的 Java 接口是 getAllItemNames()，请确认 ResourceManager.kt 中对应的方法名
                val allNames = plugin.resourceManager.getAllItemNames()
                val currentInput = args[1].lowercase()
                return allNames.filter { it.lowercase().startsWith(currentInput) }
            }
            return ArrayList()
        }

        // 【新增】如果是 medical 指令，提示技能ID
        if (args.size == 2 && args[0].equals("medical", ignoreCase = true)) {
            if (plugin.medicalManager != null) {
                // 之前让你在 Manager 里加的 getAllSkillIds()
                return ArrayList(plugin.medicalManager.getAllSkillIds())
            }
        }

        if (args.size == 2) return null // 其他指令默认回显玩家名

        if (args.size == 3) {
            if (args[0].equals("job", ignoreCase = true)) return ArrayList(jobReverseMap.keys)
            if (args[0].equals("race", ignoreCase = true)) return ArrayList(raceReverseMap.keys)
        }
        return ArrayList()
    }
}