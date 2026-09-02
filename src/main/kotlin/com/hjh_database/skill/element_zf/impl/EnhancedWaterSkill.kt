package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.EnhancedElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class EnhancedWaterSkill(private val plugin: Hjh_database) : EnhancedElementSkill {
    override fun cast(player: Player, data: PlayerData): Boolean {
        val center = getEnhancedSightLocation(player, 16.0, FluidCollisionMode.ALWAYS)
        val world = center.world ?: return false
        val damageMultiplier = plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
        val damage = data.zfStr * 2.5 * damageMultiplier
        val extraDamage = data.zfStr * damageMultiplier
        val directions = buildXDirections()
        val hit = LinkedHashSet<LivingEntity>()
        val centerTargets = HashSet<LivingEntity>()

        broadcastOriginMessage(player, "§b", "水元素", "晶凌封杀")
        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.55f)
        world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.8f)

        playIceShatterBurst(center)

        for (dir in directions) {
            var d = 0.0
            while (d <= 7.0) {
                val point = center.clone().add(dir.clone().multiply(d))
                world.spawnParticle(Particle.SNOWFLAKE, point, 5, 0.18, 0.08, 0.18, 0.025)
                world.spawnParticle(Particle.BLOCK, point, 2, 0.12, 0.08, 0.12, 0.0, Material.ICE.createBlockData())
                world.spawnParticle(Particle.DUST, point, 2, 0.08, 0.05, 0.08, 0.0, Particle.DustOptions(Color.fromRGB(135, 230, 255), 1.05f))
                if (((d * 10).toInt() % 5) == 0) {
                    world.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.05, 0.0, 0.05)
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.0, 0.0, 0.0, 0.01) // Adds an icy core look
                }
                if (d > 0.5 && ((d * 10).toInt() % 14) == 0) {
                    spawnIceShardDisplay(point.clone().add(0.0, 0.25, 0.0), dir)
                }

                for (entity in world.getNearbyEntities(point, 0.9, 1.2, 0.9)) {
                    val mob = entity as? LivingEntity ?: continue
                    if (isEnhancedMonster(mob)) hit.add(mob)
                }
                d += 0.7
            }
        }
        
        // Draw an outer connecting ring (octagon shape to match X directions)
        for (i in 0 until 32) {
            val angle = Math.PI * 2.0 * i / 32.0
            val ringLoc = center.clone().add(Math.cos(angle) * 7.0, 0.1, Math.sin(angle) * 7.0)
            world.spawnParticle(Particle.DUST, ringLoc, 1, Particle.DustOptions(Color.fromRGB(100, 200, 255), 0.8f))
            if (i % 4 == 0) world.spawnParticle(Particle.END_ROD, ringLoc, 1, 0.0, 0.0, 0.0, 0.0)
        }

        for (entity in world.getNearbyEntities(center, 1.5, 1.5, 1.5)) {
            val mob = entity as? LivingEntity ?: continue
            if (isEnhancedMonster(mob)) {
                hit.add(mob)
                centerTargets.add(mob)
            }
        }

        for (mob in hit) {
            val isCenter = centerTargets.contains(mob)
            enhancedMagicDamage(plugin, player, mob, damage + if (isCenter) extraDamage else 0.0, FormationElement.WATER)
            applyEnhancedVulnerability(
                plugin,
                mob,
                META_WATER_VULN_UNTIL,
                META_WATER_VULN_MULT,
                META_WATER_VULN_PARTICLE_TASK,
                if (isCenter) 0.5 else 0.3,
                8000L,
                Color.fromRGB(120, 225, 255)
            )
            if (isCenter) {
                mob.freezeTicks = kotlin.math.max(mob.freezeTicks, 30)
                mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 8, false, false, true))
                mob.world.spawnParticle(Particle.SNOWFLAKE, mob.location.add(0.0, mob.height * 0.55, 0.0), 20, 0.35, 0.5, 0.35, 0.0)
            }
        }
        return true
    }

    private fun playIceShatterBurst(center: org.bukkit.Location) {
        val world = center.world ?: return
        world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.SNOWFLAKE, center, 55, 1.6, 0.55, 1.6, 0.13)
        world.spawnParticle(Particle.BLOCK, center, 34, 1.15, 0.45, 1.15, 0.0, Material.PACKED_ICE.createBlockData())
        world.spawnParticle(Particle.DUST, center, 28, 1.25, 0.35, 1.25, 0.0, Particle.DustOptions(Color.fromRGB(150, 235, 255), 1.2f))

        for (i in 0 until 12) {
            val angle = PI * 2.0 * i / 12.0
            val loc = center.clone().add(cos(angle) * 1.2, 0.15 + sin(angle * 2.0) * 0.15, sin(angle) * 1.2)
            world.spawnParticle(Particle.END_ROD, loc, 1, 0.0, 0.02, 0.0, 0.06)
        }
    }

    private fun spawnIceShardDisplay(loc: org.bukkit.Location, dir: Vector) {
        val world = loc.world ?: return
        val display = world.spawn(loc, BlockDisplay::class.java) { entity ->
            entity.setBlock(Material.BLUE_ICE.createBlockData())
            entity.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                AxisAngle4f(0.8f, dir.x.toFloat(), 0.35f, dir.z.toFloat()),
                Vector3f(0.18f, 0.52f, 0.18f),
                AxisAngle4f(0f, 0f, 0f, 1f)
            )
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable { display.remove() }, 12L)
    }

    private fun buildXDirections(): List<Vector> {
        return listOf(
            Vector(1.0, 0.0, 1.0).normalize(),
            Vector(-1.0, 0.0, -1.0).normalize(),
            Vector(1.0, 0.0, -1.0).normalize(),
            Vector(-1.0, 0.0, 1.0).normalize()
        )
    }
}
