package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.EnhancedElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class EnhancedFireSkill(private val plugin: Hjh_database) : EnhancedElementSkill {
    override fun cast(player: Player, data: PlayerData): Boolean {
        val targets = findTargets(player)
        if (targets.isEmpty()) return false
        val damageMultiplier = plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
        val initialDamage = data.zfStr * 6.0 * damageMultiplier
        val tickDamage = data.zfStr * 1.5 * damageMultiplier

        broadcastOriginMessage(player, "§c", "火元素", "炎蝶之舞")
        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.2f)
        player.world.playSound(player.location, Sound.BLOCK_FIRE_AMBIENT, 0.6f, 1.7f)

        val zones = targets.map { it.location.clone() }
        for (target in targets) {
            enhancedMagicDamage(plugin, player, target, initialDamage, FormationElement.FIRE)
            target.fireTicks = max(target.fireTicks, 60)
            target.world.spawnParticle(Particle.FLAME, target.location.add(0.0, target.height * 0.5, 0.0), 18, 0.35, 0.45, 0.35, 0.04)
            target.world.spawnParticle(Particle.LAVA, target.location.add(0.0, 0.15, 0.0), 6, 0.35, 0.1, 0.35, 0.0)
        }

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks >= 100 || !player.isOnline) {
                    cancel()
                    return
                }

                val hitThisTick = HashSet<LivingEntity>()
                for (zone in zones) {
                    drawButterflyCircle(zone, ticks)
                    for (entity in zone.world!!.getNearbyEntities(zone, 2.2, 1.5, 2.2)) {
                        val mob = entity as? LivingEntity ?: continue
                        if (!isEnhancedMonster(mob) || !hitThisTick.add(mob)) continue
                        mob.fireTicks = max(mob.fireTicks, 40)
                        enhancedMagicDamage(plugin, player, mob, tickDamage, FormationElement.FIRE)
                    }
                }
                ticks += 10
            }
        }.runTaskTimer(plugin, 0L, 10L)

        return true
    }

    private fun findTargets(player: Player): List<LivingEntity> {
        val loc = player.eyeLocation
        val dir = loc.direction.normalize()
        return player.world.getNearbyEntities(player.location, 10.0, 10.0, 10.0).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isEnhancedMonster(it) }
            .filter {
                val toEntity = it.location.add(0.0, it.height * 0.5, 0.0).toVector().subtract(loc.toVector())
                toEntity.lengthSquared() <= 100.0 && dir.dot(toEntity.normalize()) > 0.25
            }
            .sortedBy { it.location.distanceSquared(player.location) }
            .take(5)
            .toList()
    }

    private fun drawButterflyCircle(center: Location, ticks: Int) {
        val world = center.world ?: return
        val phase = ticks * 0.15
        for (butterfly in 0 until 3) {
            val rise = ((ticks / 10 + butterfly * 2) % 10) / 10.0
            val y = 0.15 + rise * 2.2
            val orbit = phase + butterfly * (Math.PI * 2.0 / 3.0)
            val core = center.clone().add(cos(orbit) * 1.15, y, sin(orbit) * 1.15)

            world.spawnParticle(Particle.FLAME, core, 2, 0.04, 0.04, 0.04, 0.02)
            world.spawnParticle(Particle.DUST, core, 1, 0.02, 0.02, 0.02, 0.0, Particle.DustOptions(Color.fromRGB(255, 80, 20), 1.1f))
            for (wing in -1..1 step 2) {
                for (i in 0 until 4) {
                    val t = i / 3.0
                    val wingLoc = core.clone().add(
                        cos(orbit + wing * 1.15) * (0.18 + t * 0.45),
                        sin(t * Math.PI) * 0.22,
                        sin(orbit + wing * 1.15) * (0.18 + t * 0.45)
                    )
                    world.spawnParticle(Particle.FLAME, wingLoc, 1, 0.01, 0.01, 0.01, 0.015)
                    if (i == 2) {
                        world.spawnParticle(Particle.LAVA, wingLoc, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }
        }

        for (i in 0 until 16) {
            val angle = (Math.PI * 2.0 / 16.0) * i - phase * 0.65
            val loc = center.clone().add(cos(angle) * 2.1, 0.08, sin(angle) * 2.1)
            world.spawnParticle(Particle.FLAME, loc, 1, 0.02, 0.01, 0.02, 0.0)
            if (i % 4 == 0) {
                world.spawnParticle(Particle.DUST, loc, 1, 0.01, 0.01, 0.01, 0.0, Particle.DustOptions(Color.fromRGB(255, 115, 25), 0.9f))
            }
        }
        if (ticks % 20 == 0) world.spawnParticle(Particle.LAVA, center.clone().add(0.0, 0.5, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
    }
}
