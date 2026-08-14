package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.roundToInt

class NianQiJinSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val range = (config?.getDouble("range", 16.0) ?: 16.0).coerceAtLeast(0.0)
        val beamRadius = ((config?.getDouble("beam_width", 3.0) ?: 3.0) / 2.0).coerceAtLeast(0.0)
        val fullEffectDistance = (config?.getDouble("full_effect_distance", 2.0) ?: 2.0)
            .coerceIn(0.0, range)
        val maxDamageMultiplier = config?.getDouble("max_damage_multiplier", 4.8) ?: 4.8
        val minDamageMultiplier = config?.getDouble("min_damage_multiplier", 3.2) ?: 3.2
        val maxStunTicks = config?.getInt("max_stun_ticks", 48) ?: 48
        val minStunTicks = config?.getInt("min_stun_ticks", 16) ?: 16

        val startLoc = player.eyeLocation
        val startVector = startLoc.toVector()
        val direction = startLoc.direction.normalize()

        player.world.playSound(startLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.5f)
        player.world.playSound(startLoc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f)

        val damagedEntities = mutableSetOf<LivingEntity>()
        val step = 0.5
        var distance = 0.0

        while (distance <= range) {
            val currentLoc = startLoc.clone().add(direction.clone().multiply(distance))

            player.world.spawnParticle(Particle.CRIT, currentLoc, 5, 0.2, 0.2, 0.2, 0.05)
            if (distance % 2.0 < step) {
                player.world.spawnParticle(Particle.SONIC_BOOM, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
            }

            // 保留原有行为：气波遇到实体方块后停止，不能隔墙命中怪物。
            if (currentLoc.block.type.isSolid) {
                player.world.spawnParticle(Particle.EXPLOSION, currentLoc, 1)
                break
            }

            for (entity in player.world.getNearbyEntities(currentLoc, beamRadius, beamRadius, beamRadius)) {
                val target = entity as? LivingEntity ?: continue
                if (target.uniqueId == player.uniqueId || !target.isValid || target.isDead || target in damagedEntities) continue

                val tags = target.scoreboardTags
                if (!tags.contains(PANLING_TAG) || !tags.contains(MONSTER_TAG)) continue

                // 用怪物身体中心到气波中心线的垂距限制宽度，总宽度为 beam_width（默认 3 格）。
                val targetCenter = target.location.clone().add(0.0, target.height / 2.0, 0.0).toVector()
                val relative = targetCenter.subtract(startVector)
                val distanceFromCaster = relative.length().coerceAtMost(range)
                val axialDistance = relative.dot(direction)
                if (axialDistance < 0.0 || axialDistance > range) continue

                val perpendicular = relative.subtract(direction.clone().multiply(axialDistance))
                if (perpendicular.lengthSquared() > beamRadius * beamRadius) continue

                damagedEntities.add(target)

                // 0—2 格效果全满；2—16 格线性衰减，并在第 16 格恰好达到最低效果。
                val decayProgress = if (range <= fullEffectDistance || distanceFromCaster <= fullEffectDistance) {
                    0.0
                } else {
                    ((distanceFromCaster - fullEffectDistance) / (range - fullEffectDistance)).coerceIn(0.0, 1.0)
                }
                val damageMultiplier = lerp(maxDamageMultiplier, minDamageMultiplier, decayProgress)
                val stunTicks = lerp(maxStunTicks.toDouble(), minStunTicks.toDouble(), decayProgress)
                    .roundToInt()
                    .coerceAtLeast(1)

                plugin.medicalSpellManager.applyMedicalDamage(
                    player,
                    target,
                    data.zfStr * damageMultiplier,
                    "nianqijin"
                )

                target.world.playSound(target.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f)
                target.world.spawnParticle(
                    Particle.DAMAGE_INDICATOR,
                    target.location.clone().add(0.0, 1.0, 0.0),
                    5,
                    0.3,
                    0.3,
                    0.3,
                    0.1
                )

                // 副本 Boss 仍受到伤害，但免疫念气劲的眩晕。
                if (!tags.contains(INSTANCE_BOSS_TAG)) {
                    target.addPotionEffect(
                        PotionEffect(PotionEffectType.SLOWNESS, stunTicks, 255, false, false, true)
                    )
                }
            }

            distance += step
        }

        return true
    }

    private fun lerp(start: Double, end: Double, progress: Double): Double {
        return start + (end - start) * progress
    }

    private companion object {
        const val PANLING_TAG = "panling"
        const val MONSTER_TAG = "monster"
        const val INSTANCE_BOSS_TAG = "instance_boss"
    }
}
