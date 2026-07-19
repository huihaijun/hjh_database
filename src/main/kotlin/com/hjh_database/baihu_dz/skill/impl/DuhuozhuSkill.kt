package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillResult
import com.hjh_database.data.PlayerData
import com.hjh_database.listener.FormationMagicDamage
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DuhuozhuSkill(
    private val plugin: Hjh_database
) : BaihuWeaponSkill {
    override fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult {
        if (!hasActiveWarlockFurnace(player)) {
            player.sendMessage("§c需要副手激活术法炉，才能催动毒火烛。")
            return BaihuWeaponSkillResult.FAIL
        }

        val manaCost = config.getDouble("mana_cost", 20.0).coerceAtLeast(0.0)
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，需要 ${format(manaCost)} 点灵力。")
            return BaihuWeaponSkillResult.FAIL
        }

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        castFireRings(player, data, config)
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e${player.name}&f凭借&7毒火烛&f ，释放了阵法——&7轮火烬染"))
        sendLingliActionBar(player, data)
        return BaihuWeaponSkillResult.success(message = "")
    }

    private fun hasActiveWarlockFurnace(player: Player): Boolean {
        val furnace = plugin.playerManager.weaponManager.checkActiveWeapon(player, player.inventory.itemInOffHand, 40)
            ?: return false
        return furnace.reqJob == 2 && furnace.stats.getOrDefault("zf_str", 0.0) > 0.0
    }

    private fun castFireRings(player: Player, data: PlayerData, config: ConfigurationSection) {
        val radius = config.getDouble("radius", 8.0).coerceAtLeast(0.5)
        val rounds = config.getInt("rounds", 3).coerceAtLeast(1)
        val intervalTicks = config.getLong("interval_ticks", 15L).coerceAtLeast(1L)
        val impactDelayTicks = config.getLong("impact_delay_ticks", 8L).coerceAtLeast(0L)
        val vulnerableTicks = (config.getDouble("vulnerable_seconds", 5.0) * 20.0).toLong().coerceAtLeast(1L)

        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.85f, 0.72f)

        object : BukkitRunnable() {
            private var round = 1

            override fun run() {
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }

                val finalRound = round >= rounds
                val damage = data.zfStr * getRoundDamageMultiplier(config, round, rounds)
                spawnExpandingRing(player, radius, round, config)
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (player.isOnline && !player.isDead) {
                        damageTargets(player, radius, damage, finalRound, vulnerableTicks)
                    }
                }, impactDelayTicks)

                round++
                if (round > rounds) cancel()
            }
        }.runTaskTimer(plugin, 0L, intervalTicks)
    }

    private fun getRoundDamageMultiplier(config: ConfigurationSection, round: Int, rounds: Int): Double {
        val configured = config.getDoubleList("round_damage_multipliers")
        if (configured.isNotEmpty()) {
            return configured.getOrElse(round - 1) { configured.last() }.coerceAtLeast(0.0)
        }
        val total = config.getDouble("total_damage_multiplier", 3.3).coerceAtLeast(0.0)
        return total / rounds
    }

    private fun damageTargets(player: Player, radius: Double, damage: Double, applyVulnerable: Boolean, vulnerableTicks: Long) {
        val center = player.location
        val radiusSquared = radius * radius
        for (entity in player.world.getNearbyEntities(center, radius, 2.6, radius)) {
            val target = entity as? LivingEntity ?: continue
            if (target == player || !isValidTarget(target)) continue
            if (target.location.distanceSquared(center) > radiusSquared) continue

            damageTarget(player, target, damage)
            spawnHitEffect(target, applyVulnerable)
            if (applyVulnerable) {
                target.setMetadata(VULNERABLE_UNTIL_METADATA, FixedMetadataValue(plugin, System.currentTimeMillis() + vulnerableTicks * 50L))
            }
        }
    }

    private fun spawnExpandingRing(player: Player, radius: Double, round: Int, config: ConfigurationSection) {
        val steps = config.getInt("particle_steps", 8).coerceAtLeast(1)
        val stepInterval = config.getLong("particle_step_interval_ticks", 1L).coerceAtLeast(1L)
        object : BukkitRunnable() {
            private var step = 1

            override fun run() {
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }
                spawnRingStep(player, radius * step / steps, round, step, steps)
                step++
                if (step > steps) cancel()
            }
        }.runTaskTimer(plugin, 0L, stepInterval)
    }

    private fun spawnRingStep(player: Player, radius: Double, round: Int, step: Int, steps: Int) {
        val world = player.world
        val center = player.location.clone().add(0.0, 0.68, 0.0)
        val points = (radius * 4.0).toInt().coerceAtLeast(8)
        val lift = sin((step.toDouble() / steps) * PI) * 0.32
        val ember = Particle.DustOptions(Color.fromRGB(245, 84, 28), 1.15f)
        val soul = Particle.DustOptions(Color.fromRGB(33, 185, 210), 1.1f)

        val spokes = 5
        val trailSamples = 3
        for (spoke in 0 until spokes) {
            val angle = 2.0 * PI * spoke / spokes
            for (sample in 1..trailSamples) {
                val t = sample.toDouble() / trailSamples
                val trail = center.clone().add(
                    cos(angle) * radius * t,
                    0.05 + lift * t * 0.75,
                    sin(angle) * radius * t
                )
                when (round) {
                    1 -> world.spawnParticle(Particle.FLAME, trail, 1, 0.035, 0.025, 0.035, 0.012)
                    2 -> {
                        world.spawnParticle(Particle.FLAME, trail, 1, 0.03, 0.025, 0.03, 0.01)
                        if (sample % 2 == 0) {
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, trail, 1, 0.03, 0.025, 0.03, 0.01)
                        }
                    }
                    else -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, trail, 1, 0.035, 0.025, 0.035, 0.012)
                }
            }
        }

        for (i in 0 until points) {
            val angle = 2.0 * PI * i / points
            val outer = center.clone().add(cos(angle) * radius, lift, sin(angle) * radius)
            val inner = center.clone().add(cos(angle) * radius * 0.55, lift * 0.55, sin(angle) * radius * 0.55)

            when (round) {
                1 -> {
                    world.spawnParticle(Particle.FLAME, outer, 1, 0.025, 0.02, 0.025, 0.01)
                    world.spawnParticle(Particle.DUST, outer, 1, 0.01, 0.01, 0.01, 0.0, ember)
                    if (i % 6 == 0) world.spawnParticle(Particle.FLAME, inner, 1, 0.035, 0.025, 0.035, 0.015)
                }
                2 -> {
                    world.spawnParticle(Particle.FLAME, outer, 1, 0.025, 0.02, 0.025, 0.01)
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, outer, 1, 0.025, 0.02, 0.025, 0.01)
                    if (i % 6 == 0) {
                        world.spawnParticle(Particle.FLAME, inner, 1, 0.03, 0.025, 0.03, 0.01)
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, inner, 1, 0.03, 0.025, 0.03, 0.01)
                    }
                }
                else -> {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, outer, 1, 0.025, 0.02, 0.025, 0.01)
                    world.spawnParticle(Particle.DUST, outer, 1, 0.01, 0.01, 0.01, 0.0, soul)
                    if (i % 6 == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, inner, 1, 0.035, 0.025, 0.035, 0.015)
                }
            }
        }

        if (step == steps) {
            world.playSound(center, if (round >= 3) Sound.BLOCK_SOUL_SAND_BREAK else Sound.BLOCK_FIRE_AMBIENT, 0.7f, 0.75f + round * 0.15f)
        }
    }

    private fun spawnHitEffect(target: LivingEntity, vulnerable: Boolean) {
        val loc = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        target.world.spawnParticle(Particle.FLAME, loc, 10, 0.28, 0.35, 0.28, 0.03)
        target.world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, if (vulnerable) 14 else 5, 0.28, 0.35, 0.28, 0.025)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun damageTarget(player: Player, target: LivingEntity, amount: Double) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        try {
            FormationMagicDamage.deal(plugin, player, target, amount)
        } finally {
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
        }
    }

    private fun sendLingliActionBar(player: Player, data: PlayerData) {
        val message = "&6☯当前灵力值：&b${String.format("%.1f", data.lingli)} &6/ &b${String.format("%.0f", data.maxLingli)} &6☯"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun format(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }

    companion object {
        const val VULNERABLE_UNTIL_METADATA = "hjh_duhuozhu_vulnerable_until"
        const val VULNERABLE_MULTIPLIER = 1.25
    }
}
