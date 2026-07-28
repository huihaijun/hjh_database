package com.hjh_database.feather.impl

import org.bukkit.Color

class JifengSpeedFeather : SpeedFeather(
    id = "jifengzhiyu",
    featherName = "疾风之羽",
    skillName = "掠空",
    color = "§e",
    initialSpeedBonus = 1.00,
    durationSeconds = 30 * 60,
    reductionPerDamage = 0.10,
    damageParticleColor = Color.fromRGB(255, 215, 70)
)
