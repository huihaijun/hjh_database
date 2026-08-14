package com.hjh_database.feather.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color

class JifengSpeedFeather(plugin: Hjh_database) : SpeedFeather(
    plugin = plugin,
    id = "jifengzhiyu",
    featherName = "疾风之羽",
    skillName = "掠空",
    color = "§e",
    initialSpeedBonus = 1.60,
    durationSeconds = 30 * 60,
    retainedBonusPerDamage = 0.50,
    maxSurvivedDamageHits = 3,
    dungeonBonusMultiplier = 0.25,
    damageParticleColor = Color.fromRGB(255, 215, 70),
    cooldownSeconds = 30
)
