package com.hjh_database.dungeon.shengshan

import com.hjh_database.combat.MonsterDamageClassification
import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.boss.BarColor
import org.bukkit.block.Block
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Arrow
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Ghast
import org.bukkit.entity.Guardian
import org.bukkit.entity.LargeFireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.entity.Trident
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private fun distanceToSegmentSquared(point: Location, start: Location, end: Location): Double {
    val segment = end.toVector().subtract(start.toVector())
    val lengthSquared = segment.lengthSquared()
    if (lengthSquared < 1.0E-6) return point.toVector().distanceSquared(start.toVector())
    val t = point.toVector().subtract(start.toVector()).dot(segment).div(lengthSquared).coerceIn(0.0, 1.0)
    return point.toVector().distanceSquared(start.toVector().add(segment.multiply(t)))
}

internal class ReworkedThunderBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : ThunderBossController(manager, session, boss) {
    private data class ThunderTrident(
        val entity: Trident,
        val destination: Location,
        var direction: Vector,
        var lastLocation: Location,
        var landed: Boolean = false
    )

    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0

    private val tridents = LinkedHashMap<UUID, ThunderTrident>()
    private val chaseSpeedKey = NamespacedKey(manager.plugin, "thunder_blade_chase_speed")
    private val baseMovementSpeed = boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: 0.3
    private val thunderEyeA = Location(boss.world, 3148.5, 133.0, -1828.0)
    private val thunderEyeB = Location(boss.world, 3148.5, 133.0, -1851.0)
    private var recallStarted = false
    private var tridentsReturning = false
    private val returnHitPlayers = HashSet<UUID>()
    private var empowered = false
    private var nextPassiveStrikeAt = -1
    private var passiveTelegraphSerial = 0
    private var pendingPassiveTargets: List<Location> = emptyList()
    private var pendingPassiveKeys: List<String> = emptyList()

    override fun damageTakenMultiplier(event: EntityDamageByEntityEvent): Double {
        if (!empowered) return super.damageTakenMultiplier(event)
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        return if (attacker != null && isUnderThunderEye(attacker.location)) 1.0 else 0.30
    }

    override fun onTick() {
        super.onTick()
        if (invulnerable) return
        val interval = if (empowered) 140 else 200
        if (nextPassiveStrikeAt < 0) {
            nextPassiveStrikeAt = elapsedTicks + interval
            return
        }
        val remaining = nextPassiveStrikeAt - elapsedTicks
        if (pendingPassiveTargets.isEmpty() && remaining <= 20) beginPassiveTelegraph()
        if (elapsedTicks >= nextPassiveStrikeAt) {
            resolvePassiveStrike()
            nextPassiveStrikeAt = elapsedTicks + interval
        }
    }

    private fun beginPassiveTelegraph() {
        pendingPassiveTargets = passiveTargets()
        passiveTelegraphSerial++
        pendingPassiveKeys = pendingPassiveTargets.indices.map { "thunder_passive_${passiveTelegraphSerial}_$it" }
        pendingPassiveTargets.forEachIndexed { index, locked ->
            playEffect(ShengShanEffect.THUNDER_WARNING_RING, locked,
                options = ShengShanEffectOptions(radius = 2.0, height = .25, durationTicks = 20,
                    intervalTicks = 2, key = pendingPassiveKeys[index]))
            soundNearby(locked, Sound.BLOCK_BEACON_POWER_SELECT, 18.0, .7f, 1.5f)
        }
    }

    private fun resolvePassiveStrike() {
        val hit = HashSet<UUID>()
        pendingPassiveTargets.forEachIndexed { index, locked ->
            pendingPassiveKeys.getOrNull(index)?.let(::stopEffect)
            boss.world.strikeLightningEffect(locked)
            playEffect(ShengShanEffect.LIGHTNING_COLUMN, locked.clone().add(0.0, 18.0, 0.0), locked)
            playEffect(ShengShanEffect.THUNDER_BURST, locked,
                options = ShengShanEffectOptions(radius = 3.0, height = .25))
            soundNearby(locked, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 42.0, 1.15f, 1.05f)
            players().filter { it.uniqueId !in hit && horizontalDistanceSquared(it.location, locked) <= 4.0 }
                .forEach {
                    hit += it.uniqueId
                    dealDamage(it, 10.0)
                    it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 1, false, true, true), true)
                }
        }
        pendingPassiveTargets = emptyList()
        pendingPassiveKeys = emptyList()
    }

    private fun passiveTargets(): List<Location> {
        val participants = players()
        if (participants.isEmpty()) return emptyList()
        if (!empowered) return listOf(groundPoint(participants.random().location))
        return distributedPlayerTargets(10, participants)
    }

    /** 玩家脚下优先，盈余落点随机散布在玩家附近；同一批区域之间至少间隔五格。 */
    private fun distributedPlayerTargets(total: Int, participants: List<Player> = players()): List<Location> {
        if (total <= 0 || participants.isEmpty()) return emptyList()
        val result = ArrayList<Location>(total)
        val minimumSquared = 25.0
        participants.shuffled().take(total).forEach { player ->
            val preferred = groundPoint(player.location)
            if (result.all { horizontalDistanceSquared(it, preferred) >= minimumSquared }) result += preferred
            else randomSeparatedPoint(player.location, result, minimumSquared)?.let(result::add)
        }
        var attempts = 0
        while (result.size < total && attempts++ < 300) {
            val anchor = participants.random().location
            randomSeparatedPoint(anchor, result, minimumSquared)?.let(result::add)
        }
        val fallbackCandidates = buildList {
            for (x in 3120..3178 step 6) for (z in -1868..-1810 step 6) {
                add(Location(boss.world, x + .5, 129.2, z + .5))
            }
        }.shuffled().toMutableList()
        while (result.size < total && fallbackCandidates.isNotEmpty()) {
            val index = fallbackCandidates.indices.maxByOrNull { candidateIndex ->
                val candidate = fallbackCandidates[candidateIndex]
                result.minOfOrNull { horizontalDistanceSquared(it, candidate) } ?: Double.MAX_VALUE
            } ?: break
            val candidate = fallbackCandidates.removeAt(index)
            if (result.all { horizontalDistanceSquared(it, candidate) >= minimumSquared }) result += candidate
        }
        return result.take(total)
    }

    private fun randomSeparatedPoint(anchor: Location, existing: List<Location>, minimumSquared: Double): Location? {
        val random = ThreadLocalRandom.current()
        repeat(24) {
            val angle = random.nextDouble(0.0, PI * 2.0)
            val radius = random.nextDouble(5.0, 15.0)
            val x = (anchor.x + cos(angle) * radius).coerceIn(3119.5, 3179.5)
            val z = (anchor.z + sin(angle) * radius).coerceIn(-1869.5, -1809.5)
            val candidate = groundPoint(Location(boss.world, x, 129.0, z))
            if (existing.all { horizontalDistanceSquared(it, candidate) >= minimumSquared }) return candidate
        }
        return null
    }

    private fun groundPoint(location: Location): Location =
        manager.groundLocation(boss.world, location.x, location.z, 128).apply { y = max(129.2, y + .2) }

    override fun castNormalSkill(index: Int) {
        if (index == 0) thunderBlade() else thunderTridents()
    }

    private fun thunderBlade() {
        chaseForSlash(1, LinkedHashSet())
    }

    private fun chaseForSlash(number: Int, usedTargets: MutableSet<UUID>) {
        val candidates = players().filter { it.isOnline && !it.isDead }
        val target = (candidates.filter { it.uniqueId !in usedTargets }.ifEmpty { candidates })
            .minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
            ?: return finishThunderBlade()
        usedTargets += target.uniqueId
        (boss as? Mob)?.target = target
        enableChaseSpeed()
        boss.setAI(true)
        var chaseTicks = 0
        var previous = boss.location.clone()
        val pathHits = HashSet<UUID>()
        lateinit var chase: org.bukkit.scheduler.BukkitTask
        chase = every(0L, 1L) {
            chaseTicks++
            if (!target.isOnline || target.isDead) {
                chase.cancel(); disableChaseSpeed(); chaseForSlash(number, usedTargets); return@every
            }
            val current = boss.location.clone()
            players().filter { player ->
                player.uniqueId != target.uniqueId && player.uniqueId !in pathHits &&
                    kotlin.math.abs(player.location.y - current.y) <= 3.0 &&
                    distanceToSegmentSquared(player.location.clone().add(0.0, 1.0, 0.0),
                        previous.clone().add(0.0, 1.0, 0.0), current.clone().add(0.0, 1.0, 0.0)) <= 2.25
            }.forEach { pathHits += it.uniqueId; dealDamage(it, 20.0) }
            previous = current
            if (horizontalDistanceSquared(target.location, boss.location) <= 25.0 || chaseTicks >= 200) {
                chase.cancel(); disableChaseSpeed(); slash(target, number, usedTargets)
            } else {
                val direction = horizontalDirection(boss.location, target.location)
                boss.velocity = direction.multiply(.72).setY(boss.velocity.y)
                if (chaseTicks % 8 == 0) playEffect(ShengShanEffect.THUNDER_CHARGE,
                    boss.location.clone().subtract(direction.clone().multiply(1.2)).add(0.0, .7, 0.0),
                    options = ShengShanEffectOptions(radius = .7, height = .8))
            }
        }
    }

    private fun slash(target: Player, number: Int, usedTargets: MutableSet<UUID>) {
        if (!target.isOnline || target.isDead) return chaseForSlash(number, usedTargets)
        boss.setAI(false); boss.velocity = Vector()
        val direction = horizontalDirection(boss.location, target.location)
        boss.teleport(boss.location.clone().apply { this.direction = direction })
        val right = Vector(-direction.z, 0.0, direction.x)
        val start = groundPoint(boss.location.clone().add(direction.clone().multiply(.75)))
        val warningKeys = (-2..2).map { offset ->
            val key = "thunder_blade_${number}_$offset"
            val lineStart = start.clone().add(right.clone().multiply(offset.toDouble()))
            val lineEnd = lineStart.clone().add(direction.clone().multiply(10.0))
            playEffect(ShengShanEffect.THUNDER_SLASH, lineStart, lineEnd,
                ShengShanEffectOptions(radius = .38, height = .20, durationTicks = 12, intervalTicks = 1, key = key))
            key
        }
        soundNearby(boss.location, Sound.BLOCK_BEACON_POWER_SELECT, 24.0, .65f, 1.65f)
        later(12L) {
            warningKeys.forEach(::stopEffect)
            playEffect(ShengShanEffect.THUNDER_BURST, boss.location.clone().add(0.0, 1.0, 0.0),
                options = ShengShanEffectOptions(radius = 2.0, height = 1.2))
            soundNearby(boss.location, Sound.ITEM_TRIDENT_RIPTIDE_1, 32.0, .95f, 1.3f)
            val hit = HashSet<UUID>()
            var ticks = 0
            var travelled = 0.0
            lateinit var dash: org.bukkit.scheduler.BukkitTask
            dash = every(0L, 1L) {
                ticks++
                val current = boss.location.clone()
                val remaining = (10.0 - travelled).coerceAtLeast(0.0)
                val step = minOf(1.65, remaining)
                val wall = boss.world.rayTraceBlocks(boss.eyeLocation, direction, step + .35,
                    FluidCollisionMode.NEVER, true)
                val next = if (wall?.hitBlock?.isPassable == false) current else
                    current.clone().add(direction.clone().multiply(step)).apply { this.direction = direction }
                if (next !== current) boss.teleport(next)
                players().filter { it.uniqueId !in hit && distanceToSegmentSquared(it.location.clone().add(0.0, 1.0, 0.0),
                    current.clone().add(0.0, 1.0, 0.0), next.clone().add(0.0, 1.0, 0.0)) <= 4.0 }.forEach {
                    hit += it.uniqueId; dealDamage(it, 45.0)
                }
                travelled += kotlin.math.sqrt(horizontalDistanceSquared(current, next))
                if (travelled >= 10.0 || ticks >= 12 || wall?.hitBlock?.isPassable == false) {
                    dash.cancel(); boss.velocity = Vector()
                    if (number < 3) later(6L) { chaseForSlash(number + 1, usedTargets) }
                    else finishThunderBlade()
                }
            }
        }
    }

    private fun enableChaseSpeed() {
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attribute ->
            attribute.getModifier(chaseSpeedKey)?.let(attribute::removeModifier)
            attribute.addTransientModifier(AttributeModifier(chaseSpeedKey, .75, AttributeModifier.Operation.ADD_SCALAR))
        }
    }

    private fun disableChaseSpeed() {
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attribute ->
            attribute.getModifier(chaseSpeedKey)?.let(attribute::removeModifier)
        }
    }

    private fun finishThunderBlade() {
        disableChaseSpeed()
        boss.setAI(true)
        finishNormalSkill()
    }

    private fun thunderTridents() {
        recallStarted = false
        tridentsReturning = false
        returnHitPlayers.clear()
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        val hover = Trigram.THUNDER.spawn(boss.world).clone().add(0.0, 8.0, 0.0)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = hover.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 90) {
                returnTask.cancel(); boss.teleport(hover); boss.velocity = Vector(); beginTridentWarning()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.55)).apply {
                    this.direction = direction
                })
                if (ticks % 5 == 0) playEffect(ShengShanEffect.THUNDER_TRAIL,
                    boss.location.clone().subtract(direction.clone().multiply(1.5)), boss.location,
                    ShengShanEffectOptions(radius = .45, height = .35))
                if (ticks % 12 == 0) {
                    boss.world.strikeLightningEffect(boss.location)
                    playEffect(ShengShanEffect.LIGHTNING_COLUMN,
                        boss.location.clone().add(0.0, 14.0, 0.0), boss.location,
                        ShengShanEffectOptions(radius = .4, height = .4, color = 0xFFF3A0))
                    soundNearby(boss.location, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 30.0, .65f, 1.3f)
                }
            }
        }
    }

    private fun beginTridentWarning() {
        boss.world.strikeLightningEffect(boss.location)
        playEffect(ShengShanEffect.LIGHTNING_COLUMN, boss.location.clone().add(0.0, 22.0, 0.0), boss.location)
        sound(Sound.ITEM_TRIDENT_THUNDER, 1.15f, .82f)
        message("§6靐正在引雷淬炼三叉戟，避开地面上显现的投掷与回收路径！")
        val amount = if (empowered) 10 else 5
        val destinations = distributedPlayerTargets(amount).map { ground ->
            ground.clone().add(0.0, 1.10, 0.0)
        }
        if (destinations.isEmpty()) return finishTridentSkill()
        val groundStart = Location(boss.world, boss.location.x, 129.05, boss.location.z)
        val warningKeys = drawTridentPathWarnings("out", destinations.map { destination ->
            groundStart.clone() to Location(boss.world, destination.x, 129.05, destination.z)
        })
        phaseBar("§e引雷三叉 即将投掷", 20, BarColor.YELLOW, onComplete = {
            soundNearby(boss.location, Sound.ITEM_TRIDENT_THROW, 36.0, 1.0f, .9f)
            warningKeys.forEach(::stopEffect)
            tridents.values.forEach { it.entity.remove() }
            tridents.clear()
            destinations.forEach { destination ->
                val direction = destination.toVector().subtract(boss.eyeLocation.toVector()).normalize()
                val facing = boss.location.clone().apply { this.direction = direction }
                boss.setRotation(facing.yaw, facing.pitch)
                val trident = boss.launchProjectile(Trident::class.java, direction.clone().multiply(2.40)).apply {
                    shooter = boss
                    pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                    setGravity(false)
                    isGlowing = true
                }
                // 原版碰撞事件只用于定位，真正的普攻伤害由 onDamageByEntity -> dealDamage 统一结算。
                MonsterDamageClassification.markSyntheticProjectile(manager.plugin, trident)
                setTridentFacing(trident, direction)
                val spawn = trident.location.clone()
                own(trident)
                manager.trackEntity(session, trident, "thunder_trident")
                tridents[trident.uniqueId] = ThunderTrident(
                    trident, destination.clone(), direction, spawn.clone()
                )
            }
            boss.swingMainHand()
            animateOutgoingTridents()
        })
    }

    private fun drawTridentPathWarnings(
        phase: String,
        paths: List<Pair<Location, Location>>
    ): List<String> {
        val warningKeys = ArrayList<String>()
        paths.forEachIndexed { index, (groundStart, groundEnd) ->
            val groundDirection = horizontalDirection(groundStart, groundEnd)
            val right = Vector(-groundDirection.z, 0.0, groundDirection.x)
            listOf(-.6, 0.0, .6).forEachIndexed { line, offset ->
                val key = "thunder_trident_${phase}_${index}_$line"
                warningKeys += key
                playEffect(ShengShanEffect.THUNDER_TRAIL,
                    groundStart.clone().add(right.clone().multiply(offset)),
                    groundEnd.clone().add(right.clone().multiply(offset)),
                    ShengShanEffectOptions(radius = .55, height = .08, color = 0xFFF27A,
                        durationTicks = 20, intervalTicks = 1, key = key))
            }
        }
        return warningKeys
    }

    private fun animateOutgoingTridents() {
        var ticks = 0
        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = every(0L, 1L) {
            ticks++
            tridents.values.forEach { flight ->
                if (!flight.landed && flight.entity.isValid) {
                    val current = flight.entity.location.clone()
                    playEffect(ShengShanEffect.THUNDER_TRAIL, flight.lastLocation, current,
                        ShengShanEffectOptions(radius = .35, height = .25, color = 0xFFF7B0))
                    flight.lastLocation = current
                    val remaining = flight.destination.toVector().subtract(current.toVector())
                    if (remaining.lengthSquared() <= 5.76 || remaining.dot(flight.direction) <= 0.0) {
                        flight.landed = true
                        flight.entity.velocity = Vector()
                    } else {
                        // 每 tick 校正速度，保留原版投射物插值和飞行动画，同时避免弹道下坠或偏航。
                        setTridentFacing(flight.entity, flight.direction)
                    }
                }
            }
            if (ticks >= 60 || tridents.values.all { it.landed || !it.entity.isValid }) {
                task.cancel()
                tridents.values.forEach { it.landed = true; it.entity.velocity = Vector() }
                later(10L) { beginTridentRecallWarning() }
            }
        }
    }

    override fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val flight = tridents[event.damager.uniqueId] ?: return super.onDamageByEntity(event)
        event.isCancelled = true
        val player = (event.entity as? Player)?.takeIf { it in players() }
        if (tridentsReturning) {
            if (player != null && returnHitPlayers.add(player.uniqueId)) dealNormalAttackDamage(player, 30.0)
            setTridentFacing(flight.entity, flight.direction)
        } else {
            if (player != null) dealNormalAttackDamage(player, 30.0)
            flight.landed = true
            flight.entity.velocity = Vector()
        }
    }

    override fun onProjectileHit(event: ProjectileHitEvent) {
        val flight = tridents[event.entity.uniqueId] ?: return super.onProjectileHit(event)
        if (tridentsReturning) {
            event.isCancelled = true
            setTridentFacing(flight.entity, flight.direction)
        } else {
            flight.landed = true
            flight.entity.velocity = Vector()
            flight.entity.setGravity(false)
        }
    }

    private fun beginTridentRecallWarning() {
        if (recallStarted) return
        recallStarted = true
        if (tridents.isEmpty()) return finishTridentSkill()
        val origin = boss.eyeLocation
        message("§6三叉戟即将沿雷痕回归，立即避开地面上的回收路径！")
        val warningKeys = drawTridentPathWarnings("return", tridents.values.map { flight ->
            Location(boss.world, flight.entity.location.x, 129.05, flight.entity.location.z) to
                Location(boss.world, origin.x, 129.05, origin.z)
        })
        phaseBar("§e引雷三叉 即将回收", 20, BarColor.YELLOW, onComplete = {
            warningKeys.forEach(::stopEffect)
            soundNearby(boss.location, Sound.ITEM_TRIDENT_RETURN, 38.0, 1.1f, .7f)
            tridentsReturning = true
            returnHitPlayers.clear()
            val returning = tridents.values.mapNotNull { flight ->
                if (!flight.entity.isValid) return@mapNotNull null
                val start = flight.entity.location.clone()
                flight.entity.remove()
                val direction = origin.toVector().subtract(start.toVector()).normalize()
                val trident = boss.world.spawn(start.apply { this.direction = direction }, Trident::class.java) {
                    it.shooter = boss
                    it.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                    it.setGravity(false)
                    it.velocity = direction.clone().multiply(2.40)
                    it.isGlowing = true
                }
                setTridentFacing(trident, direction)
                own(trident)
                manager.trackEntity(session, trident, "thunder_trident_return")
                ThunderTrident(trident, origin.clone(), direction, start.clone())
            }
            tridents.clear()
            returning.forEach { tridents[it.entity.uniqueId] = it }
            animateReturningTridents(origin)
        })
    }

    private fun animateReturningTridents(origin: Location) {
        var ticks = 0
        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = every(0L, 1L) {
            ticks++
            tridents.values.forEach { flight ->
                if (!flight.landed && flight.entity.isValid) {
                    val current = flight.entity.location.clone()
                    players().filter { it.uniqueId !in returnHitPlayers &&
                        distanceToSegmentSquared(it.eyeLocation, flight.lastLocation, current) <= 2.0
                    }.forEach {
                        returnHitPlayers += it.uniqueId
                        dealDamage(it, 30.0)
                    }
                    playEffect(ShengShanEffect.THUNDER_TRAIL, flight.lastLocation, current,
                        ShengShanEffectOptions(radius = .35, height = .25, color = 0xFFF7B0))
                    flight.lastLocation = current
                    val remaining = origin.toVector().subtract(current.toVector())
                    if (remaining.lengthSquared() <= 5.76 || remaining.dot(flight.direction) <= 0.0) {
                        flight.landed = true
                        flight.entity.remove()
                    } else {
                        setTridentFacing(flight.entity, flight.direction)
                    }
                }
            }
            if (ticks >= 60 || tridents.values.all { it.landed || !it.entity.isValid }) {
                task.cancel()
                tridents.values.forEach { it.entity.remove() }
                tridents.clear()
                tridentsReturning = false
                returnHitPlayers.clear()
                boss.world.strikeLightningEffect(boss.location)
                playEffect(ShengShanEffect.THUNDER_BURST, boss.location,
                    options = ShengShanEffectOptions(radius = 6.0, height = 4.0))
                sound(boss.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, .8f)
                finishTridentSkill()
            }
        }
    }

    private fun setTridentFacing(trident: Trident, direction: Vector) {
        val facing = trident.location.clone().apply { this.direction = direction }
        trident.setRotation(facing.yaw, facing.pitch)
        trident.velocity = direction.clone().multiply(2.40)
    }

    private fun finishTridentSkill() {
        boss.setGravity(true)
        boss.setAI(true)
        finishNormalSkill()
    }

    override fun startUltimate() {
        invulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        val destination = Trigram.THUNDER.spawn(boss.world).clone().add(0.0, 8.0, 0.0)
        var moving = 0
        lateinit var move: org.bukkit.scheduler.BukkitTask
        move = every(0L, 1L) {
            moving++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || moving >= 140) {
                move.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginThunderEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.36)).apply {
                    this.direction = direction
                })
                if (moving % 5 == 0) playEffect(ShengShanEffect.THUNDER_TRAIL,
                    boss.location.clone().subtract(direction.clone().multiply(2.0)).add(0.0, 1.0, 0.0),
                    boss.location.clone().add(0.0, 1.0, 0.0))
            }
        }
    }

    private fun beginThunderEmpowerment() {
        elder("ultimate_empower",
            "它正在借天雷强化自身！§e阵眼能够扰乱护体雷势，站在阵眼下攻击便可穿透它的雷甲！",
            firstOnly = false)
        playEffect(ShengShanEffect.THUNDER_STORM, boss.location.clone().add(0.0, 18.0, 0.0),
            options = ShengShanEffectOptions(radius = 8.0, height = 5.0, durationTicks = 100, intervalTicks = 2,
                key = "thunder_empower_crown"))
        repeat(17) { index ->
            later((index * 6).toLong()) {
                boss.world.strikeLightningEffect(boss.location)
                playEffect(ShengShanEffect.LIGHTNING_COLUMN,
                    boss.location.clone().add(0.0, 26.0, 0.0), boss.location,
                    ShengShanEffectOptions(radius = .5 + (index % 5) * .12, height = .5, color = 0xFFF7B0))
                sound(boss.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, .72f + (index % 5) * .05f)
            }
        }
        later(100L) {
            stopEffect("thunder_empower_crown")
            applyThunderEmpowerment()
            finishUltimateTransformation()
            incomingDamageMultiplier = .30
            boss.setGravity(true)
            boss.setAI(true)
            message("§6天雷灌体完成！靐的步伐与攻势骤然增强，护体雷甲也已经凝成！")
        }
    }

    private fun applyThunderEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 35.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
        clearPassiveTelegraph()
        nextPassiveStrikeAt = elapsedTicks + 140
    }

    private fun clearPassiveTelegraph() {
        pendingPassiveKeys.forEach(::stopEffect)
        pendingPassiveKeys = emptyList()
        pendingPassiveTargets = emptyList()
    }

    private fun isUnderThunderEye(location: Location): Boolean =
        (horizontalDistanceSquared(location, thunderEyeA) <= 25.0 && location.y <= thunderEyeA.y + 5.0) ||
            (horizontalDistanceSquared(location, thunderEyeB) <= 25.0 && location.y <= thunderEyeB.y + 5.0)

    override fun shutdown(restoreTerrain: Boolean) {
        disableChaseSpeed()
        clearPassiveTelegraph()
        tridents.values.forEach { it.entity.remove() }
        tridents.clear()
        tridentsReturning = false
        returnHitPlayers.clear()
        super.shutdown(restoreTerrain)
    }
}

