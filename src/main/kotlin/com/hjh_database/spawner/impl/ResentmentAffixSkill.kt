package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobAffixSupport
import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

object ResentmentAffixSkill : Listener {
    private data class ResentmentStatus(
        val entity: LivingEntity,
        val baseDamage: Double,
        var stacks: Int,
        var expiresAt: Long
    )

    private lateinit var plugin: Hjh_database
    private lateinit var speedModifierKey: NamespacedKey
    private val statuses = mutableMapOf<UUID, ResentmentStatus>()
    private var updateTask: BukkitTask? = null
    private var initialized = false
    private var updateCycles = 0

    fun init(plugin: Hjh_database) {
        if (initialized) shutdown()
        this.plugin = plugin
        speedModifierKey = NamespacedKey(plugin, "resentment_speed")
        initialized = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateStatuses()
        }, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS)
    }

    fun shutdown() {
        if (!initialized) return
        updateTask?.cancel()
        updateTask = null
        statuses.values.forEach(::clearStatus)
        statuses.clear()
        HandlerList.unregisterAll(this)
        initialized = false
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAffixMobDeath(event: EntityDeathEvent) {
        val dead = event.entity
        if (!MobAffixSupport.hasAffix(dead, MobAffix.RESENTMENT)) return

        statuses.remove(dead.uniqueId)
        val origin = dead.location.clone().add(0.0, dead.height * 0.55, 0.0)
        val nearby = dead.world.getNearbyEntities(dead.location, TRANSFER_RANGE, TRANSFER_RANGE, TRANSFER_RANGE)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != dead.uniqueId && it.isValid && !it.isDead }
            .filter { it.location.distanceSquared(dead.location) <= TRANSFER_RANGE_SQUARED }
            .filter { MobAffixSupport.hasAffix(it, MobAffix.RESENTMENT) }
            .toList()

        nearby.forEach { recipient ->
            gainResentment(recipient)
            drawTransferParticles(origin, recipient.location.clone().add(0.0, recipient.height + 0.2, 0.0))
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val iterator = statuses.iterator()
        while (iterator.hasNext()) {
            val status = iterator.next().value
            if (status.entity.chunk == event.chunk) {
                clearStatus(status)
                iterator.remove()
            }
        }
    }

    private fun gainResentment(entity: LivingEntity) {
        val now = System.currentTimeMillis()
        var status = statuses[entity.uniqueId]
        if (status != null && status.expiresAt <= now) {
            clearStatus(status)
            statuses.remove(entity.uniqueId)
            status = null
        }

        if (status == null) {
            val baseDamage = entity.persistentDataContainer.get(
                MobFactory.KEY_CUSTOM_DAMAGE,
                PersistentDataType.DOUBLE
            ) ?: return
            status = ResentmentStatus(entity, baseDamage, 0, now + DURATION_MILLIS)
            statuses[entity.uniqueId] = status
        }

        if (status.stacks < MAX_STACKS) {
            status.stacks++
            healMissingHealth(entity)
            applyStats(status)
        }
        status.expiresAt = now + DURATION_MILLIS
    }

    private fun healMissingHealth(entity: LivingEntity) {
        val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        val missingHealth = (maxHealth - entity.health).coerceAtLeast(0.0)
        if (missingHealth <= 0.0) return
        entity.health = (entity.health + missingHealth * MISSING_HEALTH_HEAL_RATIO)
            .coerceAtMost(maxHealth)
    }

    private fun applyStats(status: ResentmentStatus) {
        val entity = status.entity
        entity.persistentDataContainer.set(
            MobFactory.KEY_CUSTOM_DAMAGE,
            PersistentDataType.DOUBLE,
            status.baseDamage * (1.0 + DAMAGE_BONUS_PER_STACK * status.stacks)
        )

        val speed = entity.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        speed.getModifier(speedModifierKey)?.let(speed::removeModifier)
        speed.addTransientModifier(
            AttributeModifier(
                speedModifierKey,
                SPEED_BONUS_PER_STACK * status.stacks,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1
            )
        )
    }

    private fun updateStatuses() {
        val now = System.currentTimeMillis()
        updateCycles++
        val showParticles = updateCycles % PARTICLE_INTERVAL_CYCLES == 0
        val iterator = statuses.iterator()

        while (iterator.hasNext()) {
            val status = iterator.next().value
            val entity = status.entity
            if (!entity.isValid || entity.isDead) {
                iterator.remove()
                continue
            }
            if (status.expiresAt <= now) {
                clearStatus(status)
                iterator.remove()
                continue
            }
            if (showParticles) {
                spawnStackParticles(entity, status.stacks)
            }
        }
    }

    private fun clearStatus(status: ResentmentStatus) {
        val entity = status.entity
        if (!entity.isValid) return
        entity.persistentDataContainer.set(
            MobFactory.KEY_CUSTOM_DAMAGE,
            PersistentDataType.DOUBLE,
            status.baseDamage
        )
        val speed = entity.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        speed.getModifier(speedModifierKey)?.let(speed::removeModifier)
    }

    private fun spawnStackParticles(entity: LivingEntity, stacks: Int) {
        val color = when (stacks) {
            1 -> Color.fromRGB(55, 215, 75)
            2 -> Color.fromRGB(255, 210, 35)
            else -> Color.fromRGB(235, 45, 45)
        }
        val location = entity.location.clone().add(0.0, entity.height + 0.3, 0.0)
        entity.world.spawnParticle(
            Particle.DUST,
            location,
            5,
            0.28,
            0.14,
            0.28,
            0.0,
            Particle.DustOptions(color, 1.05f)
        )
        entity.world.spawnParticle(Particle.SOUL, location, 1, 0.12, 0.08, 0.12, 0.01)
    }

    private fun drawTransferParticles(from: org.bukkit.Location, to: org.bukkit.Location) {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (distance <= 0.01) return

        val direction = delta.normalize()
        val steps = (distance / TRANSFER_PARTICLE_SPACING).toInt().coerceAtLeast(1)
        val dust = Particle.DustOptions(Color.fromRGB(150, 45, 185), 1.1f)
        for (step in 0..steps) {
            val point = from.clone().add(direction.clone().multiply(step * TRANSFER_PARTICLE_SPACING))
            from.world?.spawnParticle(Particle.DUST, point, 2, 0.04, 0.04, 0.04, 0.0, dust)
            if (step % 2 == 0) {
                from.world?.spawnParticle(Particle.WITCH, point, 1, 0.03, 0.03, 0.03, 0.0)
            }
        }
        to.world?.spawnParticle(Particle.SOUL, to, 10, 0.3, 0.4, 0.3, 0.035)
        to.world?.spawnParticle(Particle.DUST, to, 8, 0.3, 0.4, 0.3, 0.0, dust)
    }

    private const val MAX_STACKS = 3
    private const val TRANSFER_RANGE = 10.0
    private const val TRANSFER_RANGE_SQUARED = TRANSFER_RANGE * TRANSFER_RANGE
    private const val DURATION_MILLIS = 12_000L
    private const val MISSING_HEALTH_HEAL_RATIO = 0.10
    private const val DAMAGE_BONUS_PER_STACK = 0.20
    private const val SPEED_BONUS_PER_STACK = 0.15
    private const val UPDATE_INTERVAL_TICKS = 10L
    private const val PARTICLE_INTERVAL_CYCLES = 2
    private const val TRANSFER_PARTICLE_SPACING = 0.5
}
