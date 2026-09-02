package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.impl.ElementFormationTierEffects
import com.hjh_database.spawner.MobFactory
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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class Mazeituantuanzhang(private val plugin: Hjh_database, private val boss: LivingEntity) : Listener {

    private var isChanneling = false
    private var isDashing = false
    private var armorBreakGeneration = 0
    private val skillCooldown = 13 * 20L // 13秒 CD
    private val initialDelay = 5 * 20L   // 出生后 5秒 首发

    init {
        applyPassiveSkill()
        BossTargetingUtil.start(plugin, boss, radius = 48.0, chaseSpeed = 0.2, minChaseDistance = 5.0) {
            !isChanneling && !isDashing && !boss.hasPotionEffect(PotionEffectType.SLOWNESS)
        }

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
        isDashing = false
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
        isDashing = true
        boss.world.playSound(boss.location, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 1.0f)

        var distanceTraveled = 0.0
        val maxDistance = 15.0
        val speed = 1.3 // 每 Tick 1.3 格，极快的冲刺

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    isDashing = false
                    cancel()
                    return
                }
                if (distanceTraveled >= maxDistance) {
                    finishDash()
                    cancel()
                    return
                }

                // 最后一 Tick 只移动剩余距离，避免越过冲锋终点。
                val stepDistance = minOf(speed, maxDistance - distanceTraveled)
                val dashVelocity = direction.clone().multiply(stepDistance)
                if (!boss.isOnGround) {
                    dashVelocity.y = boss.velocity.y
                }
                boss.velocity = dashVelocity
                distanceTraveled += stepDistance

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
                        p.setMetadata(
                            ElementFormationTierEffects.MONSTER_SKILL_DAMAGE_METADATA,
                            org.bukkit.metadata.FixedMetadataValue(plugin, true)
                        )
                        try {
                            p.damage(20.0, boss)
                        } finally {
                            p.removeMetadata(ElementFormationTierEffects.MONSTER_SKILL_DAMAGE_METADATA, plugin)
                        }
                    }

                    // 撞到人提前停止，进入常规 CD
                    finishDash()
                    cancel()
                    return
                }

                // 2. 【碰撞障碍物检测 (精准射线法)】
                // 从头部 (Y+1.8) 和脚底 (Y+0.2) 向冲锋方向发出射线。
                // 头部射线高于 1.5 格台阶：台阶只触发起跳，真正的两格高墙才会触发撞墙眩晕。
                val rayDist = stepDistance + 0.5

                // 探测头部：判断是不是 2 格高的死胡同
                val eyeLoc = currentLoc.clone().add(0.0, 1.8, 0.0)
                // true 参数代表忽略高草丛、藤蔓等可以穿透的方块
                val headRay = boss.world.rayTraceBlocks(eyeLoc, direction, rayDist, org.bukkit.FluidCollisionMode.NEVER, true)

                if (headRay != null && headRay.hitBlock != null && !headRay.hitBlock!!.isPassable) {
                    // 头前方的射线碰到了不可穿过的方块 (比如两格高的仙人掌、墙壁) -> 直接眩晕！
                    isDashing = false
                    applyStun()
                    cancel()
                    return
                }

                // 探测脚底：如果头没撞到墙，看看脚底有没有被 1格 / 1.5格 的东西挡住
                val footLoc = currentLoc.clone().add(0.0, 0.2, 0.0)
                val footRay = boss.world.rayTraceBlocks(footLoc, direction, rayDist, org.bukkit.FluidCollisionMode.NEVER, true)

                if (footRay != null && footRay.hitBlock != null && !footRay.hitBlock!!.isPassable) {
                    // 脚被挡住了，但头没挡住 -> 起跳越过至多 1.5 格的台阶。
                    // 只在落地或开始下落时补一次跳跃力，避免连续射线把 Boss 不断向上抬升。
                    if (boss.isOnGround || boss.velocity.y <= 0.0) {
                        boss.velocity = boss.velocity.setY(0.75)
                    }
                }

            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun finishDash() {
        isDashing = false
        boss.velocity = Vector(0.0, 0.0, 0.0)
        scheduleNextSkill(skillCooldown)
    }

    // ==========================================
    // 阶段三：撞墙眩晕与破甲惩罚
    // ==========================================
    private fun applyStun() {
        boss.velocity = Vector(0.0, 0.0, 0.0)
        boss.world.playSound(boss.location, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        boss.world.playSound(boss.location, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1f, 0.5f)

        boss.world.spawnParticle(Particle.CRIT, boss.location.add(0.0, 2.2, 0.0), 30, 0.5, 0.2, 0.5, 0.1)
        boss.world.spawnParticle(
            Particle.BLOCK,
            boss.location.clone().add(0.0, 1.0, 0.0),
            35,
            0.65,
            0.8,
            0.65,
            0.05,
            org.bukkit.Material.IRON_BLOCK.createBlockData()
        )

        applyTemporaryArmorBreak()

        val nearby = boss.getNearbyEntities(10.0, 10.0, 10.0).filterIsInstance<Player>()
        nearby.forEach {
            it.sendMessage("§a团长暂时撞晕了，他的护甲大幅度受损！")
        }

        boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, STUN_TICKS, 255, false, false))
        boss.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, STUN_TICKS, 255, false, false))

        object : BukkitRunnable() {
            override fun run() {
                if (!boss.isDead && boss.isValid) {
                    scheduleNextSkill(skillCooldown)
                }
            }
        }.runTaskLater(plugin, STUN_TICKS.toLong())
    }

    private fun applyTemporaryArmorBreak() {
        val pdc = boss.persistentDataContainer
        val originalArmor = pdc.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE) ?: 0.0
        val generation = ++armorBreakGeneration
        pdc.set(
            MobFactory.KEY_CUSTOM_ARMOR,
            PersistentDataType.DOUBLE,
            originalArmor * ARMOR_REMAINING_MULTIPLIER
        )

        object : BukkitRunnable() {
            override fun run() {
                if (generation != armorBreakGeneration || boss.isDead || !boss.isValid) return
                boss.persistentDataContainer.set(
                    MobFactory.KEY_CUSTOM_ARMOR,
                    PersistentDataType.DOUBLE,
                    originalArmor
                )
                boss.world.spawnParticle(
                    Particle.ENCHANT,
                    boss.location.clone().add(0.0, 1.0, 0.0),
                    16,
                    0.5,
                    0.7,
                    0.5,
                    0.0
                )
            }
        }.runTaskLater(plugin, ARMOR_BREAK_TICKS)
    }

    private companion object {
        private const val STUN_TICKS = 50
        private const val ARMOR_BREAK_TICKS = 8L * 20L
        private const val ARMOR_REMAINING_MULTIPLIER = 0.2
    }
}
