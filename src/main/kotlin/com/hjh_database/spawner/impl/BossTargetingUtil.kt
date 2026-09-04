package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

object BossTargetingUtil {

    fun start(
        plugin: Hjh_database,
        boss: LivingEntity,
        radius: Double = 48.0,
        chaseSpeed: Double = 0.18,
        minChaseDistance: Double = 8.0,
        canChase: () -> Boolean = { true }
    ) {
        val mob = boss as? Mob ?: return
        boss.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = radius

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                val target = boss.world.players
                    .filter { it.isValidTarget() }
                    .filter { it.location.distanceSquared(boss.location) <= radius * radius }
                    .minByOrNull { it.location.distanceSquared(boss.location) }
                    ?: return

                val current = mob.target
                if (current !is Player || !current.isValidTarget() || current.location.distanceSquared(boss.location) > radius * radius) {
                    mob.target = target
                }

                if (canChase() && mob.hasAI() && target.location.distanceSquared(boss.location) > minChaseDistance * minChaseDistance) {
                    val direction = target.location.toVector().subtract(boss.location.toVector()).setY(0.0)
                    if (direction.lengthSquared() > 0.01) {
                        val velocity = direction.normalize().multiply(chaseSpeed)
                        boss.velocity = boss.velocity.setX(velocity.x).setZ(velocity.z)
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 10L)
    }

    private fun Player.isValidTarget(): Boolean {
        return isOnline && !isDead && gameMode != GameMode.SPECTATOR && gameMode != GameMode.CREATIVE
    }
}
