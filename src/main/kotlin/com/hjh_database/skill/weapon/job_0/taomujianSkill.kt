package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class taomujianSkill : WeaponSkill {

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        // 1. 读取配置参数
        val radius = config.getDouble("radius", 5.0)
        val slowDuration = config.getDouble("duration", 5.0)       // 减速总时长 (秒)
        val stunDuration = config.getDouble("stun_duration", 1.0)  // 亡灵晕眩时长 (秒)
        val slowLevel = config.getInt("slowness_level", 2) - 1     // 减速等级 (配置填2对应Effect等级1)

        // 2. 视觉效果
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f)

        // 播放太极/剑气扩散效果 (WAX_ON 看起来像剑光)
        for (i in 0 until 360 step 15) {
            val rad = Math.toRadians(i.toDouble())
            val x = Math.cos(rad) * radius
            val z = Math.sin(rad) * radius
            player.world.spawnParticle(Particle.WAX_ON, loc.clone().add(x, 0.5, z), 1, 0.0, 0.0, 0.0, 0.0)
        }

        // 3. 获取目标并应用效果
        // y轴范围设为 3.0 防止打不到脚下的怪
        val nearby = player.getNearbyEntities(radius, 3.0, radius)

        // 获取插件实例用于调度任务 (防止晕眩结束后没补上减速)
        val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

        for (entity in nearby) {
            // 排除自己，且必须是活体
            if (entity is LivingEntity && entity != player) {

                // ★★★ 核心修改：必须同时拥有 panling 和 monster 标签 ★★★
                if (entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {

                    val isUndead = org.bukkit.Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(entity.type)

                    if (isUndead) {
                        // === 亡灵生物逻辑：晕眩 + 之后补减速 ===
                        // 1. 立即给予晕眩 (Slowness 255 + Blindness)
                        // Slowness 255 会让实体完全无法移动
                        entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (stunDuration * 20).toInt(), 255))
                        entity.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, (stunDuration * 20).toInt(), 1))

                        // 播放亡灵受击特效 (灵魂粒子)
                        entity.world.playSound(entity.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.5f, 1.5f)
                        entity.world.spawnParticle(Particle.SOUL, entity.location.add(0.0, 1.5, 0.0), 10, 0.2, 0.2, 0.2, 0.0)

                        // 2. 晕眩结束后，补上剩余的减速时间
                        // 例如：总减速5秒，晕眩1秒。则晕眩结束后再给4秒的普通减速
                        val remainingTime = slowDuration - stunDuration
                        if (remainingTime > 0) {
                            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                                // 再次检查实体是否存活
                                if (entity.isValid && !entity.isDead) {
                                    entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (remainingTime * 20).toInt(), slowLevel))
                                }
                            }, (stunDuration * 20).toLong())
                        }

                    } else {
                        // === 普通生物逻辑：直接给予全程减速 ===
                        entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (slowDuration * 20).toInt(), slowLevel))
                    }
                }
            }
        }

        return true
    }
}