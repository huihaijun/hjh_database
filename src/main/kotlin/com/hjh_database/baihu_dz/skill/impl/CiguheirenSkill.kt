package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
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
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CiguheirenSkill(
    private val plugin: Hjh_database,
    private val manager: BaihuWeaponSkillManager
) : BaihuWeaponSkill {
    private data class PendingSecond(val expireAt: Long, val durabilityRestored: Boolean)
    private data class DashShieldState(
        val amount: Double,
        val generation: Long,
        val amplifier: Int,
        val ownsPotionEffect: Boolean
    )

    private val pendingSecond = ConcurrentHashMap<UUID, PendingSecond>()
    private val activeDashTokens = ConcurrentHashMap<UUID, Long>()
    private val dashShields = ConcurrentHashMap<UUID, DashShieldState>()

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
                castSecond(player, data, item, weaponData, config, pending.durabilityRestored)
                return BaihuWeaponSkillResult.success(
                    consumeDurability = false,
                    startCooldown = true,
                    message = config.getString("second_message", config.getString("actionbar_message", ""))
                )
            }
            manager.startCooldown(player, item.type, config, data)
            return BaihuWeaponSkillResult.FAIL
        }

        castFirst(player, data, item, weaponData, config)
        return BaihuWeaponSkillResult.success(
            consumeDurability = true,
            startCooldown = false,
            message = config.getString("actionbar_message", "")
        )
    }

    override fun deactivate(player: Player) {
        pendingSecond.remove(player.uniqueId)
        activeDashTokens.remove(player.uniqueId)
    }

    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!activeDashTokens.containsKey(player.uniqueId)) return

        // 冲刺无敌统一取消 Bukkit 伤害事件，不依赖伤害来源或 DamageCause。
        event.isCancelled = true
        event.damage = 0.0
    }

    private fun castFirst(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection
    ) {
        val range = config.getDouble("sweep_range", 6.0)
        val damage = data.attack * config.getDouble("sweep_damage_multiplier", 3.3)
        var killedAny = false
        val hitCount = sweepTargets(player, range).count { target ->
            val wasAlive = !target.isDead && target.health > 0.0
            damageTarget(player, target, damage, armorPiercing = false)
            grantSweepShield(player, config)
            if (wasAlive && (target.isDead || target.health <= 0.0)) killedAny = true
            true
        }

        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.55f)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THROW, 0.45f, 1.7f)
        spawnSweepBladeWave(player, range)

        if (killedAny) {
            // 主动技能的耐久在 castActive 返回后才扣除，因此延后一刻恢复，保证结算顺序正确。
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                restoreKillDurability(player, item, weaponData, config)
            }, 1L)
        }

        config.getString("message")?.takeIf { it.isNotBlank() }?.let {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', it))
        }

        val windowTicks = (config.getDouble("second_window", 3.0) * 20.0).toLong().coerceAtLeast(1L)
        val expireAt = System.currentTimeMillis() + windowTicks * 50L
        pendingSecond[player.uniqueId] = PendingSecond(expireAt, killedAny)
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

    private fun castSecond(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        durabilityAlreadyRestored: Boolean
    ) {
        val direction = getHorizontalDirection(player)
        val damage = data.attack * config.getDouble("dash_damage_multiplier", 3.5)
        val damaged = mutableSetOf<UUID>()
        val start = player.location.clone()
        val distance = config.getDouble("dash_distance", 7.0)
        val maxTicks = config.getInt("dash_ticks", 8).coerceAtLeast(1)
        val speed = config.getDouble("dash_speed", 1.18)
        val stepUpVelocity = config.getDouble("step_up_velocity", 0.42)
        val dashToken = System.nanoTime()
        activeDashTokens[player.uniqueId] = dashToken

        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.55f, 1.65f)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.55f, 1.75f)
        object : BukkitRunnable() {
            private var ticks = 0
            private var durabilityRestored = durabilityAlreadyRestored
            private var totalHealing = 0.0

            override fun run() {
                if (!player.isOnline || player.isDead) {
                    activeDashTokens.remove(player.uniqueId, dashToken)
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
                        val wasAlive = !target.isDead && target.health > 0.0
                        damageTarget(player, target, damage, armorPiercing = true)
                        totalHealing += healFromDashHit(player, config, totalHealing)
                        if (!durabilityRestored && wasAlive && (target.isDead || target.health <= 0.0)) {
                            restoreKillDurability(player, item, weaponData, config)
                            durabilityRestored = true
                        }
                    }
                }

                ticks++
                if (ticks >= maxTicks || player.location.distanceSquared(start) >= distance * distance) {
                    activeDashTokens.remove(player.uniqueId, dashToken)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun grantSweepShield(player: Player, config: ConfigurationSection) {
        val perTarget = config.getDouble("sweep_shield_per_target", 10.0).coerceAtLeast(0.0)
        val maximum = config.getDouble("sweep_shield_max", 50.0).coerceAtLeast(0.0)
        if (perTarget <= 0.0 || maximum <= 0.0) return

        val durationTicks = (config.getDouble("sweep_shield_duration", 20.0) * 20.0).toLong().coerceAtLeast(1L)
        val current = min(player.absorptionAmount, maximum)
        val amount = min(maximum, current + perTarget)
        val generation = System.nanoTime()
        val amplifier = (ceil(amount / 4.0).toInt() - 1).coerceAtLeast(0)
        val existingEffect = player.getPotionEffect(PotionEffectType.ABSORPTION)
        val ownsPotionEffect = existingEffect == null ||
            existingEffect.amplifier < amplifier ||
            (existingEffect.amplifier == amplifier && existingEffect.duration < durationTicks)

        if (ownsPotionEffect) {
            player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, durationTicks.toInt(), amplifier, false, true, true), true)
        }
        player.absorptionAmount = max(player.absorptionAmount, amount)
        dashShields[player.uniqueId] = DashShieldState(amount, generation, amplifier, ownsPotionEffect)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val state = dashShields[player.uniqueId] ?: return@Runnable
            if (state.generation != generation) return@Runnable
            dashShields.remove(player.uniqueId, state)

            // 只清理由本技能维持的同等级护盾；期间若被其他技能替换为更强护盾则不触碰。
            val effect = player.getPotionEffect(PotionEffectType.ABSORPTION)
            if (state.ownsPotionEffect &&
                effect != null && effect.amplifier == state.amplifier && effect.duration <= 2 &&
                player.absorptionAmount <= state.amount + 0.01
            ) {
                player.removePotionEffect(PotionEffectType.ABSORPTION)
                player.absorptionAmount = 0.0
            }
        }, durationTicks)
    }

    private fun healFromDashHit(player: Player, config: ConfigurationSection, alreadyHealed: Double): Double {
        val maximum = config.getDouble("dash_heal_max", 20.0).coerceAtLeast(0.0)
        val perTarget = config.getDouble("dash_heal_per_target", 5.0).coerceAtLeast(0.0)
        val requested = min(perTarget, (maximum - alreadyHealed).coerceAtLeast(0.0))
        if (requested <= 0.0 || player.isDead) return 0.0

        val maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: player.maxHealth
        val before = player.health
        player.health = min(maxHealth, before + requested)
        val healed = player.health - before
        if (healed > 0.0) {
            player.world.spawnParticle(Particle.HEART, player.location.clone().add(0.0, 1.1, 0.0), 2, 0.25, 0.25, 0.25, 0.0)
        }
        return healed
    }

    private fun restoreKillDurability(
        player: Player,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection
    ) {
        val restoreAmount = config.getInt("kill_durability_restore", 15).coerceAtLeast(0)
        val restored = plugin.baihuDzManager.restoreDurability(player, item, weaponData, restoreAmount)
        if (restored > 0) {
            player.sendMessage("§a[刺骨黑刃] 击杀怪物恢复了 §f$restored §a点虎瘴耐久。")
            player.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 1.65f)
        }
    }

    private fun sweepTargets(player: Player, range: Double): List<LivingEntity> {
        val origin = player.location
        val direction = getHorizontalDirection(player)
        return player.world.getNearbyEntities(origin, range, 2.5, range)
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && isValidTarget(it) }
            .filter { target ->
                val offset = target.location.toVector().subtract(origin.toVector())
                if (abs(offset.y) > 2.4) return@filter false
                val horizontal = Vector(offset.x, 0.0, offset.z)
                horizontal.lengthSquared() <= range * range && horizontal.dot(direction) >= 0.0
            }
            .toList()
    }

    private fun spawnSweepBladeWave(player: Player, range: Double) {
        val origin = player.location.clone().add(0.0, 1.05, 0.0)
        val forward = getHorizontalDirection(player)
        val dust = Particle.DustOptions(PLATINUM, 0.85f)

        val radialSteps = 8
        val arcPoints = 25
        for (step in 1..radialSteps) {
            val distance = range * step / radialSteps
            for (i in 0 until arcPoints) {
                val t = i.toDouble() / (arcPoints - 1)
                val angle = Math.toRadians(-90.0 + 180.0 * t)
                val lift = sin(t * Math.PI) * 0.22
                val point = origin.clone().add(rotateHorizontal(forward, angle).multiply(distance)).add(0.0, lift, 0.0)
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

    private fun damageTarget(player: Player, target: LivingEntity, amount: Double, armorPiercing: Boolean) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        if (armorPiercing) target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            if (target.hasMetadata("hjh_physical_skill")) {
                target.removeMetadata("hjh_physical_skill", plugin)
            }
            if (armorPiercing && target.hasMetadata("hjh_magic_damage")) {
                target.removeMetadata("hjh_magic_damage", plugin)
            }
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
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
