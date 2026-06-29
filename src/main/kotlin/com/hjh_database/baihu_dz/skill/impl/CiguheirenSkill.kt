package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillManager
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillResult
import com.hjh_database.data.PlayerData
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CiguheirenSkill(
    private val plugin: Hjh_database,
    private val manager: BaihuWeaponSkillManager
) : BaihuWeaponSkill {
    private data class PendingSecond(val expireAt: Long)

    private val pendingSecond = ConcurrentHashMap<UUID, PendingSecond>()

    override fun bypassDurabilityCost(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): Boolean {
        val pending = pendingSecond[player.uniqueId] ?: return false
        return System.currentTimeMillis() <= pending.expireAt
    }

    override fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult {
        val now = System.currentTimeMillis()
        val pending = pendingSecond[player.uniqueId]
        if (pending != null) {
            pendingSecond.remove(player.uniqueId)
            if (now <= pending.expireAt) {
                castSecond(player, data, config)
                return BaihuWeaponSkillResult.success(
                    consumeDurability = false,
                    startCooldown = true,
                    message = config.getString("second_message", config.getString("actionbar_message", ""))
                )
            }
            manager.startCooldown(player, item.type, config, data)
            return BaihuWeaponSkillResult.FAIL
        }

        castFirst(player, data, item, config)
        return BaihuWeaponSkillResult.success(
            consumeDurability = true,
            startCooldown = false,
            message = config.getString("actionbar_message", "")
        )
    }

    override fun deactivate(player: Player) {
        pendingSecond.remove(player.uniqueId)
    }

    private fun castFirst(player: Player, data: PlayerData, item: ItemStack, config: ConfigurationSection) {
        val range = config.getDouble("sweep_range", 4.0)
        val width = config.getDouble("sweep_width", 2.35)
        val damage = data.attack * config.getDouble("damage_multiplier", 2.2)
        val hitCount = sweepTargets(player, range, width).count { target ->
            damageTarget(player, target, damage)
            true
        }

        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.55f)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THROW, 0.45f, 1.7f)
        spawnSweepBladeWave(player, range, width)

        config.getString("message")?.takeIf { it.isNotBlank() }?.let {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', it))
        }

        val windowTicks = (config.getDouble("second_window", 3.0) * 20.0).toLong().coerceAtLeast(1L)
        val expireAt = System.currentTimeMillis() + windowTicks * 50L
        pendingSecond[player.uniqueId] = PendingSecond(expireAt)
        object : BukkitRunnable() {
            override fun run() {
                val pending = pendingSecond[player.uniqueId] ?: return
                if (pending.expireAt != expireAt) return
                pendingSecond.remove(player.uniqueId)
                if (player.isOnline) {
                    manager.startCooldown(player, item.type, config, data)
                }
            }
        }.runTaskLater(plugin, windowTicks)

        if (hitCount > 0) {
            player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.65f, 1.25f)
            player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.55f, 1.35f)
        }
    }

    private fun castSecond(player: Player, data: PlayerData, config: ConfigurationSection) {
        val direction = getHorizontalDirection(player)
        val damage = data.attack * config.getDouble("damage_multiplier", 2.2)
        val damaged = mutableSetOf<UUID>()
        val start = player.location.clone()
        val distance = config.getDouble("dash_distance", 7.0)
        val maxTicks = config.getInt("dash_ticks", 8).coerceAtLeast(1)
        val speed = config.getDouble("dash_speed", 1.18)
        val stepUpVelocity = config.getDouble("step_up_velocity", 0.42)

        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.55f, 1.65f)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.55f, 1.75f)
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }

                player.velocity = if (shouldStepUp(player, direction)) {
                    direction.clone().multiply(speed).setY(stepUpVelocity)
                } else {
                    direction.clone().multiply(speed).setY(0.0)
                }

                spawnDashTrail(player)
                dashTargets(player).forEach { target ->
                    if (damaged.add(target.uniqueId)) {
                        damageTarget(player, target, damage)
                    }
                }

                ticks++
                if (ticks >= maxTicks || player.location.distanceSquared(start) >= distance * distance) {
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun sweepTargets(player: Player, range: Double, width: Double): List<LivingEntity> {
        val origin = player.location
        val direction = getHorizontalDirection(player)
        return player.world.getNearbyEntities(origin, range + 1.0, 2.5, range + 1.0)
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && isValidTarget(it) }
            .filter { target ->
                val offset = target.location.toVector().subtract(origin.toVector())
                if (abs(offset.y) > 2.4) return@filter false
                val horizontal = Vector(offset.x, 0.0, offset.z)
                val forwardDistance = horizontal.dot(direction)
                if (forwardDistance <= 0.0 || forwardDistance > range) return@filter false
                val sideDistance = horizontal.subtract(direction.clone().multiply(forwardDistance)).length()
                sideDistance <= width
            }
            .toList()
    }

    private fun spawnSweepBladeWave(player: Player, range: Double, width: Double) {
        val origin = player.location.clone().add(0.0, 1.05, 0.0)
        val forward = getHorizontalDirection(player)
        val right = Vector(-forward.z, 0.0, forward.x).normalize()
        val dust = Particle.DustOptions(PLATINUM, 0.85f)

        val steps = 9
        val arcPoints = 13
        for (step in 1..steps) {
            val distance = range * step / steps
            val halfWidth = width * (0.35 + 0.65 * step / steps)
            for (i in 0 until arcPoints) {
                val t = if (arcPoints == 1) 0.5 else i.toDouble() / (arcPoints - 1)
                val side = (t - 0.5) * 2.0 * halfWidth
                val lift = sin(t * Math.PI) * 0.28
                val point = origin.clone()
                    .add(forward.clone().multiply(distance))
                    .add(right.clone().multiply(side))
                    .add(0.0, lift, 0.0)
                player.world.spawnParticle(Particle.DUST, point, 1, 0.015, 0.015, 0.015, 0.0, dust)
            }
        }

        for (i in 0..12) {
            val angle = Math.toRadians(-55.0 + i * (110.0 / 12.0))
            val rotated = rotateHorizontal(forward, angle)
            val point = origin.clone().add(rotated.multiply(range * 0.75)).add(0.0, 0.18, 0.0)
            player.world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.0)
        }
    }

    private fun spawnDashTrail(player: Player) {
        val loc = player.location.clone().add(0.0, 0.9, 0.0)
        val dust = Particle.DustOptions(PLATINUM, 0.75f)
        player.world.spawnParticle(Particle.DUST, loc, 8, 0.24, 0.22, 0.24, 0.0, dust)
        player.world.spawnParticle(Particle.END_ROD, loc, 2, 0.18, 0.16, 0.18, 0.0)
    }

    private fun dashTargets(player: Player): List<LivingEntity> {
        return player.world.getNearbyEntities(player.location, 1.45, 1.35, 1.45)
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && isValidTarget(it) }
            .toList()
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun damageTarget(player: Player, target: LivingEntity, amount: Double) {
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            if (target.hasMetadata("hjh_physical_skill")) {
                target.removeMetadata("hjh_physical_skill", plugin)
            }
            target.noDamageTicks = 0
        }
    }

    private fun getHorizontalDirection(player: Player): Vector {
        val direction = player.eyeLocation.direction
        direction.y = 0.0
        if (direction.lengthSquared() < 0.0001) return Vector(0.0, 0.0, 1.0)
        return direction.normalize()
    }

    private fun rotateHorizontal(vector: Vector, angle: Double): Vector {
        val x = vector.x * cos(angle) - vector.z * sin(angle)
        val z = vector.x * sin(angle) + vector.z * cos(angle)
        return Vector(x, 0.0, z).normalize()
    }

    private fun shouldStepUp(player: Player, direction: Vector): Boolean {
        val check = player.location.clone().add(direction.clone().multiply(0.75))
        val foot = check.block
        if (foot.isPassable) return false

        val aboveFoot = check.clone().add(0.0, 1.0, 0.0).block
        val head = check.clone().add(0.0, 2.0, 0.0).block
        return aboveFoot.isPassable && head.isPassable
    }

    companion object {
        private val PLATINUM: Color = Color.fromRGB(245, 232, 178)
    }
}
