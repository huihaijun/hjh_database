package com.hjh_database.skill.element_zf.impl

import com.hjh_database.skill.element_zf.FormationCast
import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import com.hjh_database.skill.element_zf.FormationElement
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

// 【改动1】继承 AbstractElementSkill，去掉 private val
class MetalSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】将 cast 改为 onCast
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {
        val formationCast = FormationCast()

        // 1. 获取配置数值 (父类已处理好了非空和路径检查)
        val damagePercent = safeConfig.getDouble("$path.damage_percent", 2.5)
        val range = safeConfig.getDouble("$path.range", 3.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        val maxTargets = safeConfig.getInt("$path.max_targets", 1)
        val effectRadius = safeConfig.getDouble("$path.effect_radius", 2.5 + (level * 0.5))
        // 五级星云只读取释放瞬间的水平朝向，之后玩家转身不会改变移动方向。
        val castDirection = player.eyeLocation.direction.clone().apply { y = 0.0 }
        if (castDirection.lengthSquared() > 0.0001) castDirection.normalize()

        // ！！！ 消耗物品逻辑已交由父类处理，此处删除 ！！！

        // 2. 增加灵力 & 提示
        val data = plugin.playerManager.getData(player.uniqueId)!!

        data.lingli = data.lingli + lingliAdd
        // 施法路径高频触发，延迟合并保存灵力变化。
        plugin.databaseManager.queuePlayerSave(data)

        // 3. 计算目标位置 (星云中心)
        val hitLoc = getHitLocation(player, range)
        val cloudCenter = hitLoc.clone().add(0.0, 0.1, 0.0) // 稍微抬高防止贴地

        // ============================================
        // 【新增改动】播放星云密布、神秘空灵的组合音效
        // ============================================
        val world = cloudCenter.world
        if (world != null) {
            // 附魔台：深邃神秘的基础施法音
            world.playSound(cloudCenter, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.8f)
            // 紫水晶：清脆空灵，模拟星星点点的闪烁感 (高音调)
            world.playSound(cloudCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.5f)
            // 信标：宇宙空间般的空旷嗡鸣感 (高音调)
            world.playSound(cloudCenter, Sound.BLOCK_BEACON_AMBIENT, 1.5f, 2.0f)
        }

        // 4. 播放指向性光束特效 (金色射线)
        playTargetingBeam(player, hitLoc)

        // 5. 寻找目标怪物并造成伤害
        // 搜索范围：以落点为中心，半径为 effectRadius 的立方体
        val nearby = cloudCenter.world!!.getNearbyEntities(cloudCenter, effectRadius, effectRadius, effectRadius)

        // Kotlin 风格的流式处理
        val areaTargets = nearby.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it !== player } // 引用比较
            .filter { it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster") }
            // 高度限制：只打中心点下方的怪
            .filter { it.location.y <= cloudCenter.y }
            // 半球限制：距离必须在半径范围内
            .filter { it.location.distance(cloudCenter) <= effectRadius }
            .toList()

        val victims = areaTargets.asSequence()
            .sortedBy { it.location.distance(player.location) }
            .take(maxTargets)
            .toList()

        // 造成伤害
        val damage = data.zfStr * damagePercent

        for (victim in victims) {
            formationMagicDamage(plugin, player, victim, damage, FormationElement.METAL, cast = formationCast)
        }

        if (level >= 3) {
            val durationMillis = (safeConfig.getDouble("tier3.offense_reduction_duration", 5.0) * 1000.0).toLong()
            val reduction = safeConfig.getDouble("tier3.offense_reduction", 0.20)
            for (target in areaTargets) {
                plugin.elementZfManager.tierEffects.applyMetalWeakness(target, reduction, durationMillis)
            }
        }

        // 6. 播放星云爆炸特效
        playEffects(cloudCenter, victims, effectRadius)

        if (level >= 5 && castDirection.lengthSquared() > 0.0001) {
            val moveDuration = safeConfig.getDouble("tier5.move_duration", 3.5)
            val moveSpeed = safeConfig.getDouble("tier5.move_speed", 2.4)
            val damageRatio = safeConfig.getDouble("tier5.damage_ratio", 0.5)
            startMovingCloud(
                player,
                cloudCenter.clone(),
                castDirection,
                effectRadius,
                damage * damageRatio,
                moveDuration,
                moveSpeed,
                formationCast
            )
        }

        return true
    }

    // ============================================
    // 下方的所有辅助方法完完全全保持你源码的原样！
    // ============================================

    /**
     * 获取视线撞击点
     * 规则：只会在【实体方块】或者【带有 panling+monster 标签的怪物】身上停下
     */
    private fun getHitLocation(player: Player, maxRange: Double): Location {
        val eye = player.eyeLocation
        val direction = eye.direction

        // 射线检测：
        val result = player.world.rayTrace(
            eye, direction, maxRange,
            FluidCollisionMode.NEVER, true, 0.5
        ) { entity ->
            entity !== player &&
                    entity.scoreboardTags.contains("panling") &&
                    entity.scoreboardTags.contains("monster")
        }

        if (result != null) {
            if (result.hitBlock != null) {
                // 撞到方块
                return result.hitPosition.toLocation(player.world)
            } else if (result.hitEntity != null) {
                // 撞到符合条件的怪物
                return result.hitEntity!!.location.add(0.0, result.hitEntity!!.height / 2.0, 0.0)
            }
        }
        return eye.add(direction.multiply(maxRange))
    }

    /**
     * 播放指向性光束
     * 从玩家眼部下方射出一道粒子直到目标点
     */
    private fun playTargetingBeam(player: Player, endLoc: Location) {
        val startLoc = player.eyeLocation.add(0.0, -0.25, 0.0)

        // 稍微向右偏移一点，模拟右手施法
        val right = startLoc.direction.crossProduct(Vector(0, 1, 0)).normalize().multiply(-0.2)
        startLoc.add(right)

        val distance = startLoc.distance(endLoc)
        val dir = endLoc.toVector().subtract(startLoc.toVector()).normalize()

        var d = 0.0
        while (d < distance) {
            val point = startLoc.clone().add(dir.clone().multiply(d))
            player.world.spawnParticle(
                Particle.DUST, point, 1,
                Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.6f)
            )
            d += 0.25
        }
        player.world.spawnParticle(Particle.FLASH, endLoc, 1)
    }

    private fun playEffects(center: Location, targets: List<LivingEntity>, radius: Double) {
        val world = center.world ?: return

        // 1. 星云特效
        val particleCount = (20 * radius).toInt()
        for (i in 0 until particleCount) {
            val angle = Math.random() * 2 * Math.PI
            val r = Math.random() * radius
            val offsetX = r * cos(angle)
            val offsetZ = r * sin(angle)
            val offsetY = (Math.random() - 0.5) * 0.5
            world.spawnParticle(
                Particle.CLOUD,
                center.clone().add(offsetX, offsetY, offsetZ),
                0, 0.0, 0.0, 0.0
            )
        }

        // 2. 坠落流星
        if (targets.isNotEmpty()) {
            for (target in targets) {
                val angle = Math.random() * 2 * Math.PI
                val r = Math.random() * radius
                val startLoc = center.clone().add(r * cos(angle), 0.0, r * sin(angle))
                val endLoc = target.location.add(0.0, target.height / 2.0, 0.0)
                spawnFallingStar(startLoc, endLoc)
            }
        } else {
            // 空放效果
            if (center.clone().subtract(0.0, 1.0, 0.0).block.type.isAir) {
                val angle = Math.random() * 2 * Math.PI
                val r = Math.random() * radius * 0.5
                val startLoc = center.clone().add(r * cos(angle), 0.0, r * sin(angle))
                spawnFallingStar(
                    startLoc,
                    center.clone().add(r * cos(angle), -radius, r * sin(angle))
                )
            }
        }
    }

    private fun spawnFallingStar(startLoc: Location, endLoc: Location) {
        val fallDir = endLoc.toVector().subtract(startLoc.toVector()).normalize().multiply(0.8)
        val distance = startLoc.distance(endLoc)
        val steps = (distance / 0.8).toInt()

        object : BukkitRunnable() {
            var step = 0
            val current = startLoc.clone()

            override fun run() {
                if (step >= steps || (current.world != null && current.block.type.isSolid)) {
                    if (current.world != null) {
                        current.world!!.spawnParticle(
                            Particle.CRIT,
                            current,
                            10,
                            0.2,
                            0.2,
                            0.2,
                            0.1
                        )
                    }
                    this.cancel()
                    return;
                }
                current.add(fallDir)
                if (current.world != null) {
                    current.world!!.spawnParticle(Particle.WAX_OFF, current, 1)
                    current.world!!.spawnParticle(Particle.END_ROD, current, 0)
                }
                step++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun startMovingCloud(
        caster: Player,
        initialCenter: Location,
        fixedDirection: Vector,
        radius: Double,
        damage: Double,
        durationSeconds: Double,
        blocksPerSecond: Double,
        formationCast: FormationCast
    ) {
        val hitTargets = HashSet<java.util.UUID>()
        val maxPulses = (durationSeconds.coerceAtLeast(0.0) * 4.0).toInt().coerceAtLeast(1)
        val step = fixedDirection.clone().multiply(blocksPerSecond.coerceAtLeast(0.0) / 4.0)
        val radiusSquared = radius * radius

        object : BukkitRunnable() {
            var pulses = 0
            val center = initialCenter.clone()

            override fun run() {
                if (pulses >= maxPulses || !caster.isOnline || caster.world != center.world) {
                    cancel()
                    return
                }

                center.add(step)
                val world = center.world ?: run {
                    cancel()
                    return
                }
                world.spawnParticle(Particle.CLOUD, center, 16, radius * 0.55, 0.3, radius * 0.55, 0.015)
                world.spawnParticle(
                    Particle.DUST,
                    center,
                    10,
                    radius * 0.45,
                    0.25,
                    radius * 0.45,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(245, 205, 85), 0.75f)
                )

                for (entity in world.getNearbyEntities(center, radius, radius, radius)) {
                    val target = entity as? LivingEntity ?: continue
                    if (!ElementFormationTierEffects.isFormationMonster(target)) continue
                    if (target.location.distanceSquared(center) > radiusSquared) continue
                    if (!hitTargets.add(target.uniqueId)) continue
                    formationMagicDamage(plugin, caster, target, damage, FormationElement.METAL, cast = formationCast)
                    target.world.spawnParticle(Particle.WAX_OFF, target.location.add(0.0, target.height * 0.55, 0.0), 12, 0.25, 0.35, 0.25, 0.02)
                }
                pulses++
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }
}
