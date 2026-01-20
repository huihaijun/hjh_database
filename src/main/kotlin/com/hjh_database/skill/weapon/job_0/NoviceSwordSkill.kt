package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class NoviceSwordSkill : WeaponSkill {

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        // 1. 读取参数
        // 使用 !! 断言 config 非空
        val radius = config!!.getDouble("radius", 5.0)
        val duration = config.getDouble("duration", 2.0)
        val amp = config.getInt("slowness_level", 1) - 1

        // 2. 视觉效果
        // 使用 !! 断言 player 非空
        val loc = player!!.location
        // 使用 !! 断言 world 非空
        player.world!!.playSound(loc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.5f)

        // Kotlin 循环写法，步长为 20
        for (i in 0 until 360 step 20) {
            val rad = Math.toRadians(i.toDouble())
            val x = Math.cos(rad) * radius
            val z = Math.sin(rad) * radius
            // spawnParticle 需要明确的 double 类型参数以匹配重载
            player.world!!.spawnParticle(Particle.CRIT, loc.clone().add(x, 0.5, z), 1, 0.0, 0.0, 0.0, 0.0)
        }

        // 3. 逻辑判定
        // getNearbyEntities 参数必须是 Double，Java 会自动提升 int(2) 但 Kotlin 不会，故写 2.0
        for (entity in player.getNearbyEntities(radius, 2.0, radius)) {
            // 模式匹配：检查是否为 LivingEntity 且不是玩家自己
            if (entity is LivingEntity && entity != player) {
                // 保持原变量名 target
                val target = entity
                if (target.scoreboardTags.contains("monster")) {
                    target.addPotionEffect(
                        PotionEffect(PotionEffectType.SLOWNESS, (duration * 20).toInt(), amp)
                    )
                }
            }
        }

        return true
    }
}