package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
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
                plugin.databaseManager.savePlayer(data)
            }
        }

        // 5. 索敌
        val target = getTarget(player, range, searchRadius)

        // 只在有目标时造成伤害
        if (target != null) {
            val baseDamage = data.zfStr
            val finalDamage = baseDamage * damagePercent

            // 法术伤害
            target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
            target.damage(finalDamage, player)
            target.removeMetadata("hjh_magic_damage", plugin)

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
}