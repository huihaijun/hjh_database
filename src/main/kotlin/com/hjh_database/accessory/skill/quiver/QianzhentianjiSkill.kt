package com.hjh_database.accessory.skill.quiver

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.data.PlayerData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil

/** 五阶弓箭手箭袋“乾震天机”：[惊雷折翼]。 */
class QianzhentianjiSkill(plugin: Hjh_database) : BaseQuiverSkill(plugin) {
    private data class PendingCharge(
        val ownerId: UUID,
        val targetId: UUID,
        val fallbackTarget: Location,
        val origins: List<Location>,
        val damage: Double,
        val effectKeys: List<String>,
        var task: BukkitTask? = null
    )

    private data class CurrentPath(
        val start: Location,
        val end: Location,
        val direction: Vector,
        val candidates: MutableSet<UUID>,
        var progress: Double = 0.0
    )

    private data class CurrentSequence(
        val ownerId: UUID,
        val paths: List<CurrentPath>,
        val damage: Double,
        val durationTicks: Int,
        val struck: MutableSet<UUID> = HashSet(),
        var elapsedTicks: Int = 0,
        var task: BukkitTask? = null
    )

    private data class LeizhenState(var until: Long)

    private val triggerArrowKey = NamespacedKey(plugin, "qianzhentianji_trigger_arrow")
    private val arrowDamageKey = NamespacedKey(plugin, "qianzhentianji_arrow_damage")
    private val leizhenSpeedKey = NamespacedKey(plugin, "qianzhentianji_leizhen_speed")
    private val pendingCharges = HashMap<UUID, PendingCharge>()
    private val currentSequences = HashMap<UUID, CurrentSequence>()
    private val leizhenStates = HashMap<UUID, LeizhenState>()
    private var leizhenTask: BukkitTask? = null
    private val thunderDust = Particle.DustOptions(Color.fromRGB(255, 234, 105), 0.8f)

