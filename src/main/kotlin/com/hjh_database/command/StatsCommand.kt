package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class StatsCommand(private val plugin: Hjh_database) : CommandExecutor {

    // 映射表 (与 AdminCommand 保持一致)
    private val jobMap = mapOf(
        0 to "战士", 1 to "弓箭手", 2 to "术士", 3 to "医师"
    )
    private val raceMap = mapOf(
        0 to "神", 1 to "仙", 2 to "人", 3 to "战神", 4 to "妖"
    )

    private fun getJobName(job: Int?): String {
        return if (job == null) "未选择" else jobMap.getOrDefault(job, "未知")
    }

    private fun getRaceName(race: Int?): String {
        return if (race == null) "未选择" else raceMap.getOrDefault(race, "未知")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("控制台无法使用此命令。")
            return true
        }

        val player = sender
        // 使用属性访问 plugin.playerManager
        val data = plugin.playerManager.getData(player.uniqueId)

        if (data == null) {
            sender.sendMessage(ChatColor.RED.toString() + "正在加载数据，请稍后再试...")
            return true
        }

        player.sendMessage(ChatColor.DARK_GRAY.toString() + "============ [ " + ChatColor.GOLD + "个人属性" + ChatColor.DARK_GRAY + " ] ============")
        // Java getter -> Kotlin property (例如 getPlayerName() -> playerName)
        player.sendMessage(ChatColor.YELLOW.toString() + " 姓名: " + ChatColor.WHITE + data.playerName)
        player.sendMessage(ChatColor.YELLOW.toString() + " 等级: " + ChatColor.WHITE + data.lv)

        // 【新增显示职业和种族】
        player.sendMessage(ChatColor.YELLOW.toString() + " 职业: " + ChatColor.AQUA + getJobName(data.job))
        player.sendMessage(ChatColor.YELLOW.toString() + " 种族: " + ChatColor.LIGHT_PURPLE + getRaceName(data.race))

        player.sendMessage("")

        // getVal 是逻辑方法，保持调用; maxHealth 是数据对象，使用属性访问
        player.sendMessage(ChatColor.RED.toString() + " ❤ 生命: " + String.format("%.1f", player.health) + " / " + data.getVal(data.maxHealth))
        player.sendMessage(ChatColor.RED.toString() + " ⚔ 攻击: " + data.getVal(data.attack))
        player.sendMessage(ChatColor.GREEN.toString() + " ➹ 远程: " + data.getVal(data.archerDamage))

        // 在 StatsCommand.java 中修改护甲显示行
        val armor = data.getVal(data.armor)
        val reducePercent = (1 - (50.0 / (50.0 + armor))) * 100

        player.sendMessage(ChatColor.BLUE.toString() + " 🛡 护甲: " + armor + ChatColor.GRAY + " (物理减伤: " + String.format("%.1f", reducePercent) + "%)")
        player.sendMessage(ChatColor.AQUA.toString() + " ⚡ 移速: " + String.format("%.1f", data.getVal(data.speed) * 1000)) // 放大显示便于阅读
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString() + " ⚛ 法强: " + data.getVal(data.zfStr))
        player.sendMessage(ChatColor.GRAY.toString() + " ❈ 韧性: " + String.format("%.0f%%", data.getVal(data.toughness) * 100))
        player.sendMessage(ChatColor.GOLD.toString() + " $ 财产: " + data.getVal(data.money))
        player.sendMessage(ChatColor.DARK_GRAY.toString() + "======================================")

        return true
    }
}