package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class BaZhenJueSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val castDistance = config?.getDouble("cast_distance", 12.0)?.coerceAtLeast(0.0) ?: 12.0
        val radius = config?.getDouble("radius", 6.0)?.coerceAtLeast(0.1) ?: 6.0
        val formationTicks = config?.getInt("formation_ticks", 20)?.coerceAtLeast(1) ?: 20
        val pulseInterval = config?.getInt("pulse_interval", 4)?.coerceAtLeast(1) ?: 4
        val pulseCount = (formationTicks / pulseInterval).coerceAtLeast(1)
        val pulseDamage = data.zfStr * (config?.getDouble("formation_damage_multiplier", 0.5) ?: 0.5)
        val burstDamage = data.zfStr * (config?.getDouble("burst_damage_multiplier", 6.0) ?: 6.0)
        val slowTicks = ((config?.getDouble("slow_duration", 3.0) ?: 3.0) * 20.0).toInt().coerceAtLeast(1)
        val slowAmplifier = config?.getInt("slow_amplifier", 0)?.coerceAtLeast(0) ?: 0
        val knockupVelocity = config?.getDouble("knockup_velocity", 1.0)?.coerceAtLeast(0.0) ?: 1.0

        val center = findFormationCenter(player, castDistance)
        center.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f)
        center.world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.9f, 1.15f)

        object : BukkitRunnable() {
            var step = 0

            override fun run() {
                if (!player.isOnline) {
                    cancel()
                    return
                }

                step++
                val progress = (step.toDouble() / pulseCount).coerceAtMost(1.0)
                drawFormationProgress(center, radius, progress)
                damageMonsters(player, center, radius, pulseDamage)
                center.world.playSound(
                    center,
                    Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    0.45f,
                    0.75f + progress.toFloat() * 0.65f
                )

                if (step >= pulseCount) {
                    finishFormation(player, center, radius, burstDamage, slowTicks, slowAmplifier, knockupVelocity)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, pulseInterval.toLong(), pulseInterval.toLong())

        return true
    }

    /** 阵眼取三维准心射线首次命中的怪物或方块；没有命中时取十二格终点。 */
    private fun findFormationCenter(player: Player, maxDistance: Double): Location {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val trace = player.world.rayTrace(
            eye,
            direction,
            maxDistance,
            FluidCollisionMode.NEVER,
            true,
            0.45
        ) { entity -> entity is LivingEntity && isMonster(entity) }

        return trace?.hitPosition?.toLocation(player.world)
            ?: eye.clone().add(direction.multiply(maxDistance))
    }

    private fun damageMonsters(player: Player, center: Location, radius: Double, damage: Double) {
        if (damage <= 0.0) return
        for (monster in monstersInFormation(center, radius)) {
            plugin.medicalSpellManager.applyMedicalDamage(player, monster, damage, "bazhenjue")
        }
    }

    private fun finishFormation(
        player: Player,
        center: Location,
        radius: Double,
        damage: Double,
        slowTicks: Int,
        slowAmplifier: Int,
        knockupVelocity: Double
    ) {
        for (monster in monstersInFormation(center, radius)) {
            if (damage > 0.0) {
                plugin.medicalSpellManager.applyMedicalDamage(player, monster, damage, "bazhenjue")
            }
            monster.addPotionEffect(
                PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowAmplifier, false, true, true)
            )

            // 延续原技能对副本首领免疫击飞的保护；其余目标仅获得 Y 轴速度。
            if (!monster.scoreboardTags.contains("instance_boss")) {
                monster.velocity = Vector(0.0, knockupVelocity, 0.0)
            }
        }

        drawFormationProgress(center, radius, 1.0)
        drawCompletionEffect(center, radius)
        center.world.spawnParticle(Particle.FLASH, center, 1)
        center.world.spawnParticle(
            Particle.END_ROD,
            center,
            70,
            radius * 0.55,
            0.45,
            radius * 0.55,
            0.06
        )
        center.world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.25f, 0.65f)
        center.world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f)
    }

    private fun monstersInFormation(center: Location, radius: Double): List<LivingEntity> {
        val radiusSquared = radius * radius
        return center.world.getNearbyEntities(center, radius, 3.0, radius)
            .filterIsInstance<LivingEntity>()
            .filter { monster ->
                isMonster(monster) &&
                    monster.location.y in (center.y - 2.0)..(center.y + 3.0) &&
                    horizontalDistanceSquared(monster.location, center) <= radiusSquared
            }
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        if (entity.isDead || !entity.isValid) return false
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun horizontalDistanceSquared(first: Location, second: Location): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    /** 只沿阵法边缘顺时针点亮，避免成阵阶段在阵内铺点。 */
    private fun drawFormationProgress(center: Location, radius: Double, progress: Double) {
        val world = center.world
        val maxAngle = 2.0 * PI * progress.coerceIn(0.0, 1.0)
        val cyanDust = Particle.DustOptions(Color.fromRGB(65, 235, 225), 1.35f)
        val goldDust = Particle.DustOptions(Color.fromRGB(255, 205, 75), 0.9f)
        val angleStep = PI / 40.0

        var angle = 0.0
        while (angle <= maxAngle + 1.0E-6) {
            val x = sin(angle)
            val z = cos(angle)
            world.spawnParticle(
                Particle.DUST,
                center.clone().add(x * radius, 0.12, z * radius),
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                cyanDust
            )
            world.spawnParticle(
                Particle.DUST,
                center.clone().add(x * (radius - 0.35), 0.18, z * (radius - 0.35)),
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                goldDust
            )
            angle += angleStep
        }

        // 八个阵门只在外缘亮起，并随圆环的推进依次激活。
        for (gate in 0 until 8) {
            val gateAngle = gate * PI / 4.0
            if (gateAngle > maxAngle + 1.0E-6) continue
            val x = sin(gateAngle) * radius
            val z = cos(gateAngle) * radius
            val gateLocation = center.clone().add(x, 0.3, z)
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, gateLocation, 4, 0.10, 0.32, 0.10, 0.005)
            world.spawnParticle(Particle.END_ROD, gateLocation.clone().add(0.0, 0.45, 0.0), 2, 0.04, 0.22, 0.04, 0.01)
        }

        // 领头的光簇强化“正在顺时针书写阵纹”的方向感。
        val headX = sin(maxAngle) * radius
        val headZ = cos(maxAngle) * radius
        val head = center.clone().add(headX, 0.35, headZ)
        world.spawnParticle(Particle.ELECTRIC_SPARK, head, 12, 0.16, 0.28, 0.16, 0.08)
        world.spawnParticle(Particle.END_ROD, head, 5, 0.06, 0.18, 0.06, 0.025)
    }

    private fun drawCompletionEffect(center: Location, radius: Double) {
        val world = center.world
        val goldDust = Particle.DustOptions(Color.fromRGB(255, 225, 105), 1.25f)
        for (gate in 0 until 8) {
            val angle = gate * PI / 4.0
            val x = sin(angle) * radius
            val z = cos(angle) * radius
            val gateLocation = center.clone().add(x, 0.25, z)
            world.spawnParticle(Particle.DUST, gateLocation, 10, 0.18, 0.18, 0.18, 0.0, goldDust)
            world.spawnParticle(Particle.ELECTRIC_SPARK, gateLocation.clone().add(0.0, 0.65, 0.0), 9, 0.14, 0.55, 0.14, 0.07)
        }

        for (ring in 0..2) {
            val ringRadius = radius - ring * 0.28
            var angle = 0.0
            while (angle < 2.0 * PI) {
                val x = sin(angle) * ringRadius
                val z = cos(angle) * ringRadius
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(x, 0.2 + ring * 0.12, z), 1, 0.0, 0.0, 0.0, 0.0)
                angle += PI / 24.0
            }
        }
    }
}
