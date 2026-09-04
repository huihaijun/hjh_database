package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.skill.element_zf.FormationDamageEvent
import com.hjh_database.skill.element_zf.FormationElement
import com.hjh_database.skill.element_zf.FormationCast
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 普通元素阵法三级、五级特性的统一状态管理器。
 *
 * 每种减益都有独立状态表和 NamespacedKey；同种减益只覆盖到期时间，绝不重复叠加
 * AttributeModifier，也不会在结束时误删其他系统的属性效果。
 */
class ElementFormationTierEffects(private val plugin: Hjh_database) : Listener {

    private data class ColdState(var until: Long, var blockRate: Double, var blockedAccumulator: Double = 0.0)
    private data class WitherState(
        val owner: UUID,
        val zfSnapshot: Double,
        val jumpRadius: Double,
        val jumpDamageMultiplier: Double,
        var until: Long,
        val cast: FormationCast
    )
    private data class FireMark(
        val owner: UUID,
        val zfSnapshot: Double,
        val explosionRadius: Double,
        val explosionDamageMultiplier: Double,
        var until: Long,
        val cast: FormationCast
    )
    private data class SpeedState(var until: Long, var amount: Double)
    private data class TimedMultiplier(var until: Long, var multiplier: Double)

    private val metalWeakness = HashMap<UUID, TimedMultiplier>()
    private val withers = HashMap<UUID, WitherState>()
    private val cold = HashMap<UUID, ColdState>()
    private val fireMarks = HashMap<UUID, FireMark>()
    private val earthArmorBreak = HashMap<UUID, TimedMultiplier>()
    private val waterSlow = HashMap<UUID, SpeedState>()
    private val frozenPathSlow = HashMap<UUID, SpeedState>()
    private val rooted = HashMap<UUID, SpeedState>()

    private val witherSpeedKey = NamespacedKey(plugin, "formation_wither_speed")
    private val waterSlowKey = NamespacedKey(plugin, "formation_frost_slow")
    private val frozenPathSlowKey = NamespacedKey(plugin, "formation_frozen_path_slow")
    private val rootSpeedKey = NamespacedKey(plugin, "formation_earth_root")
    private val maintenanceTask: BukkitTask

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        maintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun applyMetalWeakness(target: LivingEntity, reduction: Double, durationMillis: Long) {
        if (!isFormationMonster(target)) return
        metalWeakness[target.uniqueId] = TimedMultiplier(
            System.currentTimeMillis() + durationMillis,
            1.0 - reduction.coerceIn(0.0, 1.0)
        )
        target.world.spawnParticle(
            Particle.DUST,
            target.location.add(0.0, target.height * 0.65, 0.0),
            8,
            0.28,
            0.35,
            0.28,
            0.0,
            Particle.DustOptions(Color.fromRGB(210, 185, 80), 0.9f)
        )
    }

    fun applyWither(
        target: LivingEntity,
        owner: Player,
        zfSnapshot: Double,
        movementReduction: Double,
        jumpRadius: Double,
        jumpDamageMultiplier: Double,
        durationMillis: Long,
        cast: FormationCast
    ) {
        if (!isFormationMonster(target)) return
        withers[target.uniqueId] = WitherState(
            owner.uniqueId,
            zfSnapshot.coerceAtLeast(0.0),
            jumpRadius.coerceAtLeast(0.0),
            jumpDamageMultiplier.coerceAtLeast(0.0),
            System.currentTimeMillis() + durationMillis,
            cast
        )
        applySpeedModifier(target, witherSpeedKey, -movementReduction.coerceIn(0.0, 1.0))
    }

    fun applyCold(target: LivingEntity, blockRate: Double, durationMillis: Long) {
        if (!isFormationMonster(target)) return
        val until = System.currentTimeMillis() + durationMillis
        cold[target.uniqueId]?.let {
            it.until = until
            it.blockRate = blockRate.coerceIn(0.0, 1.0)
        } ?: run { cold[target.uniqueId] = ColdState(until, blockRate.coerceIn(0.0, 1.0)) }
    }

    /** 将霜冻术原有药水减速迁移到阵法专属属性标签。 */
    fun applyWaterSlow(target: LivingEntity, amount: Double, durationMillis: Long) {
        applyTimedSpeed(target, waterSlow, waterSlowKey, amount, durationMillis)
    }

    fun applyFrozenPathSlow(target: LivingEntity, amount: Double, durationMillis: Long) {
        applyTimedSpeed(target, frozenPathSlow, frozenPathSlowKey, amount, durationMillis)
    }

