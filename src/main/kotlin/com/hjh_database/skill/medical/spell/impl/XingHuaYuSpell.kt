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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class XingHuaYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val range = config?.getDouble("range", 10.0) ?: 10.0
        val healMultiplier = config?.getDouble("heal_multiplier", 1.0) ?: 1.0
        val durationSeconds = config?.getInt("duration", 10) ?: 10
        val radius = 2.5 // 5x5 的范围，半径约为 2.5

        val healAmount = zfStr * healMultiplier
        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()

        // 播放施法前摇音效
        player.world.playSound(eyeLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)

        // ================== 第一阶段：发射定位花种 ==================
        object : BukkitRunnable() {
            var distance = 0.0
            val currentLoc = eyeLoc.clone()
            val step = 0.5 // 每 tick 飞行 0.5 格

            override fun run() {
                // 超出最大射程，直接在当前位置展开
                if (distance >= range) {
                    startRain(currentLoc)
                    cancel()
                    return
                }

                currentLoc.add(direction.clone().multiply(step))
                distance += step

                // 飞行时的花瓣拖尾
                player.world.spawnParticle(Particle.CHERRY_LEAVES, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)

                // 碰撞检测：碰到方块
                if (currentLoc.block.type.isSolid) {
                    startRain(currentLoc)
                    cancel()
                    return
                }

                // 碰撞检测：碰到玩家（排除自己）
                val nearby = player.world.getNearbyEntities(currentLoc, 0.5, 0.5, 0.5)
                for (entity in nearby) {
                    if (entity is Player && entity.uniqueId != player.uniqueId) {
                        startRain(currentLoc)
                        cancel()
                        return
                    }
                }
            }

            // ================== 第二阶段：展开杏花雨 ==================
            // ================== 第二阶段：展开杏花雨 ==================
            fun startRain(centerLoc: Location) {
                // 【修改】将降雨中心提升至空中 4 格高，强化“雨水降落”的视觉效果
                val rainCenter = centerLoc.clone().add(0.0, 4.0, 0.0)
                val maxTicks = durationSeconds * 20

                player.world.playSound(rainCenter, Sound.BLOCK_CHERRY_WOOD_FALL, 1.5f, 1.0f)

                object : BukkitRunnable() {
                    var ticks = 0

                    override fun run() {
                        // 【修改】每 5 ticks 生成一次正方形落花粒子
                        if (ticks % 5 == 0) {
                            // 数量从 40 减少至 20
                            // offsetX=2.5, offsetZ=2.5 意味着在一个 5x5 的正方形平面内随机生成
                            player.world.spawnParticle(
                                Particle.CHERRY_LEAVES,
                                rainCenter,
                                20, 2.5, 0.2, 2.5, 0.0
                            )
                        }

                        // 每 20 ticks (1秒) 结算一次治疗
                        if (ticks > 0 && ticks % 20 == 0) {
                            // 【修改】长方体范围判定：X和Z各延伸 2.5 格 (即5x5的正方形)，高度覆盖下方 4.0 格
                            val targets = player.world.getNearbyEntities(centerLoc, 2.5, 4.0, 2.5)
                            for (entity in targets) {
                                if (entity is Player && !entity.isDead) {
                                    val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                                    entity.health = (entity.health + healAmount).coerceAtMost(maxHealth)
                                    // 飘点爱心
                                    entity.world.spawnParticle(Particle.HEART, entity.location.clone().add(0.0, 2.0, 0.0), 1, 0.3, 0.3, 0.3, 0.0)
                                }
                            }
                            // 治愈的清脆滴水声
                            player.world.playSound(centerLoc, Sound.BLOCK_AMETHYST_BLOCK_STEP, 0.5f, 1.5f)
                        }

                        ticks += 5

                        // 持续时间结束，发放饱和度奖励
                        if (ticks >= maxTicks) {
                            val finalTargets = player.world.getNearbyEntities(centerLoc, 2.5, 4.0, 2.5)
                            for (entity in finalTargets) {
                                if (entity is Player && !entity.isDead) {
                                    // 赋予饱和 III
                                    entity.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, 1, 2))
                                }
                            }
                            cancel()
                        }
                    }
                }.runTaskTimer(plugin, 0L, 5L)
            }
        }.runTaskTimer(plugin, 0L, 1L)

        return true
    }
}