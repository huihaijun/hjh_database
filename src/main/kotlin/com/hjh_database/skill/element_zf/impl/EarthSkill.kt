package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.min

// 【改动1】继承 AbstractElementSkill，去掉 private val
class EarthSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】将 cast 改为 onCast
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {

        // 1. 直接读取配置，不再需要判断路径是否存在
        val range = safeConfig.getDouble("$path.range", 10.0)
        val radius = safeConfig.getDouble("$path.radius", 5.0)
        val duration = safeConfig.getDouble("$path.duration", 5.0) *
            plugin.accessorySkillManager.getCurrentFormationDurationMultiplier(player)
        val strength = safeConfig.getDouble("$path.pull_strength", 0.08)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // ！！！ 原本的扣除物品代码已删除，交由父类 AbstractElementSkill 处理 ！！！

        // 2. 增加灵力
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.queuePlayerSave(data)
            }
        }

        // 3. 确定阵法中心
        val center = getTargetLocation(player, range)

        // 4. 提示 & 启动音效
        // center.world 可能为空，但在 Bukkit 运行时通常安全，使用 !! 确保调用
        center.world!!.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.5f)
        center.world!!.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.6f)

        // 5. 开启持续任务
        object : BukkitRunnable() {
            var ticks = 0
            val maxTicks = (duration * 20).toInt()

            override fun run() {
                if (ticks >= maxTicks) {
                    if (level >= 5) {
                        collapseFormation(player, center, radius, safeConfig)
                    }
                    this.cancel()
                    return
                }

                // === 增强版特效 (保持每 tick 播放，保证视觉连贯性) ===
                playZoneEffect(center, radius)

                // === 音效与物理牵引逻辑 (每 20 ticks / 1秒 触发一次) ===
                if (ticks % 20 == 0) {
                    if (center.world != null) {
                        // 播放音效
                        center.world!!.playSound(center, Sound.BLOCK_BEACON_AMBIENT, 2.0f, 0.5f)

                        // 物理牵引
                        val entities = center.world!!.getNearbyEntities(center, radius, radius, radius)

                        for (entity in entities) {
                            if (entity === player || entity !is LivingEntity) continue

                            val tags = entity.scoreboardTags
                            if (!tags.contains("panling") || !tags.contains("monster")) continue

                            if (entity.location.distance(center) > radius) continue

                            if (level >= 3) {
                                // 每秒刷新一次，宽限略大于1秒；阵法结束或目标离圈后会自然恢复。
                                val refreshMillis = (safeConfig.getDouble("tier3.armor_refresh_grace", 1.1) * 1000.0).toLong()
                                val reduction = safeConfig.getDouble("tier3.armor_reduction", 0.20)
                                plugin.elementZfManager.tierEffects.applyEarthArmorBreak(entity, reduction, refreshMillis)
                            }

                            val dir = center.toVector().subtract(entity.location.toVector())
                            dir.setY(0.0)

                            if (dir.lengthSquared() < 0.25) continue

                            // 计算并赋予牵引速度
                            dir.normalize().multiply(strength)

                            // 稍微加一点向上的力，让拉扯效果更明显一点（可选）
                            // dir.setY(0.2)

                            entity.velocity = entity.velocity.add(dir)
                        }
                    }
                }

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)

        return true
    }

    private fun collapseFormation(
        caster: Player,
        center: Location,
        radius: Double,
        config: ConfigurationSection
    ) {
        val world = center.world ?: return
        val rootMillis = (config.getDouble("tier5.root_duration", 1.5) * 1000.0).toLong()
        val radiusSquared = radius * radius

        world.spawnParticle(
            Particle.BLOCK,
            center,
            42,
            radius * 0.55,
            0.35,
            radius * 0.55,
            0.05,
            Material.ROOTED_DIRT.createBlockData()
        )
        world.spawnParticle(Particle.GUST, center.clone().add(0.0, 0.3, 0.0), 2, 0.3, 0.1, 0.3, 0.0)
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.75f, 0.55f)

        for (entity in world.getNearbyEntities(center, radius, radius, radius)) {
            val target = entity as? LivingEntity ?: continue
            if (target === caster || !ElementFormationTierEffects.isFormationMonster(target)) continue
            if (target.location.distanceSquared(center) > radiusSquared) continue
            // 管理器内部会单独排除 instance_boss，其他裂地效果仍可作用于BOSS。
            plugin.elementZfManager.tierEffects.applyRoot(target, rootMillis)
        }
    }

    // ============================================
    // 下方的 getTargetLocation 和 playZoneEffect 完完全全保持你源码的原样！
    // ============================================
    private fun getTargetLocation(player: Player, range: Double): Location {
        val eye = player.eyeLocation
        val direction = eye.direction

        val result = player.world.rayTrace(
            eye, direction, range,
            FluidCollisionMode.ALWAYS, true, 0.5
        ) { e -> e != player && e is LivingEntity }

        if (result != null) {
            if (result.hitEntity != null) {
                return result.hitEntity!!.location
            } else if (result.hitBlock != null) {
                return result.hitBlock!!.location.add(0.5, 1.0, 0.5)
            } else if (result.hitPosition != null) {
                return result.hitPosition.toLocation(player.world)
            }
        }
        return eye.add(direction.multiply(range))
    }

    /**
     * 优化后的区域特效：更真实，不遮挡
     */
    private fun playZoneEffect(center: Location, radius: Double) {
        val world = center.world ?: return

        // 1. 边缘碎裂圈 (贴地，清晰但不挡视野)
        for (i in 0 until 4) {
            val angle = Math.random() * 2 * Math.PI
            val x = center.x + radius * Math.cos(angle)
            val z = center.z + radius * Math.sin(angle)

            world.spawnParticle(
                Particle.BLOCK,
                x, center.y + 0.1, z,
                1, 0.0, 0.0, 0.0, 0.0,
                Material.DIRT.createBlockData()
            )
        }

        // 2. 区域内扬尘 (营造力场感)
        for (i in 0 until 2) {
            val r = Math.random() * radius
            val angle = Math.random() * 2 * Math.PI
            val x = center.x + r * Math.cos(angle)
            val z = center.z + r * Math.sin(angle)

            // SMOKE_NORMAL 不需要额外数据，保持原样
            world.spawnParticle(
                Particle.SMOKE,
                x, center.y + 0.2, z,
                1, 0.0, 0.1, 0.0, 0.05
            )

            // 偶尔产生地面裂纹粒子
            if (Math.random() < 0.1) {
                world.spawnParticle(
                    Particle.BLOCK,
                    x, center.y + 0.1, z,
                    1, 0.0, 0.0, 0.0, 0.0,
                    Material.COARSE_DIRT.createBlockData()
                )
            }
        }
    }
}
