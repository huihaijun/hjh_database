package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
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
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionEffectTypeCategory
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

class pokongfuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database

    private data class RiftState(
        var endAt: Long,
        var extendedMillis: Long = 0L,
        var strengthStacks: Int = 0,
        var taskId: Int = -1,
        var potionSnapshotTaken: Boolean = false,
        var potionSnapshotAt: Long = 0L,
        var previousSpeed: PotionEffect? = null,
        var previousHaste: PotionEffect? = null
    )

    private data class SharedArmorState(
        var stacks: Int = 0,
        val contributors: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    )

    private val activeStates = ConcurrentHashMap<UUID, RiftState>()
    private val affectedMobs = ConcurrentHashMap<UUID, SharedArmorState>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        // 再次发动时，先完整移除上一轮裂空的独立加成和叠层。
        deactivate(player)
        removeHarmfulPotionEffects(player)

        val state = RiftState(endAt = System.currentTimeMillis() + BASE_DURATION_MS)
        activeStates[player.uniqueId] = state
        data.tempBonuses[CRIT_OVERRIDE_KEY] = 1.0
        data.tempBonuses.remove(ATTACK_BONUS_KEY)
        plugin.playerManager.updateStats(player)
        plugin.weaponSkillManager?.registerToggle(player, "pokongfu")

        val task = object : BukkitRunnable() {
            override fun run() {
                val current = activeStates[player.uniqueId]
                if (current !== state || !player.isOnline) {
                    cancel()
                    return
                }
                if (System.currentTimeMillis() >= current.endAt) {
                    finishRift(player, state)
                    cancel()
                }
            }
        }
        task.runTaskTimer(plugin, 1L, 1L)
        state.taskId = task.taskId

        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THUNDER, 1f, 1.2f)
        player.world.spawnParticle(Particle.CRIT, player.location.clone().add(0.0, 1.0, 0.0), 10, 0.4, 0.4, 0.4, 0.0)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMeleeHit(event: EntityDamageByEntityEvent) {
        if (event.entity.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val player = event.damager as? Player ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (!isNormalMelee(event) || !isMonster(victim)) return

        val state = activeStates[player.uniqueId] ?: return
        if (!plugin.equipmentActivationManager.isHoldingActiveWeapon(player, "pokongfu")) return
        if (System.currentTimeMillis() >= state.endAt) {
            finishRift(player, state)
            return
        }

        refreshAttackPotionBuffs(player, state)
        applySharedArmorReduction(player, victim)

        // 读取本次普攻命中前的当前生命，附伤在下一 tick 独立结算。
        val bonusDamage = min(MAX_CURRENT_HEALTH_DAMAGE, victim.health * CURRENT_HEALTH_DAMAGE_RATIO)
        if (bonusDamage > 0.0) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (victim.isValid && !victim.isDead) {
                    dealArmorPiercingDamage(player, victim, bonusDamage)
                }
            })
        }
    }

    /** 护甲减益只参与伤害结算，不改写怪物原始 PDC 护甲，避免覆盖其他技能。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val state = affectedMobs[event.victim.uniqueId] ?: return
        if (state.contributors.isEmpty()) return
        val reduction = (state.stacks * ARMOR_REDUCTION_PER_STACK).coerceAtMost(MAX_ARMOR_REDUCTION)
        event.armor *= 1.0 - reduction
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMobDeath(event: EntityDeathEvent) {
        val mob = event.entity
        affectedMobs.remove(mob.uniqueId)
        if (!isMonster(mob)) return

        val killer = mob.killer ?: return
        val state = activeStates[killer.uniqueId] ?: return
        if (!plugin.equipmentActivationManager.isHoldingActiveWeapon(killer, "pokongfu")) return
        if (System.currentTimeMillis() >= state.endAt) return

        val remainingExtension = MAX_EXTENSION_MS - state.extendedMillis
        if (remainingExtension > 0L) {
            val add = min(EXTENSION_PER_KILL_MS, remainingExtension)
            state.extendedMillis += add
            state.endAt += add
        }

        if (state.strengthStacks < MAX_STRENGTH_STACKS) {
            state.strengthStacks++
            val data = plugin.playerManager.getData(killer.uniqueId)
            if (data != null) {
                data.tempBonuses[ATTACK_BONUS_KEY] = state.strengthStacks * ATTACK_BONUS_PER_KILL
                plugin.playerManager.updateStats(killer)
            }
        }

        val maxHealth = killer.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        killer.health = min(maxHealth, killer.health + HEAL_PER_KILL)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        deactivate(event.player)
    }

    override fun deactivate(player: Player) {
        finishRift(player, activeStates[player.uniqueId])
    }

    private fun finishRift(player: Player, expectedState: RiftState?) {
        val state = if (expectedState == null) {
            activeStates.remove(player.uniqueId)
        } else if (activeStates.remove(player.uniqueId, expectedState)) {
            expectedState
        } else {
            return
        }

        if (state != null && state.taskId != -1) {
            Bukkit.getScheduler().cancelTask(state.taskId)
        }
        if (state != null) restoreAttackPotionBuffs(player, state)

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            val removedCrit = data.tempBonuses.remove(CRIT_OVERRIDE_KEY) != null
            val removedAttack = data.tempBonuses.remove(ATTACK_BONUS_KEY) != null
            val changed = removedCrit || removedAttack
            if (changed) plugin.playerManager.updateStats(player)
        }

        removePlayerFromAffectedMobs(player.uniqueId)
        plugin.weaponSkillManager?.unregisterToggle(player)
    }

    private fun removeHarmfulPotionEffects(player: Player) {
        player.activePotionEffects
            .filter { it.type.category == PotionEffectTypeCategory.HARMFUL }
            .forEach { player.removePotionEffect(it.type) }
    }

    private fun refreshAttackPotionBuffs(player: Player, state: RiftState) {
        if (!state.potionSnapshotTaken) {
            state.potionSnapshotTaken = true
            state.potionSnapshotAt = System.currentTimeMillis()
            state.previousSpeed = player.getPotionEffect(PotionEffectType.SPEED)
            state.previousHaste = player.getPotionEffect(PotionEffectType.HASTE)
        }

        applyOrRefreshPotion(player, PotionEffectType.SPEED, SPEED_AMPLIFIER)
        applyOrRefreshPotion(player, PotionEffectType.HASTE, HASTE_AMPLIFIER)
    }

    private fun applyOrRefreshPotion(player: Player, type: PotionEffectType, amplifier: Int) {
        val current = player.getPotionEffect(type)
        if (current != null && current.amplifier > amplifier) return
        player.addPotionEffect(PotionEffect(type, ATTACK_BUFF_TICKS, amplifier, false, true, true), true)
    }

    private fun restoreAttackPotionBuffs(player: Player, state: RiftState) {
        if (!state.potionSnapshotTaken) return
        restorePotion(player, PotionEffectType.SPEED, SPEED_AMPLIFIER, state.previousSpeed, state.potionSnapshotAt)
        restorePotion(player, PotionEffectType.HASTE, HASTE_AMPLIFIER, state.previousHaste, state.potionSnapshotAt)
    }

    private fun restorePotion(
        player: Player,
        type: PotionEffectType,
        skillAmplifier: Int,
        previous: PotionEffect?,
        snapshotAt: Long
    ) {
        val current = player.getPotionEffect(type)
        if (current == null || current.amplifier != skillAmplifier || current.duration > ATTACK_BUFF_TICKS + 20) return

        player.removePotionEffect(type)
        if (previous == null) return

        val elapsedTicks = ceil((System.currentTimeMillis() - snapshotAt) / 50.0).toInt()
        val remainingTicks = previous.duration - elapsedTicks
        if (remainingTicks > 0) {
            player.addPotionEffect(
                PotionEffect(type, remainingTicks, previous.amplifier, previous.isAmbient, previous.hasParticles(), previous.hasIcon()),
                true
            )
        }
    }

    private fun applySharedArmorReduction(player: Player, victim: LivingEntity) {
        val state = affectedMobs.computeIfAbsent(victim.uniqueId) { SharedArmorState() }
        state.contributors.add(player.uniqueId)
        state.stacks = (state.stacks + 1).coerceAtMost(MAX_ARMOR_STACKS)

        victim.world.spawnParticle(Particle.DRIPPING_LAVA, victim.location.clone().add(0.0, 1.2, 0.0), 5, 0.2, 0.3, 0.2, 0.0)
        victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.6f, 1.5f)
    }

    private fun removePlayerFromAffectedMobs(playerId: UUID) {
        val iterator = affectedMobs.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (state.contributors.remove(playerId) && state.contributors.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private fun dealArmorPiercingDamage(attacker: Player, target: LivingEntity, amount: Double) {
        val previousMaximum = target.maximumNoDamageTicks
        val previousNoDamageTicks = target.noDamageTicks
        target.setMetadata(INTERNAL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        target.maximumNoDamageTicks = 0
        try {
            target.damage(amount, attacker)
        } finally {
            target.removeMetadata(INTERNAL_DAMAGE_METADATA, plugin)
            target.removeMetadata("hjh_magic_damage", plugin)
            target.removeMetadata("hjh_physical_skill", plugin)
            target.maximumNoDamageTicks = previousMaximum
            // 附伤只临时绕过本次普攻产生的无敌帧；结算后恢复，避免连续挥砍绕过正常攻击间隔。
            target.noDamageTicks = previousNoDamageTicks.coerceAtMost(previousMaximum)
        }
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
        const val CRIT_OVERRIDE_KEY = "pokongfu::crit_override"
        const val ATTACK_BONUS_KEY = "pokongfu::attack_percent"

        private const val BASE_DURATION_MS = 8_000L
        private const val EXTENSION_PER_KILL_MS = 1_000L
        private const val MAX_EXTENSION_MS = 7_000L
        private const val MAX_STRENGTH_STACKS = 6
        private const val ATTACK_BONUS_PER_KILL = 0.50
        private const val HEAL_PER_KILL = 6.0
        private const val ATTACK_BUFF_TICKS = 5 * 20
        private const val SPEED_AMPLIFIER = 1
        private const val HASTE_AMPLIFIER = 0
        private const val MAX_ARMOR_STACKS = 8
        private const val ARMOR_REDUCTION_PER_STACK = 0.10
        private const val MAX_ARMOR_REDUCTION = 0.80
        private const val CURRENT_HEALTH_DAMAGE_RATIO = 0.10
        private const val MAX_CURRENT_HEALTH_DAMAGE = 50.0
        private const val INTERNAL_DAMAGE_METADATA = "HJH_POKONGFU_INTERNAL_DAMAGE"
    }
}
