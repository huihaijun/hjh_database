package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType

object BaihuEquipmentDamageTag {
    const val METADATA = "hjh_baihu_equipment_damage"

    fun markTarget(plugin: Hjh_database, target: LivingEntity) {
        target.setMetadata(METADATA, FixedMetadataValue(plugin, true))
    }

    fun clearTarget(plugin: Hjh_database, target: LivingEntity) {
        if (target.hasMetadata(METADATA)) {
            target.removeMetadata(METADATA, plugin)
        }
    }

    fun isMarked(plugin: Hjh_database, target: LivingEntity): Boolean {
        return target.getMetadata(METADATA).any { it.owningPlugin == plugin && it.asBoolean() }
    }

    fun markProjectile(plugin: Hjh_database, projectile: Entity) {
        projectile.persistentDataContainer.set(projectileKey(plugin), PersistentDataType.BYTE, 1)
    }

    fun isMarkedProjectile(plugin: Hjh_database, projectile: Entity): Boolean {
        return projectile.persistentDataContainer.has(projectileKey(plugin), PersistentDataType.BYTE)
    }

    private fun projectileKey(plugin: Hjh_database): NamespacedKey {
        return NamespacedKey(plugin, "baihu_equipment_damage")
    }
}
