package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class kunlunfeixianjianSkill : WeaponSkill, Listener {

    companion object {
        private const val MONSTER_TAG = "monster"
        private const val PANLING_TAG = "panling"
        private const val INTERNAL_DAMAGE_METADATA = "kunlun_flying_sword_internal"
        private const val ARMORED_EXACT_DAMAGE_METADATA = "HJH_ARMORED_MAGIC_DAMAGE"
        private const val MAX_SWORDS = 6
    }

    private data class ReserveState(
        val token: UUID,
        val expiresAtTick: Long,
        val swords: MutableList<ItemDisplay>,
        val attackDamagePercent: Double,
        val attackIntervalTicks: Long,
        val attackHits: Int,
        val retargetRadius: Double,
        val finishHealPerSword: Double,
        val finishHealCap: Double,
        val finishCooldownReductionPerSword: Double
    )

    private data class AttackSwordState(
        val ownerId: UUID,
        val display: ItemDisplay,
        var targetId: UUID,
        var lastLocation: Location,
        val damagePercent: Double,
        val intervalTicks: Long,
        var hitsRemaining: Int,
        val retargetRadius: Double,
        var nextHitTick: Long,
        val orbitOffset: Double
    )

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
    private val mainPlugin = plugin as Hjh_database
    private val reserveStates = ConcurrentHashMap<UUID, ReserveState>()
    private val attackSwords = ConcurrentHashMap<UUID, AttackSwordState>()
    private var schedulerTick = 0L

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        /*
         * 所有背负展示、追踪展示和飞剑攻击共用一个2 tick任务。
         * 最多只迭代真实存在的飞剑，避免给每把剑创建高频独立调度器。
         */
        object : BukkitRunnable() {
            override fun run() {
                schedulerTick += 2L
                processReserveSwords()
                processAttackSwords()
            }
        }.runTaskTimer(plugin, 1L, 2L)
    }

    override fun castActive(
        player: Player?,
        data: PlayerData?,
        config: ConfigurationSection?,
        projectile: Entity?
    ): Boolean {
        if (player == null || config == null) return false

        // 再次释放时，旧技能尚未出鞘的飞剑按每把8点、至多24点结算治疗；已出鞘飞剑继续攻击。
        reserveStates[player.uniqueId]?.let { finishReserveState(player.uniqueId, it, player, true) }

        val swordCount = config.getInt("sword_count", 6).coerceIn(1, MAX_SWORDS)
        val displays = ArrayList<ItemDisplay>(swordCount)
        repeat(swordCount) {
            spawnSwordDisplay(player)?.let(displays::add)
        }
        if (displays.isEmpty()) return false

        val summonDurationTicks = (config.getDouble("summon_duration", 10.0) * 20.0)
            .toLong()
            .coerceAtLeast(1L)
        val attackIntervalTicks = config.getLong("attack_interval_ticks", 10L).coerceAtLeast(1L)
        val attackDurationTicks = (config.getDouble("attack_duration", 5.0) * 20.0)
            .toLong()
            .coerceAtLeast(attackIntervalTicks)
        val attackHits = (attackDurationTicks / attackIntervalTicks).toInt().coerceAtLeast(1)

        val state = ReserveState(
            token = UUID.randomUUID(),
            expiresAtTick = schedulerTick + summonDurationTicks,
            swords = displays,
            attackDamagePercent = config.getDouble("damage_max_health_percent", 0.25).coerceAtLeast(0.0),
            attackIntervalTicks = attackIntervalTicks,
            attackHits = attackHits,
            retargetRadius = config.getDouble("retarget_radius", 10.0).coerceAtLeast(1.0),
            finishHealPerSword = config.getDouble("finish_heal_amount", 8.0).coerceAtLeast(0.0),
            finishHealCap = config.getDouble("finish_heal_cap", 24.0).coerceAtLeast(0.0),
            finishCooldownReductionPerSword = config
                .getDouble("finish_cooldown_reduction_per_sword", 1.0)
                .coerceAtLeast(0.0)
        )
        reserveStates[player.uniqueId] = state
        updateReserveDisplays(player, state)

        val center = player.location.clone().add(0.0, 1.15, 0.0)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.35f)
        player.world.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.9f, 1.65f)
        player.world.spawnParticle(Particle.CLOUD, center, 24, 0.75, 0.65, 0.75, 0.04)
        player.world.spawnParticle(Particle.END_ROD, center, 45, 0.9, 0.9, 0.9, 0.08)
        player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 28, 0.85, 0.8, 0.85, 0.035)
        return true
    }

    /**
     * 只消费主目标的普通近战命中。横扫副目标、其他技能伤害和飞剑自身伤害均不会消费飞剑。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNormalAttackHit(event: EntityDamageByEntityEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK || event.finalDamage <= 0.0) return

        val player = event.damager as? Player ?: return
        val target = event.entity as? LivingEntity ?: return
        if (!isMonster(target) || target.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val state = reserveStates[player.uniqueId] ?: return
        if (!mainPlugin.equipmentActivationManager.isHoldingActiveWeapon(player, "kunlunfeixianjian")) return
        if (schedulerTick >= state.expiresAtTick) {
            finishReserveState(player.uniqueId, state, player, true)
            return
        }
        if (countAttackSwords(player.uniqueId, target.uniqueId) >= 2) return

        val display = if (state.swords.isEmpty()) null else state.swords.removeAt(state.swords.lastIndex)
        if (display == null || !display.isValid) return

        val attackState = AttackSwordState(
            ownerId = player.uniqueId,
            display = display,
            targetId = target.uniqueId,
            lastLocation = target.location.clone(),
            damagePercent = state.attackDamagePercent,
            intervalTicks = state.attackIntervalTicks,
            hitsRemaining = state.attackHits,
            retargetRadius = state.retargetRadius,
            nextHitTick = schedulerTick + state.attackIntervalTicks,
            orbitOffset = ThreadLocalRandom.current().nextDouble(PI * 2.0)
        )
        attackSwords[display.uniqueId] = attackState

        target.world.playSound(target.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.85f, 1.75f)
        target.world.spawnParticle(
            Particle.DUST,
            target.location.clone().add(0.0, target.height * 0.6, 0.0),
            18,
            0.45,
            0.5,
            0.45,
            Particle.DustOptions(Color.AQUA, 1.0f)
        )
    }

    private fun processReserveSwords() {
        if (reserveStates.isEmpty()) return

        for ((ownerId, state) in reserveStates) {
            val player = Bukkit.getPlayer(ownerId)
            if (player == null || !player.isOnline || player.isDead) {
                finishReserveState(ownerId, state, player, false)
                cleanupAttackSwords(ownerId)
                continue
            }

            if (schedulerTick >= state.expiresAtTick) {
                finishReserveState(ownerId, state, player, true)
                continue
            }

            val invalidCount = state.swords.count { !it.isValid }
            if (invalidCount > 0) {
                state.swords.removeIf { !it.isValid }
            }
            updateReserveDisplays(player, state)
        }
    }

    private fun processAttackSwords() {
        if (attackSwords.isEmpty()) return

        for ((displayId, state) in attackSwords) {
            val player = Bukkit.getPlayer(state.ownerId)
            if (player == null || !player.isOnline || player.isDead || !state.display.isValid) {
                finishAttackSword(displayId, state)
                continue
            }

            var target = Bukkit.getEntity(state.targetId) as? LivingEntity
            if (target == null || !isMonster(target)) {
                target = findNearestMonster(state.lastLocation, state.retargetRadius, state.ownerId)
                if (target == null) {
                    finishAttackSword(displayId, state)
                    continue
                }
                state.targetId = target.uniqueId
                target.world.playSound(target.location, Sound.ITEM_TRIDENT_RETURN, 0.65f, 1.8f)
            }

            state.lastLocation = target.location.clone()
            updateAttackDisplay(state, target)

            if (schedulerTick < state.nextHitTick) continue
            state.nextHitTick += state.intervalTicks

            val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value
                ?: mainPlugin.playerManager.getData(player.uniqueId)?.maxHealth
                ?: 20.0
            dealSwordDamage(player, target, maxHealth * state.damagePercent)

            val hitLocation = target.location.clone().add(0.0, target.height * 0.55, 0.0)
            target.world.spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1)
            target.world.spawnParticle(Particle.CRIT, hitLocation, 7, 0.35, 0.4, 0.35, 0.04)
            target.world.spawnParticle(
                Particle.DUST,
                hitLocation,
                9,
                0.35,
                0.4,
                0.35,
                Particle.DustOptions(Color.fromRGB(105, 220, 255), 0.85f)
            )
            target.world.playSound(target.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.45f, 1.85f)

            state.hitsRemaining--
            if (state.hitsRemaining <= 0) {
                finishAttackSword(displayId, state)
            }
        }
    }

    private fun updateReserveDisplays(player: Player, state: ReserveState) {
        val count = state.swords.size
        if (count == 0) return

        val base = player.location
        val forward = base.direction.clone().setY(0.0)
        if (forward.lengthSquared() < 0.0001) forward.setZ(1.0) else forward.normalize()
        val right = Vector(-forward.z, 0.0, forward.x)
        val behind = forward.clone().multiply(-0.42)

        for ((index, display) in state.swords.withIndex()) {
            if (!display.isValid) continue
            val lateral = (index - (count - 1) / 2.0) * 0.34
            val height = 1.62 + (0.22 - abs(lateral) * 0.10)
            val location = base.clone()
                .add(behind)
                .add(right.clone().multiply(lateral))
                .add(0.0, height, 0.0)

            display.teleport(location)
            display.setRotation(base.yaw, 0.0f)
            if (schedulerTick % 10L == 0L) {
                player.world.spawnParticle(Particle.END_ROD, location, 1, 0.06, 0.14, 0.06, 0.0)
            }
        }
    }

    private fun updateAttackDisplay(state: AttackSwordState, target: LivingEntity) {
        val phase = schedulerTick * 0.18 + state.orbitOffset
        val center = target.location.clone().add(0.0, target.height * 0.62, 0.0)
        val location = center.add(
            cos(phase) * 0.72,
            sin(phase * 1.7) * 0.24,
            sin(phase) * 0.72
        )
        state.display.teleport(location)
        state.display.setRotation(Math.toDegrees(-phase).toFloat(), 0.0f)
    }

    private fun spawnSwordDisplay(player: Player): ItemDisplay? = try {
        player.world.spawn(player.location.clone().add(0.0, 1.6, 0.0), ItemDisplay::class.java) { display ->
            display.setItemStack(ItemStack(Material.DIAMOND_SWORD))
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            display.transformation = Transformation(
                Vector3f(0.0f, 0.0f, 0.0f),
                AxisAngle4f(Math.toRadians(-135.0).toFloat(), 0.0f, 0.0f, 1.0f),
                Vector3f(0.64f, 0.64f, 0.64f),
                AxisAngle4f(0.0f, 0.0f, 0.0f, 1.0f)
            )
            display.interpolationDelay = 0
            display.interpolationDuration = 2
            display.teleportDuration = 2
            display.viewRange = 32.0f
            display.shadowRadius = 0.0f
            display.shadowStrength = 0.0f
            display.isPersistent = false
            display.isInvulnerable = true
            display.setGravity(false)
            display.addScoreboardTag("kunlun_flying_sword")
        }
    } catch (throwable: Throwable) {
        plugin.logger.warning("生成昆仑飞剑展示失败: ${throwable.message}")
        null
    }

    private fun dealSwordDamage(attacker: Player, target: LivingEntity, amount: Double) {
        if (amount <= 0.0 || !isMonster(target)) return

        val previousNoDamageTicks = target.noDamageTicks
        target.setMetadata(INTERNAL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        target.setMetadata(ARMORED_EXACT_DAMAGE_METADATA, FixedMetadataValue(plugin, amount))
        target.noDamageTicks = 0

        try {
            target.damage(amount, attacker)
        } finally {
            if (target.hasMetadata(INTERNAL_DAMAGE_METADATA)) {
                target.removeMetadata(INTERNAL_DAMAGE_METADATA, plugin)
            }
            if (target.hasMetadata(ARMORED_EXACT_DAMAGE_METADATA)) {
                target.removeMetadata(ARMORED_EXACT_DAMAGE_METADATA, plugin)
            }
            if (!target.isDead && target.isValid) {
                // 只移除本次飞剑伤害新产生的无敌帧，保留此前普攻尚未结束的帧数。
                target.noDamageTicks = previousNoDamageTicks.coerceAtMost(target.maximumNoDamageTicks)
            }
        }
    }

    private fun findNearestMonster(center: Location, radius: Double, ownerId: UUID): LivingEntity? {
        val radiusSquared = radius * radius
        var nearest: LivingEntity? = null
        var nearestDistance = Double.MAX_VALUE

        for (entity in center.world.getNearbyEntities(center, radius, radius, radius)) {
            val candidate = entity as? LivingEntity ?: continue
            if (!isMonster(candidate)) continue
            if (countAttackSwords(ownerId, candidate.uniqueId) >= 2) continue
            val distance = candidate.location.distanceSquared(center)
            if (distance <= radiusSquared && distance < nearestDistance) {
                nearest = candidate
                nearestDistance = distance
            }
        }
        return nearest
    }

    private fun finishReserveState(
        ownerId: UUID,
        state: ReserveState,
        player: Player?,
        healOwner: Boolean
    ) {
        if (!reserveStates.remove(ownerId, state)) return

        val remaining = state.swords.count { it.isValid }
        state.swords.forEach { if (it.isValid) it.remove() }
        state.swords.clear()

        if (healOwner && player != null && player.isOnline && !player.isDead) {
            val healAmount = min(state.finishHealCap, remaining * state.finishHealPerSword)
            healPlayer(player, healAmount)
        }

        if (remaining > 0 && player != null && player.isOnline && state.finishCooldownReductionPerSword > 0.0) {
            val reduction = remaining * state.finishCooldownReductionPerSword
            // 延迟到下一 tick：若这是再次释放导致旧飞剑消失，新的15秒冷却会先由管理器写入，
            // 随后再正确减去未出鞘飞剑对应的秒数，并同步原版物品冷却动画。
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                mainPlugin.weaponSkillManager.reduceCooldown(player, reduction, Material.DIAMOND_SWORD)
            })
        }
    }

    private fun finishAttackSword(displayId: UUID, state: AttackSwordState) {
        if (!attackSwords.remove(displayId, state)) return
        if (state.display.isValid) state.display.remove()
    }

    private fun cleanupAttackSwords(ownerId: UUID) {
        for ((displayId, state) in attackSwords) {
            if (state.ownerId == ownerId) {
                finishAttackSword(displayId, state)
            }
        }
    }

    private fun countAttackSwords(ownerId: UUID, targetId: UUID): Int {
        return attackSwords.values.count { it.ownerId == ownerId && it.targetId == targetId }
    }

    private fun healPlayer(player: Player, amount: Double) {
        if (amount <= 0.0) return
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
        val before = player.health
        player.health = min(maxHealth, before + amount)
        if (player.health <= before) return

        player.world.spawnParticle(Particle.HEART, player.location.clone().add(0.0, 1.4, 0.0), 6, 0.5, 0.45, 0.5, 0.05)
        player.world.spawnParticle(Particle.END_ROD, player.location.clone().add(0.0, 1.0, 0.0), 18, 0.55, 0.7, 0.55, 0.04)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 1.55f)
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity.isValid && !entity.isDead && tags.contains(PANLING_TAG) && tags.contains(MONSTER_TAG)
    }

    override fun deactivate(player: Player) {
        reserveStates[player.uniqueId]?.let {
            finishReserveState(player.uniqueId, it, player, false)
        }
        cleanupAttackSwords(player.uniqueId)
    }
}
