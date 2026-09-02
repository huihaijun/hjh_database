package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.combat.MonsterDamageClassification
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil

class Xuanshuihun(
    private val plugin: Hjh_database,
    private val boss: LivingEntity
) : Listener {
    private enum class State {
        IDLE,
        CHANNELING,
        DASHING,
        CARRYING,
        EXECUTING,
        DEAD
    }

    private var state = State.IDLE
    private var dashAttempts = 0
    private var capturedPlayerId: UUID? = null
    private var executingPlayerId: UUID? = null
    private var jumpPresses = 0
    private var jumpWasPressed = false
    private var lastJumpAt = 0L

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        startIdleHeightLimiter()
        startFasterMeleeAttacks()
        scheduleNextSkill(INITIAL_DELAY_TICKS)

        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid()) {
                    cleanup()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        boss.getAttribute(Attribute.SCALE)?.baseValue = 2.0
        boss.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = 32.0
    }

    private fun startFasterMeleeAttacks() {
        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid()) {
                    cancel()
                    return
                }
                if (state != State.IDLE) return

                val target = ((boss as? Mob)?.target as? Player)
                    ?.takeIf(::isValidTarget)
                    ?.takeIf { it.location.distanceSquared(boss.location) <= BONUS_MELEE_RANGE_SQUARED }
                    ?: return

                target.noDamageTicks = 0
                // 插件补足攻击频率，但语义仍是玄水魂的普通近战攻击，允许盾牌抵挡。
                MonsterDamageClassification.withNormalAttack(plugin, target) {
                    target.damage(1.0, boss)
                }
                boss.swingMainHand()
            }
        }.runTaskTimer(plugin, BONUS_MELEE_INTERVAL_TICKS, BONUS_MELEE_INTERVAL_TICKS)
    }

    private fun startIdleHeightLimiter() {
        val spawnY = boss.location.y
        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid()) {
                    cancel()
                    return
                }
                if (state != State.IDLE) return

                val referenceY = nearestPlayer(HEIGHT_REFERENCE_RANGE)?.location?.y ?: spawnY
                val maxY = referenceY + MAX_IDLE_HEIGHT_ABOVE_PLAYER
                if (boss.location.y <= maxY) return

                if (boss.location.y > maxY + HEIGHT_TELEPORT_THRESHOLD) {
                    boss.teleport(boss.location.clone().apply { y = maxY })
                } else {
                    val velocity = boss.velocity
                    boss.velocity = Vector(velocity.x, -HEIGHT_PULL_SPEED, velocity.z)
                }
            }
        }.runTaskTimer(plugin, HEIGHT_CHECK_INTERVAL_TICKS, HEIGHT_CHECK_INTERVAL_TICKS)
    }

    private fun scheduleNextSkill(delayTicks: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid() || state != State.IDLE) return
                startChanneling()
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun startChanneling() {
        if (nearestPlayer(SKILL_RANGE) == null) {
            scheduleNextSkill(RETRY_DELAY_TICKS)
            return
        }

        state = State.CHANNELING
        setBossAi(false)
        nearbyPlayers(SKILL_RANGE).forEach { player ->
            player.sendMessage("§c玄水魂即将向你冲撞，被冲撞后会被他抓到天上，注意躲避！")
            player.playSound(player.location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.75f, 1.25f)
        }

        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!isBossValid() || state != State.CHANNELING) {
                    cancel()
                    return
                }
                if (ticks >= CHANNEL_TICKS) {
                    dashAttempts = 0
                    beginDash()
                    cancel()
                    return
                }

                boss.velocity = Vector(0.0, 0.0, 0.0)
                spawnChannelParticles()
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun beginDash() {
        val target = nearestPlayer(DASH_TARGET_RANGE)
        if (target == null) {
            finishSkillCycle()
            return
        }

        dashAttempts++
        state = State.DASHING
        setBossAi(false)

        val start = boss.location.clone()
        val targetPoint = target.location.clone().add(0.0, 1.0, 0.0)
        val offset = targetPoint.toVector().subtract(start.toVector())
        if (offset.lengthSquared() < 0.01) {
            capture(target)
            return
        }

        val direction = offset.normalize()
        val maxSteps = ceil(DASH_DISTANCE / DASH_STEP).toInt().coerceAtLeast(1)
        boss.world.playSound(boss.location, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 0.7f)

        object : BukkitRunnable() {
            private var steps = 0
            private val wettedPlayers = mutableSetOf<UUID>()

            override fun run() {
                if (!isBossValid() || state != State.DASHING) {
                    cancel()
                    return
                }

                if (steps >= maxSteps) {
                    onDashMiss()
                    cancel()
                    return
                }

                val next = boss.location.clone().add(direction.clone().multiply(DASH_STEP))
                boss.teleport(next)
                boss.velocity = direction.clone().multiply(0.35)
                spawnDashParticles(next, direction)
                nearbyPlayersAt(next, DASH_WETNESS_RADIUS).forEach { player ->
                    if (wettedPlayers.add(player.uniqueId)) {
                        NorthWetnessSkill.addWetness(player, DASH_WETNESS_AMOUNT, bypassRateLimit = true)
                    }
                }

                val hit = nearbyPlayersAt(next, DASH_HIT_RADIUS).firstOrNull()
                if (hit != null) {
                    dealPhysicalDamage(hit, DASH_DAMAGE)
                    if (isValidTarget(hit)) {
                        capture(hit)
                    } else {
                        finishSkillCycle()
                    }
                    cancel()
                    return
                }
                steps++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun onDashMiss() {
        boss.velocity = Vector(0.0, 0.0, 0.0)
        if (dashAttempts >= MAX_DASH_ATTEMPTS) {
            finishSkillCycle()
            return
        }

        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid() || state != State.DASHING) return
                beginDash()
            }
        }.runTaskLater(plugin, DASH_RETRY_DELAY_TICKS)
    }

    private fun capture(player: Player) {
        state = State.CARRYING
        capturedPlayerId = player.uniqueId
        jumpPresses = 0
        jumpWasPressed = false
        lastJumpAt = 0L
        setBossAi(false)
        boss.velocity = Vector(0.0, 0.0, 0.0)
        player.fallDistance = 0f
        player.sendMessage("§c玄水魂把你带到了天上，快点使用空格键敲击躲避！")
        player.playSound(player.location, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.65f)

        val startY = boss.location.y
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!isBossValid() || state != State.CARRYING) {
                    cancel()
                    return
                }

                val captured = capturedPlayer()
                if (captured == null || !isValidTarget(captured)) {
                    releaseCapture()
                    finishSkillCycle()
                    cancel()
                    return
                }

                val progress = (ticks.toDouble() / ASCENT_TICKS).coerceAtMost(1.0)
                val nextBossLocation = boss.location.clone()
                nextBossLocation.y = startY + CAPTURE_HEIGHT * progress
                boss.teleport(nextBossLocation)
                boss.velocity = Vector(0.0, 0.0, 0.0)

                captured.teleport(boss.location.clone().add(0.0, -1.1, 0.0))
                captured.velocity = Vector(0.0, 0.0, 0.0)
                captured.fallDistance = 0f
                spawnCaptureParticles(captured.location)
                if (ticks > 0 && ticks % CAPTURE_WETNESS_INTERVAL_TICKS == 0) {
                    NorthWetnessSkill.addWetness(
                        captured,
                        CAPTURE_WETNESS_AMOUNT,
                        bypassRateLimit = true
                    )
                }

                if (ticks >= ESCAPE_WINDOW_TICKS) {
                    executeCapturedPlayer(captured)
                    cancel()
                    return
                }
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerInput(event: PlayerInputEvent) {
        if (state != State.CARRYING || event.player.uniqueId != capturedPlayerId) return

        val jumping = event.input.isJump
        if (jumping && !jumpWasPressed) {
            val now = System.currentTimeMillis()
            if (now - lastJumpAt >= MIN_JUMP_INTERVAL_MILLIS) {
                lastJumpAt = now
                jumpPresses++
                event.player.sendActionBar("§b挣脱进度：§f$jumpPresses§7/$REQUIRED_JUMP_PRESSES")
                event.player.playSound(event.player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.45f, 1.5f)
                if (jumpPresses >= REQUIRED_JUMP_PRESSES) {
                    val player = event.player
                    releaseCapture()
                    player.sendMessage("§a你挣脱了玄水魂的束缚！")
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f)
                    finishSkillCycle()
                }
            }
        }
        jumpWasPressed = jumping
    }

    private fun executeCapturedPlayer(player: Player) {
        capturedPlayerId = null
        executingPlayerId = player.uniqueId
        state = State.EXECUTING
        player.fallDistance = 0f
        player.velocity = Vector(0.0, -1.8, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 0.7f)
        player.world.spawnParticle(Particle.SPLASH, player.location, 45, 0.8, 0.8, 0.8, 0.18)

        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!isBossValid()) {
                    executingPlayerId = null
                    cancel()
                    return
                }
                if (!player.isOnline || player.isDead || player.uniqueId != executingPlayerId) {
                    executingPlayerId = null
                    finishSkillCycle()
                    cancel()
                    return
                }

                player.fallDistance = 0f
                spawnExecutionParticles(player.location)
                if (player.isOnGround || ticks >= EXECUTION_TIMEOUT_TICKS) {
                    player.noDamageTicks = 0
                    dealPhysicalDamage(player, EXECUTION_DAMAGE)
                    player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, STUN_TICKS, 255, false, true))
                    player.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, STUN_TICKS, 255, false, true))
                    player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.65f)
                    player.world.spawnParticle(Particle.EXPLOSION, player.location, 2, 0.25, 0.15, 0.25, 0.0)
                    executingPlayerId = null
                    finishSkillCycle()
                    cancel()
                    return
                }
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBossDamage(event: EntityDamageEvent) {
        if (event.entity == boss && state == State.DASHING) {
            event.damage *= DASH_DAMAGE_TAKEN_MULTIPLIER
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onControlledPlayerFall(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        if (player.uniqueId == capturedPlayerId || player.uniqueId == executingPlayerId) {
            event.isCancelled = true
            player.fallDistance = 0f
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        handleUnavailablePlayer(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        handleUnavailablePlayer(event.entity)
    }

    private fun handleUnavailablePlayer(player: Player) {
        if (player.uniqueId != capturedPlayerId && player.uniqueId != executingPlayerId) return
        capturedPlayerId = null
        executingPlayerId = null
        finishSkillCycle()
    }

    private fun dealPhysicalDamage(player: Player, amount: Double) {
        player.noDamageTicks = 0
        player.setMetadata(PHYSICAL_SKILL_METADATA, FixedMetadataValue(plugin, true))
        try {
            player.damage(amount, boss)
        } finally {
            player.removeMetadata(PHYSICAL_SKILL_METADATA, plugin)
        }
    }

    private fun finishSkillCycle() {
        if (state == State.IDLE || state == State.DEAD) return
        releaseCapture()
        executingPlayerId = null
        state = State.IDLE
        setBossAi(true)
        boss.velocity = Vector(0.0, 0.0, 0.0)
        scheduleNextSkill(SKILL_COOLDOWN_TICKS)
    }

    private fun releaseCapture() {
        capturedPlayer()?.fallDistance = 0f
        capturedPlayerId = null
        jumpPresses = 0
        jumpWasPressed = false
    }

    private fun capturedPlayer(): Player? {
        return capturedPlayerId?.let(plugin.server::getPlayer)
    }

    private fun setBossAi(enabled: Boolean) {
        (boss as? Mob)?.setAI(enabled)
    }

    private fun nearestPlayer(radius: Double): Player? {
        return nearbyPlayers(radius).minByOrNull { it.location.distanceSquared(boss.location) }
    }

    private fun nearbyPlayers(radius: Double): List<Player> {
        val radiusSquared = radius * radius
        return boss.world.players.filter {
            isValidTarget(it) && it.location.distanceSquared(boss.location) <= radiusSquared
        }
    }

    private fun nearbyPlayersAt(location: Location, radius: Double): List<Player> {
        val radiusSquared = radius * radius
        return boss.world.players.filter {
            isValidTarget(it) && it.location.distanceSquared(location) <= radiusSquared
        }
    }

    private fun isValidTarget(player: Player): Boolean {
        return player.isOnline &&
            !player.isDead &&
            player.gameMode != GameMode.CREATIVE &&
            player.gameMode != GameMode.SPECTATOR
    }

    private fun isBossValid(): Boolean = boss.isValid && !boss.isDead

    private fun cleanup() {
        if (state == State.DEAD) return
        releaseCapture()
        executingPlayerId = null
        state = State.DEAD
        HandlerList.unregisterAll(this)
    }

    private fun spawnChannelParticles() {
        val center = boss.location.clone().add(0.0, 0.75, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(24, 103, 210), 1.2f)
        boss.world.spawnParticle(Particle.DUST, center, 12, 0.85, 0.7, 0.85, 0.0, dust)
        boss.world.spawnParticle(Particle.NAUTILUS, center, 5, 0.7, 0.6, 0.7, 0.02)
        if (boss.ticksLived % 10 == 0) {
            boss.world.playSound(boss.location, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 0.7f, 0.65f)
        }
    }

    private fun spawnDashParticles(location: Location, direction: Vector) {
        val trail = location.clone().subtract(direction.clone().multiply(0.7))
        val dust = Particle.DustOptions(Color.fromRGB(35, 145, 245), 1.05f)
        boss.world.spawnParticle(Particle.SPLASH, trail, 16, 0.35, 0.35, 0.35, 0.08)
        boss.world.spawnParticle(Particle.BUBBLE_POP, trail, 9, 0.3, 0.3, 0.3, 0.04)
        boss.world.spawnParticle(Particle.DUST, trail, 7, 0.3, 0.3, 0.3, 0.0, dust)
    }

    private fun spawnCaptureParticles(location: Location) {
        val center = location.clone().add(0.0, 0.9, 0.0)
        boss.world.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, 8, 0.45, 0.7, 0.45, 0.05)
        boss.world.spawnParticle(Particle.SPLASH, center, 5, 0.4, 0.6, 0.4, 0.04)
    }

    private fun spawnExecutionParticles(location: Location) {
        boss.world.spawnParticle(Particle.SPLASH, location, 7, 0.35, 0.25, 0.35, 0.06)
        boss.world.spawnParticle(Particle.BUBBLE_POP, location, 4, 0.3, 0.25, 0.3, 0.03)
    }

    companion object {
        private const val PHYSICAL_SKILL_METADATA = "hjh_physical_skill"
        private const val INITIAL_DELAY_TICKS = 7L * 20L
        private const val SKILL_COOLDOWN_TICKS = 15L * 20L
        private const val RETRY_DELAY_TICKS = 3L * 20L
        private const val CHANNEL_TICKS = 3 * 20
        private const val SKILL_RANGE = 15.0
        private const val DASH_TARGET_RANGE = 28.0
        private const val DASH_DISTANCE = 24.0
        private const val DASH_STEP = 1.1
        private const val DASH_HIT_RADIUS = 1.35
        private const val DASH_WETNESS_RADIUS = 5.0
        private const val DASH_WETNESS_AMOUNT = 10
        private const val DASH_DAMAGE_TAKEN_MULTIPLIER = 0.4
        private const val DASH_RETRY_DELAY_TICKS = 16L
        private const val MAX_DASH_ATTEMPTS = 4
        private const val DASH_DAMAGE = 30.0
        private const val CAPTURE_HEIGHT = 10.0
        private const val ASCENT_TICKS = 40.0
        private const val CAPTURE_WETNESS_INTERVAL_TICKS = 10
        private const val CAPTURE_WETNESS_AMOUNT = 5
        private const val ESCAPE_WINDOW_TICKS = 3 * 20
        private const val REQUIRED_JUMP_PRESSES = 10
        private const val MIN_JUMP_INTERVAL_MILLIS = 80L
        private const val EXECUTION_DAMAGE = 50.0
        private const val EXECUTION_TIMEOUT_TICKS = 5 * 20
        private const val STUN_TICKS = 2 * 20
        private const val BONUS_MELEE_RANGE_SQUARED = 7.84
        private const val BONUS_MELEE_INTERVAL_TICKS = 30L
        private const val HEIGHT_REFERENCE_RANGE = 32.0
        private const val MAX_IDLE_HEIGHT_ABOVE_PLAYER = 2.0
        private const val HEIGHT_TELEPORT_THRESHOLD = 1.0
        private const val HEIGHT_PULL_SPEED = 0.3
        private const val HEIGHT_CHECK_INTERVAL_TICKS = 5L
    }
}
