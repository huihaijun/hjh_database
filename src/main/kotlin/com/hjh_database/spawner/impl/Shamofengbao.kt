package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

class Shamofengbao(private val plugin: Hjh_database, private val boss: LivingEntity) : Listener {

    private var isCasting = false
    private var nextSkill = 1
    private var skillCooldownTicks = 6 * 20

    // 预加载 PDC Key
    private val storedDamageKey = NamespacedKey(plugin, "stored_arrow_damage")
    private val storedCritKey = NamespacedKey(plugin, "stored_arrow_crit")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyPassiveSkill()
        startMainAI()

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    HandlerList.unregisterAll(this@Shamofengbao)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    // ==========================================
    // 1. 被动与属性修改
    // ==========================================
    private fun applyPassiveSkill() {
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        boss.getAttribute(Attribute.SCALE)?.baseValue = 3.0

        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                // 【修复1】切断旋风人自带的大跳机制
                // 实时监测，如果它试图向上猛扑(Y轴速度剧增)，强行压制到 0.42 (正常跳跃1格的高度)
                val vel = boss.velocity
                if (vel.y > 0.42) {
                    boss.velocity = vel.setY(0.42)
                }

                val loc = boss.location
                boss.world.spawnParticle(Particle.FALLING_DUST, loc.clone().add(0.0, 1.5, 0.0), 8, 1.5, 1.5, 1.5, 0.0, Material.SAND.createBlockData())
                boss.world.spawnParticle(Particle.BLOCK, loc, 5, 1.0, 0.2, 1.0, 0.0, Material.SAND.createBlockData())

                // 【修复3】破解弓箭手无法攻击的问题！
                // 高频扫描周围 4 格的箭矢，趁着它原版风场把箭弹开前拦截它
                val nearbyArrows = boss.getNearbyEntities(4.0, 4.0, 4.0).filterIsInstance<AbstractArrow>()
                for (arrow in nearbyArrows) {
                    if (arrow.isValid && !arrow.isOnGround) {
                        val shooter = arrow.shooter as? Player ?: continue

                        // 防止单根箭矢多次触发
                        if (arrow.hasMetadata("hjh_breeze_hit")) continue
                        arrow.setMetadata("hjh_breeze_hit", org.bukkit.metadata.FixedMetadataValue(plugin, true))

                        // 读取你写在 CombatListener 里的弓箭面板伤害
                        val pdc = arrow.persistentDataContainer
                        val baseDamage = pdc.get(storedDamageKey, PersistentDataType.DOUBLE) ?: 5.0
                        val critChance = pdc.get(storedCritKey, PersistentDataType.DOUBLE) ?: 0.0

                        var finalDamage = baseDamage
                        if (ThreadLocalRandom.current().nextDouble() < critChance) {
                            finalDamage *= 1.5
                            boss.world.spawnParticle(Particle.CRIT, boss.location.add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.1)
                        }

                        // ★ 核心绕过逻辑：
                        // 将这发伤害包装为“玩家(shooter)发起的魔法伤害(HJH_MAGIC_DAMAGE)”
                        // 1. 因为 damager 是玩家而不是 Projectile，旋风人的底层免疫被完美骗过。
                        // 2. 因为带有 HJH_MAGIC_DAMAGE，你的 CombatListener 不会用近战面板去覆盖它，而是当做真实伤害。
                        boss.setMetadata("HJH_MAGIC_DAMAGE", org.bukkit.metadata.FixedMetadataValue(plugin, finalDamage))
                        boss.damage(finalDamage, shooter)

                        boss.world.playSound(boss.location, Sound.ENTITY_ARROW_HIT, 1.0f, 1.0f)
                        arrow.remove() // 直接吞掉箭矢
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L) // 改为每 tick 扫描，确保箭矢万无一失
    }

    @EventHandler
    fun onBreezeShoot(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? LivingEntity ?: return
        if (shooter == boss && event.entity.type == EntityType.BREEZE_WIND_CHARGE) {
            if (Math.random() < 0.5) {
                event.isCancelled = true
            } else {
                val projectile = event.entity
                object : BukkitRunnable() {
                    override fun run() {
                        if (!projectile.isValid || projectile.isDead) {
                            cancel()
                            return
                        }
                        projectile.world.spawnParticle(Particle.SWEEP_ATTACK, projectile.location, 2, 0.5, 0.5, 0.5, 0.0)
                        projectile.world.spawnParticle(Particle.CLOUD, projectile.location, 3, 0.3, 0.3, 0.3, 0.0)
                    }
                }.runTaskTimer(plugin, 0L, 1L)
            }
        }
    }

    private fun startMainAI() {
        object : BukkitRunnable() {
            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                if (isCasting) return

                if (skillCooldownTicks > 0) {
                    skillCooldownTicks--
                    return
                }

                if (nextSkill == 1) {
                    castSkill1GravityField()
                    nextSkill = 2
                } else {
                    castSkill2TornadoBlade()
                    nextSkill = 1
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun finishSkillAndEnterCD() {
        isCasting = false
        skillCooldownTicks = 15 * 20
    }

    // ==========================================
    // 主动技能 1：吸力场 (Gravity Field)
    // ==========================================
    private fun castSkill1GravityField() {
        isCasting = true
        // 【修复2】移除了这里的减速控制，让 Boss 正常滑行

        val nearby = boss.getNearbyEntities(10.0, 10.0, 10.0).filterIsInstance<Player>()
        nearby.forEach { it.sendMessage("§6沙漠风暴即将生成风暴力场，会将周围所有玩家卷入，请尽快离开他！") }
        boss.world.playSound(boss.location, Sound.ENTITY_BREEZE_INHALE, 1f, 0.5f)

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }
                ticks += 5

                // 【修复2】预警圈在整个技能(7秒)期间持续显示
                drawCircle(boss.location, 6.0, Particle.FLAME)

                if (ticks <= 80) {
                    // 前 4 秒吟唱，仅显示预警粒子
                }
                else if (ticks <= 140) {
                    // 后 3 秒引力生效
                    if (ticks == 85) {
                        boss.getNearbyEntities(10.0, 10.0, 10.0).filterIsInstance<Player>().forEach {
                            it.sendMessage("§c快跑！离开力场，不然你会被甩飞的！")
                        }
                    }

                    drawCircle(boss.location, 6.0, Particle.CAMPFIRE_COSY_SMOKE)
                    boss.world.playSound(boss.location, Sound.ITEM_ELYTRA_FLYING, 0.5f, 1.5f)

                    boss.getNearbyEntities(6.0, 6.0, 6.0).filterIsInstance<Player>().forEach { p ->
                        if (!p.isDead && p.gameMode != GameMode.SPECTATOR && p.gameMode != GameMode.CREATIVE) {
                            val pullDir = boss.location.toVector().subtract(p.location.toVector()).normalize()
                            p.velocity = pullDir.multiply(0.12)
                        }
                    }
                }

                if (ticks >= 140) {
                    boss.world.spawnParticle(Particle.EXPLOSION_EMITTER, boss.location, 1)
                    boss.world.playSound(boss.location, Sound.ENTITY_BREEZE_WIND_BURST, 2f, 0.5f)

                    boss.getNearbyEntities(6.0, 6.0, 6.0).filterIsInstance<Player>().forEach { p ->
                        if (!p.isDead && p.gameMode != GameMode.SPECTATOR && p.gameMode != GameMode.CREATIVE) {
                            p.damage(10.0, boss)
                            val pushDir = p.location.toVector().subtract(boss.location.toVector()).normalize().setY(0)
                            p.velocity = pushDir.multiply(0.5).setY(1.3)
                        }
                    }

                    finishSkillAndEnterCD()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    // ==========================================
    // 主动技能 2：龙卷刃 (Tornado Blade)
    // ==========================================
    private fun castSkill2TornadoBlade() {
        isCasting = true
        // 【修复2】给 Boss 施加长达 11 秒(吟唱5秒 + 持续射击6秒) 的定身
        boss.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 11 * 20, 10, false, false))

        val nearby = boss.getNearbyEntities(10.0, 10.0, 10.0).filterIsInstance<Player>()
        nearby.forEach { it.sendMessage("§6沙漠风暴即将对你的位置连续刮起龙卷风，请注意走位！") }
        boss.world.playSound(boss.location, Sound.BLOCK_CONDUIT_AMBIENT, 2f, 2f)

        object : BukkitRunnable() {
            var ticks = 0
            var shotsFired = 0

            override fun run() {
                if (boss.isDead || !boss.isValid) {
                    cancel()
                    return
                }

                ticks += 5

                // 阶段一：前 5 秒 (100 ticks) 吟唱，蓄力特效
                if (ticks <= 100) {
                    boss.world.spawnParticle(Particle.CLOUD, boss.location.add(0.0, 1.5, 0.0), 5, 1.0, 1.0, 1.0, 0.1)
                    return
                }

                // 阶段二：【修复2】5秒后，每 1 秒 (20 ticks) 重新索敌并射击一次，共计 6 发
                if ((ticks - 100) % 20 == 0) {
                    val target = boss.getNearbyEntities(10.0, 10.0, 10.0)
                        .filterIsInstance<Player>()
                        .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
                        .minByOrNull { it.location.distanceSquared(boss.location) }

                    if (target != null) {
                        val startLoc = boss.location.clone().add(0.0, 1.5, 0.0)
                        val targetLoc = target.location.clone().add(0.0, 1.0, 0.0)
                        val direction = targetLoc.toVector().subtract(startLoc.toVector()).normalize()

                        boss.world.playSound(boss.location, Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.5f)
                        launchTornadoProjectile(startLoc, direction)
                    }

                    shotsFired++
                    if (shotsFired >= 6) { // 打满 6 发收工
                        finishSkillAndEnterCD()
                        cancel()
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun launchTornadoProjectile(startLoc: Location, direction: Vector) {
        object : BukkitRunnable() {
            var ticks = 0
            // 【修复2】移速从 0.16 提升至 0.35，压迫感增强
            val speed = 0.35
            val currentLoc = startLoc.clone()
            val hitPlayers = mutableSetOf<Player>()

            override fun run() {
                if (boss.isDead || ticks >= 80) { // 飞 4 秒自动消散
                    cancel()
                    return
                }

                ticks++
                currentLoc.add(direction.clone().multiply(speed))

                currentLoc.world.spawnParticle(Particle.CLOUD, currentLoc, 10, 0.8, 1.5, 0.8, 0.0)
                currentLoc.world.spawnParticle(Particle.GUST, currentLoc, 1, 0.4, 0.8, 0.4, 0.0)

                if (ticks % 5 == 0) {
                    currentLoc.world.playSound(currentLoc, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.5f, 1f)
                }

                val nearby = currentLoc.world.getNearbyEntities(currentLoc, 1.2, 2.0, 1.2)
                    .filterIsInstance<Player>()
                    .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }

                for (p in nearby) {
                    if (!hitPlayers.contains(p)) {
                        hitPlayers.add(p)
                        p.damage(6.0, boss)
                        p.velocity = Vector(0.0, 1.2, 0.0)
                        p.world.playSound(p.location, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun drawCircle(center: Location, radius: Double, particle: Particle) {
        val points = 40
        for (i in 0 until points) {
            val angle = 2 * Math.PI * i / points
            val x = cos(angle) * radius
            val z = sin(angle) * radius
            center.world.spawnParticle(particle, center.clone().add(x, 0.2, z), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }
}