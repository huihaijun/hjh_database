package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.EnhancedElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class EnhancedWoodSkill(private val plugin: Hjh_database) : EnhancedElementSkill {
    override fun cast(player: Player, data: PlayerData): Boolean {
        val target = findTarget(player, 10.0) ?: return false
        val baseDamage = data.zfStr * 2.25
        val damage = baseDamage * plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
        val startedAt = System.currentTimeMillis()

        broadcastOriginMessage(player, "§a", "木元素", "魂灵契约")
        player.world.playSound(player.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.75f)
        player.world.playSound(target.location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.45f, 1.6f)

        object : BukkitRunnable() {
            var seconds = 0

            override fun run() {
                if (!player.isOnline || !target.isValid || target.isDead) {
                    val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000.0).toInt().coerceIn(0, 10)
                    plugin.elementZfManager.reduceCooldown(player, "WOOD", (10 - elapsedSeconds).toDouble())
                    cancel()
                    return
                }
                if (seconds >= 10) {
                    cancel()
                    return
                }

                target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false, true))
                enhancedMagicDamage(plugin, player, target, damage, FormationElement.WOOD)
                val maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                // 木阵回流只增幅伤害，持续回血仍使用未增幅的原始伤害快照。
                player.health = min(maxHealth, player.health + baseDamage * 0.05)
                playSoulLink(player, target)
                playContractHalo(target)
                seconds++
            }
        }.runTaskTimer(plugin, 0L, 20L)

        return true
    }

    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val loc = player.location
        val dir = loc.direction.normalize()
        return player.world.getNearbyEntities(loc, range, range, range).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isEnhancedMonster(it) }
            .filter {
                val toEntity = it.location.toVector().subtract(loc.toVector())
                toEntity.lengthSquared() <= range * range && dir.dot(toEntity.normalize()) > 0.5
            }
            .minByOrNull { it.location.distanceSquared(loc) }
    }

    private fun playSoulLink(player: Player, target: LivingEntity) {
        val start = target.location.add(0.0, target.height * 0.55, 0.0)
        val end = player.location.add(0.0, 1.0, 0.0)
        val distance = start.distance(end)
        if (distance <= 0.1) return
        val direction = end.toVector().subtract(start.toVector()).normalize()
        var d = 0.0
        var index = 0
        while (d < distance) {
            val point = start.clone().add(direction.clone().multiply(d))
            player.world.spawnParticle(Particle.DUST, point, 1, 0.025, 0.025, 0.025, 0.0, Particle.DustOptions(Color.fromRGB(42, 155, 92), 0.85f))
            player.world.spawnParticle(Particle.SCULK_SOUL, point, 1, 0.03, 0.03, 0.03, 0.006)
            if (index % 3 == 0) {
                player.world.spawnParticle(Particle.BLOCK, point, 1, 0.02, 0.02, 0.02, 0.0, Material.CHAIN.createBlockData())
                player.world.spawnParticle(Particle.ENCHANT, point, 1, 0.08, 0.08, 0.08, 0.0)
            }
            d += 0.35
            index++
        }
    }

    private fun playContractHalo(target: LivingEntity) {
        val base = target.location.add(0.0, 0.15, 0.0)
        // Draw a double intertwined halo
        for (i in 0 until 16) {
            val angle = Math.PI * 2.0 * i / 16.0
            val loc1 = base.clone().add(cos(angle) * 1.3, sin(angle * 2.0) * 0.2, sin(angle) * 1.3)
            val loc2 = base.clone().add(cos(angle) * 1.3, -sin(angle * 2.0) * 0.2, sin(angle) * 1.3)
            target.world.spawnParticle(Particle.COMPOSTER, loc1, 1, 0.0, 0.0, 0.0, 0.0)
            target.world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc2, 1, 0.0, 0.0, 0.0, 0.02)
        }
        // Central burst
        target.world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, base.clone().add(0.0, 1.0, 0.0), 10, 0.5, 1.0, 0.5, 0.02)
    }
}