internal class ReworkedSkyBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : SkyBossController(manager, session, boss) {
    private data class FireballTrace(val origin: Location)

    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0

    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .3 }
    private val baseFlyingSpeed by lazy { boss.getAttribute(Attribute.FLYING_SPEED)?.baseValue ?: .3 }
    private val fireballs = LinkedHashMap<UUID, FireballTrace>()
    private val fireLineHitAt = HashMap<UUID, Int>()
    private var empowered = false
    private var effectSerial = 0
    private var solarFlightY: Double? = null

    override fun onUltimateWarningStarted() = Unit

    override fun onTick() {
        val fixedY = solarFlightY
        if (fixedY == null) {
            super.onTick()
            return
        }
        if (kotlin.math.abs(boss.location.y - fixedY) > .01) {
            boss.teleport(boss.location.clone().apply { y = fixedY })
        }
        if (boss.velocity.y != 0.0) boss.velocity = boss.velocity.clone().apply { y = 0.0 }
    }

    override fun onNormalWarningStarted(index: Int) {
        effectSerial++
        if (index == 0) {
            playEffect(ShengShanEffect.FIRE_CRACK, boss.location.clone().add(0.0, boss.height * .5, 0.0),
                options = ShengShanEffectOptions(radius = 3.4, height = 2.6, color = 0xFF8A32,
                    durationTicks = 30, intervalTicks = 2, key = "sky_feather_charge_$effectSerial"))
            sound(boss.location, Sound.BLOCK_FIRE_AMBIENT, .85f, 1.15f)
        } else {
            playEffect(ShengShanEffect.BUILD_GLOW, boss.location.clone().add(0.0, boss.height * .5, 0.0),
                options = ShengShanEffectOptions(radius = 3.8, height = 3.0, color = 0xFFF3B0,
                    durationTicks = 20, intervalTicks = 2, key = "sky_solar_charge_$effectSerial"))
            sound(boss.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .9f, 1.35f)
        }
    }

    override fun castNormalSkill(index: Int) { if (index == 0) fallingFeathers() else solarFlight() }

    private fun fallingFeathers() {
        val participants = players()
        if (participants.isEmpty()) return finishNormalSkill()
        val amount = if (empowered) 15 else 10
        val targets = distributedTargets(amount, participants)
        if (targets.isEmpty()) return finishNormalSkill()
        val batchHits = HashMap<UUID, Int>()
        val origin = boss.location.clone().add(0.0, boss.height * .55, 0.0)
        val displays = targets.mapIndexed { index, _ ->
            spawnVisualItem(origin.clone(), Material.BLAZE_ROD, .72f, "sky_fire_feather_$index").apply {
                teleportDuration = 1
            }
        }
        val previous = MutableList(targets.size) { origin.clone() }
        sound(boss.location, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.15f)
        var flightTicks = 0
        lateinit var flight: org.bukkit.scheduler.BukkitTask
        flight = every(0L, 1L) {
            flightTicks++
            val ratio = (flightTicks / 20.0).coerceIn(0.0, 1.0)
            targets.forEachIndexed { index, target ->
                val next = origin.clone().add(target.toVector().subtract(origin.toVector()).multiply(ratio)).apply {
                    y += sin(PI * ratio) * 5.5
                }
                displays[index].takeIf { it.isValid }?.teleport(next)
                playEffect(ShengShanEffect.SKY_FEATHER, previous[index], next,
                    ShengShanEffectOptions(radius = .18, height = .18, color = 0xFFB347))
                previous[index] = next
            }
            if (flightTicks >= 20) {
                flight.cancel()
                displays.forEach(Entity::remove)
                beginFeatherExplosionWarnings(targets, batchHits)
            }
        }
        // 火羽已经抛出，晶立即恢复原版移动；爆炸演出独立完成。
        boss.setAI(true)
        finishNormalSkill()
    }

    private fun beginFeatherExplosionWarnings(targets: List<Location>, batchHits: MutableMap<UUID, Int>) {
        effectSerial++
        val keys = targets.indices.map { "sky_feather_land_${effectSerial}_$it" }
        targets.forEachIndexed { index, target ->
            playEffect(ShengShanEffect.FIRE_WARNING_RING, target,
                options = ShengShanEffectOptions(radius = 3.0, height = .2, color = 0xF7D86A,
                    durationTicks = 10, intervalTicks = 1, key = keys[index]))
            playEffect(ShengShanEffect.FIRE_CRACK, target,
                options = ShengShanEffectOptions(radius = 1.1, height = .25, color = 0xF7D86A))
        }
        later(5L) { targets.forEachIndexed { index, target ->
            playEffect(ShengShanEffect.FIRE_WARNING_RING, target,
                options = ShengShanEffectOptions(radius = 3.0, height = .2, color = 0xE8702A,
                    durationTicks = 5, intervalTicks = 1, key = keys[index]))
        } }
        later(8L) { targets.forEachIndexed { index, target ->
            playEffect(ShengShanEffect.FIRE_WARNING_RING, target,
                options = ShengShanEffectOptions(radius = 3.0, height = .2, color = 0xFF3220,
                    durationTicks = 2, intervalTicks = 1, key = keys[index]))
        } }
        later(10L) {
            targets.forEachIndexed { index, target ->
                stopEffect(keys[index])
                playEffect(ShengShanEffect.FIRE_BURST, target,
                    options = ShengShanEffectOptions(radius = 3.0, height = 2.0, color = 0xE84325))
                soundNearby(target, Sound.ENTITY_BLAZE_SHOOT, 12.0, .65f, 1.25f)
                players().filter { horizontalDistanceSquared(it.location, target) <= 9.0 &&
                    (batchHits[it.uniqueId] ?: 0) < 2 }.forEach {
                    batchHits[it.uniqueId] = (batchHits[it.uniqueId] ?: 0) + 1
                    dealDamage(it, 40.0)
                }
            }
        }
    }

    private fun solarFlight() {
        val targets = distributedTargets(5)
        if (targets.isEmpty()) return finishNormalSkill()
        val flightY = boss.location.y.coerceAtMost(132.75)
        solarFlightY = flightY
        val waypoints = targets.map { Location(boss.world, it.x, flightY, it.z) }
        val home = Trigram.SKY.spawn(boss.world).clone().apply { y = flightY }
        val path = arrayListOf(boss.location.clone().apply { y = flightY })
        boss.setAI(false); boss.isCollidable = false; boss.velocity = Vector()
        soundNearby(boss.location, Sound.ITEM_ELYTRA_FLYING, 40.0, 1.0f, 1.3f)
        var flightTrailTick = 0

        fun flyTo(index: Int) {
            val destination = if (index < waypoints.size) waypoints[index] else home
            lateinit var flight: org.bukkit.scheduler.BukkitTask
            flight = every(0L, 1L) {
                val delta = destination.toVector().subtract(boss.location.toVector()).setY(0.0)
                val distance = delta.length()
                if (distance <= 1.55) {
                    flight.cancel()
                    boss.teleport(destination)
                    path += destination.clone()
                    if (index < waypoints.size) flyTo(index + 1) else {
                        solarFlightY = null
                        boss.velocity = Vector(); boss.isCollidable = true; boss.setAI(true)
                        createUnifiedGoldenWaterfall(path)
                    }
                } else {
                    val direction = delta.normalize()
                    boss.teleport(boss.location.clone().add(direction.clone().multiply(1.55)).apply {
                        y = flightY; this.direction = direction
                    })
                    if (++flightTrailTick % 2 == 0) {
                        playEffect(ShengShanEffect.SKY_LIGHT,
                            boss.location.clone().subtract(direction.clone().multiply(1.5)), boss.location,
                            ShengShanEffectOptions(radius = .22, height = .22, color = 0xFFF2A0))
                    }
                }
            }
        }
        flyTo(0)
    }

    private fun createUnifiedGoldenWaterfall(path: List<Location>) {
        val segments = path.zipWithNext()
        if (segments.isEmpty()) return finishNormalSkill()
        val duration = if (empowered) 120 else 60
        effectSerial++
        val keys = ArrayList<String>()
        var sampleIndex = 0
        segments.forEach { (start, end) ->
            val length = kotlin.math.sqrt(horizontalDistanceSquared(start, end)).coerceAtLeast(.01)
            val samples = (length / 2.75).toInt().coerceAtLeast(1)
            for (index in 0..samples) {
                val ratio = index / samples.toDouble()
                val x = start.x + (end.x - start.x) * ratio
                val z = start.z + (end.z - start.z) * ratio
                val ground = groundPoint(Location(boss.world, x, 129.2, z))
                val top = Location(boss.world, x, start.y, z)
                val key = "sky_unified_fall_${effectSerial}_${sampleIndex++}"
                keys += key
                playEffect(ShengShanEffect.SKY_WATERFALL, top, ground,
                    ShengShanEffectOptions(radius = .22, height = .22, color = 0xFFE6A0,
                        durationTicks = duration, intervalTicks = 2, key = key))
            }
        }
        message("§6完整的曜日轨迹已经化作金光瀑布，立即离开光幕！")
        sound(boss.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.25f)
        val hitAt = HashMap<UUID, Int>()
        var ticks = 0
        lateinit var monitor: org.bukkit.scheduler.BukkitTask
        monitor = every(0L, 1L) {
            ticks++
            players().filter { player -> segments.any { (start, end) ->
                distanceToSegment2D(player.location, start, end) <= 3.0
            } }.forEach { player ->
                if (elapsedTicks - (hitAt[player.uniqueId] ?: -100) >= 20) {
                    hitAt[player.uniqueId] = elapsedTicks
                    dealDamage(player, 30.0)
                }
            }
            if (ticks == 60) finishNormalSkill()
            if (ticks >= duration) {
                monitor.cancel()
                keys.forEach(::stopEffect)
            }
        }
    }

    override fun startUltimate() {
        invulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        val destination = Trigram.SKY.spawn(boss.world)
        var ticks = 0
        lateinit var flight: org.bukkit.scheduler.BukkitTask
        flight = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 120) {
                flight.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginGoldenCloudEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.48)).apply { this.direction = direction })
                if (ticks % 4 == 0) playEffect(ShengShanEffect.SKY_CLOUD, boss.location,
                    options = ShengShanEffectOptions(radius = 1.4, height = 1.0))
            }
        }
    }

    private fun beginGoldenCloudEmpowerment() {
        effectSerial++
        elder("golden_cloud_empower",
            "它正在以金云重塑自身，气息与攻势都在迅速攀升！",
            firstOnly = false)
        playEffect(ShengShanEffect.SKY_CLOUD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 5.0, height = 4.0, color = 0xF7F7F0,
                durationTicks = 100, intervalTicks = 2, key = "sky_golden_cloud_$effectSerial"))
        playEffect(ShengShanEffect.BUILD_GLOW, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 4.0, height = 3.5, color = 0xFFF2B0,
                durationTicks = 100, intervalTicks = 2, key = "sky_golden_light_$effectSerial"))
        sound(boss.location, Sound.ENTITY_PHANTOM_FLAP, 1.0f, .65f)
        later(100L) {
            applyGoldenCloudEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(false); boss.setAI(true)
            message("§6金云锻体完成，晶的速度与攻势都变得更加强盛！")
        }
    }

    private fun applyGoldenCloudEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        boss.getAttribute(Attribute.FLYING_SPEED)?.baseValue = baseFlyingSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 35.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
    }

    override fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val fireball = event.entity as? LargeFireball
        if (!empowered || fireball == null || (fireball.shooter as? Entity)?.uniqueId != boss.uniqueId) {
            return super.onProjectileLaunch(event)
        }
        fireball.isGlowing = true
        own(fireball)
        manager.trackEntity(session, fireball, "sky_empowered_fireball")
        fireballs[fireball.uniqueId] = FireballTrace(fireball.location.clone())
        later(100L) {
            if (fireballs.remove(fireball.uniqueId) != null) fireball.remove()
        }
    }

    override fun onProjectileHit(event: ProjectileHitEvent) {
        val fireball = event.entity as? LargeFireball
        val trace = fireball?.let { fireballs.remove(it.uniqueId) }
        if (fireball == null || trace == null) return super.onProjectileHit(event)
        val endpoint = fireball.location.clone()
        createFireLine(trace.origin, endpoint)
    }

    private fun createFireLine(origin: Location, endpoint: Location) {
        effectSerial++
        val key = "sky_fire_line_$effectSerial"
        playEffect(ShengShanEffect.FIRE_LINE, origin, endpoint,
            ShengShanEffectOptions(radius = .32, height = .32, color = 0xFF6A2A,
                durationTicks = 60, intervalTicks = 2, key = key))
        sound(endpoint, Sound.ITEM_FIRECHARGE_USE, .8f, 1.1f)
        var ticks = 0
        lateinit var monitor: org.bukkit.scheduler.BukkitTask
        monitor = every(0L, 1L) {
            ticks++
            players().filter { distanceToSegmentSquared(it.eyeLocation, origin, endpoint) <= 2.25 }.forEach { player ->
                if (elapsedTicks - (fireLineHitAt[player.uniqueId] ?: -100) >= 20) {
                    fireLineHitAt[player.uniqueId] = elapsedTicks
                    dealDamage(player, 20.0)
                }
            }
            if (ticks >= 60) {
                monitor.cancel(); stopEffect(key)
            }
        }
    }

    private fun distributedTargets(total: Int, participants: List<Player> = players()): List<Location> {
        if (total <= 0 || participants.isEmpty()) return emptyList()
        val result = ArrayList<Location>(total)
        val minimumSquared = 25.0
        participants.shuffled().take(total).forEach { player ->
            val preferred = groundPoint(player.location)
            if (result.all { horizontalDistanceSquared(it, preferred) >= minimumSquared }) result += preferred
            else randomSeparatedTarget(player.location, result, minimumSquared)?.let(result::add)
        }
        var attempts = 0
        while (result.size < total && attempts++ < 400) {
            randomSeparatedTarget(participants.random().location, result, minimumSquared)?.let(result::add)
        }
        val fallback = buildList {
            for (x in 3120..3178 step 6) for (z in -1868..-1810 step 6) {
                add(Location(boss.world, x + .5, 129.2, z + .5))
            }
        }.shuffled().toMutableList()
        while (result.size < total && fallback.isNotEmpty()) {
            val index = fallback.indices.maxByOrNull { candidateIndex ->
                result.minOfOrNull { horizontalDistanceSquared(it, fallback[candidateIndex]) } ?: Double.MAX_VALUE
            } ?: break
            val candidate = fallback.removeAt(index)
            if (result.all { horizontalDistanceSquared(it, candidate) >= minimumSquared }) result += candidate
        }
        return result.take(total)
    }

    private fun randomSeparatedTarget(anchor: Location, existing: List<Location>, minimumSquared: Double): Location? {
        val random = ThreadLocalRandom.current()
        repeat(28) {
            val angle = random.nextDouble(0.0, PI * 2.0)
            val radius = random.nextDouble(5.0, 15.0)
            val x = (anchor.x + cos(angle) * radius).coerceIn(3119.5, 3179.5)
            val z = (anchor.z + sin(angle) * radius).coerceIn(-1869.5, -1809.5)
            val candidate = groundPoint(Location(boss.world, x, 129.2, z))
            if (existing.all { horizontalDistanceSquared(it, candidate) >= minimumSquared }) return candidate
        }
        return null
    }

    private fun groundPoint(location: Location): Location =
        manager.groundLocation(boss.world, location.x, location.z, 128).apply { y = max(129.2, y + .2) }

    private fun distanceToSegment2D(point: Location, start: Location, end: Location): Double {
        val vx = end.x - start.x; val vz = end.z - start.z
        val wx = point.x - start.x; val wz = point.z - start.z
        val length2 = vx * vx + vz * vz
        if (length2 <= 0.0001) return kotlin.math.sqrt(wx * wx + wz * wz)
        val ratio = ((wx * vx + wz * vz) / length2).coerceIn(0.0, 1.0)
        val dx = point.x - (start.x + ratio * vx)
        val dz = point.z - (start.z + ratio * vz)
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    override fun shutdown(restoreTerrain: Boolean) {
        fireballs.keys.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        fireballs.clear()
        fireLineHitAt.clear()
        solarFlightY = null
        super.shutdown(restoreTerrain)
    }
}

