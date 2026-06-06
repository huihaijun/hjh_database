package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue

class TuiDiSpell(private val plugin: Hjh_database) : MedicalSpell {

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        // 1. 读取配置参数
        // 使用 ?: 复刻 Java 的三元运算符逻辑 (config != null ? val : default)
        val range = config?.getDouble("range", 4.0) ?: 4.0
        val knockback = config?.getDouble("knockback_strength", 1.2) ?: 1.2
        val damageRatio = config?.getDouble("damage_ratio", 1.5) ?: 1.5

        // 2. 计算伤害 (基于阵法强度)
        val zfStr = data.zfStr // 确保这里获取到了 80
        var damage = zfStr * damageRatio
        if (damage < 1) damage = 1.0

        // 3. 特效
        val loc = player.location
        // 使用 !! 断言 world 非空，防止平台类型报错
        player.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f)
        player.world.playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.5f)
        spawnShockwaveParticles(player, range)

        // 4. 索敌与处理
        var hitAny = false
        // range 必须是 Double
        for (entity in player.getNearbyEntities(range, range, range)) {

            // =========================================================
            // 【修复问题 3】 索敌逻辑：必须同时拥有 panling 和 monster 标签
            // =========================================================
            if (entity !is LivingEntity) continue
            if (entity === player) continue // 排除自己 (引用比较)

            val hasPanling = entity.scoreboardTags.contains("panling")
            val hasMonster = entity.scoreboardTags.contains("monster")

            if (hasPanling && hasMonster) {
                val mob = entity
                // Kotlin 智能转换: 上面判断了 entity is LivingEntity，这里 mob 已自动视为 LivingEntity

                // 距离校验
                if (mob.location.distance(loc) > range) continue

                // =========================================================
                // 【修复问题 2】 伤害注入
                // =========================================================
                // 1. 给怪物打上伤害标记
                mob.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
                // 2. 触发伤害事件 (参数填 0.0，Kotlin 对数字类型敏感)
                // 必须传 player 作为 attacker，否则不算玩家击杀
                mob.damage(0.0, player)
                // =========================================================

                // B. 施加击退
                var dir = mob.location.toVector().subtract(loc.toVector())
                if (dir.lengthSquared() == 0.0) dir = player.location.direction

                // setY 返回 Vector，Kotlin 中使用属性赋值 velocity
                mob.velocity = dir.normalize().multiply(knockback).setY(0.4)
                // world 肯定存在，使用 !!
                mob.world.spawnParticle(Particle.CRIT, mob.location.add(0.0, 1.0, 0.0), 5)

                hitAny = true
            }
        }

        return true
    }

    private fun spawnShockwaveParticles(p: Player, range: Double) {
        val center = p.location.add(0.0, 0.5, 0.0)
        // Java 的 for (double r = 0.5; r < range; r += 0.5)
        // Kotlin 的 for 循环不支持 double step，改用 while 保持逻辑完全一致
        var r = 0.5
        while (r < range) {
            // for (int degree = 0; degree < 360; degree += 20)
            for (degree in 0 until 360 step 20) {
                val radians = Math.toRadians(degree.toDouble())
                val x = Math.cos(radians) * r
                val z = Math.sin(radians) * r
                // 使用 !! 确保 world 非空
                center.world!!.spawnParticle(
                    Particle.CLOUD,
                    center.clone().add(x, 0.0, z),
                    1, 0.0, 0.0, 0.0, 0.05
                )
            }
            r += 0.5
        }
    }
}
