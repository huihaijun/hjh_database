package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.ElementSkill
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

class EarthSkill(private val plugin: Hjh_database) : ElementSkill {

    override fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean {
        // 1. 读取配置
        val safeConfig = config!!

        var path = "levels.$level"
        if (!safeConfig.contains(path)) path = "levels.1"

        val range = safeConfig.getDouble("$path.range", 10.0)
        val radius = safeConfig.getDouble("$path.radius", 5.0)
        val duration = safeConfig.getDouble("$path.duration", 5.0)
        val strength = safeConfig.getDouble("$path.pull_strength", 0.08)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // 2. 消耗物品
        val handItem = player.inventory.itemInMainHand
        handItem.amount = handItem.amount - 1

        // 3. 增加灵力
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.savePlayer(data)
            }
        }

        // 4. 确定阵法中心
        val center = getTargetLocation(player, range)

        // 5. 提示 & 启动音效
        // center.world 可能为空，但在 Bukkit 运行时通常安全，使用 !! 确保调用
        center.world!!.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.5f)
        center.world!!.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.6f)

        // 6. 开启持续任务
        object : BukkitRunnable() {
            var ticks = 0
            val maxTicks = (duration * 20).toInt()

            override fun run() {
                if (ticks >= maxTicks) {
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

            // 【已修复】这里必须使用 BLOCK_DUST 才能接收 BlockData
            world.spawnParticle(
                Particle.BLOCK, // 原来是 Particle.DUST (会导致崩溃)
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
                // 【已修复】SCRAPE 粒子不支持 BlockData，改为 BLOCK_DUST 使用粗土效果
                world.spawnParticle(
                    Particle.BLOCK, // 原来是 Particle.SCRAPE (会导致崩溃)
                    x, center.y + 0.1, z,
                    1, 0.0, 0.0, 0.0, 0.0,
                    Material.COARSE_DIRT.createBlockData()
                )
            }
        }
    }
}