internal class ReworkedWaterBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : WaterBossController(manager, session, boss) {
    override val forceNearestTarget = true
    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0

    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .3 }
    private var empowered = false
    private var shield = 0.0
    private var nextShieldAt = 0
    private var shieldExpiresAt = 0
    private var shieldSerial = 0
    private var effectSerial = 0
    private var pendingWaveDirection: Vector? = null
    private val warningEffectKeys = ArrayList<String>()
    private var linkedPlayerId: UUID? = null
    private var linkedGuardId: UUID? = null
    private var chainGuardAnchor: Location? = null
    private var previousChainSlowness: PotionEffect? = null
    private var chainRunning = false
    private var chainCooldownStarted = false

    override fun showGenericNormalParticle(index: Int): Boolean = false
    override fun onUltimateWarningStarted() = Unit

    override fun castNormalSkill(index: Int) {
        if (index == 1) seaSoulChains() else forwardWave()
    }

    override fun onNormalWarningStarted(index: Int) {
        clearWarningEffects()
        effectSerial++
        if (index == 0) prepareWaveWarning() else prepareChainWarning()
    }

    private fun prepareWaveWarning() {
        val target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
        val direction = target?.let { horizontalDirection(boss.location, it.location) }
            ?: boss.location.direction.clone().setY(0.0).let {
                if (it.lengthSquared() < .001) Vector(1.0, 0.0, 0.0) else it.normalize()
            }
        pendingWaveDirection = direction.clone()
        boss.teleport(boss.location.clone().apply { this.direction = direction })
        renderWaterWall(boss.location.clone().add(direction.clone().multiply(2.5)), direction, warning = true)
        sound(boss.location, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS, .75f, .75f)
    }

    private fun prepareChainWarning() {
        val center = boss.location.clone().add(0.0, boss.height * .55, 0.0)
        listOf(Vector(2.5, .7, 0.0), Vector(-2.5, .7, 0.0), Vector(0.0, .7, 2.5), Vector(0.0, .7, -2.5))
            .forEachIndexed { index, offset ->
                val key = "water_chain_warning_${effectSerial}_$index"
                warningEffectKeys += key
                playEffect(ShengShanEffect.WATER_CHAIN, center, center.clone().add(offset),
                    ShengShanEffectOptions(durationTicks = 50, intervalTicks = 4, key = key))
            }
        sound(boss.location, Sound.ENTITY_GUARDIAN_AMBIENT, .8f, .8f)
    }

    override fun onTick() {
        super.onTick()
        if (shield > 0.0 && elapsedTicks >= shieldExpiresAt) clearShield()
    }

    override fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        super.onDamageByEntity(event)
        if (event.entity.uniqueId == boss.uniqueId && shield > 0.0) {
            if (elapsedTicks >= shieldExpiresAt) {
                clearShield()
                return
            }
            val absorbed = minOf(shield, event.damage)
            shield -= absorbed; event.damage -= absorbed
            if (event.damage <= 0.0) event.isCancelled = true
            playEffect(ShengShanEffect.WATER_BURST, boss.location,
                options = ShengShanEffectOptions(radius = 2.0, height = 2.0))
            soundNearby(boss.location, Sound.ENTITY_GENERIC_SPLASH, 18.0, .75f, 1.35f)
            if (shield <= 0.0) {
                stopEffect("water_passive_shield")
                playEffect(ShengShanEffect.WATER_SHIELD, boss.location,
                    options = ShengShanEffectOptions(radius = 3.0, height = 1.5, color = 0xD8F5FF))
            }
        }
    }

    override fun onBossDealtDamage(player: Player) {
        if (elapsedTicks < nextShieldAt) return
        nextShieldAt = elapsedTicks + 140
        shieldExpiresAt = elapsedTicks + 200
        shield = 20.0
        shieldSerial++
        val serial = shieldSerial
        stopEffect("water_passive_shield")
        playEffect(ShengShanEffect.WATER_SHIELD, boss.location,
            options = ShengShanEffectOptions(radius = 2.2, height = 2.0, durationTicks = 200, intervalTicks = 8,
                key = "water_passive_shield"))
        soundNearby(boss.location, Sound.AMBIENT_UNDERWATER_ENTER, 20.0, .55f, 1.25f)
        later(200L) {
            if (serial == shieldSerial && elapsedTicks >= shieldExpiresAt) clearShield()
        }
    }

    private fun forwardWave() {
        clearWarningEffects()
        val direction = pendingWaveDirection ?: boss.location.direction.clone().setY(0.0).let {
            if (it.lengthSquared() < .001) Vector(1.0, 0.0, 0.0) else it.normalize()
        }
        pendingWaveDirection = null
        val side = Vector(-direction.z, 0.0, direction.x)
        val castOrigin = boss.location.clone()
        var center = castOrigin.clone().add(direction.clone().multiply(2.5))
        val wallBaseY = castOrigin.y
        val hit = HashSet<UUID>()
        val speed = if (empowered) .30 else .25
        var ticks = 0
        var cooldownStarted = false
        boss.velocity = Vector(); boss.setAI(true)
        sound(boss.location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.1f, .75f)
        lateinit var wave: org.bukkit.scheduler.BukkitTask
        wave = every(0L, 1L) {
            ticks++
            center.add(direction.clone().multiply(speed))
            if (ticks == 1 || ticks % 5 == 0) renderWaterWall(center, direction, warning = false)
            if (ticks % 40 == 0) soundNearby(center, Sound.ENTITY_GENERIC_SPLASH, 24.0, .65f, .78f)
            players().filter { player ->
                val relative = player.location.toVector().subtract(center.toVector()).setY(0.0)
                player.uniqueId !in hit && abs(relative.dot(side)) <= 20.0 &&
                    abs(relative.dot(direction)) <= 1.35 &&
                    player.location.y + player.height >= wallBaseY && player.location.y <= wallBaseY + 10.0 &&
                    !isProtectedFromWave(castOrigin, player)
            }.forEach { player ->
                hit += player.uniqueId
                dealDamage(player, if (empowered) 40.0 else 30.0)
            }
            if (!cooldownStarted && ticks >= 100) {
                cooldownStarted = true
                finishNormalSkill()
            }
            if (ticks >= 200) {
                wave.cancel()
                playEffect(ShengShanEffect.WATER_BURST, center,
                    options = ShengShanEffectOptions(radius = 5.0, height = 4.0))
                soundNearby(center, Sound.ENTITY_GENERIC_SPLASH, 26.0, 1.0f, .7f)
                if (!cooldownStarted) finishNormalSkill()
            }
        }
    }

    private fun seaSoulChains() {
        clearWarningEffects()
        val target = players().maxByOrNull { it.location.distanceSquared(boss.location) }
            ?: return finishNormalSkill()
        boss.velocity = Vector(); boss.setAI(true)
        val origin = boss.location.clone().add(0.0, boss.height * .55, 0.0)
        var previous = origin.clone()
        var flightTicks = 0
        sound(boss.location, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, .72f)
        lateinit var thrownChain: org.bukkit.scheduler.BukkitTask
        thrownChain = every(0L, 1L) {
            if (!target.isOnline || target.isDead) {
                thrownChain.cancel(); finishNormalSkill()
                return@every
            }
            flightTicks++
            val endpoint = target.location.clone().add(0.0, 1.0, 0.0)
            val ratio = (flightTicks / 8.0).coerceIn(0.0, 1.0)
            val current = origin.clone().add(endpoint.toVector().subtract(origin.toVector()).multiply(ratio))
            playEffect(ShengShanEffect.WATER_CHAIN, previous, current,
                ShengShanEffectOptions(radius = .28, height = .28, color = 0x8FE8FF))
            previous = current
            if (flightTicks >= 8) {
                thrownChain.cancel()
                attachChain(target)
            }
        }
    }

    override fun onEntityDeath(entity: LivingEntity) {
        if (entity.uniqueId == linkedGuardId) completeChain() else super.onEntityDeath(entity)
    }

    private fun attachChain(player: Player) {
        val anchor = findChainGuardAnchor(player)
        val guard = spawn("shengshan_water_guard", anchor, "water_chain_guard") as? Guardian
            ?: return finishNormalSkill()
        guard.setAI(false); guard.setGravity(false); guard.isInvulnerable = false; guard.target = null
        guard.velocity = Vector(); guard.isCollidable = true; guard.isPersistent = true
        guard.isInvisible = false; guard.removePotionEffect(PotionEffectType.INVISIBILITY)
        guard.isGlowing = true
        guard.customName = "§b§l海魂锁链守卫者 §c[击杀以挣脱]"
        guard.isCustomNameVisible = true
        guard.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 120.0
        guard.health = 120.0
        linkedPlayerId = player.uniqueId
        linkedGuardId = guard.uniqueId
        chainGuardAnchor = anchor.clone()
        previousChainSlowness = player.getPotionEffect(PotionEffectType.SLOWNESS)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true), true)
        chainRunning = true
        chainCooldownStarted = false
        playEffect(ShengShanEffect.WATER_BURST, guard.location,
            options = ShengShanEffectOptions(radius = 2.0, height = 1.7, color = 0x83DFFF))
        soundNearby(player.location, Sound.ENTITY_GUARDIAN_ATTACK, 24.0, .85f, 1.25f)
        var ticks = 0
        lateinit var chain: org.bukkit.scheduler.BukkitTask
        chain = every(0L, 1L) {
            if (!chainRunning) {
                chain.cancel()
                return@every
            }
            val linkedPlayer = linkedPlayerId?.let(Bukkit::getPlayer)
            val linkedGuard = linkedGuardId?.let(Bukkit::getEntity) as? Guardian
            if (linkedPlayer == null || !linkedPlayer.isOnline || linkedPlayer.isDead ||
                linkedGuard == null || !linkedGuard.isValid || linkedGuard.isDead) {
                chain.cancel(); completeChain()
                return@every
            }
            chainGuardAnchor?.let { fixed ->
                linkedGuard.velocity = Vector()
                if (linkedGuard.location.distanceSquared(fixed) > .0025) linkedGuard.teleport(fixed)
            }
            linkedGuard.isInvisible = false
            linkedGuard.isGlowing = true
            linkedGuard.isCustomNameVisible = true
            ticks++
            if (ticks % 10 == 0) {
                linkedPlayer.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true), true)
            }
            if (ticks == 1 || ticks % 5 == 0) {
                playEffect(ShengShanEffect.WATER_CHAIN,
                    linkedGuard.location.clone().add(0.0, .5, 0.0),
                    linkedPlayer.location.clone().add(0.0, 1.0, 0.0),
                    ShengShanEffectOptions(color = if (ticks % 40 <= 6) 0xC8F3FF else 0x58A9DF))
            }
            if (ticks % 40 == 0) {
                dealDamage(linkedPlayer, 20.0)
                manager.healBossPercent(boss, if (empowered) .02 else .01)
                playEffect(ShengShanEffect.WATER_FLOW, linkedPlayer.location.clone().add(0.0, 1.0, 0.0),
                    boss.location.clone().add(0.0, boss.height * .5, 0.0),
                    ShengShanEffectOptions(color = 0x7EDFFF))
                sound(linkedPlayer.location, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, .8f, .75f)
            }
            if (!chainCooldownStarted && ticks >= 100) beginChainCooldown()
            if (ticks >= 200) {
                chain.cancel(); completeChain()
            }
        }
    }

    private fun beginChainCooldown() {
        if (chainCooldownStarted) return
        chainCooldownStarted = true
        finishNormalSkill()
    }

    private fun completeChain() {
        if (!chainRunning) return
        chainRunning = false
        val guardLocation = linkedGuardId?.let(Bukkit::getEntity)?.location
        val linkedPlayer = linkedPlayerId?.let(Bukkit::getPlayer)
        linkedGuardId?.let(Bukkit::getEntity)?.remove()
        linkedGuardId = null
        linkedPlayerId = null
        chainGuardAnchor = null
        linkedPlayer?.removePotionEffect(PotionEffectType.SLOWNESS)
        previousChainSlowness?.let { previous -> linkedPlayer?.addPotionEffect(previous, true) }
        previousChainSlowness = null
        guardLocation?.let {
            playEffect(ShengShanEffect.WATER_BURST, it,
                options = ShengShanEffectOptions(radius = 2.0, height = 1.5))
            soundNearby(it, Sound.ENTITY_GENERIC_SPLASH, 16.0, .8f, 1.4f)
        }
        if (!chainCooldownStarted) beginChainCooldown()
    }

    override fun startUltimate() {
        invulnerable = true
        clearWarningEffects()
        if (chainRunning) completeChain()
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        val destination = Trigram.WATER.spawn(boss.world)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 140) {
                returnTask.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginWaterEmpowerment()
            } else {
                val direction = delta.normalize()
                val previous = boss.location.clone().add(0.0, boss.height * .5, 0.0)
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.45)).apply { this.direction = direction })
                if (ticks % 4 == 0) playEffect(ShengShanEffect.WATER_FLOW, previous,
                    boss.location.clone().add(0.0, boss.height * .5, 0.0),
                    ShengShanEffectOptions(color = 0x70D8FF))
            }
        }
    }

    private fun beginWaterEmpowerment() {
        effectSerial++
        elder("abyss_empower",
            "它正在引动深水之势重塑自身，奔流般的气息与攻势正在迅速攀升！",
            firstOnly = false)
        val center = boss.location.clone().add(0.0, boss.height * .5, 0.0)
        playEffect(ShengShanEffect.WATER_SHIELD, center,
            options = ShengShanEffectOptions(radius = 4.5, height = 3.0, color = 0x8FEAFF,
                durationTicks = 100, intervalTicks = 2, key = "water_empower_ring_$effectSerial"))
        playEffect(ShengShanEffect.WATER_WAVE, center.clone().add(-4.0, 0.0, 0.0), center.clone().add(4.0, 0.0, 0.0),
            ShengShanEffectOptions(radius = .8, height = 2.5, color = 0x72DFFF,
                durationTicks = 100, intervalTicks = 3, key = "water_empower_wave_x_$effectSerial"))
        playEffect(ShengShanEffect.WATER_WAVE, center.clone().add(0.0, 0.0, -4.0), center.clone().add(0.0, 0.0, 4.0),
            ShengShanEffectOptions(radius = .8, height = 2.5, color = 0xA8F2FF,
                durationTicks = 100, intervalTicks = 3, key = "water_empower_wave_z_$effectSerial"))
        sound(boss.location, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, .72f)
        later(100L) {
            applyWaterEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(true); boss.setAI(true)
            message("§6逆水归渊完成，淼的步伐与攻势如奔流般愈发强盛！")
        }
    }

    private fun applyWaterEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 35.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
    }

    private fun renderWaterWall(center: Location, direction: Vector, warning: Boolean) {
        val side = Vector(-direction.z, 0.0, direction.x)
        repeat(6) { layer ->
            val visualCenter = center.clone().add(0.0, .5 + layer * 1.9, 0.0)
            val options = if (warning) {
                val key = "water_wave_warning_${effectSerial}_$layer"
                warningEffectKeys += key
                ShengShanEffectOptions(radius = .50, height = .50, color = 0x72DFFF,
                    durationTicks = 30, intervalTicks = 4, key = key)
            } else ShengShanEffectOptions(radius = .50, height = .50, color = 0x3F9FE8)
            playEffect(ShengShanEffect.WATER_WAVE,
                visualCenter.clone().add(side.clone().multiply(-20.0)),
                visualCenter.clone().add(side.clone().multiply(20.0)), options)
        }
    }

    private fun isProtectedFromWave(castOrigin: Location, player: Player): Boolean {
        val rayOrigin = castOrigin.clone().add(0.0, 1.0, 0.0)
        val bodyCenter = player.location.clone().add(0.0, player.height * .5, 0.0)
        val delta = bodyCenter.toVector().subtract(rayOrigin.toVector())
        val distance = delta.length()
        if (distance <= .01) return false
        return boss.world.rayTraceBlocks(rayOrigin, delta.normalize(), distance,
            FluidCollisionMode.NEVER, true) != null
    }

    private fun findChainGuardAnchor(player: Player): Location {
        val towardBoss = horizontalDirection(player.location, boss.location)
        val angles = doubleArrayOf(0.0, PI / 4.0, -PI / 4.0, PI / 2.0, -PI / 2.0, PI)
        angles.forEach { angle ->
            val direction = Vector(
                towardBoss.x * cos(angle) - towardBoss.z * sin(angle),
                0.0,
                towardBoss.x * sin(angle) + towardBoss.z * cos(angle)
            )
            val candidate = player.location.clone().add(direction.multiply(4.0)).add(0.0, .45, 0.0)
            if (candidate.block.isPassable && candidate.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                return candidate
            }
        }
        return player.location.clone().add(towardBoss.multiply(4.0)).add(0.0, .45, 0.0)
    }

    private fun clearShield() {
        shield = 0.0
        shieldExpiresAt = 0
        stopEffect("water_passive_shield")
    }

    private fun clearWarningEffects() {
        warningEffectKeys.toList().forEach(::stopEffect)
        warningEffectKeys.clear()
    }

    override fun shutdown(restoreTerrain: Boolean) {
        chainRunning = false
        linkedGuardId?.let(Bukkit::getEntity)?.remove()
        linkedPlayerId?.let(Bukkit::getPlayer)?.removePotionEffect(PotionEffectType.SLOWNESS)
        previousChainSlowness?.let { previous ->
            linkedPlayerId?.let(Bukkit::getPlayer)?.addPotionEffect(previous, true)
        }
        linkedGuardId = null
        linkedPlayerId = null
        chainGuardAnchor = null
        previousChainSlowness = null
        clearWarningEffects()
        clearShield()
        super.shutdown(restoreTerrain)
    }
}

