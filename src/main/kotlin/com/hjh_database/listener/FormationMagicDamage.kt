package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue

/**
 * 术士/医师基于阵法强度造成的伤害入口。
 *
 * 这类伤害不会进入玩家暴击判定，并在 [CombatListener] 中获得 50% 基础法穿；
 * 术士与医师面板中的 critChance 仅作为额外法穿率使用。
 * 独立 metadata 可以避免影响怪物技能及战士/弓箭手原有的完全穿甲技能。
 */
object FormationMagicDamage {
    const val METADATA = "HJH_FORMATION_DAMAGE"
    const val BASE_ARMOR_PENETRATION = 0.5

    fun deal(
        plugin: Hjh_database,
        attacker: Player,
        target: LivingEntity,
        amount: Double
    ): Double {
        if (amount <= 0.0 || !target.isValid || target.isDead) return 0.0

        val effectiveHealthBefore = target.health + target.absorptionAmount
        val previousMaximum = target.maximumNoDamageTicks
        target.noDamageTicks = 0
        target.maximumNoDamageTicks = 0
        target.setMetadata(METADATA, FixedMetadataValue(plugin, amount))
        try {
            target.damage(amount, attacker)
        } finally {
            if (target.hasMetadata(METADATA)) {
                target.removeMetadata(METADATA, plugin)
            }
            target.noDamageTicks = 0
            target.maximumNoDamageTicks = previousMaximum
        }

        val effectiveHealthAfter = if (target.isDead) 0.0 else target.health + target.absorptionAmount
        return (effectiveHealthBefore - effectiveHealthAfter).coerceIn(0.0, effectiveHealthBefore)
    }

    fun armorPenetration(data: PlayerData?): Double {
        val job = data?.job
        val bonusPenetration = if (job == 2 || job == 3) {
            data.critChance.coerceAtLeast(0.0)
        } else {
            0.0
        }
        return (BASE_ARMOR_PENETRATION + bonusPenetration).coerceIn(0.0, 1.0)
    }
}