    fun applyFireMark(
        target: LivingEntity,
        owner: Player,
        zfSnapshot: Double,
        explosionRadius: Double,
        explosionDamageMultiplier: Double,
        durationMillis: Long,
        cast: FormationCast
    ) {
        if (!isFormationMonster(target)) return
        fireMarks[target.uniqueId] = FireMark(
            owner.uniqueId,
            zfSnapshot.coerceAtLeast(0.0),
            explosionRadius.coerceAtLeast(0.0),
            explosionDamageMultiplier.coerceAtLeast(0.0),
            System.currentTimeMillis() + durationMillis,
            cast
        )
        target.world.spawnParticle(
            Particle.SMALL_FLAME,
            target.location.add(0.0, target.height * 0.55, 0.0),
            14,
            0.35,
            0.45,
            0.35,
            0.025
        )
    }

    fun applyEarthArmorBreak(target: LivingEntity, reduction: Double, durationMillis: Long) {
        if (!isFormationMonster(target)) return
        earthArmorBreak[target.uniqueId] = TimedMultiplier(
            System.currentTimeMillis() + durationMillis,
            1.0 - reduction.coerceIn(0.0, 1.0)
        )
    }

    fun applyRoot(target: LivingEntity, durationMillis: Long) {
        if (!isFormationMonster(target) || target.scoreboardTags.contains(INSTANCE_BOSS_TAG)) return
        applyTimedSpeed(target, rooted, rootSpeedKey, -1.0, durationMillis)
        target.velocity = target.velocity.setX(0.0).setZ(0.0)
        target.world.spawnParticle(
            Particle.BLOCK,
            target.location.add(0.0, 0.15, 0.0),
            18,
            0.35,
            0.15,
            0.35,
            0.02,
            org.bukkit.Material.ROOTED_DIRT.createBlockData()
        )
    }

