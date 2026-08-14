package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.ceil

class XingHuaYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val range = (config?.getDouble("range", 16.0) ?: 16.0).coerceAtLeast(0.0)
        val damageMultiplier = config?.getDouble("damage_multiplier", 1.2) ?: 1.2
        val shieldRatio = config?.getDouble("shield_ratio", 0.3) ?: 0.3
        val shieldDurationTicks = ((config?.getDouble("shield_duration", 8.0) ?: 8.0) * 20.0)
            .toInt()
            .coerceAtLeast(1)
        val plantDelayTicks = ((config?.getDouble("plant_delay", 4.0) ?: 4.0) * 20.0)
            .toLong()
            .coerceAtLeast(0L)
        val rainRadius = (config?.getDouble("rain_radius", 8.0) ?: 8.0).coerceAtLeast(0.0)
        val rainCenterHeight = config?.getDouble("rain_center_height", 4.0) ?: 4.0
        val durationTicks = ((config?.getDouble("duration", 10.0) ?: 10.0) * 20.0)
            .toInt()
            .coerceAtLeast(0)
        val healAmount = data.zfStr * (config?.getDouble("heal_multiplier", 1.0) ?: 1.0)
        val monsterDamage = data.zfStr * damageMultiplier

        val startLocation = player.eyeLocation.clone()
        val direction = startLocation.direction.normalize()
        player.world.playSound(startLocation, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)

        object : BukkitRunnable() {
            private var distance = 0.0
            private val currentLocation = startLocation.clone()

            override fun run() {
                if (!player.isOnline) {
                    cancel()
                    return
                }
                if (distance >= range) {
                    explodeAndStartRain(
                        player,
                        currentLocation.clone(),
                        rainRadius,
                        rainCenterHeight,
                        durationTicks,
                        healAmount
                    )
                    cancel()
                    return
                }

                val travelDistance = PROJECTILE_STEP.coerceAtMost(range - distance)
                currentLocation.add(direction.clone().multiply(travelDistance))
                distance += travelDistance
                player.world.spawnParticle(Particle.CHERRY_LEAVES, currentLocation, 2, 0.05, 0.05, 0.05, 0.0)

                // 没有命中实体时，撞到方块便在碰撞点直接爆炸成雨。
                if (currentLocation.block.type.isSolid) {
                    explodeAndStartRain(
                        player,
                        currentLocation.clone(),
                        rainRadius,
                        rainCenterHeight,
                        durationTicks,
                        healAmount
                    )
                    cancel()
                    return
                }

                val hitTarget = player.world
                    .getNearbyEntities(currentLocation, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)
                    .asSequence()
                    .mapNotNull { it as? LivingEntity }
                    .filter { it.uniqueId != player.uniqueId && it.isValid && !it.isDead }
                    .filter { it is Player || isMonster(it) }
                    .minByOrNull { it.location.distanceSquared(currentLocation) }
                    ?: return

                // 必须复制命中瞬间的坐标；之后花种和雨域都不再跟随实体。
                val plantedLocation = hitTarget.location.clone()
                if (isMonster(hitTarget)) {
                    plugin.medicalSpellManager.applyMedicalDamage(
                        player,
                        hitTarget,
                        monsterDamage,
                        "xinghuayu"
                    )
                } else if (hitTarget is Player) {
                    applyShield(hitTarget, shieldRatio, shieldDurationTicks)
                }

                plantSeed(
                    player,
                    plantedLocation,
                    plantDelayTicks,
                    rainRadius,
                    rainCenterHeight,
                    durationTicks,
                    healAmount
                )
                cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)

        return true
    }

    private fun plantSeed(
        caster: Player,
        plantedLocation: Location,
        delayTicks: Long,
        rainRadius: Double,
        rainCenterHeight: Double,
        durationTicks: Int,
        healAmount: Double
    ) {
        val fixedLocation = plantedLocation.clone()
        fixedLocation.world.playSound(fixedLocation, Sound.BLOCK_GRASS_PLACE, 1.0f, 1.25f)
        fixedLocation.world.spawnParticle(
            Particle.DUST,
            fixedLocation.clone().add(0.0, 0.15, 0.0),
            12,
            0.25,
            0.08,
            0.25,
            0.0,
            Particle.DustOptions(Color.fromRGB(255, 145, 190), 1.0f)
        )

        object : BukkitRunnable() {
            override fun run() {
                explodeAndStartRain(
                    caster,
                    fixedLocation,
                    rainRadius,
                    rainCenterHeight,
                    durationTicks,
                    healAmount
                )
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun explodeAndStartRain(
        caster: Player,
        seedLocation: Location,
        radius: Double,
        rainCenterHeight: Double,
        durationTicks: Int,
        healAmount: Double
    ) {
        val fixedSeedLocation = seedLocation.clone()
        val world = fixedSeedLocation.world
        // 雨区中心高于埋种点，避免花种位于地下时漏掉地面上的玩家。
        val rainCenter = fixedSeedLocation.clone().add(0.0, rainCenterHeight, 0.0)

        val bloomLocation = fixedSeedLocation.clone().add(0.0, 0.5, 0.0)
        world.spawnParticle(
            Particle.DUST,
            bloomLocation,
            30,
            0.7,
            0.45,
            0.7,
            0.03,
            Particle.DustOptions(Color.fromRGB(255, 135, 185), 1.35f)
        )
        world.spawnParticle(Particle.END_ROD, bloomLocation, 12, 0.55, 0.4, 0.55, 0.025)
        world.spawnParticle(Particle.CHERRY_LEAVES, rainCenter, 45, 1.2, 1.2, 1.2, 0.05)
        world.playSound(fixedSeedLocation, Sound.BLOCK_CHERRY_WOOD_FALL, 1.5f, 0.8f)

        if (durationTicks <= 0) return

        object : BukkitRunnable() {
            private var elapsedTicks = 0

            override fun run() {
                world.spawnParticle(
                    Particle.CHERRY_LEAVES,
                    rainCenter,
                    28,
                    radius,
                    0.35,
                    radius,
                    0.0
                )

                elapsedTicks += RAIN_UPDATE_TICKS
                if (elapsedTicks % 20 == 0) {
                    healPlayersInRain(caster, rainCenter, radius, healAmount)
                    world.playSound(rainCenter, Sound.BLOCK_AMETHYST_BLOCK_STEP, 0.5f, 1.5f)
                }

                if (elapsedTicks >= durationTicks) cancel()
            }
        }.runTaskTimer(plugin, RAIN_UPDATE_TICKS.toLong(), RAIN_UPDATE_TICKS.toLong())
    }

    private fun healPlayersInRain(caster: Player, rainCenter: Location, radius: Double, healAmount: Double) {
        val radiusSquared = radius * radius
        for (entity in rainCenter.world.getNearbyEntities(rainCenter, radius, radius, radius)) {
            val target = entity as? Player ?: continue
            if (!target.isOnline || target.isDead) continue

            // 杏花雨按水平圆形范围结算；中心抬高只影响垂直覆盖和落花表现。
            val deltaX = target.location.x - rainCenter.x
            val deltaZ = target.location.z - rainCenter.z
            if (deltaX * deltaX + deltaZ * deltaZ > radiusSquared) continue

            plugin.medicalSpellManager.applyMedicalHeal(caster, target, healAmount, "xinghuayu")
            target.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, 1, 0, false, false, true))
            target.world.spawnParticle(
                Particle.HEART,
                target.location.clone().add(0.0, 2.0, 0.0),
                1,
                0.3,
                0.3,
                0.3,
                0.0
            )
        }
    }

    private fun applyShield(target: Player, shieldRatio: Double, durationTicks: Int) {
        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val shieldAmount = (maxHealth * shieldRatio).coerceAtLeast(0.0)
        if (shieldAmount <= 0.0) return

        val amplifier = (ceil(shieldAmount / 4.0).toInt() - 1).coerceAtLeast(0)
        target.addPotionEffect(
            PotionEffect(PotionEffectType.ABSORPTION, durationTicks, amplifier, false, false, true),
            true
        )
        target.absorptionAmount = shieldAmount
        target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.4f)
        target.world.spawnParticle(
            Particle.DUST,
            target.location.clone().add(0.0, 1.0, 0.0),
            20,
            0.45,
            0.65,
            0.45,
            0.0,
            Particle.DustOptions(Color.fromRGB(255, 190, 220), 1.15f)
        )
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains(PANLING_TAG) && tags.contains(MONSTER_TAG)
    }

    private companion object {
        const val PANLING_TAG = "panling"
        const val MONSTER_TAG = "monster"
        const val PROJECTILE_STEP = 0.5
        const val HIT_RADIUS = 0.6
        const val RAIN_UPDATE_TICKS = 5
    }
}
