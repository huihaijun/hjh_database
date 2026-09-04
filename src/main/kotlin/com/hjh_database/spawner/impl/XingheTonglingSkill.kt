package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/** 星河统领专属技能：在脚下召唤星团，预警三秒后横向击退附近玩家。 */
class XingheTonglingSkill(
    private val plugin: Hjh_database,
    private val boss: LivingEntity
) {
    init {
        scheduleCast(INITIAL_DELAY_TICKS)
    }

    private fun scheduleCast(delay: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (!isBossActive()) return
                startStarCluster()
            }
        }.runTaskLater(plugin, delay)
    }

    private fun startStarCluster() {
        nearbyDungeonPlayers(WARNING_RADIUS).forEach { player ->
            player.sendMessage("§c星河统领正在凝聚星团，三秒后将大幅击退周围玩家，立即远离！")
            player.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 0.65f)
        }
        boss.world.playSound(boss.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.75f)

        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!isBossActive()) {
                    cancel()
                    return
                }
                drawWarning(ticks)
                ticks++
                if (ticks < CHANNEL_TICKS) return

                explode()
                scheduleCast(COOLDOWN_TICKS)
                cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun drawWarning(tick: Int) {
        val center = boss.location.clone().add(0.0, 0.15, 0.0)
        val pulse = 1.8 + (tick % 20) / 20.0 * 4.2
        repeat(32) { index ->
            val angle = Math.PI * 2.0 * index / 32.0
            val point = center.clone().add(cos(angle) * pulse, 0.0, sin(angle) * pulse)
            boss.world.spawnParticle(
                Particle.DUST,
                point,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                Particle.DustOptions(Color.fromRGB(120, 190, 255), 1.25f)
            )
        }
        boss.world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, 0.8, 0.0), 4, 0.45, 0.65, 0.45, 0.03)
    }

    private fun explode() {
        val center = boss.location.clone().add(0.0, 0.8, 0.0)
        boss.world.spawnParticle(Particle.FLASH, center, 2, 0.0, 0.0, 0.0, 0.0)
        boss.world.spawnParticle(Particle.END_ROD, center, 90, 2.7, 1.0, 2.7, 0.18)
        boss.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.8f)

        nearbyDungeonPlayers(EXPLOSION_RADIUS).forEach { player ->
            val direction = player.location.toVector().subtract(boss.location.toVector()).setY(0.0)
            val horizontal = if (direction.lengthSquared() < 0.01) {
                Vector(KNOCKBACK_STRENGTH, KNOCKBACK_Y, 0.0)
            } else {
                direction.normalize().multiply(KNOCKBACK_STRENGTH).setY(KNOCKBACK_Y)
            }
            player.velocity = horizontal
        }
    }

    private fun nearbyDungeonPlayers(radius: Double): List<Player> = boss.world
        .getNearbyEntities(boss.location, radius, radius, radius)
        .filterIsInstance<Player>()
        .filter {
            !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE &&
                it.scoreboardTags.contains(QIXI_PLAYER_TAG)
        }

    private fun isBossActive(): Boolean = boss.isValid && !boss.isDead

    private companion object {
        const val QIXI_PLAYER_TAG = "qixi_queqiao_player"
        const val WARNING_RADIUS = 15.0
        const val EXPLOSION_RADIUS = 6.0
        const val KNOCKBACK_STRENGTH = 3.0
        const val KNOCKBACK_Y = 0.1
        const val CHANNEL_TICKS = 60
        const val INITIAL_DELAY_TICKS = 140L
        const val COOLDOWN_TICKS = 140L
    }
}
