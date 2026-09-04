package com.hjh_database.feather.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color

class QingyingSpeedFeather(plugin: Hjh_database) : SpeedFeather(
    plugin = plugin,
    id = "qingyingzhiyu",
    featherName = "轻盈之羽",
    skillName = "逐风",
    color = "§d",
    initialSpeedBonus = 1.00,
    durationSeconds = 30 * 60,
    retainedBonusPerDamage = 0.50,
    maxSurvivedDamageHits = 3,
    dungeonBonusMultiplier = 0.20,
    damageParticleColor = Color.fromRGB(225, 105, 255),
    cooldownSeconds = 30
)
