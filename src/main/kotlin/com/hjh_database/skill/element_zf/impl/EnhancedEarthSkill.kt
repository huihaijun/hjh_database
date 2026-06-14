package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.EnhancedElementSkill
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class EnhancedEarthSkill(private val plugin: Hjh_database) : EnhancedElementSkill {
    override fun cast(player: Player, data: PlayerData): Boolean {
        val center = getEnhancedSightLocation(player, 13.0, FluidCollisionMode.ALWAYS)
        val world = center.world ?: return false
        val radius = 10.0
        val mobs = world.getNearbyEntities(center, radius, radius, radius).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isEnhancedMonster(it) && it.location.distanceSquared(center) <= radius * radius }
            .toList()

        broadcastOriginMessage(player, "§6", "土元素", "轮转风暴")
        world.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.45f)
        world.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.65f)
        drawTornadoBurst(center, radius)

        for (mob in mobs) {
            val pull = normalizedFlatVector(mob.location, center).multiply(0.55)
            pull.y = 0.85
            mob.velocity = mob.velocity.add(pull)
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 300, 1, false, false, true))
            applyEnhancedVulnerability(
                plugin,
                mob,
                META_EARTH_VULN_UNTIL,
                META_EARTH_VULN_MULT,
                META_EARTH_VULN_PARTICLE_TASK,
                0.25,
                15000L,
                Color.fromRGB(180, 135, 70)
            )
        }

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks >= 300) {
                    cancel()
                    return
                }
                drawStorm(center, radius, ticks)
                ticks += 10
            }
        }.runTaskTimer(plugin, 0L, 10L)

        return true
    }

    private fun drawStorm(center: org.bukkit.Location, radius: Double, ticks: Int) {
        val world = center.world ?: return
        val phase = ticks * 0.2
        
        // Draw a double-helix rising tornado (very gorgeous, few particles)
        for (i in 0 until 8) {
            val h = i * 0.4
            val currentRadius = (radius * 0.3) + (h * 0.5) // expands as it goes up
            for (arm in 0 until 2) {
                val angle = phase + h * 1.2 + (arm * Math.PI)
                val loc = center.clone().add(cos(angle) * currentRadius, h, sin(angle) * currentRadius)
                world.spawnParticle(Particle.BLOCK, loc, 1, 0.2, 0.1, 0.2, 0.0, Material.COARSE_DIRT.createBlockData())
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 1, 0.1, 0.1, 0.1, 0.02)
            }
        }

        // Outer rotating ring
        for (i in 0 until 12) {
            val angle = (Math.PI * 2.0 / 12.0) * i - phase * 0.4
            val loc = center.clone().add(cos(angle) * radius, 0.15, sin(angle) * radius)
            world.spawnParticle(Particle.DUST, loc, 1, Particle.DustOptions(Color.fromRGB(200, 140, 60), 1.1f))
            if (i % 4 == 0) world.spawnParticle(Particle.FALLING_DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, Material.SAND.createBlockData())
        }
    }

    private fun drawTornadoBurst(center: org.bukkit.Location, radius: Double) {
        val world = center.world ?: return
        for (layer in 0 until 9) {
            val y = layer * 0.38
            val currentRadius = 0.6 + layer * 0.32
            for (i in 0 until 14) {
                val angle = (Math.PI * 2.0 / 14.0) * i + layer * 0.65
                val loc = center.clone().add(cos(angle) * currentRadius, y, sin(angle) * currentRadius)
                world.spawnParticle(Particle.CLOUD, loc, 1, 0.04, 0.04, 0.04, 0.02)
                world.spawnParticle(Particle.BLOCK, loc, 1, 0.08, 0.05, 0.08, 0.0, Material.COARSE_DIRT.createBlockData())
                if (i % 3 == 0) {
                    world.spawnParticle(Particle.FALLING_DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, Material.SAND.createBlockData())
                }
            }
        }

        for (i in 0 until 24) {
            val angle = Math.PI * 2.0 * i / 24.0
            val loc = center.clone().add(cos(angle) * radius * 0.55, 0.15, sin(angle) * radius * 0.55)
            world.spawnParticle(Particle.DUST, loc, 1, 0.02, 0.02, 0.02, 0.0, Particle.DustOptions(Color.fromRGB(220, 170, 80), 1.25f))
        }
    }
}