internal class ReworkedMountainBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : MountainBossController(manager, session, boss) {
    private data class ArenaHill(val index: Int, val center: Location) {
        val group: String get() = "shengshan_mountain_hill_$index"
    }

    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0
    override fun showGenericNormalParticle(index: Int): Boolean = false
    override fun onNormalWarningStarted(index: Int) = Unit
    override fun onUltimateWarningStarted() = Unit

    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .25 }
    private val hills = listOf(
        3159 to -1856, 3139 to -1853, 3163 to -1842,
        3171 to -1827, 3152 to -1817, 3138 to -1842
    ).mapIndexed { index, (x, z) -> ArenaHill(index, Location(boss.world, x + .5, 129.0, z + .5)) }
    private val destroyedHills = HashSet<Int>()
    private var nextQuakeWarningAt = -1
    private var empowered = false
    private var effectSerial = 0

    override fun onTick() {
        super.onTick()
        if (invulnerable) return
        if (nextQuakeWarningAt < 0) {
            nextQuakeWarningAt = elapsedTicks + 260
            return
        }
        if (elapsedTicks < nextQuakeWarningAt) return
        nextQuakeWarningAt += 300
        warnMountainQuake()
    }

    private fun warnMountainQuake() {
        effectSerial++
        val intact = hills.filter { it.index !in destroyedHills }
        if (intact.isEmpty()) return
        sound(boss.location, Sound.BLOCK_DEEPSLATE_BREAK, .8f, .55f)
        val keys = intact.associateWith { hill -> "mountain_quake_${effectSerial}_${hill.index}" }
        val warningColors = intArrayOf(0xB18A55, 0xD4773D, 0xE84A2F, 0xFF2020)
        warningColors.forEachIndexed { stage, color ->
            later(stage * 10L) {
                intact.filter { it.index !in destroyedHills }.forEach { hill ->
                    val key = keys.getValue(hill)
                    stopEffect(key)
                    playEffect(ShengShanEffect.MOUNTAIN_CRACK, hill.center.clone().add(0.0, 1.8, 0.0),
                        options = ShengShanEffectOptions(radius = 8.5, height = 2.8 + stage,
                            color = color, durationTicks = 10, intervalTicks = 1, key = key))
                    if (stage == warningColors.lastIndex) {
                        playEffect(ShengShanEffect.EARTH_PULSE, hill.center,
                            options = ShengShanEffectOptions(radius = 8.5, height = 1.1, color = 0xFF2020))
                        playEffect(ShengShanEffect.MOUNTAIN_SHOCK,
                            hill.center.clone().add(0.0, .25, 0.0), hill.center.clone().add(0.0, 4.5, 0.0),
                            ShengShanEffectOptions(radius = .75, height = 4.0, color = 0xFF3020))
                    }
                }
                if (stage == warningColors.lastIndex) {
                    sound(boss.location, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, .55f)
                }
            }
        }
        later(40L) {
            keys.values.forEach(::stopEffect)
            val hit = HashSet<UUID>()
            intact.filter { it.index !in destroyedHills }.forEach { hill ->
                playEffect(ShengShanEffect.EARTH_PULSE, hill.center,
                    options = ShengShanEffectOptions(radius = 7.0, height = .7, color = 0xB38A5A))
                players().filter { player ->
                    horizontalDistanceSquared(player.location, hill.center) <= 49.0 && hit.add(player.uniqueId)
                }.forEach { dealDamage(it, 30.0) }
            }
            sound(Sound.ENTITY_RAVAGER_STEP, 1.1f, .6f)
        }
    }

    override fun castNormalSkill(index: Int) { if (index == 0) heavyStrike() else flyingRocks() }

    private fun heavyStrike() {
        val target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) } ?: return finishNormalSkill()
        boss.setAI(false); boss.velocity = Vector()
        val direction = horizontalDirection(boss.location, target.location)
        val targetDistance = kotlin.math.sqrt(horizontalDistanceSquared(boss.location, target.location))
        val dashDistance = (targetDistance + 5.0).coerceIn(8.0, 40.0)
        val end = boss.location.clone().add(direction.clone().multiply(dashDistance))
        effectSerial++
        val key = "mountain_heavy_strike_$effectSerial"
        playEffect(ShengShanEffect.MOUNTAIN_SHOCK,
            boss.location.clone().add(0.0, .4, 0.0), end.clone().add(0.0, .4, 0.0),
            ShengShanEffectOptions(radius = .55, height = .45, color = 0xB08A5A,
                durationTicks = 10, intervalTicks = 2, key = key))
        soundNearby(boss.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 30.0, .85f, .6f)
        later(10L) {
            stopEffect(key)
            dashHeavyStrike(direction, dashDistance)
        }
    }

    private fun dashHeavyStrike(direction: Vector, maximumDistance: Double) {
        boss.setGravity(false)
        val hitPlayers = HashSet<UUID>()
        var travelled = 0.0
        lateinit var dash: org.bukkit.scheduler.BukkitTask
        dash = every(0L, 1L) {
            val step = minOf(1.6, maximumDistance - travelled)
            val rayOrigin = boss.location.clone().add(0.0, boss.height * .5, 0.0)
            val blocked = step <= .01 || boss.world.rayTraceBlocks(
                rayOrigin, direction, step + .65, FluidCollisionMode.NEVER, true
            ) != null
            if (blocked) {
                dash.cancel(); boss.velocity = Vector(); boss.setGravity(true); boss.setAI(true); finishNormalSkill()
                return@every
            }
            val previous = boss.location.clone()
            val next = previous.clone().add(direction.clone().multiply(step)).apply { this.direction = direction }
            boss.teleport(next)
            travelled += step
            if (travelled.toInt() % 3 == 0) playEffect(ShengShanEffect.MOUNTAIN_DUST, boss.location,
                options = ShengShanEffectOptions(radius = 1.3, height = .7))
            players().filter { player ->
                player.uniqueId !in hitPlayers && distanceToSegmentSquared(player.location, previous, next) <= 4.0
            }.forEach { player ->
                hitPlayers += player.uniqueId
                dealDamage(player, 30.0)
                forceKnockback(player, direction)
            }
            if (travelled >= maximumDistance - .01) {
                dash.cancel(); boss.velocity = Vector(); boss.setGravity(true); boss.setAI(true); finishNormalSkill()
            }
        }
        soundNearby(boss.location, Sound.ENTITY_RAVAGER_ATTACK, 34.0, 1.0f, .75f)
    }

    private fun forceKnockback(player: Player, direction: Vector) {
        val origin = player.location.clone()
        player.velocity = direction.clone().multiply(2.0).setY(.28)
        soundNearby(player.location, Sound.ENTITY_ARROW_HIT_PLAYER, 18.0, .9f, .72f)
        var ticks = 0
        lateinit var knockback: org.bukkit.scheduler.BukkitTask
        knockback = every(0L, 1L) {
            if (!player.isOnline || player.isDead) {
                knockback.cancel()
                return@every
            }
            ticks++
            val body = player.location.clone().add(0.0, player.height * .5, 0.0)
            val collided = player.world.rayTraceBlocks(
                body, direction, .75, FluidCollisionMode.NEVER, true
            ) != null
            if (collided) {
                knockback.cancel(); player.velocity = Vector()
                dealDamage(player, 20.0, if (empowered) 1.0 else 0.0)
                player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 2, false, true, true), true)
                playEffect(ShengShanEffect.GROUND_RUPTURE, player.location,
                    options = ShengShanEffectOptions(radius = 2.0, height = 1.4))
                soundNearby(player.location, Sound.BLOCK_STONE_HIT, 18.0, .9f, .6f)
                return@every
            }
            if (kotlin.math.sqrt(horizontalDistanceSquared(origin, player.location)) >= 5.0 || ticks >= 20) {
                knockback.cancel()
                player.velocity = Vector()
            }
        }
    }

    private fun flyingRocks() {
        val initialDirection = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
            ?.let { horizontalDirection(boss.location, it.location) }
            ?: boss.location.direction.clone().setY(0.0).let { if (it.lengthSquared() < .01) Vector(1.0, 0.0, 0.0) else it.normalize() }
        var releaseDirection = initialDirection
        var scale = .45f
        val display = spawnVisualItem(
            boss.location.clone().add(initialDirection.clone().multiply(2.2)).add(0.0, 1.8, 0.0),
            Material.COBBLESTONE, scale, "mountain_rolling_boulder"
        )
        display.teleportDuration = 1
        soundNearby(boss.location, Sound.BLOCK_STONE_PLACE, 30.0, .9f, .55f)
        val chargeTicks = 40
        phaseBar("§6飞沙走石准备中……", chargeTicks, BarColor.YELLOW, onTick = { remaining ->
            boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = currentMovementSpeed() * .50
            val target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
            if (target != null) releaseDirection = horizontalDirection(boss.location, target.location)
            val front = boss.location.clone().add(releaseDirection.clone().multiply(2.2)).add(0.0, 1.8, 0.0)
            display.teleport(front.apply { direction = releaseDirection })
            scale = (.45 + (chargeTicks - remaining) / chargeTicks.toDouble() * 3.55).toFloat()
            display.transformation = Transformation(
                Vector3f(), AxisAngle4f((chargeTicks - remaining) * .08f, 0f, 0f, 1f),
                Vector3f(scale, scale, scale), AxisAngle4f()
            )
            if (remaining % 4 == 0) playEffect(ShengShanEffect.MOUNTAIN_DUST, front,
                options = ShengShanEffectOptions(radius = 1.6, height = 1.3))
        }, onComplete = {
            boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = currentMovementSpeed()
            display.setGravity(true)
            display.velocity = Vector(0.0, -.12, 0.0)
            rollBoulder(display, releaseDirection)
            finishNormalSkill()
            later(20L) { launchSecondBoulder() }
        })
    }

    private fun launchSecondBoulder() {
        if (!boss.isValid || boss.isDead) return
        val target = players().maxByOrNull { horizontalDistanceSquared(it.location, boss.location) } ?: return
        val direction = horizontalDirection(boss.location, target.location)
        val origin = boss.location.clone().add(direction.clone().multiply(2.2)).add(0.0, 2.0, 0.0)
        val display = spawnVisualItem(origin, Material.COBBLESTONE, 4.0f, "mountain_rolling_boulder_second")
        display.teleportDuration = 1
        display.setGravity(true)
        display.velocity = Vector(0.0, -.12, 0.0)
        playEffect(ShengShanEffect.MOUNTAIN_DUST, origin,
            options = ShengShanEffectOptions(radius = 3.0, height = 2.4))
        soundNearby(origin, Sound.BLOCK_STONE_BREAK, 30.0, 1.0f, .58f)
        rollBoulder(display, direction)
    }

    private fun rollBoulder(display: ItemDisplay, initialDirection: Vector) {
        var direction = initialDirection.clone().setY(0.0).normalize()
        val speed = if (empowered) .55 else .45
        val hitAt = HashMap<UUID, Int>()
        var events = 0
        var ticks = 0
        var rotation = 0f
        var finished = false
        lateinit var rolling: org.bukkit.scheduler.BukkitTask

        fun explode() {
            if (finished) return
            finished = true
            rolling.cancel()
            val location = display.location.clone()
            display.remove()
            boulderBurst(location)
        }

        rolling = every(0L, 1L) {
            if (!display.isValid) {
                rolling.cancel()
                return@every
            }
            ticks++
            val previous = display.location.clone()
            val collision = boss.world.rayTraceBlocks(previous, direction, speed + 1.8,
                FluidCollisionMode.NEVER, true)
            if (collision != null) {
                val hitBlock = collision.hitBlock
                val hill = hitBlock?.let(::hillContaining)
                if (empowered && hill != null) {
                    if (destroyedHills.add(hill.index)) shatterHill(hill, collision.hitPosition.toLocation(boss.world))
                    display.teleport(previous.clone().add(direction.clone().multiply(speed + .45)))
                } else {
                    val normal = collision.hitBlockFace?.direction ?: Vector(-direction.x, 0.0, -direction.z)
                    val reflected = direction.clone().subtract(normal.clone().multiply(2.0 * direction.dot(normal))).setY(0.0)
                    direction = if (reflected.lengthSquared() < .01) direction.multiply(-1) else reflected.normalize()
                    val rebound = collision.hitPosition.toLocation(boss.world)
                        .add(direction.clone().multiply(.55)).apply { this.direction = direction }
                    display.teleport(rebound)
                    events++
                    playEffect(ShengShanEffect.MOUNTAIN_SHOCK, rebound,
                        options = ShengShanEffectOptions(radius = 2.0, height = 1.5))
                    soundNearby(rebound, Sound.BLOCK_STONE_HIT, 24.0, 1.0f, .62f)
                }
            } else {
                display.teleport(previous.clone().add(direction.clone().multiply(speed)).apply { this.direction = direction })
            }
            settleBoulderTowardGround(display)

            if (events >= 3) {
                explode()
                return@every
            }

            val current = display.location.clone()
            players().forEach { player ->
                if (events >= 3) return@forEach
                if (elapsedTicks - (hitAt[player.uniqueId] ?: -100) >= 20 &&
                    distanceToSegmentSquared(player.location.clone().add(0.0, .8, 0.0), previous, current) <= 6.25
                ) {
                    hitAt[player.uniqueId] = elapsedTicks
                    dealDamage(player, 30.0)
                    events++
                    soundNearby(player.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 18.0, .8f, .7f)
                }
            }
            if (events >= 3) {
                explode()
                return@every
            }

            rotation += speed.toFloat() * .7f
            val axis = Vector(-direction.z, 0.0, direction.x).normalize()
            display.transformation = Transformation(
                Vector3f(), AxisAngle4f(rotation, axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat()),
                Vector3f(4.0f, 4.0f, 4.0f), AxisAngle4f()
            )
            if (ticks % 5 == 0) playEffect(ShengShanEffect.MOUNTAIN_DUST, current,
                options = ShengShanEffectOptions(radius = 1.5, height = .6))
        }
        soundNearby(display.location, Sound.BLOCK_STONE_BREAK, 28.0, .9f, .65f)
    }

    private fun settleBoulderTowardGround(display: ItemDisplay) {
        val location = display.location.clone()
        val groundCenterY = manager.groundLocation(
            boss.world, location.x, location.z, startY = 128, minY = 120
        ).y + 2.0
        location.y = if (location.y > groundCenterY) maxOf(groundCenterY, location.y - .35) else groundCenterY
        display.teleport(location)
        display.velocity = Vector(0.0, -.12, 0.0)
    }

    private fun boulderBurst(location: Location) {
        playEffect(ShengShanEffect.GROUND_RUPTURE, location,
            options = ShengShanEffectOptions(radius = 5.0, height = 3.0))
        playEffect(ShengShanEffect.FALLING_DEBRIS, location.clone().add(0.0, 1.5, 0.0),
            options = ShengShanEffectOptions(radius = 5.0, height = 3.5))
        soundNearby(location, Sound.BLOCK_DEEPSLATE_BREAK, 30.0, 1.1f, .62f)
        players().filter { it.location.distanceSquared(location) <= 25.0 }.forEach { dealDamage(it, 30.0) }
    }

    private fun shatterHill(hill: ArenaHill, impact: Location) {
        playEffect(ShengShanEffect.COLLAPSE, hill.center.clone().add(0.0, 2.5, 0.0),
            options = ShengShanEffectOptions(radius = 7.0, height = 6.0))
        soundNearby(hill.center, Sound.ENTITY_RAVAGER_ATTACK, 38.0, 1.1f, .55f)
        collapseAnimated(hill.group, hillBlocks(hill), batchSize = 36, periodTicks = 1L)
        boulderBurst(impact)
    }

    private fun hillContaining(block: Block): ArenaHill? = hills.firstOrNull { hill ->
        val dx = block.x - hill.center.blockX
        val dz = block.z - hill.center.blockZ
        val dy = block.y - hill.center.blockY
        if (dx !in -4..4 || dz !in -4..4 || dy !in 0..5) return@firstOrNull false
        val radius = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
        val height = (6.0 - radius * 1.25 + abs(dx * 31 + dz * 17 + hill.index) % 2)
            .toInt().coerceIn(0, 6)
        dy < height
    }

    private fun hillBlocks(hill: ArenaHill): Set<Block> {
        val blocks = LinkedHashSet<Block>()
        for (dx in -4..4) for (dz in -4..4) {
            val radius = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
            val height = (6.0 - radius * 1.25 + abs(dx * 31 + dz * 17 + hill.index) % 2)
                .toInt().coerceIn(0, 6)
            for (dy in 0 until height) {
                blocks += boss.world.getBlockAt(hill.center.blockX + dx, hill.center.blockY + dy, hill.center.blockZ + dz)
            }
        }
        return blocks
    }

    override fun startUltimate() {
        invulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        val destination = Trigram.MOUNTAIN.spawn(boss.world)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 140) {
                returnTask.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginMountainEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.42)).apply { this.direction = direction })
                if (ticks % 4 == 0) playEffect(ShengShanEffect.MOUNTAIN_DUST, boss.location,
                    options = ShengShanEffectOptions(radius = 1.6, height = 1.2))
            }
        }
    }

    private fun beginMountainEmpowerment() {
        effectSerial++
        elder("mountain_body_empower",
            "当心！它正在以群山厚土重铸自身，步伐与攻势都变得愈发沉重！\n§e小心它接下来召唤的冲刺和滚石，会更加难以抵挡！",
            firstOnly = false)
        playEffect(ShengShanEffect.MOUNTAIN_DUST, boss.location.clone().add(0.0, 1.0, 0.0),
            options = ShengShanEffectOptions(radius = 5.0, height = 4.0, color = 0x9A7853,
                durationTicks = 100, intervalTicks = 2, key = "mountain_empower_dust_$effectSerial"))
        playEffect(ShengShanEffect.EARTH_PULSE, boss.location,
            options = ShengShanEffectOptions(radius = 5.5, height = .8, color = 0xC19A6B,
                durationTicks = 100, intervalTicks = 4, key = "mountain_empower_pulse_$effectSerial"))
        sound(boss.location, Sound.ENTITY_RAVAGER_ROAR, 1.0f, .62f)
        later(100L) {
            applyMountainEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(true); boss.setAI(true)
            message("§6力撼山岩完成，芔的身形与攻势都变得更加沉重！")
        }
    }

    private fun applyMountainEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 40.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
    }

    private fun currentMovementSpeed(): Double = baseMovementSpeed * if (empowered) 1.20 else 1.0
}

internal class ReworkedFireBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : FireBossController(manager, session, boss) {
    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0

    private data class FireAltar(val index: Int, val blockX: Int, val blockZ: Int) {
        fun effectCenter(world: org.bukkit.World): Location = Location(world, blockX + .5, 129.2, blockZ + .5)
    }

