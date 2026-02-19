package com.hjh_database.jobtrial

import com.hjh_database.Hjh_database
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class JobTrialManager(private val plugin: Hjh_database) : CommandExecutor {

    init {
        // 注册监听器
        plugin.server.pluginManager.registerEvents(JobTrialListener(plugin), plugin)
        // 简单的调试指令
        plugin.getCommand("jobtrial")?.setExecutor(this)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        sender.sendMessage("§a[JobTrial] 模块正在运行。请手动放置按钮并配置坐标。")
        return true
    }
}