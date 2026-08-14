package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class JiangTianGuangSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    private data class MarkState(
        val casterId: UUID,
        val targetId: UUID,
        val expiryTime: Long,
        val fallbackFormationStrength: Double,
        val fallbackMaxHealth: Double,
        val bonusDamageMultiplier: Double,
        val sourceHealRatio: Double,
        val vulnerability: Double,
        val glowDurationMillis: Long,
        val triggerCooldownMillis: Long,
        var glowUntil: Long = 0L,
        var lastBonusDamageTime: Long = 0L,
        var lastSourceHealTime: Long = 0L
    )

    init {
        // MedicalSpellManager 可热重载；事件监听器只注册一次，避免一次受击被重复结算。
        if (listenerRegistered.compareAndSet(false, true)) {
            plugin.server.pluginManager.registerEvents(this, plugin)
        }
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val radius = (config?.getDouble("radius", 12.0) ?: 12.0).coerceAtLeast(0.0)
        val durationSeconds = (config?.getDouble("duration", 24.0) ?: 24.0).coerceAtLeast(0.0)
        val pulseIntervalSeconds = (config?.getDouble("pulse_interval", 4.0) ?: 4.0).coerceAtLeast(0.05)
        val glowDurationSeconds = (config?.getDouble("glow_duration", 1.0) ?: 1.0).coerceAtLeast(0.05)
        val vulnerability = (config?.getDouble("vulnerability", 0.5) ?: 0.5).coerceAtLeast(0.0)
        val bonusDamageMultiplier = (config?.getDouble("bonus_damage_multiplier", 1.6) ?: 1.6)
            .coerceAtLeast(0.0)
        val sourceHealRatio = (config?.getDouble("source_heal_ratio", 0.04) ?: 0.04).coerceAtLeast(0.0)
        val triggerCooldownMillis = ((config?.getDouble("trigger_cooldown", 1.0) ?: 1.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)

        val target = findHighestHealthUnmarkedMonster(player, radius)
        if (target == null) {
            player.sendMessage("§c[降天光] §7附近没有未被降天光标记的怪物，施法已取消！")
            return false
        }

        val now = System.currentTimeMillis()
        val durationMillis = (durationSeconds * 1000.0).toLong()
        val durationTicks = (durationSeconds * 20.0).toLong().coerceAtLeast(1L)
        val pulseIntervalTicks = (pulseIntervalSeconds * 20.0).toLong().coerceAtLeast(1L)
        val glowDurationMillis = (glowDurationSeconds * 1000.0).toLong().coerceAtLeast(1L)
        val targetMap = activeMarks.computeIfAbsent(target.uniqueId) { ConcurrentHashMap() }
        val mark = MarkState(
            casterId = player.uniqueId,
            targetId = target.uniqueId,
            expiryTime = now + durationMillis,
            fallbackFormationStrength = data.zfStr,
            fallbackMaxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: data.maxHealth,
            bonusDamageMultiplier = bonusDamageMultiplier,
            sourceHealRatio = sourceHealRatio,
            vulnerability = vulnerability,
            glowDurationMillis = glowDurationMillis,
            triggerCooldownMillis = triggerCooldownMillis
        )
        targetMap[player.uniqueId] = mark

        drawDescendingLight(target)
        target.world.playSound(target.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 2.0f)
        target.world.playSound(target.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f)

        object : BukkitRunnable() {
            override fun run() {
                val currentMark = activeMarks[target.uniqueId]?.get(player.uniqueId)
                val currentTime = System.currentTimeMillis()
                if (
                    currentMark !== mark ||
                    currentTime >= mark.expiryTime ||
                    !target.isValid ||
                    target.isDead
                ) {
                    removeMark(mark)
                    cancel()
                    return
                }

                activateGlowWindow(target, mark, currentTime)
            }
        }.runTaskTimer(plugin, pulseIntervalTicks, pulseIntervalTicks)

        // 精确兜底清理，避免非整周期配置留下标记。
        plugin.server.scheduler.runTaskLater(plugin, Runnable { removeMark(mark) }, durationTicks)
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMarkedMonsterDamaged(event: EntityDamageEvent) {
        val target = event.entity as? LivingEntity ?: return
        if (event.finalDamage <= 0.0 || internalDamageTargets.contains(target.uniqueId)) return

        val targetMarks = activeMarks[target.uniqueId] ?: return
        val now = System.currentTimeMillis()
        val damageSource = resolvePlayerDamageSource(event)

        for (mark in targetMarks.values.toList()) {
            if (now >= mark.expiryTime) {
                removeMark(mark)
                continue
            }

            // 标记期内的来源回血与发光期追加伤害拥有互相独立的 1 秒内置 CD。
            if (
                damageSource != null &&
                now - mark.lastSourceHealTime >= mark.triggerCooldownMillis
            ) {
                mark.lastSourceHealTime = now
                healDamageSource(mark, damageSource)
            }

            if (
                mark.glowUntil > now &&
                now - mark.lastBonusDamageTime >= mark.triggerCooldownMillis
            ) {
                mark.lastBonusDamageTime = now
                scheduleBonusDamage(mark, target)
            }
        }
    }

    private fun findHighestHealthUnmarkedMonster(player: Player, radius: Double): LivingEntity? {
        val center = player.location
        val radiusSquared = radius * radius
        val now = System.currentTimeMillis()
        var selected: LivingEntity? = null
        var highestHealth = Double.NEGATIVE_INFINITY
        var nearestTieDistance = Double.POSITIVE_INFINITY

        for (entity in player.world.getNearbyEntities(center, radius, radius, radius)) {
            val monster = entity as? LivingEntity ?: continue
            if (!isMonster(monster)) continue
            val distanceSquared = monster.location.distanceSquared(center)
            if (distanceSquared > radiusSquared) continue
            if (hasActiveMark(monster.uniqueId, now)) continue

            if (
                monster.health > highestHealth ||
                (monster.health == highestHealth && distanceSquared < nearestTieDistance)
            ) {
                selected = monster
                highestHealth = monster.health
                nearestTieDistance = distanceSquared
            }
        }
        return selected
    }

    private fun hasActiveMark(targetId: UUID, now: Long): Boolean {
        val targetMarks = activeMarks[targetId] ?: return false

        // 索敌时顺便剔除已经到期的状态，避免清理任务延迟一刻导致误判为仍被标记。
        for ((casterId, mark) in targetMarks.entries.toList()) {
            if (mark.expiryTime <= now) targetMarks.remove(casterId, mark)
        }
        if (targetMarks.isEmpty()) {
            activeMarks.remove(targetId, targetMarks)
            return false
        }
        return true
    }

    private fun activateGlowWindow(target: LivingEntity, mark: MarkState, now: Long) {
        val windowEnd = minOf(now + mark.glowDurationMillis, mark.expiryTime)
        mark.glowUntil = windowEnd
        val existingUntil = vulnerabilityUntil(target)
        if (windowEnd > existingUntil) {
            target.setMetadata(VULNERABILITY_UNTIL_METADATA, FixedMetadataValue(plugin, windowEnd))
        }
        target.setMetadata(VULNERABILITY_AMOUNT_METADATA, FixedMetadataValue(plugin, mark.vulnerability))

        val glowTicks = ((windowEnd - now + 49L) / 50L).toInt().coerceAtLeast(1)
        target.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, glowTicks, 0, false, false, true))
        target.world.spawnParticle(
            Particle.WAX_ON,
            target.location.clone().add(0.0, target.height * 0.6, 0.0),
            24,
            0.65,
            0.8,
            0.65,
            0.06
        )
        target.world.playSound(target.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.65f)
    }

    private fun scheduleBonusDamage(mark: MarkState, target: LivingEntity) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!target.isValid || target.isDead) return@Runnable
            val currentMark = activeMarks[mark.targetId]?.get(mark.casterId)
            if (currentMark !== mark || System.currentTimeMillis() >= mark.expiryTime) return@Runnable
            val caster = plugin.server.getPlayer(mark.casterId) ?: return@Runnable
            if (!caster.isOnline || caster.isDead) return@Runnable

            val formationStrength = plugin.playerManager.getPlayerData(caster)?.zfStr
                ?: mark.fallbackFormationStrength
            val bonusDamage = formationStrength * mark.bonusDamageMultiplier
            if (bonusDamage <= 0.0) return@Runnable

            internalDamageTargets.add(target.uniqueId)
            try {
                plugin.medicalSpellManager.applyMedicalDamage(
                    caster,
                    target,
                    bonusDamage,
                    "jiangtianguang_bonus",
                    false
                )
            } finally {
                internalDamageTargets.remove(target.uniqueId)
            }

            target.world.spawnParticle(
                Particle.END_ROD,
                target.location.clone().add(0.0, target.height * 0.55, 0.0),
                14,
                0.4,
                0.55,
                0.4,
                0.04
            )
            target.world.playSound(target.location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.75f, 1.8f)
        })
    }

    private fun healDamageSource(mark: MarkState, damageSource: Player) {
        if (!damageSource.isOnline || damageSource.isDead) return
        val caster = plugin.server.getPlayer(mark.casterId)
        val casterMaxHealth = caster
            ?.getAttribute(Attribute.MAX_HEALTH)
            ?.value
            ?: mark.fallbackMaxHealth
        val healAmount = casterMaxHealth * mark.sourceHealRatio
        if (healAmount <= 0.0) return

        val healed = if (caster != null && caster.isOnline) {
            plugin.medicalSpellManager.applyMedicalHeal(
                caster,
                damageSource,
                healAmount,
                "jiangtianguang"
            )
        } else {
            val maxHealth = damageSource.getAttribute(Attribute.MAX_HEALTH)?.value ?: damageSource.health
            val before = damageSource.health
            damageSource.health = (before + healAmount).coerceAtMost(maxHealth)
            damageSource.health - before
        }

        if (healed > 0.0) {
            damageSource.world.spawnParticle(
                Particle.HEART,
                damageSource.location.clone().add(0.0, 1.8, 0.0),
                2,
                0.3,
                0.35,
                0.3,
                0.0
            )
            damageSource.world.playSound(damageSource.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.7f)
        }
    }

    private fun resolvePlayerDamageSource(event: EntityDamageEvent): Player? {
        val damageEvent = event as? EntityDamageByEntityEvent ?: return null
        return when (val damager = damageEvent.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    private fun drawDescendingLight(target: LivingEntity) {
        val base = target.location.clone().add(0.0, 0.25, 0.0)
        for (height in 0..12) {
            target.world.spawnParticle(
                Particle.END_ROD,
                base.clone().add(0.0, height.toDouble(), 0.0),
                4,
                0.2,
                0.35,
                0.2,
                0.0
            )
        }
        target.world.spawnParticle(
            Particle.DUST,
            base,
            35,
            0.85,
            0.15,
            0.85,
            0.0,
            Particle.DustOptions(Color.fromRGB(255, 235, 125), 1.15f)
        )
    }

    private fun removeMark(mark: MarkState) {
        val targetMap = activeMarks[mark.targetId] ?: return
        targetMap.remove(mark.casterId, mark)
        if (targetMap.isEmpty()) activeMarks.remove(mark.targetId, targetMap)
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity !is Player && entity.isValid && !entity.isDead &&
            tags.contains(PANLING_TAG) && tags.contains(MONSTER_TAG)
    }

    companion object {
        const val VULNERABILITY_UNTIL_METADATA = "hjh_jiangtianguang_vulnerability_until"
        const val VULNERABILITY_AMOUNT_METADATA = "hjh_jiangtianguang_vulnerability_amount"
        private const val PANLING_TAG = "panling"
        private const val MONSTER_TAG = "monster"

        private val listenerRegistered = AtomicBoolean(false)
        private val activeMarks = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, MarkState>>()
        private val internalDamageTargets = ConcurrentHashMap.newKeySet<UUID>()

        private fun vulnerabilityUntil(target: LivingEntity): Long {
            return target.getMetadata(VULNERABILITY_UNTIL_METADATA)
                .firstOrNull()
                ?.asLong()
                ?: 0L
        }
    }
}
