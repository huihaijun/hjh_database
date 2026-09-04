package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.listener.CombatListener
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
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
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.ceil

class Qianlishensheshou(
    private val plugin: Hjh_database,
    private val boss: LivingEntity
) : Listener {
    private var isChanneling = false
    private var isFiringSkill = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        scheduleNextSkill(INITIAL_DELAY_TICKS)

        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid()) {
                    HandlerList.unregisterAll(this@Qianlishensheshou)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        boss.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = INITIAL_TARGET_RANGE
    }

    private fun scheduleNextSkill(delayTicks: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (!isBossValid() || isChanneling || isFiringSkill) return
                startChanneling()
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun startChanneling() {
        if (nearestPlayer(INITIAL_TARGET_RANGE) == null) {
            scheduleNextSkill(RETRY_DELAY_TICKS)
            return
        }

        isChanneling = true
        freezeBoss(true)
        nearbyPlayers(WARNING_RANGE).forEach { player ->
            player.sendMessage("§c千里神射手正在瞄准最近的目标！此箭难以用寻常方式躲避！")
            player.sendMessage("§c箭矢穿过的障碍越多，此箭伤害越低！但湿气会加重箭矢的伤害！")
            player.sendMessage("§c或许躲入水下也能减免部分伤害……")
            player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.75f, 0.65f)
        }

        object : BukkitRunnable() {
            private var ticks = 0
            private var currentTarget: Player? = null

            override fun run() {
                if (!isBossValid() || !isChanneling) {
                    cancel()
                    return
                }

                currentTarget = nearestPlayer(TARGET_RANGE)
                if (ticks >= CHANNEL_TICKS) {
                    val finalTarget = currentTarget
                    isChanneling = false
                    if (finalTarget != null) {
                        isFiringSkill = true
                        firePenetratingShot(finalTarget)
                    } else {
                        freezeBoss(false)
                    }
                    scheduleNextSkill(SKILL_COOLDOWN_TICKS)
                    cancel()
                    return
                }

                boss.velocity = Vector(0.0, 0.0, 0.0)
                currentTarget?.let { target ->
                    drawAimLine(boss.eyeLocation, target.eyeLocation)
                    faceTarget(target)
                }
                spawnChannelParticles()
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBossDamage(event: EntityDamageEvent) {
        if (event.entity == boss && isChanneling) {
            event.damage *= CHANNEL_DAMAGE_TAKEN_MULTIPLIER
        }
    }

    private fun firePenetratingShot(target: Player) {
        if (!isValidTarget(target) || target.world != boss.world) {
            finishSkillShot()
            return
        }

        val start = boss.eyeLocation.clone()
        val destination = target.eyeLocation.clone()
        val blockCount = countPenetratedBlocks(start, destination)
        val waterReduction = if (isUnderwater(target)) WATER_REDUCTION else 0.0
        val environmentReduction = (blockCount * BLOCK_REDUCTION + waterReduction)
            .coerceAtMost(MAX_REDUCTION)
        val wetnessPercent = NorthWetnessSkill.getWetness(target).toDouble()
        val wetnessDamageBonus = (wetnessPercent * WETNESS_BONUS_PER_PERCENT)
            .coerceAtMost(MAX_WETNESS_DAMAGE_BONUS)
        val scaledDamage = BASE_DAMAGE * (1.0 + wetnessDamageBonus)
        val armorPenetration = (wetnessPercent * WETNESS_BONUS_PER_PERCENT - environmentReduction)
            .coerceIn(0.0, 1.0)

        boss.world.playSound(boss.location, Sound.ITEM_CROSSBOW_SHOOT, 1.25f, 0.55f)
        animateVirtualArrow(
            start,
            destination,
            target,
            scaledDamage,
            environmentReduction,
            armorPenetration
        )
    }

    private fun animateVirtualArrow(
        start: Location,
        destination: Location,
        target: Player,
        scaledDamage: Double,
        environmentReduction: Double,
        armorPenetration: Double
    ) {
        val delta = destination.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= 0.01) {
            dealPenetratingDamage(target, scaledDamage, environmentReduction, armorPenetration)
            finishSkillShot()
            return
        }

        val travelTicks = ceil(distance / VIRTUAL_ARROW_SPEED).toInt().coerceAtLeast(1)
        object : BukkitRunnable() {
            private var ticks = 0
            private var previous = start.clone()

            override fun run() {
                if (!isBossValid()) {
                    isFiringSkill = false
                    cancel()
                    return
                }

                ticks++
                val progress = (ticks.toDouble() / travelTicks).coerceAtMost(1.0)
                val current = start.clone().add(delta.clone().multiply(progress))
                drawArrowTrail(previous, current)
                previous = current

                if (progress >= 1.0) {
                    if (isValidTarget(target) && target.world == boss.world) {
                        dealPenetratingDamage(
                            target,
                            scaledDamage,
                            environmentReduction,
                            armorPenetration
                        )
                    }
                    finishSkillShot()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun finishSkillShot() {
        isFiringSkill = false
        if (isBossValid()) {
            freezeBoss(false)
        }
    }

    private fun dealPenetratingDamage(
        target: Player,
        scaledDamage: Double,
        environmentReduction: Double,
        armorPenetration: Double
    ) {
        val shieldBlocked = isWarriorShieldBlocking(target)
        val shieldReduction = if (shieldBlocked) SHIELD_REDUCTION else 0.0
        val totalReduction = (environmentReduction + shieldReduction).coerceAtMost(1.0)
        val damage = scaledDamage * (1.0 - totalReduction)

        target.noDamageTicks = 0
        target.setMetadata(PHYSICAL_SKILL_METADATA, FixedMetadataValue(plugin, true))
        target.setMetadata(
            CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA,
            FixedMetadataValue(plugin, armorPenetration)
        )
        try {
            if (damage > 0.0) {
                target.damage(damage)
            }
        } finally {
            target.removeMetadata(PHYSICAL_SKILL_METADATA, plugin)
            target.removeMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, plugin)
        }

        val impact = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(Particle.CRIT, impact, 28, 0.45, 0.7, 0.45, 0.2)
        target.world.playSound(impact, Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 0.65f)
        if (shieldBlocked) {
            target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.75f)
            target.playEffect(org.bukkit.EntityEffect.SHIELD_BLOCK)
        }
        NorthWetnessSkill.addWetness(target, HIT_WETNESS_AMOUNT, bypassRateLimit = true)
    }

    private fun isWarriorShieldBlocking(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        if (data.job != 0 || !player.isBlocking || player.hasCooldown(Material.SHIELD)) return false

        val inventory = player.inventory
        val hasShield = inventory.itemInMainHand.type == Material.SHIELD ||
            inventory.itemInOffHand.type == Material.SHIELD
        if (!hasShield) return false

        val directionToBoss = boss.eyeLocation.toVector().subtract(player.eyeLocation.toVector())
        if (directionToBoss.lengthSquared() <= 0.01) return true
        return player.location.direction.normalize().dot(directionToBoss.normalize()) > 0.0
    }

    private fun countPenetratedBlocks(start: Location, destination: Location): Int {
        val delta = destination.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= 0.01) return 0

        val steps = ceil(distance / BLOCK_SAMPLE_STEP).toInt().coerceAtLeast(1)
        val seenBlocks = mutableSetOf<Triple<Int, Int, Int>>()
        for (step in 1 until steps) {
            val point = start.clone().add(delta.clone().multiply(step.toDouble() / steps))
            val block = point.block
            if (!block.isPassable) {
                seenBlocks.add(Triple(block.x, block.y, block.z))
            }
        }
        return seenBlocks.size
    }

    private fun isUnderwater(player: Player): Boolean {
        val eyeBlock = player.eyeLocation.block.type
        return eyeBlock == Material.WATER || eyeBlock == Material.BUBBLE_COLUMN
    }

    private fun drawAimLine(start: Location, end: Location) {
        val delta = end.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= 0.01) return

        val direction = delta.normalize()
        val dust = Particle.DustOptions(Color.fromRGB(255, 20, 20), 0.7f)
        var traveled = 0.0
        while (traveled <= distance) {
            val point = start.clone().add(direction.clone().multiply(traveled))
            boss.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
            traveled += AIM_LINE_SPACING
        }
    }

    private fun drawArrowTrail(from: Location, to: Location) {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (distance <= 0.01) return

        val direction = delta.normalize()
        val dust = Particle.DustOptions(Color.fromRGB(245, 245, 255), 1.15f)
        var traveled = 0.0
        while (traveled <= distance) {
            val point = from.clone().add(direction.clone().multiply(traveled))
            boss.world.spawnParticle(Particle.CRIT, point, 2, 0.04, 0.04, 0.04, 0.01)
            boss.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
            traveled += ARROW_TRAIL_SPACING
        }
    }

    private fun spawnChannelParticles() {
        val center = boss.location.clone().add(0.0, 1.25, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(180, 20, 20), 1.0f)
        boss.world.spawnParticle(Particle.DUST, center, 8, 0.45, 0.7, 0.45, 0.0, dust)
        if (boss.ticksLived % 10 == 0) {
            boss.world.playSound(boss.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.55f, 0.55f)
        }
    }

    private fun faceTarget(target: Player) {
        val direction = target.eyeLocation.toVector().subtract(boss.eyeLocation.toVector())
        if (direction.lengthSquared() <= 0.01) return
        val facing = boss.location.clone().setDirection(direction)
        boss.setRotation(facing.yaw, facing.pitch)
    }

    private fun freezeBoss(frozen: Boolean) {
        (boss as? Mob)?.setAI(!frozen)
        boss.velocity = Vector(0.0, 0.0, 0.0)
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

    private fun isValidTarget(player: Player): Boolean {
        return player.isOnline &&
            !player.isDead &&
            player.gameMode != GameMode.CREATIVE &&
            player.gameMode != GameMode.SPECTATOR
    }

    private fun isBossValid(): Boolean = boss.isValid && !boss.isDead

    companion object {
        private const val PHYSICAL_SKILL_METADATA = "hjh_physical_skill"
        private const val INITIAL_DELAY_TICKS = 7L * 20L
        private const val SKILL_COOLDOWN_TICKS = 15L * 20L
        private const val RETRY_DELAY_TICKS = 3L * 20L
        private const val CHANNEL_TICKS = 5 * 20
        private const val INITIAL_TARGET_RANGE = 16.0
        private const val TARGET_RANGE = 28.0
        private const val WARNING_RANGE = 16.0
        private const val BASE_DAMAGE = 35.0
        private const val WETNESS_BONUS_PER_PERCENT = 0.025
        private const val MAX_WETNESS_DAMAGE_BONUS = 1.0
        private const val BLOCK_REDUCTION = 0.10
        private const val WATER_REDUCTION = 0.35
        private const val MAX_REDUCTION = 0.50
        private const val SHIELD_REDUCTION = 0.50
        private const val HIT_WETNESS_AMOUNT = 20
        private const val CHANNEL_DAMAGE_TAKEN_MULTIPLIER = 0.20
        private const val VIRTUAL_ARROW_SPEED = 3.5
        private const val BLOCK_SAMPLE_STEP = 0.2
        private const val AIM_LINE_SPACING = 0.65
        private const val ARROW_TRAIL_SPACING = 0.35
    }
}
