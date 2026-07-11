package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class taijijianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database

    private data class HoldState(
        val shieldAmount: Double,
        var yinYang: Int = 0,
        var taskId: Int = -1,
        var speedDebuffApplied: Boolean = false
    )

    private val activeStates = ConcurrentHashMap<UUID, HoldState>()
    private val empoweredStacks = ConcurrentHashMap<UUID, Int>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        deactivate(player)

        val maxHealth = getMaxHealth(player)
        val shieldAmount = maxHealth * SHIELD_RATIO
        val state = HoldState(shieldAmount = shieldAmount)
        activeStates[player.uniqueId] = state

        clearAbsorption(player)
        applyAbsorptionShield(player, shieldAmount)
        applySpeedDebuff(player, state)
        plugin.weaponSkillManager?.registerToggle(player, "taijijian")

        val task = object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                val current = activeStates[player.uniqueId]
                if (current !== state || !player.isOnline) {
                    cancel()
                    return
                }

                if (ticks < SHIELD_DURATION_TICKS && player.absorptionAmount <= 0.01) {
                    finishBroken(player)
                    cancel()
                    return
                }

                if (ticks % 20 == 0 && ticks < SHIELD_DURATION_TICKS) {
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.8f)
                }

                if (ticks >= SHIELD_DURATION_TICKS) {
                    if (player.absorptionAmount > 0.01) {
                        finishCompleted(player, current)
                    } else {
                        finishBroken(player)
                    }
                    cancel()
                }

                ticks++
            }
        }
        task.runTaskTimer(plugin, 0L, 1L)
        state.taskId = task.taskId

        player.world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.3f)
        player.world.spawnParticle(Particle.WAX_ON, player.location.clone().add(0.0, 1.0, 0.0), 20, 0.6, 0.6, 0.6, 0.0)
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDamaged(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val state = activeStates[player.uniqueId] ?: return

        state.yinYang = (state.yinYang + 1).coerceAtMost(MAX_YINYANG)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (activeStates[player.uniqueId] === state && player.absorptionAmount <= 0.01) {
                finishBroken(player)
            }
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEmpoweredAttack(event: EntityDamageByEntityEvent) {
        if (event.entity.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val player = event.damager as? Player ?: return
        if (!isNormalMelee(event)) return

        val victim = event.entity as? LivingEntity ?: return
        if (!isMonster(victim)) return

        val stacks = empoweredStacks.remove(player.uniqueId) ?: return
        if (stacks <= 0) return

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val damage = data.attack * stacks.toDouble()
        if (damage <= 0.0) return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (victim.isValid && !victim.isDead) {
                dealPhysicalSkillDamage(player, victim, damage)
            }
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        deactivate(event.player)
    }

    override fun deactivate(player: Player) {
        val state = activeStates.remove(player.uniqueId)
        if (state != null && state.taskId != -1) {
            Bukkit.getScheduler().cancelTask(state.taskId)
        }
        if (state != null) {
            removeSpeedDebuff(player, state)
            clearAbsorption(player)
        } else {
            removeStaleSpeedDebuff(player)
        }
        empoweredStacks.remove(player.uniqueId)
        plugin.weaponSkillManager?.unregisterToggle(player)
    }

    private fun finishBroken(player: Player) {
        val state = activeStates.remove(player.uniqueId) ?: return
        if (state.taskId != -1) {
            Bukkit.getScheduler().cancelTask(state.taskId)
        }
        removeSpeedDebuff(player, state)
        clearAbsorption(player)
        plugin.weaponSkillManager?.unregisterToggle(player)
    }

    private fun finishCompleted(player: Player, state: HoldState) {
        if (activeStates.remove(player.uniqueId) == null) return
        if (state.taskId != -1) {
            Bukkit.getScheduler().cancelTask(state.taskId)
        }

        removeSpeedDebuff(player, state)
        clearAbsorption(player)
        plugin.weaponSkillManager?.unregisterToggle(player)

        val damage = getMaxHealth(player) * EXPLOSION_DAMAGE_MAX_HEALTH_RATIO
        val center = player.location.clone()

        player.world.spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(0.0, 1.0, 0.0), 12, 1.4, 0.4, 1.4, 0.0)
        player.world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, 1.0, 0.0), 35, 2.2, 0.6, 2.2, 0.02)
        player.world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.7f, 1.4f)

        player.world.getNearbyEntities(center, EXPLOSION_RADIUS, EXPLOSION_RADIUS, EXPLOSION_RADIUS)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player && isMonster(it) && it.location.distanceSquared(center) <= EXPLOSION_RADIUS * EXPLOSION_RADIUS }
            .forEach { target ->
                knockBackFromCenter(target, center)
                dealPhysicalSkillDamage(player, target, damage)
            }

        if (state.yinYang > 0) {
            empoweredStacks[player.uniqueId] = state.yinYang
        }
    }

    private fun applySpeedDebuff(player: Player, state: HoldState) {
        if (state.speedDebuffApplied) return
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        data.tempBonuses[SPEED_BONUS_KEY] = SPEED_DEBUFF
        state.speedDebuffApplied = true
        plugin.playerManager.updateStats(player)
    }

    private fun removeSpeedDebuff(player: Player, state: HoldState) {
        if (!state.speedDebuffApplied) return
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses.remove(SPEED_BONUS_KEY) != null) plugin.playerManager.updateStats(player)
    }

    private fun removeStaleSpeedDebuff(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses.remove(SPEED_BONUS_KEY) != null) plugin.playerManager.updateStats(player)
    }

    private fun clearAbsorption(player: Player) {
        player.removePotionEffect(PotionEffectType.ABSORPTION)
        player.absorptionAmount = 0.0
    }

    private fun applyAbsorptionShield(player: Player, amount: Double) {
        val amplifier = (ceil(amount / 4.0).toInt() - 1).coerceAtLeast(0)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, SHIELD_DURATION_TICKS + 20, amplifier, false, true, true), true)
        player.absorptionAmount = amount
    }

    private fun dealPhysicalSkillDamage(attacker: Player, target: LivingEntity, amount: Double) {
        val previousMaximum = target.maximumNoDamageTicks
        target.setMetadata(INTERNAL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        target.maximumNoDamageTicks = 0
        try {
            target.damage(amount, attacker)
        } finally {
            if (target.hasMetadata(INTERNAL_DAMAGE_METADATA)) {
                target.removeMetadata(INTERNAL_DAMAGE_METADATA, plugin)
            }
            if (target.hasMetadata("hjh_physical_skill")) {
                target.removeMetadata("hjh_physical_skill", plugin)
            }
            target.noDamageTicks = 0
            target.maximumNoDamageTicks = previousMaximum
        }
    }

    private fun knockBackFromCenter(target: LivingEntity, center: org.bukkit.Location) {
        val direction = target.location.toVector().subtract(center.toVector())
        val horizontal = Vector(direction.x, 0.0, direction.z)
        if (horizontal.lengthSquared() < 0.0001) {
            horizontal.setX(1.0)
        }
        target.velocity = horizontal.normalize().multiply(1.2).setY(0.35)
    }

    private fun getMaxHealth(player: Player): Double {
        return player.getAttribute(Attribute.MAX_HEALTH)?.value ?: plugin.playerManager.getData(player.uniqueId)?.maxHealth ?: 20.0
    }

    private fun isNormalMelee(event: EntityDamageByEntityEvent): Boolean {
        return event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
            event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    companion object {
        private const val SHIELD_RATIO = 0.50
        private const val SHIELD_DURATION_TICKS = 5 * 20
        private const val SPEED_DEBUFF = -0.50
        private const val SPEED_BONUS_KEY = "taijijian::speed_percent"
        private const val MAX_YINYANG = 5
        private const val EXPLOSION_RADIUS = 5.0
        private const val EXPLOSION_DAMAGE_MAX_HEALTH_RATIO = 0.65
        private const val INTERNAL_DAMAGE_METADATA = "HJH_TAIJI_INTERNAL_DAMAGE"
    }
}
