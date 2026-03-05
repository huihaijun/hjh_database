package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class NianQiJinSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val range = config?.getDouble("range", 10.0) ?: 10.0
        val damageMultiplier = config?.getDouble("damage_multiplier", 2.5) ?: 2.5
        val stunTicks = config?.getInt("stun_ticks", 10) ?: 10 // 0.5秒 = 10 ticks

        val damage = zfStr * damageMultiplier
        val startLoc = player.eyeLocation
        val direction = startLoc.direction.normalize()

        // 播放极具冲击力的音效
        player.world.playSound(startLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.5f)
        player.world.playSound(startLoc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f)

        val damagedEntities = mutableSetOf<LivingEntity>()

        val step = 0.5
        var distance = 0.0

        while (distance <= range) {
            val currentLoc = startLoc.clone().add(direction.clone().multiply(distance))

            // 绘制气波轨迹
            player.world.spawnParticle(Particle.CRIT, currentLoc, 5, 0.2, 0.2, 0.2, 0.05)
            if (distance % 2.0 < step) {
                player.world.spawnParticle(Particle.SONIC_BOOM, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
            }

            // 遇到坚固方块则气波消散
            if (currentLoc.block.type.isSolid) {
                player.world.spawnParticle(Particle.EXPLOSION, currentLoc, 1)
                break
            }

            val nearby = player.world.getNearbyEntities(currentLoc, 1.5, 1.5, 1.5)
            for (entity in nearby) {
                if (entity is LivingEntity && entity.uniqueId != player.uniqueId && !damagedEntities.contains(entity)) {
                    val tags = entity.scoreboardTags
                    // 【关键修改】必须同时包含 panling 和 monster 标签才视为有效怪物
                    if (tags.contains("panling") && tags.contains("monster")) {
                        damagedEntities.add(entity)

                        // 取消无敌帧，并打上魔法伤害标签
                        entity.noDamageTicks = 0
                        entity.setMetadata("HJH_MAGIC_DAMAGE", org.bukkit.metadata.FixedMetadataValue(plugin, damage))

                        // 造成伤害
                        entity.damage(damage, player)

                        // 播放击中音效与粒子
                        entity.world.playSound(entity.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f)
                        entity.world.spawnParticle(Particle.DAMAGE_INDICATOR, entity.location.clone().add(0.0, 1.0, 0.0), 5, 0.3, 0.3, 0.3, 0.1)

                        // 击晕判定：检查是否带有 instance_boss 的 tag
                        if (!tags.contains("instance_boss")) {
                            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, stunTicks, 10, false, false, true))
                        }
                    }
                }
            }

            distance += step
        }

        return true
    }
}