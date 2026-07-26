package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobAffixSupport
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

object NorthWetnessSkill : Listener {
    private const val MAX_WETNESS = 100
    private const val NORMAL_INCREASE = 5
    private const val APPLICATION_LOCK_MILLIS = 1_500L
    private const val DECAY_DELAY_MILLIS = 15_000L
    private const val PASSIVE_RANGE_SQUARED = 25.0

    private data class WetnessStatus(
        var value: Int,
        var lastIncreaseAt: Long,
        var lastApplicationAt: Long
    )

    private lateinit var plugin: Hjh_database
    private lateinit var speedModifierKey: NamespacedKey
    private val statuses = mutableMapOf<UUID, WetnessStatus>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private val trackedArrows = mutableSetOf<AbstractArrow>()
    private var passiveTask: BukkitTask? = null
    private var decayTask: BukkitTask? = null
    private var arrowTask: BukkitTask? = null
    private var initialized = false

    fun init(plugin: Hjh_database) {
        if (initialized) shutdown()
        this.plugin = plugin
        speedModifierKey = NamespacedKey(plugin, "north_wetness_slowness")
        initialized = true
        plugin.server.pluginManager.registerEvents(this, plugin)

        passiveTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            pulseNearbyPlayers()
        }, 7L * 20L, 7L * 20L)

        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            decayWetness()
        }, 20L, 20L)

        arrowTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateAffixArrows()
        }, 1L, 1L)
    }

    fun shutdown() {
        if (!initialized) return
        passiveTask?.cancel()
        decayTask?.cancel()
        arrowTask?.cancel()
        passiveTask = null
        decayTask = null
        arrowTask = null
        trackedArrows.forEach { arrow ->
            if (arrow.isValid) arrow.setGravity(true)
        }
        trackedArrows.clear()
        statuses.keys
            .mapNotNull(Bukkit::getPlayer)
            .forEach(::removeMovementPenalty)
        bars.values.forEach { it.removeAll() }
        bars.clear()
        statuses.clear()
        HandlerList.unregisterAll(this)
        initialized = false
    }

    fun getWetness(player: Player): Int = statuses[player.uniqueId]?.value ?: 0

    fun cleanseToOnePercent(player: Player): Boolean {
        val status = statuses[player.uniqueId] ?: return false
        if (status.value <= 1) return false

        status.value = 1
        status.lastIncreaseAt = System.currentTimeMillis()
        updateDisplay(player, status.value)
        return true
    }

    /**
     * 特殊技能可以传入 bypassRateLimit=true，绕过普通词条共享的1.5秒叠加锁。
     */
    fun addWetness(
        player: Player,
        amount: Int = NORMAL_INCREASE,
        bypassRateLimit: Boolean = false
    ): Boolean {
        if (!isValidTarget(player) || amount <= 0) return false

        val now = System.currentTimeMillis()
        val status = statuses.getOrPut(player.uniqueId) {
            WetnessStatus(0, now, 0L)
        }
        if (!bypassRateLimit && now - status.lastApplicationAt < APPLICATION_LOCK_MILLIS) return false
        if (status.value >= MAX_WETNESS) return false

        status.value = (status.value + amount).coerceAtMost(MAX_WETNESS)
        status.lastIncreaseAt = now
        status.lastApplicationAt = now
        updateDisplay(player, status.value)
        playIncreaseEffect(player)
        return true
    }

    /**
     * 技能管理器只提供本地冷却写入逻辑，其余判定和表现均留在词条内。
     */
    fun tryInterruptSkill(player: Player, applyFixedCooldown: () -> Unit): Boolean {
        val status = statuses[player.uniqueId] ?: return false
        if (status.value <= 0) return false
        if (ThreadLocalRandom.current().nextDouble(100.0) >= status.value.toDouble()) return false

        status.value = (status.value - SILENCE_WETNESS_COST).coerceAtLeast(0)
        applyFixedCooldown()
        updateDisplay(player, status.value)
        if (status.value == 0) {
            statuses.remove(player.uniqueId)
        }

        val center = player.location.clone().add(0.0, 1.0, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(52, 154, 255), 1.25f)
        player.world.spawnParticle(Particle.DUST, center, 28, 0.65, 0.8, 0.65, 0.02, dust)
        player.world.spawnParticle(Particle.SPLASH, center, 36, 0.7, 0.7, 0.7, 0.15)
        player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 0.75f)
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§b湿气扰乱了你的技能，释放失败并进入5秒冷却，湿气降低20%！")
        )
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAffixAttack(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (event.finalDamage <= 0.0) return
        val attacker = MobAffixSupport.realAttacker(event.damager) ?: return
        if (attacker is Player || !MobAffixSupport.hasAffix(attacker, MobAffix.NORTH_WETNESS)) return
        if (ThreadLocalRandom.current().nextDouble() >= 0.5) return
        addWetness(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAffixArrowLaunch(event: ProjectileLaunchEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val shooter = arrow.shooter as? LivingEntity ?: return
        if (shooter is Player || !MobAffixSupport.hasAffix(shooter, MobAffix.NORTH_WETNESS)) return
        trackedArrows.add(arrow)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAffixArrowHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        if (trackedArrows.remove(arrow) && arrow.isValid) {
            arrow.setGravity(true)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        clearPlayer(event.entity)
    }

    private fun pulseNearbyPlayers() {
        Bukkit.getWorlds().forEach { world ->
            world.livingEntities
                .asSequence()
                .filter { MobAffixSupport.hasAffix(it, MobAffix.NORTH_WETNESS) }
                .forEach { monster ->
                    world.players
                        .asSequence()
                        .filter(::isValidTarget)
                        .filter { it.location.distanceSquared(monster.location) <= PASSIVE_RANGE_SQUARED }
                        .forEach { addWetness(it) }
                }
        }
    }

    private fun decayWetness() {
        val now = System.currentTimeMillis()
        val iterator = statuses.iterator()
        while (iterator.hasNext()) {
            val (uuid, status) = iterator.next()
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                bars.remove(uuid)?.removeAll()
                iterator.remove()
                continue
            }
            if (now - status.lastIncreaseAt < DECAY_DELAY_MILLIS) continue

            status.value = (status.value - NORMAL_INCREASE).coerceAtLeast(0)
            updateDisplay(player, status.value)
            if (status.value == 0) {
                iterator.remove()
            }
        }
    }

    private fun updateAffixArrows() {
        val iterator = trackedArrows.iterator()
        while (iterator.hasNext()) {
            val arrow = iterator.next()
            if (!arrow.isValid || arrow.isDead || arrow.ticksLived > MAX_TRACKED_ARROW_TICKS) {
                if (arrow.isValid) arrow.setGravity(true)
                iterator.remove()
                continue
            }
            arrow.setGravity(!arrow.isInWater)
        }
    }

    private fun updateDisplay(player: Player, value: Int) {
        updateMovementPenalty(player, value)
        if (value <= 0) {
            bars.remove(player.uniqueId)?.removeAll()
            return
        }

        val bar = bars.getOrPut(player.uniqueId) {
            Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SEGMENTED_10).also {
                it.addPlayer(player)
            }
        }
        bar.setTitle("§b§l湿气 §f$value%")
        bar.progress = value / MAX_WETNESS.toDouble()
        bar.isVisible = true
    }

    private fun playIncreaseEffect(player: Player) {
        val center = player.location.clone().add(0.0, 0.9, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(40, 135, 235), 0.9f)
        player.world.spawnParticle(Particle.SPLASH, center, 14, 0.45, 0.65, 0.45, 0.08)
        player.world.spawnParticle(Particle.BUBBLE_POP, center, 8, 0.4, 0.6, 0.4, 0.04)
        player.world.spawnParticle(Particle.DUST, center, 8, 0.45, 0.65, 0.45, 0.0, dust)
        player.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 0.35f, 1.35f)
    }

    private fun clearPlayer(player: Player) {
        statuses.remove(player.uniqueId)
        bars.remove(player.uniqueId)?.removeAll()
        removeMovementPenalty(player)
    }

    private fun updateMovementPenalty(player: Player, wetness: Int) {
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(speedModifierKey)?.let(attribute::removeModifier)

        val reduction = wetness.coerceIn(0, MAX_SLOW_PERCENT) / 100.0
        if (reduction <= 0.0) return
        attribute.addTransientModifier(
            AttributeModifier(
                speedModifierKey,
                -reduction,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1
            )
        )
    }

    private fun removeMovementPenalty(player: Player) {
        if (!::speedModifierKey.isInitialized) return
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(speedModifierKey)?.let(attribute::removeModifier)
    }

    private fun isValidTarget(player: Player): Boolean {
        return player.isOnline &&
            !player.isDead &&
            player.gameMode != GameMode.CREATIVE &&
            player.gameMode != GameMode.SPECTATOR
    }

    private const val MAX_SLOW_PERCENT = 60
    private const val SILENCE_WETNESS_COST = 20
    private const val MAX_TRACKED_ARROW_TICKS = 20 * 120
}
