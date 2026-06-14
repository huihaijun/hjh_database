package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Xiongshentaisui(private val plugin: Hjh_database, private val boss: LivingEntity) : Listener {

    companion object {
        const val PLAYER_DEBUFF_METADATA = "hjh_xiongshentaisui_quicksand_player"
        const val BOSS_BUFF_METADATA = "hjh_xiongshentaisui_quicksand_boss"
        private const val QUICKSAND_DAMAGE_METADATA = "hjh_xiongshentaisui_quicksand_damage"

        private val MAGIC_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.DRAGON_BREATH,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.POISON
        )

        private val TRUE_DAMAGE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.SUICIDE,
            EntityDamageEvent.DamageCause.STARVATION
        )
    }

    private data class QuicksandCircle(val center: Location, val radius: Double) {
        fun contains(location: Location): Boolean {
            if (location.world != center.world) return false
            if (abs(location.y - center.y) > 5.0) return false

            val dx = location.x - center.x
            val dz = location.z - center.z
            return dx * dx + dz * dz <= radius * radius
        }
    }

    private val affectedPlayers = mutableSetOf<UUID>()
    private val initialDelayTicks = 5 * 20L
    private val cooldownTicks = 15 * 20L
    private val channelTicks = 3 * 20
    private val fieldTicks = 6 * 20
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")
    private var disposed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        BossTargetingUtil.start(plugin, boss, radius = 48.0, chaseSpeed = 0.18, minChaseDistance = 5.0) {
            (boss as? Mob)?.hasAI() == true
        }
        startLifecycleCleanup()
        scheduleNextSkill(initialDelayTicks)
    }

    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        boss.getAttribute(Attribute.SCALE)?.baseValue = 2.2
    }

    private fun startLifecycleCleanup() {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    dispose()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun scheduleNextSkill(delayTicks: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (disposed || boss.isDead || !boss.isValid) return
                startQuicksandCast()
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun startQuicksandCast() {
        val targets = findNearbyPlayers(15.0)
        if (targets.isEmpty()) {
            scheduleNextSkill(3 * 20L)
            return
        }

        val circles = chooseQuicksandCircles(targets)

        sendNearbyWarning()
        boss.world.playSound(boss.location, Sound.ENTITY_MAGMA_CUBE_SQUISH, 1.5f, 0.6f)
        freezeBoss(true)

        object : BukkitRunnable() {
            private var elapsed = 0

            override fun run() {
                if (disposed || boss.isDead || !boss.isValid) {
                    freezeBoss(false)
                    cleanupAllEffects()
                    cancel()
                    return
                }

                drawWarningCircles(circles)
                elapsed += 5

                if (elapsed >= channelTicks) {
                    activateQuicksand(circles)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun activateQuicksand(circles: List<QuicksandCircle>) {
        freezeBoss(false)
        boss.world.playSound(boss.location, Sound.BLOCK_SAND_BREAK, 1.6f, 0.55f)
        val warnedPlayers = mutableSetOf<UUID>()

        object : BukkitRunnable() {
            private var elapsed = 0

            override fun run() {
                if (disposed || boss.isDead || !boss.isValid) {
                    cleanupAllEffects()
                    cancel()
                    return
                }

                elapsed += 5
                drawActiveFields(circles)
                updateFieldEffects(circles, warnedPlayers, elapsed)

                if (elapsed >= fieldTicks) {
                    cleanupAllEffects()
                    scheduleNextSkill(cooldownTicks)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun chooseQuicksandCircles(players: List<Player>): List<QuicksandCircle> {
        val origin = boss.location
        val circles = ArrayList<QuicksandCircle>(3)
        val shuffledPlayers = players.shuffled()

        shuffledPlayers.take(3).forEach { player ->
            circles += QuicksandCircle(circleCenterAtFieldHeight(player.location), 5.0)
        }

        while (circles.size < 3) {
            circles += QuicksandCircle(randomOffsetLocation(origin, 15.0), 5.0)
        }

        return circles
    }

    private fun randomOffsetLocation(origin: Location, maxRadius: Double): Location {
        val random = ThreadLocalRandom.current()
        val angle = random.nextDouble(Math.PI * 2.0)
        val distance = sqrt(random.nextDouble()) * maxRadius
        return circleCenterAtFieldHeight(origin.clone().add(cos(angle) * distance, 0.0, sin(angle) * distance))
    }

    private fun circleCenterAtFieldHeight(location: Location): Location {
        return Location(boss.world, location.x, findSurfaceY(location), location.z)
    }

    private fun findSurfaceY(location: Location): Double {
        val world = location.world ?: return boss.location.y
        val startY = location.blockY.coerceAtMost(world.maxHeight - 1)
        val minY = world.minHeight

        for (y in startY downTo minY) {
            val block = world.getBlockAt(location.blockX, y, location.blockZ)
            if (!block.isPassable) {
                return y + 1.0
            }
        }

        return location.y
    }

    private fun findNearbyPlayers(radius: Double): List<Player> {
        return boss.getNearbyEntities(radius, 8.0, radius)
            .filterIsInstance<Player>()
            .filter { it.isValidPlayer() }
            .sortedBy { it.location.distanceSquared(boss.location) }
    }

    private fun sendNearbyWarning() {
        val nearby = boss.getNearbyEntities(18.0, 10.0, 18.0)
            .filterIsInstance<Player>()
            .filter { it.isValidPlayer() }

        nearby.forEach {
            it.sendMessage("§c凶神太岁即将制造流沙领域，处于流沙领域内自身属性会大幅削弱，而其会获得加强，将它引出流沙！")
            it.playSound(it.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.75f)
        }
    }

    private fun freezeBoss(frozen: Boolean) {
        if (boss is Mob) {
            boss.setAI(!frozen)
        }

        if (frozen) {
            boss.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
            boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, channelTicks + 20, 255, false, false))
            boss.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, channelTicks + 20, 255, false, false))
        } else {
            boss.removePotionEffect(PotionEffectType.SLOWNESS)
            boss.removePotionEffect(PotionEffectType.WEAKNESS)
        }
    }

    private fun drawWarningCircles(circles: List<QuicksandCircle>) {
        circles.forEach { circle ->
            drawCircle(circle, Particle.DUST)
            boss.world.spawnParticle(
                Particle.FALLING_DUST,
                circle.center.clone().add(0.0, 0.5, 0.0),
                8, 2.2, 0.2, 2.2, 0.0,
                Material.SAND.createBlockData()
            )
        }
    }

    private fun drawActiveFields(circles: List<QuicksandCircle>) {
        circles.forEach { circle ->
            drawCircle(circle, Particle.BLOCK)

            boss.world.spawnParticle(
                Particle.BLOCK,
                circle.center.clone().add(0.0, 0.15, 0.0),
                60, 2.4, 0.08, 2.4, 0.0,
                Material.SAND.createBlockData()
            )
            boss.world.spawnParticle(
                Particle.FALLING_DUST,
                circle.center.clone().add(0.0, 0.6, 0.0),
                16, 2.5, 0.2, 2.5, 0.0,
                Material.RED_SAND.createBlockData()
            )
        }

        if (elapsedSoundGate()) {
            boss.world.playSound(boss.location, Sound.BLOCK_GRAVEL_BREAK, 0.35f, 0.7f)
        }
    }

    private var soundTicks = 0
    private fun elapsedSoundGate(): Boolean {
        soundTicks += 5
        if (soundTicks < 20) return false
        soundTicks = 0
        return true
    }

    private fun drawCircle(circle: QuicksandCircle, particle: Particle) {
        val points = 48
        for (i in 0 until points) {
            val angle = 2.0 * Math.PI * i / points
            val loc = circle.center.clone().add(cos(angle) * circle.radius, 0.18, sin(angle) * circle.radius)

            if (particle == Particle.DUST) {
                boss.world.spawnParticle(
                    Particle.DUST,
                    loc,
                    1, 0.0, 0.0, 0.0, 0.0,
                    Particle.DustOptions(Color.fromRGB(215, 158, 67), 1.35f)
                )
            } else {
                boss.world.spawnParticle(
                    Particle.BLOCK,
                    loc,
                    1, 0.0, 0.0, 0.0, 0.0,
                    Material.RED_SAND.createBlockData()
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.entity.hasMetadata(QUICKSAND_DAMAGE_METADATA)) return

        val source = when (val damager = event.damager) {
            is LivingEntity -> damager
            is Projectile -> damager.shooter as? LivingEntity
            else -> null
        }

        if (source == boss && event.entity is Player) {
            if (boss.hasMetadata(BOSS_BUFF_METADATA)) {
                event.damage *= 1.2
            }
        }

        if (source is Player && source.hasMetadata(PLAYER_DEBUFF_METADATA)) {
            event.damage *= 0.5
        }

        if (!ignoresArmor(event.cause)) {
            val victim = event.entity
            if (victim == boss && boss.hasMetadata(BOSS_BUFF_METADATA)) {
                val armor = boss.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
                event.damage *= armorChangeMultiplier(armor, 1.3)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBossDeath(event: EntityDeathEvent) {
        if (event.entity == boss) {
            dispose()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        removePlayerEffect(event.entity)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        removePlayerEffect(event.player)
    }

    private fun ignoresArmor(cause: EntityDamageEvent.DamageCause): Boolean {
        return MAGIC_CAUSES.contains(cause) || TRUE_DAMAGE_CAUSES.contains(cause)
    }

    private fun armorChangeMultiplier(currentArmor: Double, armorFactor: Double): Double {
        if (currentArmor <= 0.0) return 1.0
        return (50.0 + currentArmor) / (50.0 + currentArmor * armorFactor)
    }

    private fun updateFieldEffects(circles: List<QuicksandCircle>, warnedPlayers: MutableSet<UUID>, fieldAgeTicks: Int) {
        val currentlyAffected = mutableSetOf<UUID>()

        boss.world.players
            .filter { it.isValidPlayer() }
            .filter { player -> circles.any { it.contains(player.location) } }
            .forEach { player ->
                currentlyAffected += player.uniqueId
                player.setMetadata(PLAYER_DEBUFF_METADATA, FixedMetadataValue(plugin, true))
                if (warnedPlayers.add(player.uniqueId)) {
                    player.sendMessage("§c快离开流沙区域！")
                    player.playSound(player.location, Sound.BLOCK_SAND_HIT, 1.0f, 0.7f)
                }
                player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 12, 2, false, true, true))

                if (fieldAgeTicks % 20 == 0) {
                    damagePlayerInQuicksand(player)
                }
            }

        affectedPlayers
            .filterNot { currentlyAffected.contains(it) }
            .forEach { uuid ->
                plugin.server.getPlayer(uuid)?.let { removePlayerEffect(it) }
            }

        affectedPlayers.clear()
        affectedPlayers += currentlyAffected

        if (circles.any { it.contains(boss.location) }) {
            boss.setMetadata(BOSS_BUFF_METADATA, FixedMetadataValue(plugin, true))
        } else if (boss.hasMetadata(BOSS_BUFF_METADATA)) {
            boss.removeMetadata(BOSS_BUFF_METADATA, plugin)
        }
    }

    private fun damagePlayerInQuicksand(player: Player) {
        if (!player.isValidPlayer()) return

        val damage = player.health * 0.3
        if (damage <= 0.0) return

        player.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        player.setMetadata(QUICKSAND_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        player.noDamageTicks = 0
        try {
            player.damage(damage, boss)
        } finally {
            if (player.hasMetadata("HJH_MAGIC_DAMAGE")) {
                player.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            if (player.hasMetadata(QUICKSAND_DAMAGE_METADATA)) {
                player.removeMetadata(QUICKSAND_DAMAGE_METADATA, plugin)
            }
        }
    }

    private fun cleanupAllEffects() {
        affectedPlayers.toList().forEach { uuid ->
            plugin.server.getPlayer(uuid)?.let { removePlayerEffect(it) }
        }
        affectedPlayers.clear()

        if (boss.hasMetadata(BOSS_BUFF_METADATA)) {
            boss.removeMetadata(BOSS_BUFF_METADATA, plugin)
        }
        if (!boss.isDead && boss.isValid) {
            freezeBoss(false)
        }
    }

    private fun removePlayerEffect(player: Player) {
        if (player.hasMetadata(PLAYER_DEBUFF_METADATA)) {
            player.removeMetadata(PLAYER_DEBUFF_METADATA, plugin)
            player.removePotionEffect(PotionEffectType.SLOWNESS)
        }
        affectedPlayers.remove(player.uniqueId)
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        cleanupAllEffects()
        HandlerList.unregisterAll(this)
    }

    private fun Player.isValidPlayer(): Boolean {
        return !isDead && gameMode != GameMode.SPECTATOR && gameMode != GameMode.CREATIVE
    }
}
