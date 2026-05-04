package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class Zhizhunvwang(private val plugin: Hjh_database, private val boss: LivingEntity) {

    init {
        startSkillTask()
    }

    private fun startSkillTask() {
        // 主技能循环：每 30 秒 (600 ticks) 触发一次
        object : BukkitRunnable() {
            override fun run() {
                // 如果 Boss 死了或者被移除了，停止定时任务
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                // 1. 先获取 5 格范围内的玩家进行预警
                val nearbyPlayers = boss.getNearbyEntities(5.0, 5.0, 5.0)
                    .filterIsInstance<Player>()
                    // 【修改】排除创造模式
                    .filter { !it.isDead && it.gameMode != org.bukkit.GameMode.SPECTATOR && it.gameMode != org.bukkit.GameMode.CREATIVE }

                if (nearbyPlayers.isNotEmpty()) {
                    // 发送预警提示
                    nearbyPlayers.forEach {
                        it.sendMessage("§c蜘蛛女王即将寻找最近的一名玩家释放蛛丝，请注意躲避！")
                    }

                    // 2. 延迟 3 秒 (60 ticks) 后正式索敌并释放技能
                    object : BukkitRunnable() {
                        override fun run() {
                            // 再次检查 Boss 是否存活（这3秒内Boss可能被击杀了）
                            if (boss.isDead || !boss.isValid) return

                            // 重新索敌（因为 3 秒过去，玩家位置可能发生变化）
                            val target = boss.getNearbyEntities(5.0, 5.0, 5.0)
                                .filterIsInstance<Player>()
                                // 【修改】排除创造模式
                                .filter { !it.isDead && it.gameMode != org.bukkit.GameMode.SPECTATOR && it.gameMode != org.bukkit.GameMode.CREATIVE }
                                .minByOrNull { it.location.distanceSquared(boss.location) }

                            if (target != null) {
                                castWeb(target)
                            }
                        }
                    }.runTaskLater(plugin, 60L) // 延迟 60 ticks (3秒) 执行
                }
            }
        }.runTaskTimer(plugin, 600L, 600L)
    }

    private fun castWeb(target: Player) {
        // 播放音效提示玩家
        boss.world.playSound(boss.location, Sound.ENTITY_SPIDER_DEATH, 1.0f, 0.5f)

        // 计算从 Boss 眼部到玩家眼部的方向向量
        val startLoc = boss.eyeLocation
        val targetLoc = target.eyeLocation
        val direction = targetLoc.toVector().subtract(startLoc.toVector()).normalize()

        // 启动一个每 Tick 执行的弹道任务，模拟缓慢前进的蛛丝粒子
        object : BukkitRunnable() {
            var distanceTraveled = 0.0
            val maxDistance = 5.0 // 最大射程 5 格
            val speed = 0.4 // 每 tick 飞行 0.4 格（越小越慢，玩家越好躲）
            val currentLoc = startLoc.clone()

            override fun run() {
                // 弹道终止条件
                if (boss.isDead || distanceTraveled >= maxDistance) {
                    cancel()
                    return
                }

                // 移动当前坐标点
                currentLoc.add(direction.clone().multiply(speed))
                distanceTraveled += speed

                // 生成蛛丝粒子 (使用白色烟雾或云团模拟蛛网效果)
                boss.world.spawnParticle(Particle.CLOUD, currentLoc, 3, 0.1, 0.1, 0.1, 0.0)
                boss.world.spawnParticle(Particle.WHITE_ASH, currentLoc, 5, 0.1, 0.1, 0.1, 0.0)

                // 2. 碰撞检测：检查当前粒子点周围 0.5 格内是否有玩家
                val hitPlayers = boss.world.getNearbyEntities(currentLoc, 0.5, 0.5, 0.5)
                    .filterIsInstance<Player>()
                    // 【新增修改】如果不小心撞到了飞在空中的创造模式管理员，不要停止弹道也不要减速
                    .filter { !it.isDead && it.gameMode != org.bukkit.GameMode.SPECTATOR && it.gameMode != org.bukkit.GameMode.CREATIVE }

                if (hitPlayers.isNotEmpty()) {
                    for (p in hitPlayers) {
                        p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 80, 1))
                        p.sendMessage("§c你被蜘蛛女王的蛛丝击中了！移动速度降低！")
                    }
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L) // 0延迟，每1 Tick(0.05秒)更新一次位置
    }
}