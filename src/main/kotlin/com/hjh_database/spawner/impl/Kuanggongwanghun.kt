package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobFactory
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class Kuanggongwanghun(
    private val plugin: Hjh_database,
    private val boss: LivingEntity
) : Listener {
    private val baseSpeed = boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: 0.25
    private val baseDamage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 25.0
    private var enraged = false
    private var awakened = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        BossTargetingUtil.start(plugin, boss, radius = 48.0, chaseSpeed = 0.22, minChaseDistance = 5.0) {
            true
        }
        scheduleInitialRage()
        startFireMonitor()

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    restoreBaseStats()
                    HandlerList.unregisterAll(this@Kuanggongwanghun)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBossDamage(event: EntityDamageEvent) {
        if (event.entity == boss && enraged) {
            event.damage *= DAMAGE_TAKEN_MULTIPLIER
        }
    }

    private fun scheduleInitialRage() {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) return
                awakened = true
                if (isNearBurningTownFire()) {
                    soothe()
                } else {
                    enrage(dealBurstDamage = true)
                }
            }
        }.runTaskLater(plugin, INITIAL_DELAY_TICKS)
    }

    private fun startFireMonitor() {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }
                if (!awakened) return

                if (isNearBurningTownFire()) {
                    if (enraged) soothe()
                } else if (!enraged) {
                    enrage(dealBurstDamage = false)
                }
                spawnAmbientParticles()
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    private fun enrage(dealBurstDamage: Boolean) {
        enraged = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseSpeed * ENRAGE_MULTIPLIER
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, baseDamage * ENRAGE_MULTIPLIER)
        boss.removePotionEffect(PotionEffectType.SLOWNESS)
        boss.world.playSound(boss.location, Sound.ENTITY_VINDICATOR_CELEBRATE, 1.0f, 0.55f)

        val players = nearbyPlayers(MESSAGE_RADIUS)
        players.forEach {
            it.sendMessage("§c矿工的亡魂变得暴动不安，看样子是无尽的黑暗笼罩着他……")
            it.sendMessage("§6把他引到一些火光处，试着平复一下他！")
            it.playSound(it.location, Sound.ENTITY_WITHER_AMBIENT, 0.65f, 0.7f)
        }

        if (dealBurstDamage) {
            players.forEach { dealMagicDamage(it, INITIAL_MAGIC_DAMAGE) }
        }
    }

    private fun soothe() {
        enraged = false
        restoreBaseStats()
        boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, SOOTHE_SLOW_TICKS, 2, false, true, true))
        boss.world.playSound(boss.location, Sound.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 1.2f)
        boss.world.spawnParticle(Particle.SOUL_FIRE_FLAME, boss.location.clone().add(0.0, 1.0, 0.0), 28, 0.75, 0.45, 0.75, 0.03)

        nearbyPlayers(MESSAGE_RADIUS).forEach {
            it.sendMessage("§a火光的温暖安抚了亡魂暴动的内心……")
            it.playSound(it.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.75f, 1.45f)
        }
    }

    private fun restoreBaseStats() {
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseSpeed
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, baseDamage)
    }

    private fun isNearBurningTownFire(): Boolean {
        return plugin.baihuTownFireManager.isNearBurningFire(boss.location, TOWN_FIRE_SOOTHE_RADIUS)
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

    private fun dealMagicDamage(player: Player, damage: Double) {
        player.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        val previousMaximum = player.maximumNoDamageTicks
        player.noDamageTicks = 0
        player.maximumNoDamageTicks = 0
        try {
            player.damage(damage, boss)
        } finally {
            if (player.hasMetadata("HJH_MAGIC_DAMAGE")) {
                player.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            player.noDamageTicks = 0
            player.maximumNoDamageTicks = previousMaximum
        }
    }

    private fun spawnAmbientParticles() {
        if (enraged) {
            boss.world.spawnParticle(
                Particle.DUST,
                boss.location.clone().add(0.0, 1.0, 0.0),
                6,
                0.42,
                0.55,
                0.42,
                0.0,
                Particle.DustOptions(Color.fromRGB(8, 8, 10), 1.1f)
            )
        } else {
            boss.world.spawnParticle(Particle.SOUL, boss.location.clone().add(0.0, 0.85, 0.0), 4, 0.3, 0.35, 0.3, 0.01)
        }
    }

    companion object {
        private const val INITIAL_DELAY_TICKS = 3L * 20L
        private const val ENRAGE_MULTIPLIER = 1.3
        private const val DAMAGE_TAKEN_MULTIPLIER = 0.7
        private const val INITIAL_MAGIC_DAMAGE = 15.0
        private const val MESSAGE_RADIUS = 10.0
        private const val TOWN_FIRE_SOOTHE_RADIUS = 10.0
        private const val SOOTHE_SLOW_TICKS = 10 * 20
    }
}
