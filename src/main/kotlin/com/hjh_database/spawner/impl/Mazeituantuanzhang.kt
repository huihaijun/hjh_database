package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class Mazeituantuanzhang(private val plugin: Hjh_database, private val boss: LivingEntity) : Listener {

    private var isChanneling = false
    private val skillCooldown = 13 * 20L // 13秒 CD
    private val initialDelay = 5 * 20L   // 出生后 5秒 首发

    init {
        applyPassiveSkill()

        // 动态注册一个专属监听器，用于实现吟唱期 80% 免伤
        plugin.server.pluginManager.registerEvents(this, plugin)

        // 生命周期回收任务：Boss 死亡或消失时，彻底清理这个技能和监听器
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    HandlerList.unregisterAll(this@Mazeituantuanzhang)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)

        // 安排第一次技能释放
        scheduleNextSkill(initialDelay)
    }

    // ==========================================
    // 被动技能：100% 击退抗性
    // ==========================================
    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
    }

    // ==========================================
    // 监听器：吟唱期间免伤 80%
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBossDamage(event: EntityDamageEvent) {
        if (event.entity == boss && isChanneling) {
            event.damage *= 0.2 // 受到伤害变为原来的 20%
        }
    }

    // ==========================================
    // 主动技能调度器
    // ==========================================
    private fun scheduleNextSkill(delayTicks: Long) {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) return
                startChanneling()
            }
        }.runTaskLater(plugin, delayTicks)
    }

    // ==========================================
    // 阶段一：吟唱 (3秒)
    // ==========================================
    private fun startChanneling() {
        // 寻找 10 格内最近的合法玩家
        val target = boss.getNearbyEntities(10.0, 5.0, 10.0)
            .filterIsInstance<Player>()
            .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
            .minByOrNull { it.location.distanceSquared(boss.location) }

        if (target == null) {
            // 如果附近没人，过 3 秒再尝试
            scheduleNextSkill(3 * 20L)
            return
        }

        isChanneling = true
        // 施加最高级缓慢，让他呆在原地不动
        boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 255, false, false))

        // 发送警告语音和文本给附近玩家
        val nearby = boss.getNearbyEntities(15.0, 15.0, 15.0).filterIsInstance<Player>()
        nearby.forEach {
            it.sendMessage("§c马贼团团长即将蓄力向指定方向冲锋，请注意躲避！")
            it.sendMessage("§c注意！团长的冲锋速度极快，最好躲在高大的障碍物后！")
            it.playSound(it.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f)
        }

        var ticks = 0
        val channelTime = 80 // 4 秒 (80 Ticks)
        // 初始方向锁定
        var lockedDirection = target.location.toVector().subtract(boss.location.toVector()).setY(0.0).normalize()

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                if (ticks >= channelTime) {
                    isChanneling = false
                    boss.removePotionEffect(PotionEffectType.SLOWNESS)

                    // 新增：锁定最终方向并给予 0.5s 停顿缓冲感
                    val finalDirection = lockedDirection.clone()
                    boss.world.playSound(boss.location, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.5f, 0.5f)

                    object : BukkitRunnable() {
                        override fun run() {
                            if (boss.isValid && !boss.isDead) {
                                startDash(finalDirection)
                            }
                        }
                    }.runTaskLater(plugin, 10L) // 0.5秒缓冲

                    cancel()
                    return
                }

                // 吟唱期间不断修正方向（红外线追踪），这会给玩家压迫感
                if (target.isValid && !target.isDead) {
                    val currentVec = target.location.toVector().subtract(boss.location.toVector()).setY(0.0)
                    if (currentVec.lengthSquared() > 0) {
                        lockedDirection = currentVec.normalize()
                    }
                }

                // 绘制代表冲锋方向的红线粒子 (长约 15 格)
                val bossEyeLoc = boss.location.clone().add(0.0, 1.2, 0.0)
                for (i in 1..15 step 2) {
                    val particleLoc = bossEyeLoc.clone().add(lockedDirection.clone().multiply(i))
                    boss.world.spawnParticle(
                        Particle.DUST,
                        particleLoc,
                        2, 0.1, 0.1, 0.1, 0.0,
                        Particle.DustOptions(Color.RED, 1.2f)
                    )
                }

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // ==========================================
    // 阶段二：高速冲锋 (最远 15 格)
    // ==========================================
    private fun startDash(direction: Vector) {
        boss.world.playSound(boss.location, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 1.0f)

        var distanceTraveled = 0.0
        val maxDistance = 15.0
        val speed = 1.3 // 每 Tick 1.3 格，极快的冲刺
        var previousLoc = boss.location.clone()

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid || distanceTraveled >= maxDistance) {
                    // 冲锋结束（未撞到玩家也未撞到墙），直接进入常规 CD
                    scheduleNextSkill(skillCooldown)
                    cancel()
                    return
                }

                // 给 Boss 赋予极速动力
                boss.velocity = direction.clone().multiply(speed)
                distanceTraveled += speed

                // 冲锋沿途的烟雾粒子特效
                boss.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, boss.location.add(0.0, 0.5, 0.0), 5, 0.5, 0.5, 0.5, 0.0)

                val currentLoc = boss.location

                // 1. 【碰撞玩家检测】(加入视线无阻挡判定)
                val hitPlayers = boss.getNearbyEntities(1.5, 1.5, 1.5)
                    .filterIsInstance<Player>()
                    .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
                    // ⭐ 新增：Boss与玩家之间不能有方块(如仙人掌/墙壁)阻挡视线！防止隔山打牛！
                    .filter { boss.hasLineOfSight(it) }

                if (hitPlayers.isNotEmpty()) {
                    val hitTarget = hitPlayers.first()

                    // 播放爆破音效和粒子
                    boss.world.playSound(hitTarget.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                    boss.world.spawnParticle(Particle.EXPLOSION, hitTarget.location, 2)

                    // 对撞击点 2 格范围内的所有玩家造成 20 点伤害
                    val aoePlayers = hitTarget.world.getNearbyEntities(hitTarget.location, 2.0, 2.0, 2.0)
                        .filterIsInstance<Player>()
                        .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
                        // AOE伤害也必须保证视线，避免躲在墙后也被炸到
                        .filter { boss.hasLineOfSight(it) }

                    for (p in aoePlayers) {
                        p.damage(20.0, boss)
                    }

                    // 撞到人提前停止，进入常规 CD
                    scheduleNextSkill(skillCooldown)
                    cancel()
                    return
                }

                // 2. 【碰撞障碍物检测 (精准射线法)】
                // 从头顶 (Y+1.2) 和脚底 (Y+0.2) 向冲锋方向发出射线，探测距离比速度略长一点点 (1.8格)
                val rayDist = speed + 0.5

                // 探测头部：判断是不是 2 格高的死胡同
                val eyeLoc = currentLoc.clone().add(0.0, 1.2, 0.0)
                // true 参数代表忽略高草丛、藤蔓等可以穿透的方块
                val headRay = boss.world.rayTraceBlocks(eyeLoc, direction, rayDist, org.bukkit.FluidCollisionMode.NEVER, true)

                if (headRay != null && headRay.hitBlock != null && !headRay.hitBlock!!.isPassable) {
                    // 头前方的射线碰到了不可穿过的方块 (比如两格高的仙人掌、墙壁) -> 直接眩晕！
                    applyStun()
                    cancel()
                    return
                }

                // 探测脚底：如果头没撞到墙，看看脚底有没有被 1格 / 1.5格 的东西挡住
                val footLoc = currentLoc.clone().add(0.0, 0.2, 0.0)
                val footRay = boss.world.rayTraceBlocks(footLoc, direction, rayDist, org.bukkit.FluidCollisionMode.NEVER, true)

                if (footRay != null && footRay.hitBlock != null && !footRay.hitBlock!!.isPassable) {
                    // 脚被挡住了，但头没挡住 -> 给一个向上的力，让它像跑酷一样起跳越过！
                    boss.velocity = boss.velocity.setY(0.6)
                }

                previousLoc = currentLoc.clone()
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // ==========================================
    // 阶段三：撞墙眩晕惩罚 (2秒)
    // ==========================================
    private fun applyStun() {
        boss.world.playSound(boss.location, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        boss.world.playSound(boss.location, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1f, 0.5f)

        // 头顶冒金星特效
        boss.world.spawnParticle(Particle.CRIT, boss.location.add(0.0, 2.2, 0.0), 30, 0.5, 0.2, 0.5, 0.1)
        // 新增：撞墙扣除 15 点体力 (生命值)
        boss.damage(15.0)
        // 提示附近 5 格玩家
        val nearby = boss.getNearbyEntities(10.0, 10.0, 10.0).filterIsInstance<Player>()
        nearby.forEach {
            it.sendMessage("§a团长暂时撞晕了！尽快输出他！")
        }

        // 施加 2 秒眩晕 (无法移动，同时施加虚弱以防期间普攻)
        boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 255, false, false))
        boss.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 40, 255, false, false))

        // 眩晕 2 秒结束后，才开始计算技能的 13 秒 CD
        object : BukkitRunnable() {
            override fun run() {
                if (!boss.isDead) {
                    scheduleNextSkill(skillCooldown)
                }
            }
        }.runTaskLater(plugin, 40L)
    }
}
