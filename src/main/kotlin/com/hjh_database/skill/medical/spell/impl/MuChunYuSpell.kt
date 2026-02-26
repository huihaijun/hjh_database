package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.random.Random

class MuChunYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置中的参数，做兜底处理
        val radius = config?.getDouble("radius", 5.0) ?: 5.0
        val durationSeconds = config?.getInt("duration", 5) ?: 5
        val healMultiplier = config?.getDouble("heal_multiplier", 0.5) ?: 0.5

        val healAmount = zfStr * healMultiplier

        // 播放施法音效
        player.world.playSound(player.location, Sound.WEATHER_RAIN, 1.0f, 1.0f)

        // 开启定时任务
        // TPS 优化：以 10 ticks (0.5秒) 为周期运行，粒子每 0.5秒刷一次，治疗每 1秒刷一次
        object : BukkitRunnable() {
            var ticksCount = 0
            val maxTicks = durationSeconds * 20

            override fun run() {
                // 安全检查：如果玩家掉线或死亡，直接停止降雨
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }

                val centerLoc = player.location

                // --- 粒子效果 (每 10 ticks 执行一次) ---
                if (ticksCount % 10 == 0) {
                    spawnRainParticles(centerLoc, radius)
                }

                // --- 治疗效果结算 (每 20 ticks 执行一次，即每秒1次) ---
                if (ticksCount % 20 == 0) {
                    // 播放清脆的治疗提示音
                    player.world.playSound(centerLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f)

                    // 获取范围内的实体 (优化：使用自带的 getNearbyEntities 而不是遍历世界)
                    val nearbyEntities = player.getNearbyEntities(radius, radius, radius)
                    val targets = mutableListOf<Player>()
                    targets.add(player) // 始终包含施法者自己

                    for (entity in nearbyEntities) {
                        // 如果你有专门的组队判定逻辑，可以在这里加 entity.isTeammate(player) 之类的条件
                        if (entity is Player && entity.location.distance(centerLoc) <= radius) {
                            targets.add(entity)
                        }
                    }

                    // 结算治疗
                    for (target in targets) {
                        if (!target.isDead) {
                            val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                            target.health = (target.health + healAmount).coerceAtMost(maxHealth)
                            // 在被治疗的人身上冒几个开心的绿星粒子
                            target.world.spawnParticle(Particle.HAPPY_VILLAGER, target.location.add(0.0, 1.0, 0.0), 3, 0.3, 0.3, 0.3, 0.0)
                        }
                    }
                }

                ticksCount += 10
                if (ticksCount > maxTicks) {
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 10L)

        return true
    }

    private fun spawnRainParticles(center: Location, radius: Double) {
        val world = center.world ?: return
        // 性能优化：根据半径动态计算每次抛洒的粒子数量（5格范围大约生成75个水滴粒子）
        val count = (radius * radius * 3).toInt()

        for (i in 0 until count) {
            val offsetX = Random.nextDouble(-radius, radius)
            val offsetZ = Random.nextDouble(-radius, radius)
            // 确保粒子只在圆形范围内部生成
            if (offsetX * offsetX + offsetZ * offsetZ <= radius * radius) {
                // 在玩家头顶大约 3.0 ~ 4.0 的高度生成水滴下落
                val y = center.y + 3.0 + Random.nextDouble(0.0, 1.0)
                val particleLoc = Location(world, center.x + offsetX, y, center.z + offsetZ)
                world.spawnParticle(Particle.FALLING_WATER, particleLoc, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }
}