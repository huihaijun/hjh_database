package com.hjh_database.combat

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.impl.ElementFormationTierEffects
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue

/**
 * 怪物伤害的统一语义分类。
 *
 * 原版怪物近战/投射物默认就是普攻；插件手动制造的伤害必须明确标记为
 * [NORMAL_ATTACK_METADATA] 或沿用已有技能伤害标记。这样盾牌不需要依赖
 * DamageCause 猜测伤害来源，也不会把 Boss 技能误认为普通攻击。
 */
object MonsterDamageClassification {
    const val NORMAL_ATTACK_METADATA = "hjh_monster_normal_attack"
    const val SKILL_DAMAGE_METADATA = "hjh_monster_skill_damage"
    const val SYNTHETIC_HIT_METADATA = "hjh_monster_synthetic_hit"

    private val legacySkillMetadata = arrayOf(
        SKILL_DAMAGE_METADATA,
        ElementFormationTierEffects.MONSTER_SKILL_DAMAGE_METADATA,
        "HJH_MAGIC_DAMAGE",
        "HJH_ARMORED_MAGIC_DAMAGE",
        "hjh_physical_skill"
    )

    enum class Type {
        NORMAL_ATTACK,
        SKILL,
        OTHER
    }

    /**
     * 在一次同步 damage(...) 调用期间标记插件强制实现的怪物普攻。
     * finally 清理可避免标签泄漏到同一玩家之后受到的其他伤害。
     */
    inline fun <T> withNormalAttack(plugin: Hjh_database, target: LivingEntity, action: () -> T): T {
        target.setMetadata(NORMAL_ATTACK_METADATA, FixedMetadataValue(plugin, true))
        return try {
            action()
        } finally {
            target.removeMetadata(NORMAL_ATTACK_METADATA, plugin)
        }
    }

    /** 给会自行产生 EntityDamageByEntityEvent 的技能投射物使用。 */
    fun markSkillProjectile(plugin: Hjh_database, projectile: Projectile) {
        projectile.setMetadata(SKILL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
    }

    /**
     * 标记只负责碰撞检测、随后会被取消并改由手动 damage(...) 结算的投射物事件。
     * 否则盾牌会先处理一次即将取消的原版事件，再错过真正的手动伤害事件。
     */
    fun markSyntheticProjectile(plugin: Hjh_database, projectile: Projectile) {
        projectile.setMetadata(SYNTHETIC_HIT_METADATA, FixedMetadataValue(plugin, true))
    }

    fun classify(plugin: Hjh_database, event: EntityDamageByEntityEvent): Type {
        val direct = event.damager
        val causing = event.damageSource.causingEntity

        if (hasOwnedMetadata(plugin, direct, SYNTHETIC_HIT_METADATA)) return Type.OTHER

        // 显式 normal 优先于旧版 physical/magic 标记：部分强制普攻仍需经过自定义护甲公式。
        if (hasOwnedMetadata(plugin, event.entity, NORMAL_ATTACK_METADATA) ||
            hasOwnedMetadata(plugin, direct, NORMAL_ATTACK_METADATA) ||
            (causing != null && hasOwnedMetadata(plugin, causing, NORMAL_ATTACK_METADATA))
        ) return Type.NORMAL_ATTACK

        if (legacySkillMetadata.any { key ->
                hasOwnedMetadata(plugin, event.entity, key) ||
                    hasOwnedMetadata(plugin, direct, key) ||
                    (causing != null && hasOwnedMetadata(plugin, causing, key))
            }
        ) return Type.SKILL

        // Bukkit 的 DamageSource 能直接还原箭矢、火球等背后的真正攻击者。
        // 兼容少数未填 causingEntity 的服务端实现，再回退读取 Projectile.shooter。
        val attacker = causing ?: when (direct) {
            is Projectile -> direct.shooter as? Entity
            else -> direct
        }
        return when (attacker) {
            is Player -> Type.OTHER
            is LivingEntity -> Type.NORMAL_ATTACK
            else -> Type.OTHER
        }
    }

    private fun hasOwnedMetadata(plugin: Hjh_database, entity: Entity, key: String): Boolean =
        entity.getMetadata(key).any { it.owningPlugin == plugin && it.asBoolean() }
}
