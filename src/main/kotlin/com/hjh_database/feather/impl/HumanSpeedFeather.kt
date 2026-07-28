package com.hjh_database.feather.impl

import org.bukkit.Color

class HumanSpeedFeather : SpeedFeather(
    id = "hjh_xyzy",
    featherName = "新芽之羽",
    skillName = "初飞",
    color = "§9",
    initialSpeedBonus = 0.50,
    durationSeconds = 10 * 60,
    reductionPerDamage = null,
    damageParticleColor = Color.fromRGB(100, 180, 255)
)
