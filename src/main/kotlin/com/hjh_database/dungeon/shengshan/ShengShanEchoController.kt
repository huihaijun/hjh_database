package com.hjh_database.dungeon.shengshan

import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** 卦象残响只提供数值修正，不再拥有技能轴、计时器、实体或 BossBar。 */
internal class ShengShanEchoController(
    private val manager: ShengShanDungeonManager,
    private val session: ShengShanSession,
    private val boss: LivingEntity,
    private val echo: Trigram
) {
    private val damageKey = MobFactory.KEY_CUSTOM_DAMAGE
    private val armorKey = MobFactory.KEY_CUSTOM_ARMOR
    private val speedKey = NamespacedKey(manager.plugin, "shengshan_echo_speed")
    private val baseDamage = boss.persistentDataContainer.get(damageKey, PersistentDataType.DOUBLE) ?: 0.0
    private val baseArmor = boss.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
    private val baseMaxHealth = boss.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: boss.health
    private val procCooldowns = HashMap<UUID, Int>()
    private var active = true

    val armorPenetration: Double get() = if (active && echo == Trigram.FIRE) 0.20 else 0.0
    val isSequenced: Boolean get() = false

    fun start() {
        when (echo) {
            Trigram.THUNDER, Trigram.SKY ->
                boss.persistentDataContainer.set(damageKey, PersistentDataType.DOUBLE, baseDamage * 1.20)
            Trigram.WATER -> boss.getAttribute(Attribute.MAX_HEALTH)?.let { attribute ->
                val ratio = (boss.health / attribute.value.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                attribute.baseValue = baseMaxHealth * 1.10
                boss.health = (attribute.value * ratio).coerceAtLeast(1.0)
            }
            Trigram.MOUNTAIN ->
                boss.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, baseArmor * 1.20)
            Trigram.WIND -> boss.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attribute ->
                attribute.getModifier(speedKey)?.let(attribute::removeModifier)
                attribute.addTransientModifier(AttributeModifier(speedKey, 0.15, AttributeModifier.Operation.ADD_SCALAR))
            }
            Trigram.FIRE, Trigram.SWAMP, Trigram.EARTH -> Unit
        }
        manager.broadcast(session, "§6${echo.displayName}留下的残响融入了当前卦象，为它带来了新的力量……")
    }

    fun triggerOnce(onComplete: () -> Unit) = onComplete()
    fun onBossDamaged(@Suppress("UNUSED_PARAMETER") event: EntityDamageByEntityEvent) = Unit
    fun handleDamageEvent(@Suppress("UNUSED_PARAMETER") event: EntityDamageByEntityEvent): Boolean = false
    fun onProjectileHit(@Suppress("UNUSED_PARAMETER") event: ProjectileHitEvent) = Unit
    fun onParticipantDeath() = Unit

    fun onBossDealtDamage(player: Player?) {
        if (!active || player == null || echo !in setOf(Trigram.SWAMP, Trigram.EARTH)) return
        val now = Bukkit.getCurrentTick()
        if (now < (procCooldowns[player.uniqueId] ?: 0) || ThreadLocalRandom.current().nextDouble() >= 0.50) return
        procCooldowns[player.uniqueId] = now + 200
        val type = if (echo == Trigram.SWAMP) PotionEffectType.POISON else PotionEffectType.WITHER
        player.addPotionEffect(PotionEffect(type, 100, 2, false, true, true), true)
    }

    fun shutdown() {
        active = false
        procCooldowns.clear()
        if (!boss.isValid || boss.isDead) return
        boss.persistentDataContainer.set(damageKey, PersistentDataType.DOUBLE, baseDamage)
        boss.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, baseArmor)
        boss.getAttribute(Attribute.MAX_HEALTH)?.let { attribute ->
            val ratio = (boss.health / attribute.value.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            attribute.baseValue = baseMaxHealth
            boss.health = (attribute.value * ratio).coerceIn(1.0, attribute.value)
        }
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attribute ->
            attribute.getModifier(speedKey)?.let(attribute::removeModifier)
        }
    }
}
