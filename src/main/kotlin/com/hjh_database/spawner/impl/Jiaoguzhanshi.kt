package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Jiaoguzhanshi(
    private val plugin: Hjh_database,
    private val boss: LivingEntity
) : Listener {
    private var isChanneling = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        BossTargetingUtil.start(plugin, boss, radius = 48.0, chaseSpeed = 0.22, minChaseDistance = 5.0) {
            !isChanneling && !boss.hasPotionEffect(PotionEffectType.SLOWNESS)
        }
        scheduleNextSkill(INITIAL_DELAY_TICKS)

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    HandlerList.unregisterAll(this@Jiaoguzhanshi)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBossDamage(event: EntityDamageEvent) {
        if (event.entity == boss && isChanneling) {
            event.isCancelled = true
        }
    }

    private fun scheduleNextSkill(delayTicks: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) return
                startChanneling()
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun startChanneling() {
        val targets = nearbyPlayers(SKILL_RANGE)
        if (targets.isEmpty()) {
            scheduleNextSkill(RETRY_DELAY_TICKS)
            return
        }

        isChanneling = true
        freezeBoss(true)
        warnPlayers(targets)
        boss.world.playSound(boss.location, Sound.ENTITY_WITHER_SPAWN, 0.75f, 1.45f)

        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    isChanneling = false
                    cancel()
                    return
                }

                if (ticks >= CHANNEL_TICKS) {
                    isChanneling = false
                    freezeBoss(false)
                    summonSwords(nearbyPlayers(SKILL_RANGE))
                    scheduleNextSkill(SKILL_COOLDOWN_TICKS)
                    cancel()
                    return
                }

                boss.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
                spawnChannelParticles()
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun warnPlayers(players: List<Player>) {
        players.forEach { player ->
            player.sendMessage("§c焦骨战士正化瘴气为剑以此发动攻击，注意躲避！")
            player.sendMessage("§c注意！凝聚期间，焦骨战士将不受到任何伤害！")
            player.playSound(player.location, Sound.ENTITY_WITHER_AMBIENT, 0.9f, 0.65f)
        }
    }

    private fun summonSwords(players: List<Player>) {
        if (players.isEmpty()) return
        boss.world.playSound(boss.location, Sound.ITEM_TRIDENT_THUNDER, 0.75f, 0.65f)
        players.forEach { player ->
            val impact = player.location.clone()
            impact.y = findGroundY(impact) + 0.05
            createFallingSword(player, impact)
        }
    }

    private fun createFallingSword(sourcePlayer: Player, impact: Location) {
        val world = impact.world ?: return
        val start = impact.clone().add(0.0, 6.0, 0.0)
        val display = world.spawn(start, ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.STONE_SWORD))
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD)
            entity.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                AxisAngle4f(PI.toFloat(), 1f, 0f, 0f),
                Vector3f(2.8f, 2.8f, 2.8f),
                AxisAngle4f(0f, 0f, 1f, 0f)
            )
        }

        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (boss.isDead || !boss.isValid || !display.isValid) {
                    display.remove()
                    cancel()
                    return
                }

                if (ticks >= FALL_TICKS) {
                    display.remove()
                    strike(sourcePlayer, impact)
                    cancel()
                    return
                }

                val progress = ticks.toDouble() / FALL_TICKS
                val y = start.y - 5.75 * progress
                display.teleport(Location(world, impact.x, y, impact.z, display.location.yaw + 24f, 0f))
                spawnSwordTrail(display.location)
                if (ticks % 4 == 0) spawnWarningCircle(impact)
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun strike(sourcePlayer: Player, impact: Location) {
        val world = impact.world ?: return
        world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 1.15f, 0.45f)
        world.playSound(impact, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.85f, 0.75f)
        world.spawnParticle(Particle.EXPLOSION, impact.clone().add(0.0, 0.2, 0.0), 2, 0.35, 0.05, 0.35, 0.0)
        world.spawnParticle(Particle.SMOKE, impact.clone().add(0.0, 0.2, 0.0), 34, 1.25, 0.25, 1.25, 0.03)

        val radiusSquared = IMPACT_RADIUS * IMPACT_RADIUS
        val targets = world.getNearbyEntities(impact, IMPACT_RADIUS, 2.4, IMPACT_RADIUS)
            .filterIsInstance<Player>()
            .filter { isValidTarget(it) && it.location.distanceSquared(impact) <= radiusSquared }

        targets.forEach { player ->
            val miasmaRatio = plugin.baihuMiasmaManager.getMiasma(player) / 1000.0
            val damage = 40.0 + 20.0 * miasmaRatio
            player.noDamageTicks = 0
            player.damage(damage, boss)
        }
    }

    private fun nearbyPlayers(radius: Double): List<Player> {
        val radiusSquared = radius * radius
        return boss.world.players
            .filter { isValidTarget(it) }
            .filter { it.location.distanceSquared(boss.location) <= radiusSquared }
    }

    private fun isValidTarget(player: Player): Boolean {
        return player.isOnline &&
            !player.isDead &&
            player.gameMode != GameMode.SPECTATOR &&
            player.gameMode != GameMode.CREATIVE
    }

    private fun freezeBoss(frozen: Boolean) {
        if (boss is Mob) {
            boss.setAI(!frozen)
        }
        boss.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
        if (frozen) {
            val duration = (CHANNEL_TICKS + 20L).toInt()
            boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, 255, false, false))
            boss.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, duration, 255, false, false))
        } else {
            boss.removePotionEffect(PotionEffectType.SLOWNESS)
            boss.removePotionEffect(PotionEffectType.WEAKNESS)
        }
    }

    private fun spawnChannelParticles() {
        val center = boss.location.clone().add(0.0, 1.1, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(16, 16, 18), 1.15f)
        for (i in 0 until 8) {
            val angle = (boss.ticksLived * 0.18) + i * (2.0 * PI / 8.0)
            val radius = 1.05 + sin((boss.ticksLived + i) * 0.12) * 0.25
            val loc = center.clone().add(cos(angle) * radius, 0.18 * sin(angle * 2.0), sin(angle) * radius)
            boss.world.spawnParticle(Particle.DUST, loc, 1, 0.03, 0.03, 0.03, 0.0, dust)
        }
        if (boss.ticksLived % 8 == 0) {
            boss.world.playSound(boss.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.35f, 0.65f)
        }
    }

    private fun spawnSwordTrail(location: Location) {
        val dust = Particle.DustOptions(Color.fromRGB(4, 4, 5), 1.25f)
        location.world?.spawnParticle(Particle.DUST, location, 4, 0.18, 0.35, 0.18, 0.0, dust)
        location.world?.spawnParticle(Particle.SMOKE, location, 2, 0.12, 0.24, 0.12, 0.01)
    }

    private fun spawnWarningCircle(center: Location) {
        val world = center.world ?: return
        val dust = Particle.DustOptions(Color.fromRGB(16, 16, 18), 1.0f)
        val points = 24
        for (i in 0 until points) {
            val angle = 2.0 * PI * i / points
            val loc = center.clone().add(cos(angle) * IMPACT_RADIUS, 0.06, sin(angle) * IMPACT_RADIUS)
            world.spawnParticle(Particle.DUST, loc, 1, 0.01, 0.01, 0.01, 0.0, dust)
        }
    }

    private fun findGroundY(location: Location): Double {
        val world = location.world ?: return location.y
        var y = location.blockY
        while (y > world.minHeight) {
            val block = world.getBlockAt(location.blockX, y - 1, location.blockZ)
            if (!block.isPassable) return y.toDouble()
            y--
        }
        return location.y
    }

    companion object {
        private const val INITIAL_DELAY_TICKS = 7L * 20L
        private const val SKILL_COOLDOWN_TICKS = 15L * 20L
        private const val RETRY_DELAY_TICKS = 3L * 20L
        private const val CHANNEL_TICKS = 3L * 20L
        private const val FALL_TICKS = 20
        private const val SKILL_RANGE = 32.0
        private const val IMPACT_RADIUS = 3.0
    }
}
