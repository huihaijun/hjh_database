package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID

class DuSuZhenSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置中的参数
        val baseDamage = zfStr * (config?.getDouble("damage_multiplier", 1.0) ?: 1.0)
        val maxRange = config?.getDouble("range", 10.0) ?: 10.0
        val slowDurationTicks = (config?.getInt("slow_duration", 3) ?: 3) * 20

        // 计算毒针数量：基础3根，每5点阵法强度增加2根，上限10根
        var needleCount = 3 + ((zfStr / 5) * 2).toInt()
        if (needleCount > 10) needleCount = 10

        // 【核心机制】记录本次技能命中的实体UUID。
        // 因为所有的毒针都在这个方法的闭包内运行，它们会共享这个Set，从而实现同批次命中的伤害衰减。
        val hitEntities = mutableSetOf<UUID>()

        // 播放发射音效 (类似吹箭的声音)
        player.world.playSound(player.location, Sound.ENTITY_LLAMA_SPIT, 1.0f, 1.5f)

        // 发射多根毒针
        for (i in 0 until needleCount) {
            val eyeLoc = player.eyeLocation.clone()
            val baseDirection = eyeLoc.direction

            // 为每根毒针生成一个随机的散射偏移量 (范围约在 -0.15 到 0.15 之间)
            val spreadX = (Math.random() - 0.5) * 0.3
            val spreadY = (Math.random() - 0.5) * 0.3
            val spreadZ = (Math.random() - 0.5) * 0.3

            val needleDirection = baseDirection.add(Vector(spreadX, spreadY, spreadZ)).normalize()

            // 每根毒针独立的飞行任务
            object : BukkitRunnable() {
                var distanceTraveled = 0.0
                val currentLoc = eyeLoc.clone()
                val step = 0.5 // 每 tick 飞行 0.5 格（飞行速度）

                override fun run() {
                    // 超出最大射程，毒针消失
                    if (distanceTraveled >= maxRange) {
                        cancel()
                        return
                    }

                    // 毒针向前飞行
                    currentLoc.add(needleDirection.clone().multiply(step))
                    distanceTraveled += step

                    // 绘制毒针的粒子特效 (绿色粉末代表毒素，暴击粒子代表锐利的针)
                    player.world.spawnParticle(Particle.DUST, currentLoc, 2, 0.0, 0.0, 0.0, Particle.DustOptions(Color.LIME, 0.6f))
                    player.world.spawnParticle(Particle.CRIT, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)

                    // 碰撞检测：检查是否碰到方块
                    if (currentLoc.block.type.isSolid) {
                        cancel() // 打中墙壁消失
                        player.world.spawnParticle(Particle.ITEM_SLIME, currentLoc, 5, 0.1, 0.1, 0.1, 0.0)
                        return
                    }

                    // 碰撞检测：检查是否命中怪物 (检测半径0.5格)
                    val nearby = player.world.getNearbyEntities(currentLoc, 0.5, 0.5, 0.5)
                    for (entity in nearby) {
                        if (entity !== player && entity is LivingEntity && entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {

                            // 命中！取消这根毒针的飞行
                            cancel()

                            // 播放命中音效与爆浆特效
                            player.world.playSound(currentLoc, Sound.ENTITY_SPIDER_STEP, 1.0f, 1.5f)
                            player.world.spawnParticle(Particle.ITEM_SLIME, currentLoc, 10, 0.2, 0.2, 0.2, 0.0)

                            // 【伤害衰减计算】
                            val actualDamage = if (hitEntities.contains(entity.uniqueId)) {
                                baseDamage * 0.5 // 后续毒针：伤害衰减至 50%
                            } else {
                                hitEntities.add(entity.uniqueId) // 记录第一根毒针命中
                                baseDamage // 第一根毒针：100% 满伤害
                            }

                            // 取消无敌帧，施加真实魔法伤害
                            plugin.medicalSpellManager.applyMedicalDamage(player, entity, actualDamage, "dusuzhen")

                            // 施加轻微减速效果 (Slowness I, 持续3秒)
                            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowDurationTicks, 0))

                            // 打中一个目标就消失，不穿透
                            return
                        }
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L) // 0延迟，每 tick 执行一次
        }

        return true
    }
}
