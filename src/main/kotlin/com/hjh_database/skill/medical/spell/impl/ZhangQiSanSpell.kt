package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class ZhangQiSanSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        // 读取配置参数
        val radius = config?.getDouble("radius", 6.0) ?: 6.0
        val speedDuration = config?.getInt("speed_duration", 10) ?: 10
        val loseTargetDuration = config?.getInt("lose_target_duration", 2) ?: 2
        val poisonDuration = config?.getInt("poison_duration", 5) ?: 5
        val poisonRatio = config?.getDouble("poison_ratio", 0.2) ?: 0.2
        val poisonMaxDamage = config?.getDouble("poison_max_damage", 150.0) ?: 150.0

        val center = player.location

        // 施法音效与大范围的毒气粒子爆发
        player.world.playSound(center, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.0f, 0.5f)
        player.world.playSound(center, Sound.BLOCK_BREWING_STAND_BREW, 1.0f, 1.5f)
        player.world.spawnParticle(Particle.WITCH, center.clone().add(0.0, 1.0, 0.0), 100, radius / 2, 1.0, radius / 2, 0.05)
        player.world.spawnParticle(Particle.SMOKE, center.clone().add(0.0, 1.0, 0.0), 50, radius / 2, 1.0, radius / 2, 0.02)

        // 获取范围内的所有实体
        val nearbyEntities = player.world.getNearbyEntities(center, radius, radius, radius)

        val affectedMobsForAggro = mutableListOf<Mob>()
        val affectedMonstersForPoison = mutableListOf<LivingEntity>()

        for (entity in nearbyEntities) {
            // 友军判定：玩家，施加速度 II
            if (entity is Player && entity.location.distance(center) <= radius) {
                entity.addPotionEffect(PotionEffect(PotionEffectType.SPEED, speedDuration * 20, 1, false, false, true))
                entity.world.spawnParticle(Particle.HAPPY_VILLAGER, entity.location.clone().add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.0)
            }
            // 敌军判定：盘灵怪物
            else if (entity is LivingEntity && entity.location.distance(center) <= radius) {
                val tags = entity.scoreboardTags
                if (tags.contains("panling") && tags.contains("monster")) {
                    affectedMonstersForPoison.add(entity)

                    // 丢失索敌判定：非副本BOSS，且必须是原版Mob类型(拥有target属性)
                    if (!tags.contains("instance_boss") && entity is Mob) {
                        affectedMobsForAggro.add(entity)
                    }
                }
            }
        }

        // 施法者自己即使不在遍历结果里也应获得加速
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, speedDuration * 20, 1, false, false, true))

        // --- 1. 持续丢失索敌任务 ---
        // 为了防止怪物立刻重新锁定目标，我们需要在2秒内高频(每4tick)清除一次它们的仇恨
        val aggroClearTimes = (loseTargetDuration * 20) / 4
        if (affectedMobsForAggro.isNotEmpty()) {
            object : BukkitRunnable() {
                var count = 0
                override fun run() {
                    if (count >= aggroClearTimes) {
                        cancel()
                        return
                    }
                    for (mob in affectedMobsForAggro) {
                        if (!mob.isDead) mob.target = null
                    }
                    count++
                }
            }.runTaskTimer(plugin, 0L, 4L)
        }

        // --- 2. 持续中毒扣血任务 ---
        // 每秒(20tick)执行一次，持续5秒
        if (affectedMonstersForPoison.isNotEmpty()) {
            object : BukkitRunnable() {
                var ticks = 0
                override fun run() {
                    if (ticks >= poisonDuration) {
                        cancel()
                        return
                    }
                    for (monster in affectedMonstersForPoison) {
                        if (!monster.isDead) {
                            // 计算扣血量：当前生命值的 20%，且不超过 150
                            val damage = (monster.health * poisonRatio).coerceAtMost(poisonMaxDamage)

                            // 取消无敌帧，施加真实魔法伤害
                            monster.noDamageTicks = 0
                            monster.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
                            monster.damage(damage, player)

                            // 播放怪物中毒的专属粒子
                            monster.world.spawnParticle(Particle.ITEM_SLIME, monster.location.clone().add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3, 0.0)
                        }
                    }
                    ticks++
                }
            }.runTaskTimer(plugin, 20L, 20L)
        }

        return true
    }
}