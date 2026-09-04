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

class NingQiBaoSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val maxRange = config?.getDouble("max_range", 15.0) ?: 15.0
        val radius = config?.getDouble("radius", 5.0) ?: 5.0
        val baseDamageMult = config?.getDouble("base_damage_multiplier", 2.0) ?: 2.0 // 起始 200%
        val damageIncMult = config?.getDouble("damage_increase_multiplier", 1.0) ?: 1.0 // 每次递增 100%

        // 1. 发射射线寻找凝气中心点 (检测方块与实体，忽略施法者自己)
        val rayTrace = player.world.rayTrace(
            player.eyeLocation,
            player.location.direction,
            maxRange,
            FluidCollisionMode.NEVER,
            true,
            0.5 // 射线粗细，稍微给点判定体积更容易命中怪物
        ) { entity -> entity is LivingEntity && entity.uniqueId != player.uniqueId }

        // 如果撞到东西就取碰撞点，否则取最远距离点
        val center = rayTrace?.hitPosition?.toLocation(player.world) ?: player.eyeLocation.add(player.location.direction.multiply(maxRange))

        // 播放施法前摇音效
        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.5f)

        // 2. 定义爆炸逻辑函数 (方便后续延时调用)
        fun triggerExplosion(damageMultiplier: Double, isFinal: Boolean) {
            val damage = zfStr * damageMultiplier

            // 播放爆炸音效与粒子
            center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, if (isFinal) 0.5f else 1.2f)
            // 最后一击音效更加沉闷震撼
            if (isFinal) {
                center.world.playSound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.0f, 0.8f)
            }

            // 爆炸粒子：使用原版大型爆炸粒子与紫色的女巫魔法粒子混合，模拟“凝气”的感觉
            center.world.spawnParticle(if (isFinal) Particle.EXPLOSION else Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0)
            center.world.spawnParticle(Particle.WITCH, center, 50, radius / 2, radius / 2, radius / 2, 0.1)
            center.world.spawnParticle(Particle.SMOKE, center, 20, radius / 2, radius / 2, radius / 2, 0.05)

            // 寻找范围内的怪物造成伤害
            val nearbyEntities = center.world.getNearbyEntities(center, radius, radius, radius)
            for (entity in nearbyEntities) {
                if (entity is LivingEntity && entity.uniqueId != player.uniqueId) {
                    val tags = entity.scoreboardTags
                    // 确保是盘灵怪物且在球形半径内
                    if (tags.contains("panling") && tags.contains("monster") && entity.location.distance(center) <= radius) {

                        // 取消无敌帧
                        plugin.medicalSpellManager.applyMedicalDamage(player, entity, damage, "ningqibao")
                    }
                }
            }
        }

        // 3. 触发连环爆破
        // 第 0 秒：第一段伤害 (200%)
        triggerExplosion(baseDamageMult, false)

        // 第 1 秒 (20 tick)：第二段伤害 (300%)
        object : BukkitRunnable() {
            override fun run() {
                triggerExplosion(baseDamageMult + damageIncMult, false)
            }
        }.runTaskLater(plugin, 20L)

        // 第 2 秒 (40 tick)：第三段伤害 (400%)
        object : BukkitRunnable() {
            override fun run() {
                triggerExplosion(baseDamageMult + damageIncMult * 2, true)
            }
        }.runTaskLater(plugin, 40L)

        return true
    }
}
