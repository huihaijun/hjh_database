package com.hjh_database.accessory.element.mastery.warrior

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class WaterMasteryWarrior(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 12000L
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageDealt(player: Player, victim: LivingEntity, eData: ElementCrystalData, pData: PlayerData) {
        tryTrigger(player, eData, pData)
    }

    fun onDamageTaken(player: Player, event: org.bukkit.event.entity.EntityDamageEvent, eData: ElementCrystalData, pData: PlayerData) {
        tryTrigger(player, eData, pData)
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
    }

    private fun tryTrigger(player: Player, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.waterPoints < 4) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        cd[uuid] = System.currentTimeMillis() + CD_MS
        trigger(player, pData)
    }

    private fun isOnCd(uuid: UUID): Boolean {
        val end = cd[uuid] ?: return false
        if (System.currentTimeMillis() >= end) {
            cd.remove(uuid)
            return false
        }
        return true
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
               entity !is ArmorStand &&
               !entity.isDead &&
               entity.isValid &&
               entity.scoreboardTags.contains("panling") &&
               entity.scoreboardTags.contains("monster")
    }

    private fun trigger(player: Player, pData: PlayerData) {
        player.sendMessage("§9[水·精进] [潮返] §f已触发")

        // 2秒内依次向周围10格扩散三道水波
        // 第一波 (2 tick 后)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                runExpandingWave(player, pData.maxHealth * 0.8, isThird = false)
            }
        }, 2L)

        // 第二波 (18 tick 后)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                runExpandingWave(player, pData.maxHealth * 0.8, isThird = false)
            }
        }, 18L)

        // 第三波 (34 tick 后)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                runExpandingWave(player, pData.maxHealth * 1.0, isThird = true)
            }
        }, 34L)
    }

    private fun runExpandingWave(player: Player, damage: Double, isThird: Boolean) {
        val center = player.location.clone()
        val world = player.world
        val hitMonsters = HashSet<UUID>()

        world.playSound(center, Sound.ENTITY_GENERIC_SPLASH, 1.0f, if (isThird) 0.8f else 1.2f)

        object : BukkitRunnable() {
            var step = 0
            val maxSteps = 8 // 8 ticks to reach 10 blocks
            val radiusIncrement = 1.25 // 1.25 * 8 = 10 blocks

            override fun run() {
                if (step >= maxSteps) {
                    cancel()
                    return
                }

                val r = (step + 1) * radiusIncrement

                // 绘制当前半径 r 的环形粒子效果 (由内到外扩散)
                val particleCount = (r * 6).toInt().coerceAtLeast(12)
                for (i in 0 until particleCount) {
                    val angle = Math.PI * 2.0 * i / particleCount
                    val point = center.clone().add(cos(angle) * r, 0.2, sin(angle) * r)

                    // 蓝色 Dust 粒子
                    world.spawnParticle(
                        Particle.DUST,
                        point,
                        1,
                        0.05, 0.05, 0.05,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(40, 150, 240), 1.1f)
                    )

                    if (i % 3 == 0) {
                        world.spawnParticle(Particle.SPLASH, point, 2, 0.1, 0.05, 0.1, 0.01)
                    }
                }

                // 索敌与伤害判定：刚好碰触到扩散圆环的怪物
                val nearby = world.getNearbyEntities(center, r + 1.2, 3.0, r + 1.2)
                for (entity in nearby) {
                    if (entity is LivingEntity && isValidTarget(entity) && !hitMonsters.contains(entity.uniqueId)) {
                        val distance = entity.location.distance(center)
                        // 判定是否在水波扩散的当前边缘
                        if (distance >= r - 1.2 && distance <= r + 0.5) {
                            hitMonsters.add(entity.uniqueId)
                            magicDamage(player, entity, damage)

                            if (isThird) {
                                // 第三道水波：小幅击飞 (约 0.5 格高)
                                val currentVel = entity.velocity
                                entity.velocity = currentVel.clone().setY(0.35)
                                entity.world.spawnParticle(Particle.FALLING_WATER, entity.location.add(0.0, 1.0, 0.0), 12, 0.25, 0.25, 0.25, 0.1)
                            } else {
                                // 前两波：轻微减速 3 秒
                                entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, false, true))
                            }
                        }
                    }
                }

                step++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun magicDamage(attacker: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.noDamageTicks = 0
        try {
            target.damage(damage, attacker)
        } finally {
            if (target.hasMetadata("HJH_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            target.noDamageTicks = 0
        }
    }
}
