package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class Shenmushouwei(private val plugin: Hjh_database, private val boss: LivingEntity) {

    init {
        applyPassiveSkill()
        startActiveSkillTask()
    }

    // ==========================================
    // 被动技能：100% 击退抗性
    // ==========================================
    private fun applyPassiveSkill() {
        // 在 1.21.3 中直接使用 Attribute.KNOCKBACK_RESISTANCE 即可
        val knockbackAttribute = boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)
        if (knockbackAttribute != null) {
            knockbackAttribute.baseValue = 1.0 // 1.0 代表 100% 免疫击退
        }
    }

    // ==========================================
    // 主动技能：缓慢爬行的藤蔓追踪与禁锢爆发
    // ==========================================
    private fun startActiveSkillTask() {
        // 每 9 秒 (180 ticks) 触发一次
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                // 1. 寻找 10 格范围内，距离 Boss 最近的至多 3 名玩家
                val targets = boss.getNearbyEntities(10.0, 10.0, 10.0)
                    .filterIsInstance<Player>()
                    // 【修改】同时排除旁观和创造模式
                    .filter { !it.isDead && it.gameMode != org.bukkit.GameMode.SPECTATOR && it.gameMode != org.bukkit.GameMode.CREATIVE }
                    .sortedBy { it.location.distanceSquared(boss.location) }
                    .take(3)

                if (targets.isNotEmpty()) {
                    // 2. 播放预警提示和音效
                    targets.forEach {
                        it.sendMessage("§c小心！神木守卫召唤了藤蔓正在向你爬行，被击中后将被禁锢，快快闪开！")
                    }
                    boss.world.playSound(boss.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.8f)

                    // 3. 开启真实的藤蔓追踪弹道任务
                    startVineTrackingTask(targets)
                }
            }
        }.runTaskTimer(plugin, 300L, 300L)
    }

    // 用于记录每一根藤蔓的当前位置
    private inner class Vine(val target: Player) {
        var currentLoc = boss.location.clone().apply { y += 0.2 }
        // 【修改】玩家中途切创造也会导致失去追踪
        val isTargetValid get() = !target.isDead && target.gameMode != org.bukkit.GameMode.SPECTATOR && target.gameMode != org.bukkit.GameMode.CREATIVE
    }

    private fun startVineTrackingTask(initialTargets: List<Player>) {
        // 为每一个被选中的玩家生成一根专属藤蔓
        val vines = initialTargets.map { Vine(it) }

        object : BukkitRunnable() {
            var ticksElapsed = 0
            val duration = 80 // 追踪持续 4秒 (60 ticks)
            val speed = 0.25  // 藤蔓爬行速度

            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                ticksElapsed++

                // ==========================================
                // A. 追踪爬行阶段 (0 ~ 59 tick)
                // ==========================================
                if (ticksElapsed < duration) {
                    for (vine in vines) {
                        if (!vine.isTargetValid) continue

                        val targetLoc = vine.target.location.clone()
                        val direction = targetLoc.toVector().subtract(vine.currentLoc.toVector())

                        if (direction.lengthSquared() > 0.5) {
                            direction.normalize().multiply(speed)
                            vine.currentLoc.add(direction)
                        }

                        // 【优化点1】让爬行的藤蔓变粗：
                        // 增加 X 和 Z 的扩散度(0.4)，增加粒子数量(15)，使其看起来是一大团藤蔓
                        boss.world.spawnParticle(
                            Particle.BLOCK,
                            vine.currentLoc,
                            15, 0.4, 0.1, 0.4, 0.0,
                            org.bukkit.Material.OAK_LEAVES.createBlockData()
                        )
                        // 额外加入一点点泥土飞溅，更有在地上攀爬的感觉
                        boss.world.spawnParticle(
                            Particle.BLOCK,
                            vine.currentLoc,
                            5, 0.3, 0.1, 0.3, 0.0,
                            org.bukkit.Material.DIRT.createBlockData()
                        )

                        if (ticksElapsed % 10 == 0) {
                            boss.world.playSound(vine.currentLoc, Sound.BLOCK_GRASS_STEP, 0.5f, 0.8f)
                        }
                    }
                }
                // ==========================================
                // B. 爆发阶段 (第 60 tick)
                // ==========================================
                else {
                    for (vine in vines) {
                        if (!vine.isTargetValid) continue

                        val eruptionLoc = vine.currentLoc

                        // 【优化点2】竖直高度 3.5 格的爆发感：
                        // 将中心点设在上方 1.75 格处，并让 Y 轴上下扩散 1.75 格，总计高度约 3.5 格
                        val columnCenter = eruptionLoc.clone().add(0.0, 1.75, 0.0)

                        // 主体树叶喷发
                        boss.world.spawnParticle(
                            Particle.BLOCK,
                            columnCenter,
                            80, 0.8, 1.75, 0.8, 0.0, // XZ聚拢，Y轴拉长
                            org.bukkit.Material.OAK_LEAVES.createBlockData()
                        )
                        // 加入原木颗粒，增加中心木质的厚重力量感
                        boss.world.spawnParticle(
                            Particle.BLOCK,
                            columnCenter,
                            40, 0.4, 1.75, 0.4, 0.0,
                            org.bukkit.Material.OAK_LOG.createBlockData()
                        )

                        // 【优化点3】半径 3 格的破土圆环：
                        // 使用三角函数在爆发点周围生成一圈泥土和树叶，模拟破土而出的感觉
                        val radius = 3.0
                        for (degree in 0 until 360 step 15) { // 每隔 15 度生成一簇粒子
                            val radians = Math.toRadians(degree.toDouble())
                            val ringX = radius * kotlin.math.cos(radians)
                            val ringZ = radius * kotlin.math.sin(radians)

                            val ringLoc = eruptionLoc.clone().add(ringX, 0.2, ringZ) // 紧贴地面略高

                            // 破土的泥土飞溅
                            boss.world.spawnParticle(
                                Particle.BLOCK,
                                ringLoc,
                                5, 0.2, 0.2, 0.2, 0.0,
                                org.bukkit.Material.DIRT.createBlockData()
                            )
                            // 边缘延伸出的碎叶
                            boss.world.spawnParticle(
                                Particle.BLOCK,
                                ringLoc,
                                3, 0.2, 0.2, 0.2, 0.0,
                                org.bukkit.Material.OAK_LEAVES.createBlockData()
                            )
                        }

                        // 播放破土音效
                        boss.world.playSound(eruptionLoc, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8f, 0.5f)
                        boss.world.playSound(eruptionLoc, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.5f)

                        // 检查引爆点 3 格范围内的所有玩家
                        val hitPlayers = boss.world.getNearbyEntities(eruptionLoc, 3.0, 3.0, 3.0).filterIsInstance<Player>()
                        for (p in hitPlayers) {
                            // 【修改】保证爆发的伤害也不会波及到创造/旁观的管理员
                            if (p.isDead || p.gameMode == org.bukkit.GameMode.SPECTATOR || p.gameMode == org.bukkit.GameMode.CREATIVE) continue

                            p.damage(10.0, boss)
                            p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 127))
                            p.sendMessage("§4你被神木守卫的藤蔓禁锢了！")
                        }
                    }
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}