    private val fireAltarGroup = "shengshan_fire_altars"
    private val fireAltars = listOf(
        3148 to -1855, 3160 to -1852, 3163 to -1840, 3160 to -1828,
        3148 to -1825, 3136 to -1828, 3133 to -1840, 3136 to -1852
    ).mapIndexed { index, (x, z) -> FireAltar(index, x, z) }
    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .3 }
    private val passiveTasks = LinkedHashSet<org.bukkit.scheduler.BukkitTask>()
    private val passiveEffectKeys = LinkedHashSet<String>()
    private var empowered = false
    private var heavenlyFireActive = false
    private var nextBurnAt = -1
    private var effectSerial = 0

    override fun onUltimateWarningStarted() = Unit

    override fun onTick() {
        super.onTick()
        if (invulnerable || heavenlyFireActive) return
        if (nextBurnAt < 0) {
            nextBurnAt = elapsedTicks + 200
            return
        }
        if (elapsedTicks < nextBurnAt) return
        do nextBurnAt += 200 while (nextBurnAt <= elapsedTicks)
        beginBurningPaths()
    }

    private fun beginBurningPaths() {
        val origin = groundPoint(boss.location)
        val targets = passiveTargets(5, origin)
        if (targets.isEmpty()) return
        effectSerial++
        val serial = effectSerial
        message("§6五股§c灼热地火§6已锁定落点，§e一秒后§6将沿地面缓慢追涌，注意避开§c流动前端§6！")
        targets.forEachIndexed { index, target ->
            // 火径终点使用竖直火柱预警，与天火临的地面圆环明确区分。
            val key = "fire_path_target_${serial}_$index"
            passiveEffectKeys += key
            playEffect(ShengShanEffect.FIRE_PATH_WARNING, target, target.clone().add(0.0, 4.5, 0.0),
                ShengShanEffectOptions(radius = .18, height = .18, color = 0xFF7A2E,
                    durationTicks = 20, intervalTicks = 4, key = key))
        }
        soundNearby(origin, Sound.BLOCK_FIRE_AMBIENT, 38.0, .9f, .72f)
        passiveLater(20L) {
            targets.indices.forEach { index ->
                stopPassiveEffect("fire_path_target_${serial}_$index")
            }
            if (!invulnerable && !heavenlyFireActive) launchBurningPaths(origin, targets, serial)
        }
    }

    private fun launchBurningPaths(origin: Location, targets: List<Location>, serial: Int) {
        val directions = targets.map { target -> horizontalDirection(origin, target) }
        val lengths = targets.map { target -> sqrt(horizontalDistanceSquared(origin, target)) }
        val travelled = DoubleArray(targets.size)
        val previous = MutableList(targets.size) { origin.clone() }
        val trackingHits = HashSet<UUID>()
        val triggeredAltars = HashSet<Int>()
        soundNearby(origin, Sound.ENTITY_BLAZE_SHOOT, 36.0, 1.0f, .82f)
        lateinit var advance: org.bukkit.scheduler.BukkitTask
        advance = every(0L, 2L) {
            var allArrived = true
            targets.forEachIndexed { index, target ->
                if (travelled[index] >= lengths[index]) return@forEachIndexed
                allArrived = false
                // 0.2格/刻，呈现岩浆缓慢向外流淌的感觉。
                travelled[index] = (travelled[index] + .4).coerceAtMost(lengths[index])
                val current = origin.clone().add(directions[index].clone().multiply(travelled[index])).apply { y = 129.2 }
                playEffect(ShengShanEffect.FIRE_PATH_SPARSE, previous[index], current,
                    ShengShanEffectOptions(radius = .34, height = .18, color = 0xFF5A28))
                // 只有当前向前流动的短段具有伤害；流过的地面不会留下持续伤害区。
                players().filter { player -> player.uniqueId !in trackingHits &&
                    abs(player.location.y - 129.2) <= 2.0 &&
                    distanceToGroundSegmentSquared(player.location, previous[index], current) <= 2.25
                }.forEach { player ->
                    trackingHits += player.uniqueId
                    dealDamage(player, 30.0)
                }
                if (empowered) fireAltars.filter { altar -> altar.index !in triggeredAltars &&
                    distanceToGroundSegmentSquared(altar.effectCenter(boss.world), previous[index], current) <= 12.25
                }.forEach { altar ->
                    triggeredAltars += altar.index
                    warnAltarEruption(altar, serial)
                }
                previous[index] = current
            }
            if (allArrived || travelled.indices.all { travelled[it] >= lengths[it] }) {
                advance.cancel()
                passiveTasks.remove(advance)
            }
        }
        passiveTasks += advance
    }

    private fun warnAltarEruption(altar: FireAltar, serial: Int) {
        val center = altar.effectCenter(boss.world)
        val key = "fire_altar_erupt_${serial}_${altar.index}"
        passiveEffectKeys += key
        passiveEffectKeys += "${key}_flame"
        playEffect(ShengShanEffect.FIRE_WARNING_RING, center,
            options = ShengShanEffectOptions(radius = 3.5, height = .3, color = 0xFF3020,
                durationTicks = 20, intervalTicks = 2, key = key))
        playEffect(ShengShanEffect.FIRE_CRACK, center.clone().add(0.0, 1.0, 0.0),
            options = ShengShanEffectOptions(radius = 2.4, height = 3.0, color = 0xFF682A,
                durationTicks = 20, intervalTicks = 2, key = "${key}_flame"))
        soundNearby(center, Sound.BLOCK_CAMPFIRE_CRACKLE, 20.0, .9f, .65f)
        passiveLater(20L) {
            stopPassiveEffect(key); stopPassiveEffect("${key}_flame")
            playEffect(ShengShanEffect.FIRE_BURST, center.clone().add(0.0, 1.0, 0.0),
                options = ShengShanEffectOptions(radius = 3.5, height = 4.0, color = 0xFF4A20))
            soundNearby(center, Sound.ENTITY_GENERIC_EXPLODE, 28.0, .9f, .82f)
            players().filter { horizontalDistanceSquared(it.location, center) <= 12.25 }.forEach {
                dealDamage(it, 30.0)
            }
        }
    }

    override fun onNormalWarningStarted(index: Int) {
        effectSerial++
        if (index == 1) {
            heavenlyFireActive = true
            cancelPassivePaths()
            return
        }
        val target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
        val direction = target?.let { horizontalDirection(boss.location, it.location) }
            ?: boss.location.direction.clone().setY(0.0).let { if (it.lengthSquared() < .001) Vector(1.0, 0.0, 0.0) else it.normalize() }
        boss.teleport(boss.location.clone().apply { this.direction = direction })
        val center = boss.location.clone().add(direction.clone().multiply(2.8)).add(0.0, 1.8, 0.0)
        showVerticalFireX(center, direction, 30, "fire_slash_warning_$effectSerial")
        soundNearby(boss.location, Sound.ITEM_FIRECHARGE_USE, 30.0, .9f, .65f)
    }

    override fun castNormalSkill(index: Int) {
        if (index == 0) flameSlash() else heavenlyFire()
    }

    private fun flameSlash() {
        val participants = players()
        if (participants.isEmpty()) return finishNormalSkill()
        val locked = if (empowered) {
            participants.sortedBy { horizontalDistanceSquared(it.location, boss.location) }
        } else {
            listOfNotNull(
                participants.minByOrNull { horizontalDistanceSquared(it.location, boss.location) },
                participants.maxByOrNull { horizontalDistanceSquared(it.location, boss.location) }
            ).distinctBy { it.uniqueId }
        }
        val origin = boss.location.clone().add(0.0, 1.8, 0.0)
        val directions = locked.map { horizontalDirection(origin, it.location) }
        val distances = directions.map { rayDistanceToArenaEdge(origin, it) }
        val travelled = DoubleArray(directions.size)
        val previous = MutableList(directions.size) { origin.clone() }
        val hitPlayers = HashSet<UUID>()
        soundNearby(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 42.0, 1.1f, .7f)
        var ticks = 0
        lateinit var slash: org.bukkit.scheduler.BukkitTask
        slash = every(0L, 1L) {
            ticks++
            directions.forEachIndexed { index, direction ->
                if (travelled[index] >= distances[index]) return@forEachIndexed
                travelled[index] = (travelled[index] + 1.8).coerceAtMost(distances[index])
                val current = origin.clone().add(direction.clone().multiply(travelled[index]))
                if (ticks % 2 == 1) showVerticalFireX(current, direction)
                players().filter { player -> player.uniqueId !in hitPlayers &&
                    distanceToGroundSegmentSquared(player.location, previous[index], current) <= 4.0 &&
                    player.location.y in 127.0..135.0
                }.forEach { player ->
                    hitPlayers += player.uniqueId
                    dealDamage(player, 40.0)
                }
                previous[index] = current
            }
            if (travelled.indices.all { travelled[it] >= distances[it] }) {
                slash.cancel()
                soundNearby(boss.location, Sound.ITEM_FIRECHARGE_USE, 38.0, 1.0f, 1.15f)
                finishNormalSkill()
            }
        }
    }

    private fun heavenlyFire() {
        // 天火临只限制移动，不再给予伤害免疫；升空与十秒引导期间均可被正常攻击。
        invulnerable = false
        boss.isInvulnerable = false
        boss.setAI(false); boss.setGravity(false); boss.isCollidable = false; boss.velocity = Vector()
        val hover = safeHeavenlyHover()
        moveFireBoss(hover, .55) { beginHeavenlyFireChannel(hover) }
    }

    private fun beginHeavenlyFireChannel(hover: Location) {
        val radius = if (empowered) 55.0 else 45.0
        val ratePerSecond = if (empowered) 25 else 15
        val center = Location(boss.world, hover.x, 129.2, hover.z)
        val recent = ArrayDeque<Location>()
        val pending = ArrayDeque<Location>()
        var launchAccumulator = 0
        var launched = 0
        phaseBar("§c天火临 引导中……", 200, BarColor.RED, onTick = { remaining ->
            val elapsed = 200 - remaining
            boss.velocity = Vector()
            if (elapsed % 20 == 0) {
                pending.clear()
                pending.addAll(heavenlyImpactBatch(center, radius, ratePerSecond, recent))
            }
            launchAccumulator += ratePerSecond
            while (launchAccumulator >= 20 && pending.isNotEmpty()) {
                launchAccumulator -= 20
                val impact = pending.removeFirst()
                launched++
                launchHeavenlyFireball(impact, launched)
                if (launched % 10 == 0) soundNearby(impact, Sound.ENTITY_BLAZE_SHOOT, 24.0, .65f, .82f)
            }
        }, onComplete = {
            val landing = Location(boss.world, hover.x, 129.2, hover.z, boss.location.yaw, boss.location.pitch)
            moveFireBoss(landing, .55) {
                boss.setGravity(true); boss.isCollidable = true; boss.setAI(true)
                heavenlyFireActive = false
                // 被动总冷却为10秒；天火临结束时视为已经冷却7秒，三秒后重新释放。
                nextBurnAt = elapsedTicks + 60
                finishNormalSkill()
            }
        })
    }

    private fun launchHeavenlyFireball(ground: Location, index: Int) {
        effectSerial++
        val serial = effectSerial
        val random = ThreadLocalRandom.current()
        val origin = ground.clone().add(random.nextDouble(-1.8, 1.8), random.nextDouble(13.0, 18.0),
            random.nextDouble(-1.8, 1.8))
        val display = spawnVisualItem(origin, Material.SHROOMLIGHT, .82f, "fire_heavenly_display_$index")
        val warningKey = "heavenly_fire_warning_$serial"
        val trailKey = "heavenly_fire_trail_$serial"
        playEffect(ShengShanEffect.FIRE_WARNING_RING, ground,
            options = ShengShanEffectOptions(radius = 2.0, height = .2, color = 0xFF3A20,
                durationTicks = 9, intervalTicks = 2, key = warningKey))
        playEffect(ShengShanEffect.FIRE_LINE, origin, ground.clone().add(0.0, .4, 0.0),
            ShengShanEffectOptions(radius = .28, height = .28, color = 0xFF6A2A,
                durationTicks = 9, intervalTicks = 4, key = trailKey))
        later(1L) { interpolateVisualItem(display, ground.clone().add(0.0, .45, 0.0), 8) }
        later(9L) {
            display.remove(); stopEffect(warningKey); stopEffect(trailKey)
            playEffect(ShengShanEffect.FIRE_BURST, ground.clone().add(0.0, .35, 0.0),
                options = ShengShanEffectOptions(radius = 2.0, height = 2.2, color = 0xFF542A))
            soundNearby(ground, Sound.ENTITY_GENERIC_EXPLODE, 14.0, .45f, 1.12f)
            players().filter { horizontalDistanceSquared(it.location, ground) <= 4.0 }.forEach {
                dealDamage(it, 30.0)
            }
        }
    }

    private fun safeHeavenlyHover(): Location {
        val baseX = boss.location.x
        val baseZ = boss.location.z
        val offsets = buildList {
            add(0 to 0)
            for (radius in 1..6) for (dx in -radius..radius) for (dz in -radius..radius) {
                if (abs(dx) == radius || abs(dz) == radius) add(dx to dz)
            }
        }
        val (dx, dz) = offsets.firstOrNull { (offsetX, offsetZ) ->
            val x = kotlin.math.floor(baseX + offsetX).toInt()
            val z = kotlin.math.floor(baseZ + offsetZ).toInt()
            !boss.world.getBlockAt(x, 128, z).isPassable && !boss.world.getBlockAt(x, 128, z).isLiquid &&
                (129..140).all { y -> boss.world.getBlockAt(x, y, z).isPassable }
        } ?: (0 to 0)
        return Location(boss.world, baseX + dx, 137.2, baseZ + dz, boss.location.yaw, 0.0f)
    }

    private fun moveFireBoss(destination: Location, speed: Double, onComplete: () -> Unit) {
        var ticks = 0
        lateinit var move: org.bukkit.scheduler.BukkitTask
        move = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= speed * speed || ticks >= 100) {
                move.cancel(); boss.teleport(destination); boss.velocity = Vector(); onComplete()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(speed)).apply { this.direction = direction })
                if (ticks % 3 == 0) playEffect(ShengShanEffect.FIRE_CRACK,
                    boss.location.clone().add(0.0, boss.height * .5, 0.0),
                    options = ShengShanEffectOptions(radius = 1.3, height = 1.2, color = 0xFF6A2A))
            }
        }
    }

    override fun startUltimate() {
        invulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.isCollidable = false; boss.velocity = Vector()
        val destination = Trigram.FIRE.spawn(boss.world)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 140) {
                returnTask.cancel(); boss.teleport(destination); boss.velocity = Vector(); summonFireAltars()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.42)).apply { this.direction = direction })
                if (ticks % 3 == 0) playEffect(ShengShanEffect.FIRE_LINE,
                    boss.location.clone().subtract(direction.clone().multiply(1.8)).add(0.0, 1.0, 0.0),
                    boss.location.clone().add(0.0, 1.0, 0.0), ShengShanEffectOptions(color = 0xFF6A2A))
            }
        }
    }

    private fun summonFireAltars() {
        terrainGroups += fireAltarGroup
        buildAnimated(fireAltarGroup, fireAltarChanges(), batchSize = 4, periodTicks = 1L) {
            beginFireEmpowerment()
        }
    }

    private fun beginFireEmpowerment() {
        effectSerial++
        elder("eight_fire_empower",
            "当心！它正在以八方离火重铸自身，步伐与攻势都变得愈发炽烈！\n§e离火台已被唤醒：灼热路径靠近祭台时会引发喷发，离焰斩将追索每一名闯入者，天火也会覆盖更广的区域！",
            firstOnly = false)
        playEffect(ShengShanEffect.FIRE_CRACK, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 5.5, height = 4.5, color = 0xE84120,
                durationTicks = 100, intervalTicks = 2, key = "fire_empower_crack_$effectSerial"))
        playEffect(ShengShanEffect.FIRE_BURST, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 4.5, height = 4.0, color = 0xFF6A2A,
                durationTicks = 100, intervalTicks = 4, key = "fire_empower_burst_$effectSerial"))
        fireAltars.forEach { altar ->
            playEffect(ShengShanEffect.FIRE_WARNING_RING, altar.effectCenter(boss.world),
                options = ShengShanEffectOptions(radius = 3.5, height = 2.2, color = 0xD93D20,
                    durationTicks = 100, intervalTicks = 4, key = "fire_altar_awaken_${effectSerial}_${altar.index}"))
        }
        sound(boss.location, Sound.ITEM_FIRECHARGE_USE, 1.25f, .62f)
        later(100L) {
            applyFireEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(true); boss.isCollidable = true; boss.setAI(true)
            message("§6八方离火已经成阵！焱的步伐与攻势更加炽烈，离火台也开始响应地火！")
        }
    }

    private fun applyFireEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 35.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
    }

    private fun fireAltarChanges(): Map<Block, org.bukkit.block.data.BlockData> {
        val changes = LinkedHashMap<Block, org.bukkit.block.data.BlockData>()
        fireAltars.forEach { altar ->
            changes[boss.world.getBlockAt(altar.blockX, 129, altar.blockZ)] = Material.NETHERRACK.createBlockData()
            changes[boss.world.getBlockAt(altar.blockX, 130, altar.blockZ)] = Material.SOUL_CAMPFIRE.createBlockData()
        }
        return changes
    }

    private fun passiveTargets(total: Int, origin: Location): List<Location> {
        val participants = players()
        val result = ArrayList<Location>(total)
        participants.shuffled().take(total).forEach { result += groundPoint(it.location) }
        val random = ThreadLocalRandom.current()
        val angleOffset = random.nextDouble(0.0, PI * 2.0)
        while (result.size < total) {
            val occupiedAngles = result.mapNotNull { point ->
                val dx = point.x - origin.x
                val dz = point.z - origin.z
                if (dx * dx + dz * dz < 1.0) null else kotlin.math.atan2(dz, dx)
            }
            val candidates = buildList {
                for (slot in 0 until 48) {
                    val angle = angleOffset + slot * PI * 2.0 / 48.0
                    for (radius in listOf(18.0, 24.0, 30.0)) {
                        val x = origin.x + cos(angle) * radius
                        val z = origin.z + sin(angle) * radius
                        if (x in 3119.5..3179.5 && z in -1869.5..-1809.5) {
                            add(Location(boss.world, x, 129.2, z) to angle)
                        }
                    }
                }
            }
            val selected = candidates.filter { (candidate, _) ->
                horizontalDistanceSquared(candidate, origin) >= 225.0 &&
                    result.all { horizontalDistanceSquared(it, candidate) >= 20.25 }
            }.maxByOrNull { (candidate, angle) ->
                val angleSpacing = occupiedAngles.minOfOrNull { occupied ->
                    abs(kotlin.math.atan2(sin(angle - occupied), cos(angle - occupied)))
                } ?: PI
                angleSpacing * 100.0 + (result.minOfOrNull { horizontalDistanceSquared(it, candidate) } ?: 900.0) / 100.0
            }?.first ?: break
            result += selected
        }
        val fallback = buildList {
            for (x in 3120..3178 step 5) for (z in -1868..-1810 step 5) {
                add(Location(boss.world, x + .5, 129.2, z + .5))
            }
        }.shuffled()
        fallback.forEach { candidate ->
            if (result.size < total && horizontalDistanceSquared(candidate, origin) >= 225.0 &&
                result.all { horizontalDistanceSquared(it, candidate) >= 20.25 }) {
                result += candidate
            }
        }
        // 极端情况下玩家位置挤在场地边缘，放宽间隔也必须补足五个落点。
        fallback.forEach { candidate ->
            if (result.size < total && candidate !in result) result += candidate
        }
        return result.take(total)
    }

    private fun randomHeavenlyImpact(center: Location, radius: Double, recent: ArrayDeque<Location>): Location {
        val random = ThreadLocalRandom.current()
        var selected = center.clone()
        for (attempt in 0 until 24) {
            val angle = random.nextDouble(0.0, PI * 2.0)
            val distance = sqrt(random.nextDouble()) * radius
            val x = center.x + cos(angle) * distance
            val z = center.z + sin(angle) * distance
            if (x !in 3119.5..3179.5 || z !in -1869.5..-1809.5) continue
            val candidate = Location(boss.world, x, 129.2, z)
            selected = candidate
            if (recent.all { horizontalDistanceSquared(it, candidate) >= 9.0 }) break
        }
        recent += selected
        while (recent.size > 24) recent.removeFirst()
        return selected
    }

    private fun heavenlyImpactBatch(
        center: Location,
        radius: Double,
        total: Int,
        recent: ArrayDeque<Location>
    ): List<Location> {
        val result = ArrayList<Location>(total)
        players().shuffled().take(total).forEach { player ->
            val locked = Location(boss.world, player.location.x, 129.2, player.location.z)
            result += locked
            recent += locked
        }
        while (recent.size > 24) recent.removeFirst()
        while (result.size < total) result += randomHeavenlyImpact(center, radius, recent)
        return result
    }

    private fun groundPoint(location: Location): Location = Location(boss.world, location.x, 129.2, location.z)

    private fun distanceToGroundSegmentSquared(point: Location, start: Location, end: Location): Double {
        val vx = end.x - start.x
        val vz = end.z - start.z
        val lengthSquared = vx * vx + vz * vz
        if (lengthSquared <= 1.0E-6) return horizontalDistanceSquared(point, start)
        val ratio = (((point.x - start.x) * vx + (point.z - start.z) * vz) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = point.x - (start.x + vx * ratio)
        val dz = point.z - (start.z + vz * ratio)
        return dx * dx + dz * dz
    }

    private fun rayDistanceToArenaEdge(origin: Location, direction: Vector): Double {
        val center = shengShanMechanicCenter(boss.world, origin.y)
        val offsetX = origin.x - center.x
        val offsetZ = origin.z - center.z
        val projection = offsetX * direction.x + offsetZ * direction.z
        val discriminant = projection * projection - (offsetX * offsetX + offsetZ * offsetZ - 31.5 * 31.5)
        return (-projection + sqrt(discriminant.coerceAtLeast(0.0))).coerceIn(8.0, 64.0)
    }

    private fun showVerticalFireX(center: Location, direction: Vector, duration: Int = 1, key: String = "") {
        val side = Vector(-direction.z, 0.0, direction.x).normalize().multiply(2.15)
        val bottomLeft = center.clone().subtract(side).add(0.0, -2.0, 0.0)
        val topRight = center.clone().add(side).add(0.0, 2.0, 0.0)
        val bottomRight = center.clone().add(side).add(0.0, -2.0, 0.0)
        val topLeft = center.clone().subtract(side).add(0.0, 2.0, 0.0)
        playEffect(ShengShanEffect.FIRE_SLASH, bottomLeft, topRight,
            ShengShanEffectOptions(radius = .28, height = .28, color = 0xFF6A2A,
                durationTicks = duration, intervalTicks = 2, key = if (key.isBlank()) "" else "${key}_a"))
        playEffect(ShengShanEffect.FIRE_SLASH, bottomRight, topLeft,
            ShengShanEffectOptions(radius = .28, height = .28, color = 0xFFB13A,
                durationTicks = duration, intervalTicks = 2, key = if (key.isBlank()) "" else "${key}_b"))
    }

    private fun passiveLater(delay: Long, action: () -> Unit) {
        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = later(delay) {
            passiveTasks.remove(task)
            action()
        }
        passiveTasks += task
    }

    private fun stopPassiveEffect(key: String) {
        passiveEffectKeys.remove(key)
        stopEffect(key)
    }

    private fun cancelPassivePaths() {
        passiveTasks.toList().forEach { it.cancel() }
        passiveTasks.clear()
        passiveEffectKeys.toList().forEach(::stopEffect)
        passiveEffectKeys.clear()
    }
}

internal class ReworkedWindBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : WindBossController(manager, session, boss) {
    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0

    private data class TornadoZone(
        val id: Int,
        val center: Location,
        val key: String,
        val immunePlayers: MutableSet<UUID> = HashSet()
    )
    private data class TornadoStruggle(val tornadoId: Int, var presses: Int = 0)
    private data class WindArrowImpact(val distance: Double, val player: Player? = null, val blockKey: String? = null)