    /** 只标记饰品激活时射出的真实箭；实际触发点放在箭矢命中合法怪物时。 */
    override fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData) {
        val arrow = event.projectile as? AbstractArrow ?: return
        arrow.persistentDataContainer.set(triggerArrowKey, PersistentDataType.BYTE, 1.toByte())
        arrow.persistentDataContainer.set(
            arrowDamageKey,
            PersistentDataType.DOUBLE,
            (data.archerDamage * CURRENT_DAMAGE_MULTIPLIER).coerceAtLeast(0.0)
        )
    }

    fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val pdc = arrow.persistentDataContainer
        if (!pdc.has(triggerArrowKey, PersistentDataType.BYTE)) return

        val target = event.hitEntity as? LivingEntity ?: return
        if (!isValidTarget(target)) return
        val player = arrow.shooter as? Player ?: return
        val damage = pdc.get(arrowDamageKey, PersistentDataType.DOUBLE) ?: return
        // 一支箭只拥有一次触发机会；穿透箭后续命中不会延迟触发旧的一箭。
        pdc.remove(triggerArrowKey)
        pdc.remove(arrowDamageKey)

        val now = System.currentTimeMillis()
        if (now < getTrackedCooldownEnd(player)) return
        startTrackedCooldown(player, SKILL_COOLDOWN_MILLIS, now)

        summonThunderArrows(player, target, damage)
    }

    private fun summonThunderArrows(player: Player, target: LivingEntity, damage: Double) {
        val forward = player.eyeLocation.direction.clone().apply { y = 0.0 }
        if (forward.lengthSquared() < 1.0e-6) forward.setZ(1.0) else forward.normalize()
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize()
        val base = player.location.clone().add(0.0, THUNDER_ARROW_HEIGHT, 0.0)
            .add(forward.clone().multiply(FORWARD_OFFSET))
        val origins = listOf(
            base.clone().add(right.clone().multiply(SIDE_OFFSET)),
            base.clone().subtract(right.clone().multiply(SIDE_OFFSET))
        )
        val fallback = target.location.clone().add(0.0, target.height * TARGET_HEIGHT_FACTOR, 0.0)
        val chargeId = UUID.randomUUID()
        val effectKeys = origins.indices.map { "qianzhentianji_charge_${chargeId}_$it" }
        val charge = PendingCharge(
            player.uniqueId,
            target.uniqueId,
            fallback,
            origins,
            damage,
            effectKeys
        )
        pendingCharges[chargeId] = charge

        origins.forEachIndexed { index, origin -> playChargeEffect(origin, forward, effectKeys[index]) }
        player.sendActionBar(Component.text("§e§l饰品技【惊雷折翼】发动！"))
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.45f)
        player.world.playSound(player.location, Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, 0.55f, 1.65f)

        charge.task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            fireChargedArrows(chargeId)
        }, CHARGE_DELAY_TICKS)
    }

    private fun fireChargedArrows(chargeId: UUID) {
        val charge = pendingCharges.remove(chargeId) ?: return
        charge.effectKeys.forEach { plugin.clientBridge.cancelTimedEffect(Bukkit.getOnlinePlayers(), it) }
        val player = Bukkit.getPlayer(charge.ownerId) ?: return
        if (!player.isOnline || player.isDead || player.world != charge.fallbackTarget.world) return

        val liveTarget = Bukkit.getEntity(charge.targetId) as? LivingEntity
        val targetPoint = if (liveTarget != null && isValidTarget(liveTarget) && liveTarget.world == player.world) {
            liveTarget.location.clone().add(0.0, liveTarget.height * TARGET_HEIGHT_FACTOR, 0.0)
        } else charge.fallbackTarget.clone()

        player.world.playSound(player.location, Sound.ENTITY_BREEZE_SHOOT, 0.8f, 1.75f)
        player.world.playSound(player.location, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.42f, 1.8f)
        startCurrentSequence(player, charge.origins, targetPoint, charge.damage)
    }

    private fun startCurrentSequence(player: Player, origins: List<Location>, targetPoint: Location, damage: Double) {
        val world = targetPoint.world ?: return
        val paths = origins.mapNotNull { origin ->
            if (origin.world != world) return@mapNotNull null
            val delta = targetPoint.toVector().subtract(origin.toVector())
            if (delta.lengthSquared() < 1.0e-6) return@mapNotNull null
            val candidates = world.getNearbyEntities(BoundingBox.of(origin, targetPoint).expand(CURRENT_RADIUS))
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter(::isValidTarget)
                .map { it.uniqueId }
                .toMutableSet()
            CurrentPath(origin.clone(), targetPoint.clone(), delta, candidates)
        }
        if (paths.isEmpty()) return

        val longest = paths.maxOf { it.direction.length() }
        val duration = ceil(longest / CURRENT_BLOCKS_PER_TICK).toInt().coerceIn(MIN_FLIGHT_TICKS, MAX_FLIGHT_TICKS)
        val sequenceId = UUID.randomUUID()
        val sequence = CurrentSequence(player.uniqueId, paths, damage, duration)
        currentSequences[sequenceId] = sequence
        sequence.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            tickCurrentSequence(sequenceId)
        }, 0L, 1L)
    }

    private fun tickCurrentSequence(sequenceId: UUID) {
        val sequence = currentSequences[sequenceId] ?: return
        val player = Bukkit.getPlayer(sequence.ownerId)
        if (player == null || !player.isOnline || player.isDead) {
            stopCurrentSequence(sequenceId, leaveCurrent = false)
            return
        }

        sequence.elapsedTicks++
        val linear = (sequence.elapsedTicks.toDouble() / sequence.durationTicks).coerceIn(0.0, 1.0)
        // 雷矢先快速离弦、临近目标时略微收势，使两条轨迹更具弧光掠空感。
        val progress = 1.0 - (1.0 - linear) * (1.0 - linear)
        val debuffAfterDamage = LinkedHashSet<LivingEntity>()

        for (path in sequence.paths) {
            val previous = path.start.clone().add(path.direction.clone().multiply(path.progress))
            val front = path.start.clone().add(path.direction.clone().multiply(progress))
            playFlyingCurrent(previous, front)
            path.progress = progress

            val iterator = path.candidates.iterator()
            while (iterator.hasNext()) {
                val targetId = iterator.next()
                val target = Bukkit.getEntity(targetId) as? LivingEntity
                if (target == null || !isValidTarget(target) || target.world != path.start.world) {
                    iterator.remove()
                    continue
                }
                val point = target.location.clone().add(0.0, target.height * TARGET_HEIGHT_FACTOR, 0.0).toVector()
                val projection = projectionAlongPath(path.start.toVector(), path.direction, point)
                if (projection > progress + 1.0e-6) continue
                if (distanceSquaredToPath(path.start.toVector(), path.direction, point, projection) >
                    CURRENT_RADIUS * CURRENT_RADIUS
                ) continue
                // 两条电流共享命中表：即使路径重叠，同一怪物整次技能也只承受一根电流的伤害。
                if (!sequence.struck.add(targetId)) continue

                dealCurrentDamage(player, target, sequence.damage)
                debuffAfterDamage += target
            }
        }

        // 同一刻两条电流先分别结算伤害，再统一刷新雷震，避免遍历先后改变第二条电流的护甲结果。
        debuffAfterDamage.forEach(::applyLeizhen)
        if (sequence.elapsedTicks >= sequence.durationTicks) {
            stopCurrentSequence(sequenceId, leaveCurrent = true)
        }
    }

    private fun stopCurrentSequence(sequenceId: UUID, leaveCurrent: Boolean) {
        val sequence = currentSequences.remove(sequenceId) ?: return
        sequence.task?.cancel()
        if (!leaveCurrent) return

        sequence.paths.forEachIndexed { index, path ->
            val key = "qianzhentianji_current_${sequenceId}_$index"
            plugin.clientBridge.emitTimedParticles(
                path.start.world?.players.orEmpty(),
                path.start,
                currentTrailLayers(),
                path.end,
                durationTicks = CURRENT_REMAIN_TICKS,
                intervalTicks = 3,
                key = key
            )
        }
        val end = sequence.paths.first().end
        end.world?.playSound(end, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.95f, 1.2f)
        end.world?.playSound(end, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.45f, 1.65f)
        end.world?.spawnParticle(Particle.FLASH, end, 1)
    }

    private fun dealCurrentDamage(player: Player, target: LivingEntity, damage: Double) {
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        val previousNoDamageTicks = target.noDamageTicks
        val previousLastDamage = target.lastDamage
        target.noDamageTicks = 0
        try {
            target.damage(damage, player)
        } finally {
            target.removeMetadata("hjh_physical_skill", plugin)
            // 两条电流独立命中，但不制造新的无敌帧，也不吞掉玩家原有攻击。
            if (target.isValid && !target.isDead) {
                target.noDamageTicks = previousNoDamageTicks.coerceAtMost(target.maximumNoDamageTicks)
                target.lastDamage = previousLastDamage
            }
        }
    }

    private fun applyLeizhen(target: LivingEntity) {
        if (!isValidTarget(target)) return
        leizhenStates[target.uniqueId] = LeizhenState(System.currentTimeMillis() + LEIZHEN_DURATION_MILLIS)
        val movement = target.getAttribute(Attribute.MOVEMENT_SPEED)
        if (movement != null && movement.getModifier(leizhenSpeedKey) == null) {
            movement.addTransientModifier(
                AttributeModifier(leizhenSpeedKey, -LEIZHEN_REDUCTION, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
            )
        }
        target.world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            target.location.clone().add(0.0, target.height * 0.55, 0.0),
            7,
            .28,
            .38,
            .28,
            .035
        )
        ensureLeizhenTask()
    }

    fun onArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val state = leizhenStates[event.victim.uniqueId] ?: return
        if (state.until <= System.currentTimeMillis()) {
            removeLeizhen(event.victim)
            return
        }
        event.armor *= 1.0 - LEIZHEN_REDUCTION
    }

    private fun ensureLeizhenTask() {
        if (leizhenTask != null) return
        leizhenTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            val iterator = leizhenStates.iterator()
            while (iterator.hasNext()) {
                val (uuid, state) = iterator.next()
                val target = Bukkit.getEntity(uuid) as? LivingEntity
                if (state.until > now && target != null && target.isValid && !target.isDead) continue
                if (target != null) removeSpeedModifier(target)
                iterator.remove()
            }
            if (leizhenStates.isEmpty()) {
                leizhenTask?.cancel()
                leizhenTask = null
            }
        }, LEIZHEN_TASK_PERIOD_TICKS, LEIZHEN_TASK_PERIOD_TICKS)
    }

    private fun removeLeizhen(target: LivingEntity) {
        leizhenStates.remove(target.uniqueId)
        removeSpeedModifier(target)
    }

    private fun removeSpeedModifier(target: LivingEntity) {
        val movement = target.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        movement.getModifier(leizhenSpeedKey)?.let(movement::removeModifier)
    }

    private fun playChargeEffect(origin: Location, forward: Vector, key: String) {
        val tail = origin.clone().subtract(forward.clone().multiply(0.85))
        plugin.clientBridge.emitTimedParticles(
            origin.world?.players.orEmpty(),
            tail,
            listOf(
                ClientParticleLayer("minecraft:electric_spark", ClientParticleShape.LINE, 9, radius = .08, height = .08, speed = .04),
                ClientParticleLayer("minecraft:dust", ClientParticleShape.LINE, 6, 0xFFF09B, .85f, .06, .06, .0),
                // 不使用附魔/末影系贴图，避免资源包将其替换成突兀的末影箱粒子。
                ClientParticleLayer("minecraft:wax_on", ClientParticleShape.RING, 5, radius = .32, height = .12, speed = .02)
            ),
            origin,
            durationTicks = CHARGE_DELAY_TICKS.toInt(),
            intervalTicks = 2,
            key = key
        )
    }

    private fun playFlyingCurrent(previous: Location, front: Location) {
        plugin.clientBridge.emitParticles(
            previous.world?.players.orEmpty(),
            previous,
            currentTrailLayers(),
            front
        )
        front.world?.spawnParticle(Particle.ELECTRIC_SPARK, front, 3, .08, .08, .08, .025)
        front.world?.spawnParticle(Particle.DUST, front, 1, .04, .04, .04, 0.0, thunderDust)
    }

    private fun currentTrailLayers() = listOf(
        ClientParticleLayer("minecraft:electric_spark", ClientParticleShape.LINE, 13, radius = .12, height = .12, speed = .065),
        ClientParticleLayer("minecraft:dust", ClientParticleShape.LINE, 8, 0xFFF29C, .95f, .08, .08, .0),
        ClientParticleLayer("minecraft:end_rod", ClientParticleShape.LINE, 3, radius = .08, height = .08, speed = .015)
    )

    private fun projectionAlongPath(start: Vector, path: Vector, point: Vector): Double {
        return point.clone().subtract(start).dot(path).div(path.lengthSquared()).coerceIn(0.0, 1.0)
    }

    private fun distanceSquaredToPath(start: Vector, path: Vector, point: Vector, projection: Double): Double {
        val closest = start.clone().add(path.clone().multiply(projection))
        return point.distanceSquared(closest)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
            entity !is ArmorStand &&
            entity.isValid &&
            !entity.isDead &&
            entity.scoreboardTags.contains("panling") &&
            entity.scoreboardTags.contains("monster")
    }

    fun cleanup(player: Player) {
        val ownerId = player.uniqueId
        cleanupHudState(player)
        pendingCharges.filterValues { it.ownerId == ownerId }.keys.toList().forEach { id ->
            pendingCharges.remove(id)?.let { charge ->
                charge.task?.cancel()
                charge.effectKeys.forEach { plugin.clientBridge.cancelTimedEffect(Bukkit.getOnlinePlayers(), it) }
            }
        }
        currentSequences.filterValues { it.ownerId == ownerId }.keys.toList().forEach { id ->
            stopCurrentSequence(id, leaveCurrent = false)
        }
    }

    fun shutdown() {
        pendingCharges.values.forEach { charge ->
            charge.task?.cancel()
            charge.effectKeys.forEach { plugin.clientBridge.cancelTimedEffect(Bukkit.getOnlinePlayers(), it) }
        }
        pendingCharges.clear()
        currentSequences.values.forEach { it.task?.cancel() }
        currentSequences.clear()
        leizhenTask?.cancel()
        leizhenTask = null
        leizhenStates.keys.toList().forEach { uuid ->
            (Bukkit.getEntity(uuid) as? LivingEntity)?.let(::removeSpeedModifier)
        }
        leizhenStates.clear()
        shutdownHudState()
    }

    companion object {
        private const val SKILL_COOLDOWN_MILLIS = 7_000L
        private const val CHARGE_DELAY_TICKS = 12L
        private const val CURRENT_DAMAGE_MULTIPLIER = 3.0
        private const val CURRENT_RADIUS = 1.5
        private const val CURRENT_BLOCKS_PER_TICK = 3.0
        private const val MIN_FLIGHT_TICKS = 4
        private const val MAX_FLIGHT_TICKS = 14
        private const val CURRENT_REMAIN_TICKS = 20
        private const val SIDE_OFFSET = 2.0
        private const val FORWARD_OFFSET = 1.15
        private const val THUNDER_ARROW_HEIGHT = 1.35
        private const val TARGET_HEIGHT_FACTOR = 0.5
        private const val LEIZHEN_REDUCTION = 0.20
        private const val LEIZHEN_DURATION_MILLIS = 5_000L
        private const val LEIZHEN_TASK_PERIOD_TICKS = 5L
    }
}
