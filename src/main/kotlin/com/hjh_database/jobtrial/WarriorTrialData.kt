package com.hjh_database.jobtrial

import org.bukkit.Bukkit
import org.bukkit.Location

object WarriorTrialData {
    private val WORLD_NAME = "world" // 请根据实际情况修改

    // 按钮坐标
    val BUTTON_LOC by lazy { Location(Bukkit.getWorld(WORLD_NAME), 1237.0, 37.0, -388.0) }

    // 僵尸生成坐标
    val ZOMBIE_SPAWN_LOC by lazy { Location(Bukkit.getWorld(WORLD_NAME), 1235.5, 36.5, -388.5) }
}