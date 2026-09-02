package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.EnhancedElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.sin

class EnhancedMetalSkill(private val plugin: Hjh_database) : EnhancedElementSkill {
    override fun cast(player: Player, data: PlayerData): Boolean {
        val targetPoint = getEnhancedSightLocation(player, 20.0, FluidCollisionMode.NEVER)
        val cloudCenter = targetPoint.clone().add(0.0, 7.5, 0.0)
        val world = targetPoint.world ?: return false
        val damage = data.zfStr * 5.0 *
            plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
        val radius = 5.5

        broadcastOriginMessage(player, "§e", "金元素", "暗云裂解")
        world.playSound(cloudCenter, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.45f, 1.2f)
        world.playSound(cloudCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.75f)
        world.playSound(cloudCenter, Sound.BLOCK_BEACON_AMBIENT, 1.4f, 1.8f)
        playCloudForm(cloudCenter, radius)
        playTargetingBeam(player, cloudCenter)

        val victims = world.getNearbyEntities(targetPoint, radius, 9.0, radius).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isEnhancedMonster(it) }
            .filter { it.location.y <= cloudCenter.y && horizontalDistanceSquared(it.location, targetPoint) <= radius * radius }
            .toList()

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks >= 12) {
                    for (victim in victims) {
                        enhancedMagicDamage(plugin, player, victim, damage, FormationElement.METAL)
                        victim.setMetadata(META_METAL_WEAKEN_UNTIL, FixedMetadataValue(plugin, System.currentTimeMillis() + 5000L))
                        victim.world.spawnParticle(Particle.FLASH, victim.location.add(0.0, victim.height * 0.5, 0.0), 1)
                    }
                    world.playSound(targetPoint, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.55f, 1.35f)
                    cancel()
                    return
                }

                playCloudPulse(cloudCenter, radius)
                for (victim in victims.take(8)) {
                    val end = victim.location.add(0.0, victim.height * 0.55, 0.0)
                    playDownStrike(cloudCenter, end)
                }
                if (victims.isEmpty() && ticks % 3 == 0) {
                    playDownStrike(cloudCenter, targetPoint.clone().add(0.0, 0.15, 0.0))
                }
                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)

        return true
    }

    private fun playCloudForm(center: Location, radius: Double) {
        val world = center.world ?: return
        // Use fewer loop iterations but better particle counts to maintain high TPS
        for (i in 0 until 35) {
            val angle = Math.random() * Math.PI * 2.0
            val r = Math.random() * radius
            val y = (Math.random() - 0.5) * 1.5
            val loc = center.clone().add(cos(angle) * r, y, sin(angle) * r)
            world.spawnParticle(Particle.SQUID_INK, loc, 2, 0.2, 0.2, 0.2, 0.02)
            if (i % 2 == 0) {
                world.spawnParticle(Particle.WITCH, loc, 1, 0.1, 0.1, 0.1, 0.0)
            }
            if (i % 4 == 0) {
                world.spawnParticle(Particle.DUST, loc, 1, Particle.DustOptions(Color.fromRGB(60, 10, 80), 1.2f))
            }
        }
        // Core implosion effect (裂解)
        world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 15, radius * 0.5, 0.5, radius * 0.5, 0.1)
    }

    private fun playCloudPulse(center: Location, radius: Double) {
        val world = center.world ?: return
        for (i in 0 until 10) {
            val angle = Math.random() * Math.PI * 2.0
            val r = Math.random() * radius
            val loc = center.clone().add(cos(angle) * r, (Math.random() - 0.5) * 0.8, sin(angle) * r)
            world.spawnParticle(Particle.SMOKE, loc, 1, 0.1, 0.1, 0.1, 0.01)
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, loc, 1, Particle.DustOptions(Color.fromRGB(120, 20, 180), 0.9f))
                world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 1, 0.0, 0.0, 0.0, 0.05)
            }
        }
    }

    private fun playTargetingBeam(player: Player, cloudCenter: Location) {
        val start = player.eyeLocation.add(0.0, -0.2, 0.0)
        val distance = start.distance(cloudCenter)
        if (distance <= 0.1) return
        val dir = cloudCenter.toVector().subtract(start.toVector()).normalize()
        var d = 0.0
        while (d < distance) {
            val point = start.clone().add(dir.clone().multiply(d))
            val color = if (((d * 10).toInt() and 1) == 0) Color.fromRGB(210, 165, 50) else Color.fromRGB(135, 65, 200)
            player.world.spawnParticle(Particle.DUST, point, 1, Particle.DustOptions(color, 0.65f))
            d += 0.45
        }
    }

    private fun playDownStrike(cloudCenter: Location, end: Location) {
        val world = cloudCenter.world ?: return
        val start = cloudCenter.clone().add((Math.random() - 0.5) * 2.2, -0.4, (Math.random() - 0.5) * 2.2)
        val distance = start.distance(end)
        if (distance <= 0.1) return
        val dir = end.toVector().subtract(start.toVector()).normalize()
        var d = 0.0
        while (d <= distance) {
            val point = start.clone().add(dir.clone().multiply(d))
            val color = if (((d * 8).toInt() and 1) == 0) Color.fromRGB(245, 205, 75) else Color.fromRGB(165, 80, 230)
            world.spawnParticle(Particle.DUST, point, 1, Particle.DustOptions(color, 0.75f))
            if (d % 1.2 < 0.2) world.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0)
            d += 0.45
        }
    }
}
