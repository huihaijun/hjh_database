package com.hjh_database.feather.impl

import org.bukkit.Color

class QingyingSpeedFeather : SpeedFeather(
    id = "qingyingzhiyu",
    featherName = "轻盈之羽",
    skillName = "逐风",
    color = "§d",
    initialSpeedBonus = 0.80,
    durationSeconds = 30 * 60,
    reductionPerDamage = 0.10,
    damageParticleColor = Color.fromRGB(225, 105, 255)
)
