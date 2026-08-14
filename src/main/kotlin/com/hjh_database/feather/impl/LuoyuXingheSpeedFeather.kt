package com.hjh_database.feather.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color

/**
 * 落羽星河：[星途]
 *
 * 提供 240% multiply_base 移速；受到一次有效怪物或虎瘴伤害后立即结束。
 */
class LuoyuXingheSpeedFeather(plugin: Hjh_database) : SpeedFeather(
    plugin = plugin,
    id = "luoyuxinghe",
    featherName = "落羽星河",
    skillName = "星途",
    color = "§5",
    initialSpeedBonus = 2.40,
    durationSeconds = 45 * 60,
    retainedBonusPerDamage = null,
    dungeonBonusMultiplier = 0.25,
    damageParticleColor = Color.fromRGB(130, 190, 255),
    cooldownSeconds = 60,
    requiredLevel = 40
)