    private val activeTornadoes = LinkedHashMap<Int, TornadoZone>()
    private val tornadoStruggles = HashMap<UUID, TornadoStruggle>()
    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .3 }
    private var nextTornadoAt = -1
    private var tornadoSerial = 0
    private var effectSerial = 0
    private var empowered = false
    private var chargedWindArrow: Arrow? = null
    private var chargedWindArrowEffectKey: String? = null
    private var chargedWindArrowTargetId: UUID? = null
    private var chargedWindArrowLockTask: org.bukkit.scheduler.BukkitTask? = null
    private var windSpinBar: org.bukkit.boss.BossBar? = null
    private val windSpinHitRound = HashMap<UUID, Int>()

    override fun onUltimateWarningStarted() = Unit

    override fun onTick() {
        updateTornadoStruggles()
        if (invulnerable) return
        if (nextTornadoAt < 0) {
            nextTornadoAt = elapsedTicks + 240
            return
        }
        if (elapsedTicks < nextTornadoAt) return
        do nextTornadoAt += 240 while (nextTornadoAt <= elapsedTicks)
        warnPassiveTornadoes()
    }

    private fun warnPassiveTornadoes() {
        val total = if (empowered) 10 else 5
        val targets = spacedArenaTargets(boss.world, players().map { it.location }, total, 10.5)
        if (targets.isEmpty()) return
        tornadoSerial++
        val serial = tornadoSerial
        message("§6$total 处风眼正在汇聚，三秒后龙卷风将席卷预警区域！")
        targets.forEachIndexed { index, target ->
            playEffect(ShengShanEffect.WIND_FIELD, target,
                options = ShengShanEffectOptions(radius = 5.0, height = .35, color = 0xDDF7F2,
                    durationTicks = 60, intervalTicks = 4, key = "wind_tornado_warning_${serial}_$index"))
        }
        sound(shengShanMechanicCenter(boss.world), Sound.ENTITY_BREEZE_WHIRL, .9f, .68f)
        later(60L) {
            targets.indices.forEach { stopEffect("wind_tornado_warning_${serial}_$it") }
            if (invulnerable) return@later
            targets.forEach(::activateTornado)
        }
    }

    private fun activateTornado(center: Location) {
        tornadoSerial++
        val id = tornadoSerial
        val key = "wind_tornado_active_$id"
        val zone = TornadoZone(id, center.clone().apply { y = 129.0 }, key)
        activeTornadoes[id] = zone
        playEffect(ShengShanEffect.WIND_TORNADO, zone.center,
            options = ShengShanEffectOptions(radius = 5.0, height = 8.0, color = 0xE9FAF6,
                durationTicks = 100, intervalTicks = 2, key = key))
        soundNearby(zone.center, Sound.ENTITY_BREEZE_WHIRL, 24.0, .8f, .82f)
        later(100L) {
            activeTornadoes.remove(id)
            stopEffect(key)
            releaseTornadoStruggles(id)
        }
    }

    private fun updateTornadoStruggles() {
        val participants = players()
        participants.forEach { player ->
            val current = tornadoStruggles[player.uniqueId]
            val currentZone = current?.let { activeTornadoes[it.tornadoId] }
            val insideCurrent = currentZone != null &&
                horizontalDistanceSquared(player.location, currentZone.center) <= 25.0
            if (current != null && !insideCurrent) releaseTornadoPlayer(player)
            if (tornadoStruggles.containsKey(player.uniqueId)) return@forEach
            val zone = activeTornadoes.values
                .filter { player.uniqueId !in it.immunePlayers && horizontalDistanceSquared(player.location, it.center) <= 25.0 }
                .minByOrNull { horizontalDistanceSquared(player.location, it.center) } ?: return@forEach
            tornadoStruggles[player.uniqueId] = TornadoStruggle(zone.id)
            player.sendActionBar("§f你被龙卷风卷起！§e连续按左键10次§f挣脱！")
            soundNearby(player.location, Sound.ENTITY_BREEZE_WIND_BURST, 14.0, .65f, 1.25f)
        }
        tornadoStruggles.toList().forEach { (playerId, struggle) ->
            val player = Bukkit.getPlayer(playerId)
            val zone = activeTornadoes[struggle.tornadoId]
            if (player == null || !player.isOnline || player.isDead || zone == null ||
                horizontalDistanceSquared(player.location, zone.center) > 25.0) {
                player?.let(::releaseTornadoPlayer)
                tornadoStruggles.remove(playerId)
                return@forEach
            }
            player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 10, 4, false, true, true), true)
            if (player.velocity.y < .55) player.velocity = player.velocity.clone().setY(.55)
            if (elapsedTicks % 10 == 0) playEffect(ShengShanEffect.WIND_FIELD,
                player.location.clone().add(0.0, 1.0, 0.0),
                options = ShengShanEffectOptions(radius = 1.2, height = 2.4, color = 0xE8FAF5))
        }
    }

    override fun castNormalSkill(index: Int) {
        if (index == 0) spinningWindChase() else launchWindArrow()
    }

    override fun onNormalWarningStarted(index: Int) {
        effectSerial++
        if (index == 0) {
            boss.isInvisible = true
            playEffect(ShengShanEffect.WIND_FIELD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
                options = ShengShanEffectOptions(radius = 3.2, height = 3.0, color = 0xECFAF7,
                    durationTicks = 50, intervalTicks = 2, key = "wind_spin_cloud_$effectSerial"))
            soundNearby(boss.location, Sound.ENTITY_BREEZE_CHARGE, 30.0, .9f, .72f)
        } else {
            prepareWindArrow()
        }
    }

    private fun spinningWindChase() {
        boss.isInvisible = false
        windSpinHitRound.clear()
        val total = if (empowered) 360 else 260
        windSpinBar?.let(::removeBar)
        windSpinBar = phaseBar("§f回旋风刃持续中", total, BarColor.WHITE,
            onComplete = { windSpinBar = null })
        runSpinRound(1)
    }

    private fun runSpinRound(round: Int) {
        val totalRounds = if (empowered) 4 else 3
        val duration = 60
        var target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
            ?: return finishWindSpin()
        var ticks = 0
        boss.setAI(false); boss.velocity = Vector()
        soundNearby(boss.location, Sound.ENTITY_BREEZE_WHIRL, 34.0, 1.0f, 1.05f)
        lateinit var chase: org.bukkit.scheduler.BukkitTask
        chase = every(0L, 1L) {
            ticks++
            if (!target.isOnline || target.isDead) {
                target = players().minByOrNull { horizontalDistanceSquared(it.location, boss.location) }
                    ?: run { chase.cancel(); finishWindSpin(); return@every }
            }
            val current = boss.location.clone()
            val direction = horizontalDirection(current, target.location)
            val destination = windChaseDestination(current, direction, .45)
            boss.teleport(destination.apply {
                yaw = current.yaw + 72.0f
                pitch = 0.0f
            })
            players().filter { player -> windSpinHitRound[player.uniqueId] != round &&
                distanceToSegmentSquared(player.location.clone().add(0.0, 1.0, 0.0), current, boss.location) <= 6.25
            }.forEach { player ->
                windSpinHitRound[player.uniqueId] = round
                dealDamage(player, 35.0)
            }
            boss.velocity = Vector()
            if (ticks % 2 == 0) playEffect(ShengShanEffect.WIND_BLADE,
                boss.location.clone().add(0.0, boss.height * .5, 0.0),
                options = ShengShanEffectOptions(radius = 2.6, height = 2.2, color = 0xE8FAF6))
            if (ticks >= duration) {
                chase.cancel(); boss.velocity = Vector()
                if (round >= totalRounds) finishWindSpin() else {
                    playEffect(ShengShanEffect.WIND_FIELD, boss.location.clone().add(0.0, 1.0, 0.0),
                        options = ShengShanEffectOptions(radius = 3.0, height = 2.8, color = 0xDDF5F0,
                            durationTicks = 40, intervalTicks = 3, key = "wind_spin_pause_${effectSerial}_$round"))
                    later(40L) { runSpinRound(round + 1) }
                }
            }
        }
    }

    private fun windChaseDestination(origin: Location, direction: Vector, distance: Double): Location {
        val direct = origin.clone().add(direction.clone().multiply(distance))
        windChaseWalkOrStep(direct)?.let { return it }
        val slideX = origin.clone().add(direction.x * distance, 0.0, 0.0)
        windChaseWalkOrStep(slideX)?.let { return it }
        val slideZ = origin.clone().add(0.0, 0.0, direction.z * distance)
        return windChaseWalkOrStep(slideZ) ?: origin.clone()
    }

    private fun windChaseWalkOrStep(destination: Location): Location? {
        if (isWindChasePassable(destination)) return destination
        if (destination.block.isPassable) return null
        val oneBlockUp = destination.clone().add(0.0, 1.0, 0.0)
        return oneBlockUp.takeIf(::isWindChasePassable)
    }

    private fun isWindChasePassable(location: Location): Boolean =
        location.block.isPassable && location.clone().add(0.0, 1.0, 0.0).block.isPassable

    private fun finishWindSpin() {
        windSpinBar?.let(::removeBar)
        windSpinBar = null
        windSpinHitRound.clear()
        boss.isInvisible = false
        boss.velocity = Vector(); boss.setAI(true)
        soundNearby(boss.location, Sound.ENTITY_BREEZE_IDLE_GROUND, 30.0, .8f, 1.2f)
        finishNormalSkill()
    }

    private fun prepareWindArrow() {
        clearChargedWindArrow()
        val target = players().maxByOrNull { horizontalDistanceSquared(it.location, boss.location) } ?: return
        val direction = target.eyeLocation.toVector().subtract(boss.eyeLocation.toVector()).let {
            if (it.lengthSquared() < .001) Vector(1.0, 0.0, 0.0) else it.normalize()
        }
        boss.teleport(boss.location.clone().apply { this.direction = direction })
        val origin = boss.eyeLocation.clone().add(direction.clone().multiply(2.2))
        val arrow = spawnWindArrowEntity(origin, direction)
        chargedWindArrow = arrow
        chargedWindArrowTargetId = target.uniqueId
        message("§6雾正在身前凝聚风之矢，玩家§e${target.name}§6即将遭到极高伤害的射击，躲在障碍物后可减缓箭矢的冲击力！")
        val key = "wind_arrow_charge_$effectSerial"
        chargedWindArrowEffectKey = key
        playEffect(ShengShanEffect.WIND_PROJECTILE, origin,
            options = ShengShanEffectOptions(radius = 1.6, height = 1.6, color = 0xE9FAF7,
                durationTicks = 100, intervalTicks = 2, key = key))
        playEffect(ShengShanEffect.WIND_TRAIL, boss.eyeLocation, origin,
            ShengShanEffectOptions(radius = .3, height = .3, color = 0xF4FFFD,
                durationTicks = 100, intervalTicks = 3, key = "${key}_shaft"))
        var lockTicks = 0
        lateinit var lockTask: org.bukkit.scheduler.BukkitTask
        lockTask = every(0L, 5L) {
            lockTicks += 5
            val locked = chargedWindArrowTargetId?.let(Bukkit::getPlayer)
            if (locked == null || !locked.isOnline || locked.isDead || lockTicks > 100) {
                lockTask.cancel()
                if (chargedWindArrowLockTask === lockTask) chargedWindArrowLockTask = null
            } else {
                playEffect(ShengShanEffect.WIND_TARGET_LOCK, locked.location.clone().add(0.0, .15, 0.0),
                    options = ShengShanEffectOptions(radius = 1.15, height = .18, color = 0xFF3030))
            }
        }
        chargedWindArrowLockTask = lockTask
        soundNearby(boss.location, Sound.ENTITY_BREEZE_CHARGE, 34.0, 1.0f, .62f)
    }

    private fun launchWindArrow() {
        chargedWindArrowLockTask?.cancel()
        chargedWindArrowLockTask = null
        val target = chargedWindArrowTargetId?.let(Bukkit::getPlayer)?.takeIf { it.isOnline && !it.isDead }
            ?: players().maxByOrNull { horizontalDistanceSquared(it.location, boss.location) }
        chargedWindArrowTargetId = null
        if (target == null) {
            clearChargedWindArrow()
            return finishNormalSkill()
        }
        val direction = target.eyeLocation.toVector().subtract(boss.eyeLocation.toVector()).let {
            if (it.lengthSquared() < .001) Vector(1.0, 0.0, 0.0) else it.normalize()
        }
        boss.teleport(boss.location.clone().apply { this.direction = direction })
        val arrow = chargedWindArrow?.takeIf { it.isValid }
            ?: spawnWindArrowEntity(boss.eyeLocation.clone().add(direction.clone().multiply(2.2)), direction)
        chargedWindArrow = null
        chargedWindArrowEffectKey?.let { key -> stopEffect(key); stopEffect("${key}_shaft") }
        chargedWindArrowEffectKey = null
        arrow.teleport(boss.eyeLocation.clone().add(direction.clone().multiply(2.2)).apply { this.direction = direction })
        setArrowFacing(arrow, direction)
        soundNearby(boss.location, Sound.ENTITY_BREEZE_SHOOT, 42.0, 1.15f, 1.05f)
        val hitPlayers = HashSet<UUID>()
        val crossedBlocks = HashSet<String>()
        var damage = 80.0
        var travelled = 0.0
        var previous = arrow.location.clone()
        lateinit var flight: org.bukkit.scheduler.BukkitTask
        flight = every(0L, 1L) {
            val next = previous.clone().add(direction.clone().multiply(1.8))
            val impacts = windArrowImpacts(previous, next, hitPlayers, crossedBlocks)
            for (impact in impacts.sortedBy { it.distance }) {
                if (damage <= 0.0) break
                val player = impact.player
                if (player != null) {
                    hitPlayers += player.uniqueId
                    dealDamage(player, damage, 1.0)
                    playEffect(ShengShanEffect.WIND_FIELD, player.location.clone().add(0.0, 1.0, 0.0),
                        options = ShengShanEffectOptions(radius = 1.5, height = 2.0, color = 0xF1FFFC))
                    damage -= 20.0
                } else impact.blockKey?.let {
                    crossedBlocks += it
                    damage -= 20.0
                    playEffect(ShengShanEffect.WIND_FIELD, next,
                        options = ShengShanEffectOptions(radius = 1.0, height = 1.0, color = 0xD9F3EF))
                }
            }
            playEffect(ShengShanEffect.WIND_TRAIL, previous, next,
                ShengShanEffectOptions(radius = .25, height = .25, color = 0xF4FFFD))
            arrow.teleport(next.apply { this.direction = direction })
            setArrowFacing(arrow, direction)
            previous = next
            travelled += 1.8
            if (damage <= 0.0 || travelled >= 70.0 || next.x !in 3115.0..3184.0 || next.z !in -1874.0..-1804.0) {
                flight.cancel(); arrow.remove()
                soundNearby(next, Sound.ENTITY_BREEZE_WIND_BURST, 26.0, .8f, 1.25f)
                finishNormalSkill()
            }
        }
    }

    private fun windArrowImpacts(
        start: Location,
        end: Location,
        hitPlayers: Set<UUID>,
        crossedBlocks: Set<String>
    ): List<WindArrowImpact> {
        val result = ArrayList<WindArrowImpact>()
        val segment = end.toVector().subtract(start.toVector())
        val length = segment.length().coerceAtLeast(.001)
        val direction = segment.clone().normalize()
        players().filter { it.uniqueId !in hitPlayers && distanceToSegmentSquared(it.eyeLocation, start, end) <= 2.25 }
            .forEach { player ->
                val along = player.eyeLocation.toVector().subtract(start.toVector()).dot(direction).coerceIn(0.0, length)
                result += WindArrowImpact(along, player = player)
            }
        val samples = (length / .2).toInt().coerceAtLeast(1)
        for (index in 0..samples) {
            val distance = length * index / samples.toDouble()
            val point = start.clone().add(direction.clone().multiply(distance))
            val block = point.block
            if (block.isPassable || block.isLiquid) continue
            val key = "${block.x},${block.y},${block.z}"
            if (key !in crossedBlocks && result.none { it.blockKey == key }) {
                result += WindArrowImpact(distance, blockKey = key)
            }
        }
        return result
    }

    private fun spawnWindArrowEntity(location: Location, direction: Vector): Arrow =
        boss.world.spawn(location, Arrow::class.java) { arrow ->
            arrow.shooter = boss
            arrow.setGravity(false)
            arrow.velocity = Vector()
            arrow.damage = 0.0
            arrow.isCritical = false
            arrow.isInvulnerable = true
            arrow.isPersistent = false
            arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
            setArrowFacing(arrow, direction)
        }.also {
            own(it)
            manager.trackEntity(session, it, "wind_piercing_arrow")
        }

    private fun setArrowFacing(arrow: Arrow, direction: Vector) {
        val facing = arrow.location.clone().apply { this.direction = direction }
        arrow.setRotation(facing.yaw, facing.pitch)
    }

    override fun onPlayerInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.LEFT_CLICK_AIR && event.action != Action.LEFT_CLICK_BLOCK) return false
        val struggle = tornadoStruggles[event.player.uniqueId] ?: return false
        struggle.presses++
        if (struggle.presses >= 10) {
            activeTornadoes[struggle.tornadoId]?.immunePlayers?.add(event.player.uniqueId)
            tornadoStruggles.remove(event.player.uniqueId)
            event.player.removePotionEffect(PotionEffectType.LEVITATION)
            event.player.velocity = event.player.velocity.clone().setY(-.08)
            event.player.fallDistance = 0.0f
            event.player.sendActionBar("§a你挣脱了这处龙卷风！")
            soundNearby(event.player.location, Sound.ENTITY_BREEZE_WIND_BURST, 14.0, .7f, 1.35f)
        } else {
            event.player.sendActionBar("§f龙卷风挣脱进度：§e${struggle.presses}/10")
        }
        return true
    }

    private fun releaseTornadoPlayer(player: Player) {
        tornadoStruggles.remove(player.uniqueId)
        player.removePotionEffect(PotionEffectType.LEVITATION)
        player.fallDistance = 0.0f
    }

    private fun releaseTornadoStruggles(tornadoId: Int) {
        tornadoStruggles.filterValues { it.tornadoId == tornadoId }.keys.toList().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let(::releaseTornadoPlayer) ?: tornadoStruggles.remove(playerId)
        }
    }

    override fun startUltimate() {
        clearChargedWindArrow()
        invulnerable = true
        boss.isInvisible = false
        boss.setAI(false); boss.setGravity(false); boss.isCollidable = false; boss.velocity = Vector()
        val destination = Trigram.WIND.spawn(boss.world)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 140) {
                returnTask.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginWindEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.46)).apply { this.direction = direction })
                if (ticks % 3 == 0) playEffect(ShengShanEffect.WIND_TRAIL,
                    boss.location.clone().subtract(direction.clone().multiply(1.8)).add(0.0, 1.0, 0.0),
                    boss.location.clone().add(0.0, 1.0, 0.0), ShengShanEffectOptions(color = 0xE8F8F5))
            }
        }
    }

    private fun beginWindEmpowerment() {
        effectSerial++
        elder("wind_erosion_empower",
            "当心！它正在以风蚀之力重塑自身，步伐与攻势都变得愈发迅疾！\n§e接下来战场会出现更多龙卷风，回旋风刃也将进行更漫长、更频繁的追猎！",
            firstOnly = false)
        playEffect(ShengShanEffect.WIND_FIELD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 5.5, height = 5.0, color = 0xECFAF7,
                durationTicks = 100, intervalTicks = 2, key = "wind_empower_cloud_$effectSerial"))
        playEffect(ShengShanEffect.SKY_CLOUD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 4.5, height = 4.0, color = 0xF4FFFD,
                durationTicks = 100, intervalTicks = 3, key = "wind_empower_mist_$effectSerial"))
        sound(boss.location, Sound.ENTITY_BREEZE_WHIRL, 1.1f, .62f)
        later(100L) {
            applyWindEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(true); boss.isCollidable = true; boss.setAI(true)
            message("§6风蚀圣山完成，雾的步伐与攻势都变得更加迅疾，战场风眼也愈发密集！")
        }
    }

    private fun applyWindEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 30.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
    }

    private fun clearChargedWindArrow() {
        chargedWindArrowLockTask?.cancel()
        chargedWindArrowLockTask = null
        chargedWindArrowTargetId = null
        chargedWindArrow?.remove()
        chargedWindArrow = null
        chargedWindArrowEffectKey?.let { key -> stopEffect(key); stopEffect("${key}_shaft") }
        chargedWindArrowEffectKey = null
    }

    override fun shutdown(restoreTerrain: Boolean) {
        windSpinBar?.let(::removeBar)
        windSpinBar = null
        clearChargedWindArrow()
        tornadoStruggles.keys.toList().mapNotNull(Bukkit::getPlayer).forEach(::releaseTornadoPlayer)
        tornadoStruggles.clear()
        activeTornadoes.values.forEach { stopEffect(it.key) }
        activeTornadoes.clear()
        boss.isInvisible = false
        super.shutdown(restoreTerrain)
    }
}

internal class ReworkedSwampBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : SwampBossController(manager, session, boss) {
    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = if (empowered) 1.20 else 1.0
    override fun showGenericNormalParticle(index: Int): Boolean = false

    private data class SplitClone(var direction: Vector, var ticks: Int = 0)
    private data class SplitTrap(
        val player: Player,
        val slime: LivingEntity,
        var hits: Int,
        val endsAt: Int,
        val previousAllowFlight: Boolean,
        val previousFlying: Boolean
    )

    private val baseMovementSpeed by lazy { boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: .3 }
    private val splitClones = LinkedHashMap<UUID, SplitClone>()
    private val splitTraps = LinkedHashMap<UUID, SplitTrap>()
    private val splitTrapImmuneUntil = HashMap<UUID, Int>()
    private var nextLeapAt = -1
    private var sawNormalSkill = false
    private var hasSettledNormalSkill = false
    private var passiveLeapActive = false
    private var empowered = false
    private var effectSerial = 0

    override fun onNormalWarningStarted(index: Int) {
        effectSerial++
        val center = boss.location.clone().add(0.0, boss.height * .5, 0.0)
        if (index == 0) {
            playEffect(ShengShanEffect.SWAMP_BUBBLE, center,
                options = ShengShanEffectOptions(radius = 3.2, height = 3.0, color = 0x6E9E42,
                    durationTicks = 20, intervalTicks = 2, key = "swamp_tide_charge_$effectSerial"))
        } else {
            playEffect(ShengShanEffect.POISON_FOG, center,
                options = ShengShanEffectOptions(radius = 3.3, height = 3.0, color = 0x9760B8,
                    durationTicks = 20, intervalTicks = 2, key = "swamp_split_charge_$effectSerial"))
        }
        soundNearby(boss.location, Sound.ENTITY_SLIME_SQUISH, 30.0, .9f, if (index == 0) .62f else .82f)
    }

    override fun onTick() {
        super.onTick()
        updateSplitClones()
        updateSplitTraps()
        if (nextLeapAt < 0) nextLeapAt = elapsedTicks + passiveIntervalTicks()
        if (normalSkillRunning && !passiveLeapActive) sawNormalSkill = true
        if (!normalSkillRunning && sawNormalSkill) {
            sawNormalSkill = false
            hasSettledNormalSkill = true
        }
        if (!hasSettledNormalSkill || normalSkillRunning || invulnerable || elapsedTicks < nextLeapAt) return
        beginPassiveLeap()
    }

