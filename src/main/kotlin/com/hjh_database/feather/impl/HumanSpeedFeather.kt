package com.hjh_database.feather.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color

class HumanSpeedFeather(plugin: Hjh_database) : SpeedFeather(
    plugin = plugin,
    id = "hjh_xyzy",
    featherName = "新芽之羽",
    skillName = "初飞",
    color = "§9",
    initialSpeedBonus = 0.50,
    durationSeconds = 10 * 60,
    retainedBonusPerDamage = null,
    dungeonBonusMultiplier = 0.20,
    damageParticleColor = Color.fromRGB(100, 180, 255),
    cooldownSeconds = 30
)