    /** 金·三级：在所有怪物来源伤害完成基础覆写后独立降低20%。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWeakenedMonsterDamage(event: EntityDamageByEntityEvent) {
        val source = resolveSource(event.damager) ?: return
        val state = metalWeakness[source.uniqueId] ?: return
        if (state.until <= System.currentTimeMillis()) {
            metalWeakness.remove(source.uniqueId)
            return
        }
        event.damage *= state.multiplier
    }

    /** 水·三级：不改怪物技能，只让近战普攻的有效频率下降30%。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onColdMeleeAttack(event: EntityDamageByEntityEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return
        if (event.entity.hasMetadata(MONSTER_SKILL_DAMAGE_METADATA) ||
            event.entity.hasMetadata("HJH_MAGIC_DAMAGE") ||
            event.entity.hasMetadata("HJH_ARMORED_MAGIC_DAMAGE") ||
            event.entity.hasMetadata("hjh_physical_skill")
        ) return
        val attacker = event.damager as? LivingEntity ?: return
        if (attacker is Player || shouldAllowColdAttack(attacker)) return
        event.isCancelled = true
    }

    /** 骷髅等怪物的弓箭普攻同样纳入寒气频率限制。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onColdBowAttack(event: EntityShootBowEvent) {
        val shooter = event.entity
        if (shooter is Player || shouldAllowColdAttack(shooter)) return
        event.isCancelled = true
    }

    @EventHandler
    fun onArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val state = earthArmorBreak[event.victim.uniqueId] ?: return
        if (state.until <= System.currentTimeMillis()) {
            earthArmorBreak.remove(event.victim.uniqueId)
            return
        }
        event.armor *= state.multiplier
    }

    /** 火·三级：只响应标记者本人造成的其他元素阵法伤害，触发前先消耗标记防止递归。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onFormationDamage(event: FormationDamageEvent) {
        val element = event.element ?: return
        if (element == FormationElement.FIRE || event.actualDamage <= 0.0) return

        val mark = fireMarks[event.target.uniqueId] ?: return
        if (mark.until <= System.currentTimeMillis()) {
            fireMarks.remove(event.target.uniqueId)
            return
        }
        if (mark.owner != event.caster.uniqueId) return

        fireMarks.remove(event.target.uniqueId)
        explodeFireMark(
            event.target,
            event.caster,
            mark.zfSnapshot,
            mark.explosionRadius,
            mark.explosionDamageMultiplier,
            mark.cast
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMonsterDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val now = System.currentTimeMillis()
        val wither = withers.remove(entity.uniqueId)
        removeModifier(entity, witherSpeedKey)

        if (wither != null && wither.until > now) {
            val owner = Bukkit.getPlayer(wither.owner)
            if (owner != null && owner.isOnline && owner.world == entity.world) {
                launchSoulJump(
                    owner,
                    entity,
                    wither.zfSnapshot,
                    wither.jumpRadius,
                    wither.jumpDamageMultiplier,
                    wither.cast
                )
            }
        }
        clearEntityState(entity)
    }

    private fun explodeFireMark(
        marked: LivingEntity,
        caster: Player,
        zfSnapshot: Double,
        radius: Double,
        damageMultiplier: Double,
        formationCast: FormationCast
    ) {
        val center = marked.location.clone().add(0.0, marked.height * 0.45, 0.0)
        val radiusSquared = radius * radius
        marked.world.spawnParticle(Particle.FLAME, center, 28, 0.9, 0.45, 0.9, 0.08)
        marked.world.spawnParticle(Particle.FLASH, center, 1)
        marked.world.playSound(center, Sound.ENTITY_BLAZE_HURT, 1.0f, 1.45f)

        for (entity in marked.world.getNearbyEntities(center, radius, radius, radius)) {
            val target = entity as? LivingEntity ?: continue
            if (target.uniqueId == marked.uniqueId || !isFormationMonster(target)) continue
            if (target.location.distanceSquared(center) > radiusSquared) continue
            formationMagicDamage(plugin, caster, target, zfSnapshot * damageMultiplier, FormationElement.FIRE, cast = formationCast)
        }
    }

    private fun launchSoulJump(
        caster: Player,
        deadTarget: LivingEntity,
        zfSnapshot: Double,
        radius: Double,
        damageMultiplier: Double,
        formationCast: FormationCast
    ) {
        val start = deadTarget.location.clone().add(0.0, deadTarget.height * 0.55, 0.0)
        val radiusSquared = radius * radius
        val nextTarget = deadTarget.world
            .getNearbyEntities(start, radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != deadTarget.uniqueId && isFormationMonster(it) }
            .filter { it.location.distanceSquared(start) <= radiusSquared }
            .minByOrNull { it.location.distanceSquared(start) }
            ?: return

        val targetId = nextTarget.uniqueId
        object : BukkitRunnable() {
            var step = 0
            var current = start.clone()

            override fun run() {
                val target = Bukkit.getEntity(targetId) as? LivingEntity
                if (target == null || !target.isValid || target.isDead || !caster.isOnline || caster.world != target.world) {
                    cancel()
                    return
                }
                val end = target.location.add(0.0, target.height * 0.55, 0.0)
                val delta = end.toVector().subtract(current.toVector())
                if (delta.lengthSquared() <= 0.64 || step >= 12) {
                    target.world.spawnParticle(Particle.SOUL, end, 14, 0.3, 0.4, 0.3, 0.03)
                    target.world.playSound(end, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.9f, 0.65f)
                    formationMagicDamage(plugin, caster, target, zfSnapshot * damageMultiplier, FormationElement.WOOD, cast = formationCast)
                    cancel()
                    return
                }
                current.add(delta.normalize().multiply(0.8))
                target.world.spawnParticle(Particle.SOUL, current, 3, 0.08, 0.08, 0.08, 0.01)
                target.world.spawnParticle(Particle.HAPPY_VILLAGER, current, 1, 0.05, 0.05, 0.05, 0.0)
                step++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun shouldAllowColdAttack(attacker: LivingEntity): Boolean {
        val state = cold[attacker.uniqueId] ?: return true
        if (state.until <= System.currentTimeMillis()) {
            cold.remove(attacker.uniqueId)
            return true
        }

        // 累加器每次增加0.3，长期严格保持约30%的普攻尝试被拦截；刷新持续时间不重置序列。
        state.blockedAccumulator += state.blockRate
        if (state.blockedAccumulator + 1.0e-9 < 1.0) return true
        state.blockedAccumulator -= 1.0
        return false
    }

    private fun resolveSource(damager: org.bukkit.entity.Entity): LivingEntity? = when (damager) {
        is Projectile -> damager.shooter as? LivingEntity
        is LivingEntity -> damager
        else -> null
    }

    private fun applyTimedSpeed(
        target: LivingEntity,
        states: MutableMap<UUID, SpeedState>,
        key: NamespacedKey,
        rawAmount: Double,
        durationMillis: Long
    ) {
        if (!isFormationMonster(target)) return
        val amount = rawAmount.coerceIn(-1.0, 10.0)
        states[target.uniqueId] = SpeedState(System.currentTimeMillis() + durationMillis, amount)
        applySpeedModifier(target, key, amount)
    }

    private fun applySpeedModifier(target: LivingEntity, key: NamespacedKey, amount: Double) {
        val attribute = target.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(key)?.let(attribute::removeModifier)
        attribute.addTransientModifier(
            AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
        )
    }

    private fun removeModifier(target: LivingEntity, key: NamespacedKey) {
        val attribute = target.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(key)?.let(attribute::removeModifier)
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        // 定身期间每tick清除水平速度，防止AI寻路和外部微小推力继续移动目标。
        val rootIterator = rooted.iterator()
        while (rootIterator.hasNext()) {
            val (uuid, state) = rootIterator.next()
            val target = Bukkit.getEntity(uuid) as? LivingEntity
            if (state.until <= now || target == null || !target.isValid || target.isDead) {
                if (target != null) removeModifier(target, rootSpeedKey)
                rootIterator.remove()
            } else {
                target.velocity = target.velocity.setX(0.0).setZ(0.0)
            }
        }

        if (Bukkit.getCurrentTick() % 5 != 0) return
        cleanupMultipliers(metalWeakness, now)
        cleanupMultipliers(earthArmorBreak, now)
        cleanupCold(now)
        cleanupWithers(now)
        cleanupFireMarks(now)
        cleanupSpeed(waterSlow, waterSlowKey, now)
        cleanupSpeed(frozenPathSlow, frozenPathSlowKey, now)
    }

    private fun cleanupMultipliers(states: MutableMap<UUID, TimedMultiplier>, now: Long) {
        states.entries.removeIf { (uuid, state) ->
            state.until <= now || (Bukkit.getEntity(uuid) as? LivingEntity)?.let { !it.isValid || it.isDead } != false
        }
    }

    private fun cleanupCold(now: Long) {
        cold.entries.removeIf { (uuid, state) ->
            state.until <= now || (Bukkit.getEntity(uuid) as? LivingEntity)?.let { !it.isValid || it.isDead } != false
        }
    }

    private fun cleanupWithers(now: Long) {
        val iterator = withers.iterator()
        while (iterator.hasNext()) {
            val (uuid, state) = iterator.next()
            val target = Bukkit.getEntity(uuid) as? LivingEntity
            if (state.until <= now || target == null || !target.isValid || target.isDead) {
                if (target != null) removeModifier(target, witherSpeedKey)
                iterator.remove()
            }
        }
    }

    private fun cleanupFireMarks(now: Long) {
        fireMarks.entries.removeIf { (uuid, state) ->
            state.until <= now || (Bukkit.getEntity(uuid) as? LivingEntity)?.let { !it.isValid || it.isDead } != false
        }
    }

    private fun cleanupSpeed(states: MutableMap<UUID, SpeedState>, key: NamespacedKey, now: Long) {
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val (uuid, state) = iterator.next()
            val target = Bukkit.getEntity(uuid) as? LivingEntity
            if (state.until <= now || target == null || !target.isValid || target.isDead) {
                if (target != null) removeModifier(target, key)
                iterator.remove()
            }
        }
    }

    private fun clearEntityState(entity: LivingEntity) {
        metalWeakness.remove(entity.uniqueId)
        cold.remove(entity.uniqueId)
        fireMarks.remove(entity.uniqueId)
        earthArmorBreak.remove(entity.uniqueId)
        waterSlow.remove(entity.uniqueId)
        frozenPathSlow.remove(entity.uniqueId)
        rooted.remove(entity.uniqueId)
        removeModifier(entity, waterSlowKey)
        removeModifier(entity, frozenPathSlowKey)
        removeModifier(entity, rootSpeedKey)
    }

    fun shutdown() {
        maintenanceTask.cancel()
        for ((uuid, _) in withers) (Bukkit.getEntity(uuid) as? LivingEntity)?.let { removeModifier(it, witherSpeedKey) }
        for ((uuid, _) in waterSlow) (Bukkit.getEntity(uuid) as? LivingEntity)?.let { removeModifier(it, waterSlowKey) }
        for ((uuid, _) in frozenPathSlow) (Bukkit.getEntity(uuid) as? LivingEntity)?.let { removeModifier(it, frozenPathSlowKey) }
        for ((uuid, _) in rooted) (Bukkit.getEntity(uuid) as? LivingEntity)?.let { removeModifier(it, rootSpeedKey) }
        metalWeakness.clear()
        withers.clear()
        cold.clear()
        fireMarks.clear()
        earthArmorBreak.clear()
        waterSlow.clear()
        frozenPathSlow.clear()
        rooted.clear()
    }

    companion object {
        const val MONSTER_SKILL_DAMAGE_METADATA = "HJH_MONSTER_SKILL_DAMAGE"
        private const val INSTANCE_BOSS_TAG = "instance_boss"
        fun isFormationMonster(entity: LivingEntity): Boolean {
            val tags = entity.scoreboardTags
            return entity.isValid && !entity.isDead && tags.contains("panling") && tags.contains("monster")
        }
    }
}
