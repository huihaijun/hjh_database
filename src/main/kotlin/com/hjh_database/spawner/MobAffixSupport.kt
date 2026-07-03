package com.hjh_database.spawner

import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.persistence.PersistentDataType

object MobAffixSupport {
    private val affixKey = NamespacedKey.fromString("hjh_database:mob_affixes")!!

    fun hasAffix(entity: LivingEntity, affix: MobAffix): Boolean {
        val raw = entity.persistentDataContainer.get(affixKey, PersistentDataType.STRING) ?: return false
        return raw.split(',').any { it == affix.id }
    }

    fun realAttacker(damager: org.bukkit.entity.Entity): LivingEntity? {
        return when (damager) {
            is LivingEntity -> damager
            is Projectile -> damager.shooter as? LivingEntity
            else -> null
        }
    }
}
