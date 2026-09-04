package com.hjh_database.dz.data

import com.hjh_database.Hjh_database
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class DzPlayerData(
    val uuid: UUID,
    val playerName: String
) {
    // 锻造等级
    var forgeLevel: Int = 1
    // 锻造经验
    var forgeExp: Int = 0
    // 锻造许可 (0=无, 1=初级, 2=中级...)
    var forgeLicense: Int = 0

    // Kotlin 属性会自动生成 getUuid(), getPlayerName(), getForgeLevel(), setForgeLevel() 等方法
    // 因此无需手动编写 Getters and Setters，外部调用名保持不变。

    /**
     * 核心升级逻辑：增加经验并检查升级
     * @param amount 增加的经验值
     * @param plugin 插件实例 (用于读取配置和保存数据)
     * @param player 玩家对象 (用于发送升级提示和音效)
     */
    fun addExp(amount: Int, plugin: Hjh_database, player: Player) {
        // 1. 增加经验
        this.forgeExp += amount

        // 2. 循环检查是否满足升级条件 (防止一次加大量经验导致只升一级)
        var hasLeveledUp = false

        while (true) {
            // 获取当前等级升级所需的最大经验
            // 注意：Kotlin 中使用属性访问语法 .dzLevelManager 代替 getDzLevelManager()
            val maxExp = plugin.dzLevelManager.getMaxExp(this.forgeLevel)

            // 如果 maxExp <= 0，说明已经满级了，不再升级
            if (maxExp <= 0) {
                break
            }

            // 如果经验足够升级
            if (this.forgeExp >= maxExp) {
                this.forgeExp -= maxExp // 扣除升级所需经验 (保留溢出部分)
                this.forgeLevel++       // 等级 +1
                hasLeveledUp = true
            } else {
                // 经验不足以继续升级，跳出循环
                break
            }
        }

        // 3. 升级反馈 (音效与提示)
        if (hasLeveledUp) {
            // sendMessage 支持 varargs，逻辑保持不变
            player.sendMessage("§6§l锻造升级！", "§e当前锻造等级: §fLv.$forgeLevel")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            player.sendMessage("§8[§6锻造§8] §a恭喜！你的锻造等级提升到了 §eLv.$forgeLevel")
        }

        // 4. 【关键】保存数据到数据库
        saveToDatabase(plugin)
    }

    // 简单的保存辅助方法
    private fun saveToDatabase(plugin: Hjh_database) {
        // 使用异步任务保存，防止卡主线程
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.saveDzPlayerData(this)
        })
    }
}
