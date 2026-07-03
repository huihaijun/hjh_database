package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobFactory
import com.hjh_database.spawner.MobDefinition
import com.hjh_database.spawner.MobRegistry
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Slime
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.persistence.PersistentDataType
import kotlin.math.min

class CustomMagmaCubeListener(private val plugin: Hjh_database) : Listener {

    private val splitGenerationKey = NamespacedKey(plugin, "hjh_slime_split_generation")
    private val pendingSplits = ArrayList<SplitSnapshot>()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun respectVanillaDamageFrames(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (player.hasMetadata("HJH_MAGIC_DAMAGE")) return

        val attacker = event.damager as? LivingEntity ?: return
        if (!isRegisteredMagmaCubeMob(attacker)) return

        if (player.noDamageTicks > player.maximumNoDamageTicks / 2) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun restoreVanillaDamageFrames(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (player.hasMetadata("HJH_MAGIC_DAMAGE")) return

        val attacker = event.damager as? LivingEntity ?: return
        if (!isRegisteredMagmaCubeMob(attacker)) return

        player.noDamageTicks = player.maximumNoDamageTicks
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCustomSlimeSplit(event: SlimeSplitEvent) {
        val parent = event.entity as? Slime ?: return
        val mobId = parent.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) ?: return
        val def = MobRegistry.get(mobId) ?: return
        if (def.type != EntityType.SLIME && def.type != EntityType.MAGMA_CUBE) return

        val pdc = parent.persistentDataContainer
        val snapshot = SplitSnapshot(
            location = parent.location.clone(),
            type = parent.type,
            mobId = mobId,
            remaining = event.count,
            parentSize = parent.size,
            generation = (pdc.get(splitGenerationKey, PersistentDataType.INTEGER) ?: 0) + 1,
            maxHealth = ((parent.getAttribute(Attribute.MAX_HEALTH)?.value ?: def.health) * 0.5).coerceAtLeast(1.0),
            damage = ((pdc.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: def.damage) * 0.5).coerceAtLeast(0.0),
            armor = ((pdc.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE) ?: def.armor) * 0.5).coerceAtLeast(0.0),
            speed = ((parent.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: def.speed) * 0.5).coerceAtLeast(0.03)
        )

        pendingSplits.add(snapshot)
        for (delay in 1L..SPLIT_REPAIR_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                applySplitSnapshot(snapshot)
                if (delay == SPLIT_REPAIR_TICKS) {
                    pendingSplits.remove(snapshot)
                }
            }, delay)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSplitChildSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.SLIME_SPLIT) return
        val child = event.entity as? Slime ?: return
        val snapshot = findPendingSplitFor(child)
        if (snapshot == null) {
            applyInferredSplitAttributes(child)
            return
        }

        applyScaledChildAttributes(child, snapshot)
        snapshot.remaining--
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun suppressSplitChildRewards(event: EntityDeathEvent) {
        val entity = event.entity as? Slime ?: return
        val pdc = entity.persistentDataContainer
        if (!pdc.has(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE) && inferDefinition(entity) == null) return

        event.drops.clear()
        event.droppedExp = 0
        entity.removeScoreboardTag("panling")
        entity.removeScoreboardTag("monster")
        pdc.remove(MobFactory.KEY_MOB_ID)
    }

    private fun applySplitSnapshot(snapshot: SplitSnapshot) {
        val world = snapshot.location.world ?: return
        val radius = snapshot.parentSize.coerceAtLeast(2).toDouble() + 3.0
        val children = world.getNearbyEntities(snapshot.location, radius, radius, radius)
            .asSequence()
            .filterIsInstance<Slime>()
            .filter { it.type == snapshot.type }
            .filter { it.size < snapshot.parentSize }
            .filter { it.ticksLived <= SPLIT_REPAIR_TICKS + 5 }
            .filter {
                val generation = it.persistentDataContainer.get(splitGenerationKey, PersistentDataType.INTEGER) ?: 0
                generation < snapshot.generation
            }
            .sortedBy { it.location.distanceSquared(snapshot.location) }
            .take(snapshot.remaining.coerceAtLeast(0))
            .toList()

        for (child in children) {
            applyScaledChildAttributes(child, snapshot)
            snapshot.remaining--
        }
    }

    private fun applyInferredSplitAttributes(child: Slime) {
        val def = inferDefinition(child) ?: return
        val baseSize = 4.0
        val scale = (child.size.coerceAtLeast(1).toDouble() / baseSize).coerceIn(0.25, 1.0)

        val snapshot = SplitSnapshot(
            location = child.location.clone(),
            type = child.type,
            mobId = def.id,
            remaining = 1,
            parentSize = child.size + 1,
            generation = 1,
            maxHealth = (def.health * scale).coerceAtLeast(1.0),
            damage = (def.damage * scale).coerceAtLeast(0.0),
            armor = (def.armor * scale).coerceAtLeast(0.0),
            speed = (def.speed * scale).coerceAtLeast(0.03)
        )
        applyScaledChildAttributes(child, snapshot)
    }

    private fun inferDefinition(entity: Slime): MobDefinition? {
        val mobId = entity.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING)
        if (mobId != null) {
            return MobRegistry.get(mobId)?.takeIf { it.type == entity.type }
        }

        val customName = entity.customName ?: return null
        return MobRegistry.getAllIds()
            .asSequence()
            .mapNotNull { MobRegistry.get(it) }
            .firstOrNull { def ->
                def.type == entity.type &&
                    ChatColor.translateAlternateColorCodes('&', def.name) == customName
            }
    }

    private fun findPendingSplitFor(child: Slime): SplitSnapshot? {
        return pendingSplits
            .asSequence()
            .filter { it.remaining > 0 }
            .filter { it.type == child.type }
            .filter { child.size < it.parentSize }
            .filter { child.world == it.location.world }
            .filter { child.location.distanceSquared(it.location) <= splitSearchRadiusSquared(it) }
            .minByOrNull { child.location.distanceSquared(it.location) }
    }

    private fun splitSearchRadiusSquared(snapshot: SplitSnapshot): Double {
        val radius = snapshot.parentSize.coerceAtLeast(2).toDouble() + 3.0
        return radius * radius
    }

    private fun applyScaledChildAttributes(child: Slime, snapshot: SplitSnapshot) {
        child.addScoreboardTag("panling")
        child.addScoreboardTag("monster")
        child.customName = MobRegistry.get(snapshot.mobId)?.name
            ?.let { org.bukkit.ChatColor.translateAlternateColorCodes('&', it) }
        child.isCustomNameVisible = child.customName != null

        child.getAttribute(Attribute.MAX_HEALTH)?.let { maxHealth ->
            maxHealth.modifiers.toList().forEach { modifier ->
                maxHealth.removeModifier(modifier)
            }
            maxHealth.baseValue = snapshot.maxHealth
            child.health = min(snapshot.maxHealth, maxHealth.value)
        }
        child.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = snapshot.speed
        child.getAttribute(Attribute.ARMOR)?.baseValue = 0.0

        val pdc = child.persistentDataContainer
        pdc.set(MobFactory.KEY_MOB_ID, PersistentDataType.STRING, snapshot.mobId)
        pdc.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, snapshot.damage)
        pdc.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, snapshot.armor)
        pdc.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
        pdc.set(splitGenerationKey, PersistentDataType.INTEGER, snapshot.generation)
    }

    private fun isRegisteredMagmaCubeMob(entity: LivingEntity): Boolean {
        if (entity.type != EntityType.MAGMA_CUBE) return false

        if (entity.persistentDataContainer.has(MobFactory.KEY_MOB_ID, PersistentDataType.STRING)) return true
        return (entity as? Slime)?.let { inferDefinition(it) }?.type == EntityType.MAGMA_CUBE
    }

    private data class SplitSnapshot(
        val location: Location,
        val type: EntityType,
        val mobId: String,
        var remaining: Int,
        val parentSize: Int,
        val generation: Int,
        val maxHealth: Double,
        val damage: Double,
        val armor: Double,
        val speed: Double
    )

    private companion object {
        private const val SPLIT_REPAIR_TICKS = 10L
    }
}