    private fun beginPassiveLeap() {
        val target = players().randomOrNull() ?: return
        val landing = manager.groundLocation(boss.world, target.location.x, target.location.z,
            target.location.blockY.coerceIn(128, 210))
        if (!landing.block.isPassable || !landing.clone().add(0.0, 2.0, 0.0).block.isPassable) return
        passiveLeapActive = true
        normalSkillRunning = true
        boss.setAI(false); boss.setGravity(false); boss.velocity = Vector()
        effectSerial++
        val key = "swamp_leap_warning_$effectSerial"
        val from = boss.location.clone()
        val apex = landing.clone().add(0.0, 7.0, 0.0)
        message("§6恶降临在了§e${target.name}§6的头上，立即离开脚下的危险区域！")
        playEffect(ShengShanEffect.SWAMP_OUTLINE, landing,
            options = ShengShanEffectOptions(radius = 6.0, height = .25, color = 0xF02D35,
                durationTicks = 48, intervalTicks = 3, key = key))
        soundNearby(boss.location, Sound.ENTITY_SLIME_JUMP, 28.0, .9f, .7f)
        var ticks = 0
        lateinit var rise: org.bukkit.scheduler.BukkitTask
        rise = every(0L, 1L) {
            ticks++
            val ratio = (ticks / 10.0).coerceIn(0.0, 1.0)
            val point = from.toVector().multiply(1.0 - ratio).add(apex.toVector().multiply(ratio)).toLocation(boss.world)
            boss.teleport(point)
            if (ticks >= 10) {
                rise.cancel()
                boss.teleport(apex); boss.velocity = Vector()
                later(30L) { descendPassiveLeap(apex, landing, key) }
            }
        }
    }

    private fun descendPassiveLeap(apex: Location, landing: Location, warningKey: String) {
        var ticks = 0
        lateinit var descent: org.bukkit.scheduler.BukkitTask
        descent = every(0L, 1L) {
            ticks++
            val ratio = (ticks / 8.0).coerceIn(0.0, 1.0)
            boss.teleport(apex.toVector().multiply(1.0 - ratio).add(landing.toVector().multiply(ratio)).toLocation(boss.world))
            if (ticks >= 8) {
                descent.cancel()
                resolvePassiveLanding(landing, warningKey)
            }
        }
    }

    private fun resolvePassiveLanding(landing: Location, warningKey: String) {
        stopEffect(warningKey)
        boss.teleport(landing); boss.setGravity(true); boss.setAI(true); boss.velocity = Vector()
        playEffect(ShengShanEffect.SWAMP_SPLASH, landing,
            options = ShengShanEffectOptions(radius = 6.0, height = 3.2, color = 0x759B48))
        playEffect(ShengShanEffect.SWAMP_SPLASH, landing.clone().add(0.0, .4, 0.0),
            options = ShengShanEffectOptions(radius = 8.0, height = 5.0, color = 0x8BAE58))
        playEffect(ShengShanEffect.POISON_FOG, landing.clone().add(0.0, .5, 0.0),
            options = ShengShanEffectOptions(radius = 7.0, height = 3.2, color = 0x6C9248))
        playEffect(ShengShanEffect.EARTH_PULSE, landing,
            options = ShengShanEffectOptions(radius = 7.5, height = .8, color = 0x738C47))
        sound(Sound.ENTITY_GENERIC_EXPLODE, 4.0f, .52f, landing)
        sound(Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3.5f, .68f, landing)
        sound(Sound.ENTITY_RAVAGER_STEP, 1.8f, .58f, landing)
        sound(Sound.ENTITY_SLIME_SQUISH, 1.7f, .55f, landing)
        players().filter { horizontalDistanceSquared(it.location, landing) <= 36.0 }.forEach { player ->
            dealDamage(player, 50.0)
            player.velocity = player.velocity.clone().setY(1.05)
            player.fallDistance = 0.0f
        }
        passiveLeapActive = false
        normalSkillRunning = false
        nextLeapAt = elapsedTicks + passiveIntervalTicks()
    }

    private fun passiveIntervalTicks(): Int = if (empowered) 100 else 140

    override fun castNormalSkill(index: Int) {
        if (index == 0) poisonTide() else splitSwamp()
    }

    private fun poisonTide() {
        val participants = players().shuffled().take(5)
        if (participants.isEmpty()) return finishNormalSkill()
        val targets = spacedArenaTargets(boss.world, participants.map { it.location }, total = 5, minSeparation = 10.5)
        val origin = Location(boss.world, boss.location.x, 129.0, boss.location.z)
        val paths = targets.mapNotNull { target ->
            val direction = horizontalDirection(origin, target)
            (1..15).map { step -> origin.clone().add(direction.clone().multiply(step * 3.0)) }
                .takeWhile(::insideSwampCombatField)
                .takeIf { it.isNotEmpty() }
        }
        if (paths.isEmpty()) return finishNormalSkill()
        soundNearby(boss.location, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 34.0, .9f, .7f)
        val maximumNodes = paths.maxOf { it.size }
        paths.forEachIndexed { pathIndex, path ->
            path.forEachIndexed { nodeIndex, center ->
                showPoisonVentWarning(center, pathIndex, nodeIndex, 20 + nodeIndex * 10)
            }
        }
        repeat(maximumNodes) { stage ->
            later(20L + stage * 10L) {
                paths.forEachIndexed { pathIndex, path ->
                    if (stage < path.size) eruptPoisonFountain(path[stage], pathIndex, stage)
                }
            }
        }
        later(20L + maximumNodes * 10L) { finishNormalSkill() }
    }

    private fun showPoisonVentWarning(center: Location, pathIndex: Int, nodeIndex: Int, duration: Int) {
        playEffect(ShengShanEffect.SWAMP_VENT_WARNING, center,
            options = ShengShanEffectOptions(radius = 1.35, height = .12, color = 0xF03A45,
                durationTicks = duration, intervalTicks = 10,
                key = "swamp_tide_vent_${effectSerial}_${pathIndex}_$nodeIndex"))
    }

    private fun eruptPoisonFountain(center: Location, pathIndex: Int, nodeIndex: Int) {
        stopEffect("swamp_tide_vent_${effectSerial}_${pathIndex}_$nodeIndex")
        val summit = center.clone().add(0.0, 4.5, 0.0)
        playEffect(ShengShanEffect.SWAMP_VOLCANO, center, summit,
            ShengShanEffectOptions(radius = 1.6, height = 4.5, color = 0x6D9A42))
        playEffect(ShengShanEffect.SWAMP_SPLASH, summit,
            options = ShengShanEffectOptions(radius = 1.8, height = 1.4, color = 0x7CAA49))
        playEffect(ShengShanEffect.POISON_FOG, center.clone().add(0.0, .35, 0.0),
            options = ShengShanEffectOptions(radius = 2.0, height = .8, color = 0x587F39))
        soundNearby(center, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 18.0, .65f, .72f)
        soundNearby(center, Sound.ENTITY_SLIME_SQUISH, 18.0, .55f, .86f)
        players().filter { horizontalDistanceSquared(it.location, center) <= 16.0 }.forEach { player ->
            dealDamage(player, 35.0)
            player.addPotionEffect(PotionEffect(PotionEffectType.POISON, 100, if (empowered) 4 else 2,
                false, true, true), true)
        }
    }

    private fun insideSwampCombatField(location: Location): Boolean =
        location.x in 3115.0..3184.0 && location.z in -1874.0..-1804.0

    private fun splitSwamp() {
        repeat(4) { round -> later(round * 30L) { fireSplitRound() } }
        later(100L) { finishNormalSkill() }
    }

    private fun fireSplitRound() {
        val participants = players().shuffled()
        val projectileCount = if (empowered) 3 else 1
        val playerTargets = participants.take(projectileCount)
        playerTargets.forEach { target -> spawnSplitClone(horizontalDirection(boss.location, target.location)) }
        repeat(projectileCount - playerTargets.size) {
            val angle = ThreadLocalRandom.current().nextDouble(0.0, PI * 2.0)
            spawnSplitClone(Vector(cos(angle), 0.0, sin(angle)))
        }
        soundNearby(boss.location, Sound.ENTITY_SLIME_JUMP, 28.0, .85f, .78f)
    }

    private fun spawnSplitClone(direction: Vector) {
        val clone = spawn("shengshan_swamp_clone", boss.location.clone().add(0.0, .5, 0.0), "swamp_trap_clone")
            ?: return
        clone.setAI(false); clone.setGravity(false); clone.velocity = Vector()
        splitClones[clone.uniqueId] = SplitClone(direction.clone().normalize())
        playEffect(ShengShanEffect.POISON_STREAM, clone.location,
            clone.location.clone().add(direction.clone().multiply(8.0)),
            ShengShanEffectOptions(radius = .35, height = .8, color = 0x9461AF))
    }

    private fun updateSplitClones() {
        val iterator = splitClones.iterator()
        while (iterator.hasNext()) {
            val (id, shot) = iterator.next()
            val clone = Bukkit.getEntity(id) as? LivingEntity
            if (clone == null || !clone.isValid || clone.isDead || shot.ticks >= 120) {
                iterator.remove(); clone?.remove(); continue
            }
            shot.ticks++
            clone.teleport(clone.location.clone().add(shot.direction.clone().multiply(.75)).apply {
                this.direction = shot.direction
            })
            if (shot.ticks % 4 == 0) playEffect(ShengShanEffect.POISON_FOG, clone.location,
                options = ShengShanEffectOptions(radius = .85, height = .9, color = 0x9360AE))
            val player = players().firstOrNull { it.uniqueId !in splitTraps &&
                elapsedTicks >= (splitTrapImmuneUntil[it.uniqueId] ?: 0) &&
                it.location.distanceSquared(clone.location) <= 3.0 }
            if (player != null) {
                iterator.remove()
                val trap = SplitTrap(player, clone, 0, elapsedTicks + 100,
                    player.allowFlight, player.isFlying)
                player.isFlying = false
                player.allowFlight = true
                player.fallDistance = 0.0f
                splitTraps[player.uniqueId] = trap
                player.sendActionBar("§5你被分沼吞噬！§e攻击身旁分身5次§5即可挣脱！")
            }
        }
    }

    private fun updateSplitTraps() {
        splitTraps.values.toList().forEach { trap ->
            if (!trap.player.isOnline || trap.player.isDead || !trap.slime.isValid || trap.hits >= 5 || elapsedTicks >= trap.endsAt) {
                releaseSplitTrap(trap)
                return@forEach
            }
            trap.player.isFlying = false
            trap.player.fallDistance = 0.0f
            trap.player.teleport(trap.slime.location.clone().add(0.0, .4, 0.0).apply {
                yaw = trap.player.location.yaw; pitch = trap.player.location.pitch
            })
            if ((trap.endsAt - elapsedTicks) % 20 == 0) dealNormalAttackDamage(trap.player, 10.0)
        }
    }

    override fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val trap = splitTraps.values.firstOrNull { it.slime.uniqueId == event.entity.uniqueId }
            ?: return super.onDamageByEntity(event)
        val player = event.damager as? Player ?: return
        if (!manager.isParticipant(session, player)) return
        event.isCancelled = true
        trap.hits++
        player.sendActionBar("§e挣脱分沼：${trap.hits}/5")
        if (trap.hits >= 5) releaseSplitTrap(trap)
    }

    private fun releaseSplitTrap(trap: SplitTrap) {
        splitTraps.remove(trap.player.uniqueId)
        splitTrapImmuneUntil[trap.player.uniqueId] = elapsedTicks + 60
        restoreSplitTrapFlight(trap)
        trap.slime.remove()
        playEffect(ShengShanEffect.SWAMP_SPLASH, trap.player.location,
            options = ShengShanEffectOptions(radius = 1.5, height = 1.6, color = 0x9461AF))
        sound(trap.player.location, Sound.ENTITY_SLIME_SQUISH, .9f, 1.3f)
    }

    private fun restoreSplitTrapFlight(trap: SplitTrap) {
        trap.player.isFlying = false
        trap.player.allowFlight = trap.previousAllowFlight
        if (trap.previousAllowFlight && trap.previousFlying) trap.player.isFlying = true
        trap.player.fallDistance = 0.0f
    }

    override fun removePlayer(player: Player) {
        splitTraps[player.uniqueId]?.let(::releaseSplitTrap)
        super.removePlayer(player)
    }

    override fun onUltimateWarningStarted() {
        splitClones.keys.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        splitClones.clear()
        splitTraps.values.toList().forEach(::releaseSplitTrap)
    }

    override fun startUltimate() {
        invulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.isCollidable = false; boss.velocity = Vector()
        val destination = Trigram.SWAMP.spawn(boss.world)
        var ticks = 0
        lateinit var returnTask: org.bukkit.scheduler.BukkitTask
        returnTask = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .36 || ticks >= 140) {
                returnTask.cancel(); boss.teleport(destination); boss.velocity = Vector(); beginSwampEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.44)).apply { this.direction = direction })
                if (ticks % 4 == 0) playEffect(ShengShanEffect.POISON_FOG,
                    boss.location.clone().add(0.0, boss.height * .5, 0.0),
                    options = ShengShanEffectOptions(radius = 1.6, height = 1.5, color = 0x8B54A8))
            }
        }
    }

    private fun beginSwampEmpowerment() {
        effectSerial++
        elder("swamp_soil_empower",
            "当心！它正在以息壤封泽重塑自身，步伐与攻势都变得愈发凶猛！\n§e接下来它会更频繁地从天而降，菌潮毒性加深，分沼也会从多个方向同时袭来！",
            firstOnly = false)
        playEffect(ShengShanEffect.SWAMP_OUTLINE, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 5.5, height = 4.5, color = 0xA55AD1,
                durationTicks = 100, intervalTicks = 2, key = "swamp_empower_ring_$effectSerial"))
        playEffect(ShengShanEffect.POISON_FOG, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 4.8, height = 4.0, color = 0x8A45B5,
                durationTicks = 100, intervalTicks = 2, key = "swamp_empower_cloud_$effectSerial"))
        sound(boss.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.1f, .62f)
        later(100L) {
            applySwampEmpowerment()
            finishUltimateTransformation()
            boss.setGravity(true); boss.isCollidable = true; boss.setAI(true)
            message("§6息壤封泽完成，恶的步伐与攻势更加强盛，毒泽也变得愈发躁动！")
        }
    }

    private fun applySwampEmpowerment() {
        if (empowered) return
        empowered = true
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * 1.20
        val damage = boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 25.0
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, damage * 1.20)
        if (nextLeapAt >= 0) nextLeapAt = minOf(nextLeapAt, elapsedTicks + 100)
    }

    override fun shutdown(restoreTerrain: Boolean) {
        splitClones.keys.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        splitClones.clear()
        splitTraps.values.forEach { trap -> restoreSplitTrapFlight(trap); trap.slime.remove() }
        splitTraps.clear()
        splitTrapImmuneUntil.clear()
        boss.setGravity(true); boss.isCollidable = true
        super.shutdown(restoreTerrain)
    }
}

