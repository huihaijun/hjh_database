package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// 【改动1】继承 AbstractElementSkill
class FireSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】方法名从 cast 改为 onCast (参数依然是你原本需要的那些)
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {

        // 此时，父类已经帮你处理好了“不扣法宝”和“回流判定”了！

        val damagePercent = safeConfig.getDouble("$path.damage_percent", 3.0)
        val range = safeConfig.getDouble("$path.range", 10.0)
        val searchRadius = safeConfig.getDouble("$path.search_radius", 4.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // 3. 获取数据
        val data = plugin.playerManager.getData(player.uniqueId)!!

        // 4. 增加灵力
        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.queuePlayerSave(data)
            }
        }

        // 5. 索敌
        val target = getTarget(player, range, searchRadius)

        // 只在有目标时造成伤害
        if (target != null) {
            val baseDamage = data.zfStr * plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
            val finalDamage = baseDamage * damagePercent

            formationMagicDamage(plugin, player, target, finalDamage, FormationElement.FIRE)

            if (level >= 3 && target.isValid && !target.isDead) {
                val markDuration = safeConfig.getDouble("tier3.mark_duration", 5.0)
                plugin.elementZfManager.tierEffects.applyFireMark(
                    target,
                    player,
                    baseDamage,
                    safeConfig.getDouble("tier3.explosion_radius", 3.0),
                    safeConfig.getDouble("tier3.explosion_damage_percent", 1.0),
                    (markDuration * 1000.0).toLong()
                )
            }

            if (level >= 5) {
                val sparkRadius = safeConfig.getDouble("tier5.spark_radius", 10.0)
                val sparkCount = safeConfig.getInt("tier5.spark_count", 3).coerceAtLeast(0)
                val sparkDamage = baseDamage * safeConfig.getDouble("tier5.spark_damage_percent", 1.25)
                launchSparks(player, target, sparkRadius, sparkCount, sparkDamage)
            }

            // 只有打中人才播放特效
            playBurnEffect(target)
        }

        return true
    }

    // ============================================
    // 下方的 getTarget 和 playBurnEffect 完完全全保持你源码的原样！
    // ============================================
    private fun getTarget(player: Player, range: Double, searchRadius: Double): LivingEntity? {
        val eye = player.eyeLocation
        val direction = eye.direction

        // 射线检测
        val result = player.world.rayTrace(
            eye, direction, range,
            FluidCollisionMode.NEVER, true, 0.5
        ) { entity ->
            entity != player &&
                    entity.scoreboardTags.contains("panling") &&
                    entity.scoreboardTags.contains("monster")
        }

        val hitLocation: Location

        if (result != null && result.hitEntity is LivingEntity) {
            return result.hitEntity as LivingEntity
        }
        else if (result != null && result.hitBlock != null) {
            hitLocation = result.hitPosition.toLocation(player.world)
        }
        else {
            hitLocation = eye.clone().add(direction.multiply(range))
        }

        val nearby = hitLocation.world!!.getNearbyEntities(hitLocation, searchRadius, searchRadius, searchRadius)

        return nearby.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player }
            .filter { e ->
                e.scoreboardTags.contains("panling") && e.scoreboardTags.contains("monster")
            }
            .minByOrNull { e -> e.location.distance(hitLocation) }
    }

    private fun playBurnEffect(target: LivingEntity) {
        val loc = target.location
        val world = loc.world ?: return
        val height = target.height

        world.spawnParticle(Particle.LAVA, loc, 10, 0.5, 0.1, 0.5, 0.1)
        world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f)

        object : BukkitRunnable() {
            var y = 0.0
            var angle = 0.0

            override fun run() {
                if (y > height + 0.5) {
                    this.cancel()
                    world.spawnParticle(
                        Particle.SMOKE,
                        loc.clone().add(0.0, height, 0.0),
                        5,
                        0.2,
                        0.2,
                        0.2,
                        0.05
                    )
                    return
                }

                for (i in 0 until 2) {
                    val rad = angle + (i * Math.PI)
                    val x = 0.6 * cos(rad)
                    val z = 0.6 * sin(rad)

                    val particleLoc = loc.clone().add(x, y, z)
                    world.spawnParticle(Particle.FLAME, particleLoc, 1, 0.0, 0.0, 0.0, 0.02)
                }
                y += 0.2
                angle += 0.5
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun launchSparks(
        caster: Player,
        primaryTarget: LivingEntity,
        radius: Double,
        count: Int,
        damage: Double
    ) {
        if (count <= 0) return
        val origin = primaryTarget.location.clone().add(0.0, primaryTarget.height * 0.55, 0.0)
        val radiusSquared = radius * radius
        val selected = primaryTarget.world.getNearbyEntities(origin, radius, radius, radius).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != primaryTarget.uniqueId && ElementFormationTierEffects.isFormationMonster(it) }
            .filter { it.location.distanceSquared(origin) <= radiusSquared }
            .sortedBy { it.location.distanceSquared(origin) }
            .take(count)
            .toList()

        for (index in 0 until count) {
            val target = selected.getOrNull(index)
            if (target == null) {
                launchDissipatingSpark(origin, index, count)
            } else {
                launchTargetedSpark(caster, origin, target.uniqueId, damage)
            }
        }
    }

    private fun launchTargetedSpark(caster: Player, origin: Location, targetId: java.util.UUID, damage: Double) {
        object : BukkitRunnable() {
            var step = 0
            var current = origin.clone()

            override fun run() {
                val target = Bukkit.getEntity(targetId) as? LivingEntity
                if (target == null || !target.isValid || target.isDead || !caster.isOnline || caster.world != target.world) {
                    current.world?.spawnParticle(Particle.SMOKE, current, 5, 0.12, 0.12, 0.12, 0.02)
                    cancel()
                    return
                }

                val end = target.location.add(0.0, target.height * 0.55, 0.0)
                val offset = end.toVector().subtract(current.toVector())
                if (offset.lengthSquared() <= 0.64 || step >= 14) {
                    target.world.spawnParticle(Particle.FLAME, end, 12, 0.25, 0.35, 0.25, 0.035)
                    target.world.playSound(end, Sound.ENTITY_BLAZE_SHOOT, 0.55f, 1.7f)
                    formationMagicDamage(plugin, caster, target, damage, FormationElement.FIRE)
                    cancel()
                    return
                }

                current.add(offset.normalize().multiply(0.9))
                target.world.spawnParticle(Particle.FLAME, current, 3, 0.06, 0.06, 0.06, 0.01)
                target.world.spawnParticle(Particle.ELECTRIC_SPARK, current, 1, 0.03, 0.03, 0.03, 0.02)
                step++
            }
        }.runTaskTimer(plugin, 2L, 1L)
    }

    private fun launchDissipatingSpark(origin: Location, index: Int, total: Int) {
        val angle = (Math.PI * 2.0 / total.coerceAtLeast(1)) * index + Math.random() * 0.35
        val direction = org.bukkit.util.Vector(cos(angle), 0.25, sin(angle)).normalize().multiply(0.35)
        object : BukkitRunnable() {
            var ticks = 0
            val current = origin.clone()

            override fun run() {
                if (ticks >= 8) {
                    current.world?.spawnParticle(Particle.SMOKE, current, 4, 0.1, 0.1, 0.1, 0.01)
                    cancel()
                    return
                }
                current.add(direction)
                current.world?.spawnParticle(Particle.SMALL_FLAME, current, 2, 0.04, 0.04, 0.04, 0.0)
                ticks++
            }
        }.runTaskTimer(plugin, 2L, 1L)
    }
}
