package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.min

// 【改动1】继承 AbstractElementSkill，去掉 private val
class WaterSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】将 cast 改为 onCast
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {

        // 1. 读取配置 (父类已处理好了非空和路径检查)
        val damagePercent = safeConfig.getDouble("$path.damage_percent", 1.0)
        val range = safeConfig.getDouble("$path.range", 4.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        val slowDurationSeconds = safeConfig.getDouble("$path.slow_duration", 2.0)
        val slowAmplifier = safeConfig.getInt("$path.slow_amplifier", 1)
        val castOrigin = player.eyeLocation.clone().add(0.0, -0.2, 0.0)
        val castDirection = castOrigin.direction.normalize()

        // ！！！ 消耗物品逻辑已交由父类处理，此处彻底删除 ！！！

        // 2. 获取数据并增加灵力
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.queuePlayerSave(data)
            }
        }

        // 3. 寻找目标
        val targets = findTargets(player, range)

        // 4. 造成伤害与控制
        // 如果有目标，才循环造成伤害；没有目标就跳过，但不打断流程
        if (targets.isNotEmpty()) {
            val baseDamage = data.zfStr * plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player)
            val finalDamage = baseDamage * damagePercent

            for (target in targets) {
                formationMagicDamage(plugin, player, target, finalDamage, FormationElement.WATER)

                // 保留原有每级15%的减速幅度，但使用阵法独立属性标签，不再覆盖其他药水减速。
                plugin.elementZfManager.tierEffects.applyWaterSlow(
                    target,
                    -0.15 * (slowAmplifier + 1),
                    (slowDurationSeconds * 1000.0).toLong()
                )

                if (level >= 3) {
                    val coldDuration = safeConfig.getDouble("tier3.cold_duration", 5.0)
                    val attackReduction = safeConfig.getDouble("tier3.attack_frequency_reduction", 0.30)
                    plugin.elementZfManager.tierEffects.applyCold(
                        target,
                        attackReduction,
                        (coldDuration * 1000.0).toLong()
                    )
                }
            }

            if (level >= 5) {
                startFrozenPath(player, castOrigin, castDirection, range, finalDamage, safeConfig)
            }
        } else if (level >= 5) {
            // 五级即使空放也会在释放瞬间的固定路径上留下冰径。
            startFrozenPath(
                player,
                castOrigin,
                castDirection,
                range,
                data.zfStr * damagePercent * plugin.accessorySkillManager.getCurrentFormationDamageMultiplier(player),
                safeConfig
            )
        }

        // 5. 提示与特效 (空放也会播放扇形特效)
        playConeEffects(player, range)

        return true // 总是进入冷却
    }

    // ============================================
    // 下方的 findTargets 和 playConeEffects 完完全全保持你源码的原样！
    // ============================================

    private fun findTargets(player: Player, range: Double): List<LivingEntity> {
        val entities = player.getNearbyEntities(range, range, range)
        val playerLoc = player.location
        val direction = playerLoc.direction

        return entities.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player }
            .filter { e ->
                val tags = e.scoreboardTags
                tags.contains("panling") && tags.contains("monster")
            }
            .filter { e ->
                val toEntity = e.location.toVector().subtract(playerLoc.toVector()).normalize()
                direction.dot(toEntity) > 0.5
            }
            .filter { e -> e.location.distance(playerLoc) <= range }
            .toList()
    }

    /**
     * 播放锥形/扇形 冰霜喷射特效
     */
    private fun playConeEffects(player: Player, range: Double) {
        // 起点：玩家眼睛稍微往下一点，模拟嘴巴/手部吹气
        val start = player.eyeLocation.add(0.0, -0.2, 0.0)
        val mainDir = start.direction.normalize()

        val world = player.world

        // 步长 0.5，意味着每半格生成一簇粒子
        var d = 0.5
        while (d < range) {
            // 中心点：沿着视线方向延伸 d 米
            val centerPoint = start.clone().add(mainDir.clone().multiply(d))

            // 扩散半径：距离越远，粒子扩散范围越大 (d * 0.6 约等于 60度角的开口)
            val spread = d * 0.6

            // 在这个距离切面上生成多个随机粒子，填满体积
            // 距离越远，需要的粒子越多才能填满视觉，所以 i < 5 + d
            val particleCount = (5 + d * 2).toInt()

            for (i in 0 until particleCount) {
                // 生成随机偏移量 (-spread/2 到 +spread/2)
                val offsetX = (Math.random() - 0.5) * spread
                val offsetY = (Math.random() - 0.5) * spread
                val offsetZ = (Math.random() - 0.5) * spread

                val particleLoc = centerPoint.clone().add(offsetX, offsetY, offsetZ)

                // 1. 雪花粒子 (主要视觉)
                world.spawnParticle(Particle.SNOWFLAKE, particleLoc, 1, 0.0, 0.0, 0.0, 0.01)

                // 2. 蓝色尘埃 (增加魔法感) - 30% 概率生成
                if (Math.random() < 0.3) {
                    world.spawnParticle(
                        Particle.DUST, particleLoc, 1,
                        Particle.DustOptions(Color.fromRGB(150, 240, 255), 0.8f)
                    ) // 冰蓝色
                }

                // 3. 云雾 (增加厚重感) - 10% 概率生成，只在远端生成
                if (d > 2.0 && Math.random() < 0.1) {
                    world.spawnParticle(Particle.CLOUD, particleLoc, 0, 0.0, 0.0, 0.0, 0.05)
                }
            }
            d += 0.5
        }

        // 音效
        world.playSound(start, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f) // 碎裂声
        world.playSound(start, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.6f, 1.8f) // 呼啸声（高音调模拟寒风）
    }

    private fun startFrozenPath(
        caster: Player,
        origin: Location,
        direction: Vector,
        range: Double,
        originalDamage: Double,
        config: ConfigurationSection
    ) {
        val duration = config.getDouble("tier5.path_duration", 5.0)
        val pulseInterval = config.getDouble("tier5.path_interval", 0.5).coerceAtLeast(0.05)
        val damageRatio = config.getDouble("tier5.path_damage_ratio", 0.10)
        val slowAmount = -config.getDouble("tier5.path_slow_percent", 0.10).coerceIn(0.0, 1.0)
        val totalPulses = (duration / pulseInterval).toInt().coerceAtLeast(1)
        val intervalTicks = (pulseInterval * 20.0).toLong().coerceAtLeast(1L)
        val radiusSquared = range * range

        drawFrozenPath(origin, direction, range)
        object : BukkitRunnable() {
            var pulses = 0

            override fun run() {
                if (pulses >= totalPulses || !caster.isOnline || caster.world != origin.world) {
                    cancel()
                    return
                }
                val world = origin.world ?: run {
                    cancel()
                    return
                }

                for (entity in world.getNearbyEntities(origin, range, range, range)) {
                    val target = entity as? LivingEntity ?: continue
                    if (!ElementFormationTierEffects.isFormationMonster(target)) continue
                    val targetCenter = target.location.add(0.0, target.height * 0.45, 0.0)
                    val offset = targetCenter.toVector().subtract(origin.toVector())
                    if (offset.lengthSquared() <= 0.0001 || offset.lengthSquared() > radiusSquared) continue
                    if (direction.dot(offset.normalize()) <= 0.5) continue

                    formationMagicDamage(
                        plugin,
                        caster,
                        target,
                        originalDamage * damageRatio,
                        FormationElement.WATER,
                        suppressKnockback = true
                    )
                    plugin.elementZfManager.tierEffects.applyFrozenPathSlow(
                        target,
                        slowAmount,
                        intervalTicks * 50L + 300L
                    )
                }

                drawFrozenPath(origin, direction, range)
                pulses++
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks)
    }

    private fun drawFrozenPath(origin: Location, direction: Vector, range: Double) {
        val world = origin.world ?: return
        var distance = 0.5
        while (distance <= range) {
            val center = origin.clone().add(direction.clone().multiply(distance))
            val spread = distance * 0.20
            world.spawnParticle(Particle.SNOWFLAKE, center, 2, spread, 0.12 + spread * 0.25, spread, 0.005)
            if ((distance * 2.0).toInt() % 3 == 0) {
                world.spawnParticle(
                    Particle.DUST,
                    center,
                    1,
                    spread,
                    0.08,
                    spread,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(105, 220, 255), 0.7f)
                )
            }
            distance += 0.75
        }
    }
}