internal class ReworkedEarthBossController(
    manager: ShengShanDungeonManager, session: ShengShanSession, boss: LivingEntity
) : EarthBossController(manager, session, boss) {
    override val normalSkillCooldownTicks = 200
    override val ultimateHealthThresholds = doubleArrayOf(0.50)
    override val outgoingDamageMultiplier: Double get() = combatMultiplier()

    private data class EarthGate(val index: Int, val center: Location) {
        val group: String get() = "shengshan_earth_gate_$index"
        val effectKey: String get() = "earth_reworked_gate_$index"
    }

    private enum class SwordMode { PLAYER, BOSS, ROAM }

    private data class SwordEnergy(
        val pair: Int,
        val yin: Boolean,
        var location: Location,
        var mode: SwordMode,
        var targetId: UUID? = null,
        var roamTarget: Location? = null
    )

    private val gates = listOf(3140 to -1823, 3162 to -1841, 3141 to -1861)
        .mapIndexed { index, (x, z) -> EarthGate(index, Location(boss.world, x + .5, 129.0, z + .5)) }
    private val baseDamage = boss.persistentDataContainer
        .get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 30.0
    private val baseMovementSpeed = .20
    private val passiveSouls = LinkedHashSet<UUID>()
    private val passiveSoulAnchors = HashMap<UUID, Location>()
    private var permanentBonus = 0.0
    private var empowered = false
    private var nextPassiveAt = -1
    private var passivePreparing = false
    private var passiveWaveEndsAt = -1
    private var passiveWaveBar: org.bukkit.boss.BossBar? = null
    private var effectSerial = 0
    private var projectionCloneId: UUID? = null
    private var projectionActive = false
    private var projectionEndsAt = -1
    private var projectionNextAttackAt = -1
    private var projectionBar: org.bukkit.boss.BossBar? = null

    override fun onAppearance() {
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed
        gates.forEach { gate ->
            buildAnimated(gate.group, gateChanges(gate), batchSize = 32, periodTicks = 1L)
            playEffect(ShengShanEffect.GATE_PORTAL, gate.center.clone().add(0.0, 2.6, 0.0),
                options = ShengShanEffectOptions(radius = 3.8, height = 5.0, color = 0x553568,
                    durationTicks = 3600, intervalTicks = 6, key = gate.effectKey))
        }
        super.onAppearance()
    }

    private fun gateChanges(gate: EarthGate): Map<Block, org.bukkit.block.data.BlockData> {
        val changes = LinkedHashMap<Block, org.bukkit.block.data.BlockData>()
        for (dy in 0..5) for (offset in -2..2) {
            if (offset == -2 || offset == 2 || dy == 0 || dy == 5) {
                val block = if (gate.index == 1) {
                    boss.world.getBlockAt(gate.center.blockX, gate.center.blockY + dy, gate.center.blockZ + offset)
                } else {
                    boss.world.getBlockAt(gate.center.blockX + offset, gate.center.blockY + dy, gate.center.blockZ)
                }
                changes[block] = (if (dy == 5 && offset == 0) Material.CRYING_OBSIDIAN else Material.OBSIDIAN)
                    .createBlockData()
            }
        }
        return changes
    }

    override fun onTick() {
        updateProjectionClone()
        updatePassiveWave()
        if (invulnerable || ultimateActive || passivePreparing || passiveWaveEndsAt >= 0) return
        if (nextPassiveAt < 0) {
            nextPassiveAt = elapsedTicks + 300
        } else if (elapsedTicks >= nextPassiveAt && !normalSkillRunning) {
            beginPassiveWave()
        }
    }

    private fun beginPassiveWave() {
        passivePreparing = true
        normalSkillRunning = true
        boss.setAI(false); boss.velocity = Vector()
        message("§6垚正在呼唤三座鬼门，亡魂将在片刻后破门而出！")
        effectSerial++
        playEffect(ShengShanEffect.SOUL_FIELD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 2.8, height = 2.8, color = 0x6A477B,
                durationTicks = 10, intervalTicks = 2, key = "earth_passive_charge_$effectSerial"))
        soundNearby(boss.location, Sound.PARTICLE_SOUL_ESCAPE, 36.0, 1.05f, .62f)
        later(10L) {
            passivePreparing = false
            normalSkillRunning = false
            boss.setAI(true)
            spawnGateSouls()
        }
    }

    private fun spawnGateSouls() {
        passiveSouls.clear()
        passiveSoulAnchors.clear()
        val participantCount = players().size
        val soulCount = when (participantCount) {
            1 -> 1
            in 2..3 -> 2
            else -> 3
        }
        gates.shuffled().take(soulCount).forEach { gate ->
            val spawnAt = gate.center.clone().add(0.0, 1.0, 0.0)
            playEffect(ShengShanEffect.SOUL_SILHOUETTE, spawnAt,
                options = ShengShanEffectOptions(radius = 1.6, height = 2.8, color = 0x76518A))
            playEffect(ShengShanEffect.SOUL_LINK, gate.center.clone().add(0.0, 2.5, 0.0), spawnAt,
                ShengShanEffectOptions(radius = .22, height = .22, color = 0x553568))
            soundNearby(gate.center, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 34.0, .8f, .68f)
            val soul = spawn("shengshan_soul", spawnAt, "earth_gate_passive_soul") ?: return@forEach
            soul.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 250.0
            soul.health = 250.0
            soul.isInvulnerable = false
            soul.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, 0.0)
            soul.setGravity(false); soul.setAI(false); soul.velocity = Vector()
            soul.isCollidable = true
            (soul as? Mob)?.target = null
            passiveSouls += soul.uniqueId
            passiveSoulAnchors[soul.uniqueId] = spawnAt.clone()
        }
        val duration = if (empowered) 240 else 300
        passiveWaveEndsAt = elapsedTicks + duration
        passiveWaveBar = phaseBar("§8鬼门亡魂", duration, BarColor.PURPLE)
        message(if (empowered) "§6鬼门中出现了§e${passiveSouls.size}只亡魂§6！必须在§c十二秒内§6将它们全部击杀！"
            else "§6鬼门中出现了§e${passiveSouls.size}只亡魂§6！必须在§c十五秒内§6将它们全部击杀！")
    }

    private fun updatePassiveWave() {
        if (passiveWaveEndsAt < 0) return
        passiveSouls.removeIf { id ->
            val soul = Bukkit.getEntity(id) as? LivingEntity
            val removed = soul == null || !soul.isValid || soul.isDead
            if (removed) {
                passiveSoulAnchors.remove(id)
            } else {
                soul.setAI(false)
                soul.setGravity(false)
                soul.velocity = Vector()
                (soul as? Mob)?.target = null
                passiveSoulAnchors[id]?.let { anchor ->
                    if (soul.location.distanceSquared(anchor) > .0025) soul.teleport(anchor)
                }
            }
            removed
        }
        if (passiveSouls.isEmpty()) {
            resolvePassiveWave(0)
            return
        }
        if (elapsedTicks >= passiveWaveEndsAt) resolvePassiveWave(passiveSouls.size)
    }

    private fun resolvePassiveWave(survivors: Int) {
        clearPassiveWaveBar()
        if (survivors > 0) {
            passiveSouls.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }.forEach { soul ->
                playEffect(ShengShanEffect.SOUL_LINK, soul.location.clone().add(0.0, 1.0, 0.0),
                    boss.location.clone().add(0.0, boss.height * .5, 0.0),
                    ShengShanEffectOptions(radius = .18, height = .18, color = 0x6E3D82))
                soul.remove()
            }
            addPermanentBonus(survivors * .05)
            playEffect(ShengShanEffect.SOUL_FIELD, boss.location.clone().add(0.0, 1.2, 0.0),
                options = ShengShanEffectOptions(radius = 3.2, height = 3.0, color = 0x6E3D82))
            soundNearby(boss.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 38.0, 1.1f, .55f)
            message("§c未被斩灭的亡魂被垚吞入体内，它的攻势与步伐获得了永久强化！")
        } else {
            message("§a本轮鬼门亡魂已被尽数斩灭，垚未能夺取它们的力量！")
        }
        passiveSouls.clear()
        passiveSoulAnchors.clear()
        passiveWaveEndsAt = -1
        nextPassiveAt = elapsedTicks + 300
    }

    private fun clearPassiveWaveBar() {
        passiveWaveBar?.let(::removeBar)
        passiveWaveBar = null
    }

    override fun showGenericNormalParticle(index: Int): Boolean = false

    override fun onNormalWarningStarted(index: Int) {
        effectSerial++
        if (index == 0) {
            playEffect(ShengShanEffect.YIN_YANG_SLASH,
                boss.location.clone().add(2.2, boss.height * .55, 0.0),
                boss.location.clone().add(-2.2, boss.height * .55, 0.0),
                ShengShanEffectOptions(radius = .28, height = 1.8, color = 0x17141C,
                    durationTicks = 60, intervalTicks = 2, key = "earth_slash_yin_$effectSerial"))
            playEffect(ShengShanEffect.YIN_YANG_SLASH,
                boss.location.clone().add(0.0, boss.height * .55, 2.2),
                boss.location.clone().add(0.0, boss.height * .55, -2.2),
                ShengShanEffectOptions(radius = .28, height = 1.8, color = 0xF7F1E5,
                    durationTicks = 60, intervalTicks = 2, key = "earth_slash_yang_$effectSerial"))
        } else {
            playEffect(ShengShanEffect.SOUL_FIELD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
                options = ShengShanEffectOptions(radius = 3.0, height = 3.2, color = 0x644273,
                    durationTicks = 60, intervalTicks = 2, key = "earth_projection_warning_$effectSerial"))
        }
    }

    override fun castNormalSkill(index: Int) {
        if (index == 0) yinYangDoubleSlash() else soulProjection()
    }

    private fun yinYangDoubleSlash() {
        val participants = players()
        if (participants.isEmpty()) return finishNormalSkill()
        val pairCount = if (empowered) 3 else 1
        val energies = ArrayList<SwordEnergy>(pairCount * 2)
        var bossAnchorUsed = false
        repeat(pairCount) { pair ->
            val selected = participants.shuffled().take(2)
            repeat(2) { side ->
                val angle = (pair * 2 + side) * PI * 2.0 / (pairCount * 2.0)
                val location = boss.location.clone().add(cos(angle) * 2.3, 1.2, sin(angle) * 2.3)
                val player = selected.getOrNull(side)
                val mode = when {
                    player != null -> SwordMode.PLAYER
                    !bossAnchorUsed -> SwordMode.BOSS.also { bossAnchorUsed = true }
                    else -> SwordMode.ROAM
                }
                energies += SwordEnergy(pair, side == 0, location, mode, player?.uniqueId,
                    if (mode == SwordMode.ROAM) randomEarthRoamPoint() else null)
            }
        }
        soundNearby(boss.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 38.0, 1.0f, .48f)
        var ticks = 0
        phaseBar("§8阴阳剑气追踪", 100, BarColor.PURPLE)
        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = every(0L, 1L) {
            ticks++
            updateSwordEnergies(energies)
            if (ticks % 40 == 0) {
                energies.forEach { energy ->
                    players().filter { it.location.distanceSquared(energy.location) <= 9.0 }
                        .forEach { dealDamage(it, 20.0) }
                }
                soundNearby(boss.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 34.0, .75f, .72f)
            }
            if (ticks >= 100) {
                task.cancel()
                resolveSwordExplosions(energies)
            }
        }
    }

    private fun updateSwordEnergies(energies: MutableList<SwordEnergy>) {
        energies.forEach { energy ->
            if (energy.mode == SwordMode.PLAYER) {
                val target = energy.targetId?.let(Bukkit::getPlayer)
                if (target == null || target !in players()) reassignSwordEnergy(energy, energies)
            }
            val destination = when (energy.mode) {
                SwordMode.PLAYER -> energy.targetId?.let(Bukkit::getPlayer)?.location?.clone()?.add(0.0, 1.1, 0.0)
                SwordMode.BOSS -> boss.location.clone().add(0.0, boss.height * .55, 0.0)
                SwordMode.ROAM -> energy.roamTarget
            }
            if (destination != null && energy.mode != SwordMode.BOSS) {
                val delta = destination.toVector().subtract(energy.location.toVector())
                if (delta.lengthSquared() <= .36 && energy.mode == SwordMode.ROAM) {
                    energy.roamTarget = randomEarthRoamPoint()
                } else if (delta.lengthSquared() > 1.0E-5) {
                    energy.location.add(delta.normalize().multiply(.3))
                }
            } else if (destination != null) {
                energy.location = destination
            }
            if (elapsedTicks % 2 == 0) {
                playEffect(ShengShanEffect.YIN_YANG_SLASH, energy.location.clone().subtract(0.0, .8, 0.0),
                    energy.location.clone().add(0.0, .8, 0.0),
                    ShengShanEffectOptions(radius = .24, height = 1.5,
                        color = if (energy.yin) 0x17141C else 0xF7F1E5))
            }
        }
    }

    private fun reassignSwordEnergy(energy: SwordEnergy, energies: List<SwordEnergy>) {
        val participants = players()
        val otherTarget = energies.firstOrNull { it.pair == energy.pair && it !== energy && it.mode == SwordMode.PLAYER }
            ?.targetId
        val candidate = participants.firstOrNull { it.uniqueId != otherTarget }
        when {
            candidate != null -> {
                energy.mode = SwordMode.PLAYER
                energy.targetId = candidate.uniqueId
            }
            energies.none { it !== energy && it.mode == SwordMode.BOSS } -> {
                energy.mode = SwordMode.BOSS
                energy.targetId = null
            }
            else -> {
                energy.mode = SwordMode.ROAM
                energy.targetId = null
                energy.roamTarget = randomEarthRoamPoint()
            }
        }
    }

    private fun randomEarthRoamPoint(): Location {
        val random = ThreadLocalRandom.current()
        return Location(boss.world, random.nextDouble(3120.0, 3179.0), 130.1,
            random.nextDouble(-1869.0, -1809.0))
    }

    private fun resolveSwordExplosions(energies: List<SwordEnergy>) {
        val triggerDistance = if (empowered) 6.0 else 10.0
        val explosionRadius = if (empowered) 6.0 else 20.0
        val centers = energies.groupBy { it.pair }.values.mapNotNull { pair ->
            if (pair.size != 2 || horizontalDistanceSquared(pair[0].location, pair[1].location) > triggerDistance * triggerDistance) {
                null
            } else {
                Location(boss.world, (pair[0].location.x + pair[1].location.x) * .5, 129.15,
                    (pair[0].location.z + pair[1].location.z) * .5)
            }
        }
        if (centers.isEmpty()) return finishNormalSkill()
        effectSerial++
        centers.forEachIndexed { index, center ->
            playEffect(ShengShanEffect.EARTH_PULSE, center,
                options = ShengShanEffectOptions(radius = explosionRadius, height = .2, color = 0xF04452,
                    durationTicks = 20, intervalTicks = 2, key = "earth_sword_blast_${effectSerial}_$index"))
            soundNearby(center, Sound.BLOCK_BEACON_POWER_SELECT, 48.0, 1.0f, .58f)
        }
        message("§6阴阳剑气将在一秒后汇合爆裂，立即离开红色预警区域！")
        later(20L) {
            centers.indices.forEach { stopEffect("earth_sword_blast_${effectSerial}_$it") }
            centers.forEach { center ->
                playEffect(ShengShanEffect.EARTH_PULSE, center,
                    options = ShengShanEffectOptions(radius = explosionRadius, height = 1.2, color = 0xB766D0))
                playEffect(ShengShanEffect.SOUL_FIELD, center.clone().add(0.0, 1.2, 0.0),
                    options = ShengShanEffectOptions(radius = explosionRadius, height = 3.0, color = 0x5D376D))
                playEffect(ShengShanEffect.SOUL_SILHOUETTE, center.clone().add(0.0, 1.0, 0.0),
                    options = ShengShanEffectOptions(radius = explosionRadius * .65, height = 3.5, color = 0xDABCE5))
                soundNearby(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 52.0, 1.25f, .48f)
                soundNearby(center, Sound.ENTITY_GENERIC_EXPLODE, 52.0, 1.1f, .62f)
            }
            // 多对剑气同时汇合时，同一玩家只承受一次爆炸伤害。
            players().filter { player ->
                centers.any { horizontalDistanceSquared(player.location, it) <= explosionRadius * explosionRadius }
            }.forEach { dealDamage(it, 80.0) }
            finishNormalSkill()
        }
    }

    private fun soulProjection() {
        boss.setAI(false); boss.velocity = Vector()
        boss.isInvulnerable = true
        invulnerable = true
        val clone = spawn("shengshan_earth_projection", boss.location, "earth_soul_projection")
        if (clone == null) {
            restoreBossAfterProjection()
            return finishNormalSkill()
        }
        clone.setAI(false)
        clone.setGravity(true)
        clone.isPersistent = false
        clone.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = currentBossMovementSpeed()
        clone.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE,
            if (empowered) 50.0 else 25.0)
        clone.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, 0.0)
        clone.equipment?.helmet = boss.equipment?.helmet?.clone()
        clone.equipment?.chestplate = boss.equipment?.chestplate?.clone()
        clone.equipment?.leggings = boss.equipment?.leggings?.clone()
        clone.equipment?.boots = boss.equipment?.boots?.clone()
        clone.equipment?.setItemInMainHand(boss.equipment?.itemInMainHand?.clone() ?: ItemStack(Material.GOLDEN_SWORD))
        projectionCloneId = clone.uniqueId
        projectionActive = true
        projectionEndsAt = elapsedTicks + 300
        projectionNextAttackAt = elapsedTicks + 20
        playEffect(ShengShanEffect.SOUL_LINK, boss.location.clone().add(0.0, 1.2, 0.0),
            clone.location.clone().add(0.0, 1.2, 0.0), ShengShanEffectOptions(radius = .3, height = .3))
        playEffect(ShengShanEffect.SOUL_FIELD, clone.location.clone().add(0.0, 1.0, 0.0),
            options = ShengShanEffectOptions(radius = 2.6, height = 2.8, color = 0x6A477B))
        soundNearby(boss.location, Sound.ENTITY_WITHER_SPAWN, 42.0, .9f, .72f)
        val cloneBar = phaseBar("§8鬼魂出窍", 300, BarColor.PURPLE)
        projectionBar = cloneBar
    }

    private fun updateProjectionClone() {
        if (!projectionActive) return
        val clone = projectionCloneId?.let(Bukkit::getEntity) as? LivingEntity
        if (clone == null || !clone.isValid || clone.isDead) {
            projectionActive = false
            projectionCloneId = null
            clearProjectionBar()
            restoreBossAfterProjection()
            finishNormalSkill()
            return
        }
        val target = players().minByOrNull { horizontalDistanceSquared(it.location, clone.location) }
        if (target != null) {
            val distanceSquared = horizontalDistanceSquared(target.location, clone.location)
            if (distanceSquared > 12.25) moveProjectionClone(clone, target.location)
            if (distanceSquared <= 12.25 && elapsedTicks >= projectionNextAttackAt) {
                projectionNextAttackAt = elapsedTicks + 20
                dealNormalAttackDamage(target, baseDamage * combatMultiplier(), source = clone)
                playEffect(ShengShanEffect.SOUL_FIELD, target.location.clone().add(0.0, .8, 0.0),
                    options = ShengShanEffectOptions(radius = 1.4, height = 1.7, color = 0x5F3D70))
                soundNearby(target.location, Sound.ENTITY_WITHER_SKELETON_HURT, 24.0, .75f, .7f)
            }
        }
        if (elapsedTicks % 5 == 0) {
            playEffect(ShengShanEffect.SOUL_LINK, clone.location.clone().add(0.0, 1.0, 0.0),
                boss.location.clone().add(0.0, 1.0, 0.0),
                ShengShanEffectOptions(radius = .11, height = .11, color = 0x5F3D70))
        }
        if (elapsedTicks >= projectionEndsAt) resolveProjectionFailure(clone)
    }

    private fun moveProjectionClone(clone: LivingEntity, target: Location) {
        val direction = horizontalDirection(clone.location, target)
        val speed = currentBossMovementSpeed()
        clone.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = speed
        val proposed = clone.location.clone().add(direction.clone().multiply(speed))
        val grounded = manager.groundLocation(clone.world, proposed.x, proposed.z, 128)
        grounded.direction = direction
        clone.teleport(grounded)
        clone.velocity = Vector()
    }

    private fun resolveProjectionFailure(clone: LivingEntity) {
        if (!projectionActive) return
        val start = boss.location.clone()
        val destination = clone.location.clone()
        projectionActive = false
        projectionCloneId = null
        clone.remove()
        clearProjectionBar()
        playEffect(ShengShanEffect.SOUL_LINK, start.clone().add(0.0, 1.0, 0.0),
            destination.clone().add(0.0, 1.0, 0.0),
            ShengShanEffectOptions(radius = .45, height = .45, color = 0x6F3E82))
        players().filter { distanceToSegmentSquared(it.location, start, destination) <= 9.0 }
            .forEach { dealDamage(it, 80.0) }
        val landing = manager.groundLocation(boss.world, destination.x, destination.z, 128)
        boss.teleport(landing); boss.velocity = Vector()
        playEffect(ShengShanEffect.SOUL_FIELD, landing.clone().add(0.0, 1.0, 0.0),
            options = ShengShanEffectOptions(radius = 4.0, height = 3.2, color = 0x6F3E82))
        soundNearby(landing, Sound.ENTITY_ENDERMAN_TELEPORT, 50.0, 1.2f, .55f)
        addPermanentBonus(.10)
        message("§c出窍魂体成功归位，垚从阴世夺取了更强的攻势与步伐！")
        restoreBossAfterProjection()
        finishNormalSkill()
    }

    override fun onEntityDeath(entity: LivingEntity) {
        if (projectionActive && entity.uniqueId == projectionCloneId) {
            projectionActive = false
            projectionCloneId = null
            clearProjectionBar()
            playEffect(ShengShanEffect.BREAK_SUCCESS, entity.location.clone().add(0.0, 1.0, 0.0),
                options = ShengShanEffectOptions(radius = 3.5, height = 3.0, color = 0xE9D8F2))
            soundNearby(entity.location, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 44.0, 1.15f, 1.1f)
            boss.health = (boss.health - 600.0).coerceAtLeast(0.0)
            message("§a出窍魂体已被击破，垚的本体遭到了灵魂反噬！")
            if (boss.isValid && !boss.isDead) {
                restoreBossAfterProjection()
                finishNormalSkill()
            }
        }
        super.onEntityDeath(entity)
    }

    private fun restoreBossAfterProjection() {
        boss.isInvulnerable = false
        boss.isCollidable = true
        boss.setGravity(true)
        boss.setAI(true)
        boss.velocity = Vector()
        invulnerable = false
    }

    private fun currentBossMovementSpeed(): Double =
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: baseMovementSpeed * combatMultiplier()

    private fun clearProjectionBar() {
        projectionBar?.let(::removeBar)
        projectionBar = null
    }

    private fun addPermanentBonus(amount: Double) {
        permanentBonus += amount
        applyCombatAttributes()
    }

    private fun combatMultiplier(): Double = 1.0 + permanentBonus + if (empowered) .20 else 0.0

    private fun applyCombatAttributes() {
        val multiplier = combatMultiplier()
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, baseDamage * multiplier)
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed * multiplier
    }

    override fun onUltimateWarningStarted() {
        if (passiveWaveEndsAt >= 0) {
            passiveSouls.mapNotNull { Bukkit.getEntity(it) }.forEach(Entity::remove)
            passiveSouls.clear()
            passiveSoulAnchors.clear()
            passiveWaveEndsAt = -1
            clearPassiveWaveBar()
            nextPassiveAt = elapsedTicks + 300
        }
    }

    override fun startUltimate() {
        invulnerable = true
        boss.isInvulnerable = true
        boss.setAI(false); boss.setGravity(false); boss.isCollidable = false; boss.velocity = Vector()
        val destination = Trigram.EARTH.spawn(boss.world)
        var ticks = 0
        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = every(0L, 1L) {
            ticks++
            val delta = destination.toVector().subtract(boss.location.toVector())
            if (delta.lengthSquared() <= .25 || ticks >= 120) {
                task.cancel()
                boss.teleport(destination); boss.velocity = Vector()
                beginEarthEmpowerment()
            } else {
                val direction = delta.normalize()
                boss.teleport(boss.location.clone().add(direction.clone().multiply(.45)).apply { this.direction = direction })
                if (ticks % 3 == 0) playEffect(ShengShanEffect.SOUL_LINK,
                    boss.location.clone().add(0.0, boss.height * .5, 0.0), destination,
                    ShengShanEffectOptions(radius = .14, height = .14, color = 0x664177))
            }
        }
    }

    private fun beginEarthEmpowerment() {
        effectSerial++
        elder("earth_ghost_gate_empower",
            "当心！它正在借三座鬼门的阴气重塑魂躯，步伐与攻势都在不断增强！\n§e此后鬼门收魂更快，阴阳剑气数量激增，出窍魂体也会变得更加坚固，但仍与本体保持相同步伐！",
            firstOnly = false)
        playEffect(ShengShanEffect.SOUL_FIELD, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = 5.5, height = 4.5, color = 0x7A4C8E,
                durationTicks = 100, intervalTicks = 2, key = "earth_empower_field_$effectSerial"))
        playEffect(ShengShanEffect.SOUL_SILHOUETTE, boss.location.clone().add(0.0, 1.2, 0.0),
            options = ShengShanEffectOptions(radius = 4.0, height = 3.6, color = 0xB088C1,
                durationTicks = 100, intervalTicks = 2, key = "earth_empower_souls_$effectSerial"))
        soundNearby(boss.location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 52.0, 1.2f, .52f)
        later(100L) {
            empowered = true
            applyCombatAttributes()
            finishUltimateTransformation()
            boss.isInvulnerable = false
            boss.setGravity(true); boss.isCollidable = true; boss.setAI(true)
            message("§6鬼门阴气已融入垚的魂躯，它的攻势、步伐与唤魂之术都变得更加危险！")
        }
    }

    override fun shutdown(restoreTerrain: Boolean) {
        passiveSouls.mapNotNull { Bukkit.getEntity(it) }.forEach(Entity::remove)
        passiveSouls.clear()
        passiveSoulAnchors.clear()
        clearPassiveWaveBar()
        projectionCloneId?.let(Bukkit::getEntity)?.remove()
        projectionCloneId = null
        projectionActive = false
        clearProjectionBar()
        gates.forEach { stopEffect(it.effectKey) }
        boss.isInvulnerable = false
        boss.setGravity(true); boss.isCollidable = true
        boss.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, baseDamage)
        boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = baseMovementSpeed
        super.shutdown(restoreTerrain)
    }
}
