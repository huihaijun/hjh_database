package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.FluidCollisionMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class BaZhenJueSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val maxCastDistance = config?.getDouble("cast_distance", 10.0) ?: 10.0
        val radius = config?.getDouble("radius", 5.0) ?: 5.0
        val damageMultiplier = config?.getDouble("damage_multiplier", 3.5) ?: 3.5
        val healMultiplier = config?.getDouble("heal_multiplier", 1.0) ?: 1.0
        val durationSeconds = config?.getInt("duration", 10) ?: 10
        val healIntervalTicks = config?.getInt("heal_interval", 40) ?: 40 // 2秒 = 40 ticks

        val damage = zfStr * damageMultiplier
        val healAmount = zfStr * healMultiplier
        val durationTicks = durationSeconds * 20

        // 1. 计算施法中心点 (射线检测准星指向 10 格内，若无方块则取最大距离处)
        val rayTrace = player.world.rayTraceBlocks(player.eyeLocation, player.location.direction, maxCastDistance, FluidCollisionMode.NEVER, true)
        val center = rayTrace?.hitPosition?.toLocation(player.world) ?: player.eyeLocation.add(player.location.direction.multiply(maxCastDistance))

        // 播放阵法展开的震撼音效
        center.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.5f)
        center.world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 1.0f)

        // 瞬间爆发出一圈青色法阵粒子
        for (i in 0..360 step 10) {
            val radians = Math.toRadians(i.toDouble())
            val x = radius * cos(radians)
            val z = radius * sin(radians)
            center.world.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(x, 0.5, z), 2, 0.1, 0.1, 0.1, 0.0)
        }

        // 2. 初始爆发：寻找范围内的怪物造成伤害并击飞
        val nearbyInitial = center.world.getNearbyEntities(center, radius, radius, radius)
        for (entity in nearbyInitial) {
            if (entity is LivingEntity && entity.uniqueId != player.uniqueId) {
                val tags = entity.scoreboardTags
                if (tags.contains("panling") && tags.contains("monster")) {

                    // 造成魔法伤害
                    entity.noDamageTicks = 0
                    entity.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
                    entity.damage(damage, player)

                    // 判断是否为副本BOSS，非BOSS才应用击飞逻辑
                    if (!tags.contains("instance_boss")) {
                        var kbVector = entity.location.toVector().subtract(center.toVector())
                        // 防止实体和中心点完全重合导致计算出 NaN (Not a Number)
                        if (kbVector.lengthSquared() < 0.001) {
                            kbVector = Vector(0.0, 0.8, 0.0)
                        } else {
                            // 向外击退 1.5 的力度，向上击飞 0.8 的力度
                            kbVector = kbVector.normalize().multiply(1.5).setY(0.8)
                        }
                        entity.velocity = kbVector
                    }
                }
            }
        }

        // 3. 开启八阵图的持续任务 (粒子渲染与定期治疗)
        object : BukkitRunnable() {
            var ticksPassed = 0

            override fun run() {
                // 时间到，阵法消散
                if (ticksPassed > durationTicks) {
                    center.world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
                    cancel()
                    return
                }

                // --- 每 5 ticks 渲染一次法阵边缘粒子 ---
                if (ticksPassed % 5 == 0) {
                    for (i in 0..360 step 30) {
                        val radians = Math.toRadians(i.toDouble())
                        val x = radius * cos(radians)
                        val z = radius * sin(radians)
                        // 使用附魔台跳动的符文代表八阵诀的深奥
                        center.world.spawnParticle(Particle.ENCHANT, center.clone().add(x, 0.1, z), 1, 0.1, 0.5, 0.1, 0.0)
                        // 使用微弱的白色粒子标定边界
                        center.world.spawnParticle(Particle.END_ROD, center.clone().add(x, 0.1, z), 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }

                // --- 每 40 ticks (2秒) 触发一次阵内友军治疗 ---
                if (ticksPassed > 0 && ticksPassed % healIntervalTicks == 0) {
                    val currentNearby = center.world.getNearbyEntities(center, radius, radius, radius).filterIsInstance<Player>()
                    var healedAny = false

                    for (target in currentNearby) {
                        if (!target.isDead && target.location.distance(center) <= radius) {
                            plugin.medicalSpellManager.applyMedicalHeal(player, target, healAmount, "bazhenjue")

                            // 阵法恢复的专属粒子 (海豚飞溅水花，显得温和)
                            target.world.spawnParticle(Particle.SPLASH, target.location.clone().add(0.0, 1.0, 0.0), 20, 0.4, 0.4, 0.4, 0.0)
                            target.world.spawnParticle(Particle.HEART, target.location.clone().add(0.0, 1.5, 0.0), 1, 0.2, 0.2, 0.2, 0.0)
                            healedAny = true
                        }
                    }

                    if (healedAny) {
                        center.world.playSound(center, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.8f)
                    }
                }

                ticksPassed += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)

        return true
    }
}
