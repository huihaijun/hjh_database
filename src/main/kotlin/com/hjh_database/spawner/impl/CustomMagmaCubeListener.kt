package com.hjh_database.spawner.impl

import com.hjh_database.spawner.MobFactory
import com.hjh_database.spawner.MobRegistry
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Slime
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.persistence.PersistentDataType

class CustomMagmaCubeListener : Listener {

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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onXiongshentaisuiSplit(event: SlimeSplitEvent) {
        val entity = event.entity as? LivingEntity ?: return
        if (isMobId(entity, "xiongshentaisui")) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onXiongshentaisuiDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Slime && isMobId(entity, "xiongshentaisui")) {
            entity.size = 1
        }
    }

    private fun isRegisteredMagmaCubeMob(entity: LivingEntity): Boolean {
        if (entity.type != EntityType.MAGMA_CUBE) return false

        val mobId = entity.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) ?: return false
        return MobRegistry.get(mobId)?.type == EntityType.MAGMA_CUBE
    }

    private fun isMobId(entity: LivingEntity, expectedMobId: String): Boolean {
        if (entity.type != EntityType.MAGMA_CUBE) return false
        return entity.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) == expectedMobId
    }
}
