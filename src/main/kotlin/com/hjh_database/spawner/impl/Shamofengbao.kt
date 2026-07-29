package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
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
        BossTargetingUtil.start(plugin, boss, radius = 48.0, chaseSpeed = 0.22, minChaseDistance = 7.0) {
            !isCasting && !boss.hasPotionEffect(PotionEffectType.SLOWNESS)
        }
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

                // 【修复3】破解弓箭手无法攻击的问题，同时保留箭矢命中事件链。
                // 原版 Breeze 会把箭矢弹开，所以这里仍然高频扫描并拦截箭。
                // 关键变化：不要直接 boss.damage(...) 结束，而是先补发一次“箭矢 -> Boss”的伤害事件，
                // 让 yantiegongSkill 等依赖 AbstractArrow 命中的武器技能可以正常叠层/引爆。
                val nearbyArrows = boss.getNearbyEntities(4.0, 4.0, 4.0).filterIsInstance<AbstractArrow>()
                for (arrow in nearbyArrows) {
                    if (arrow.isValid && !arrow.isOnGround) {
                        val shooter = arrow.shooter as? Player ?: continue

                        // 防止单根箭矢多次触发
                        if (arrow.hasMetadata("hjh_breeze_hit")) continue
                        arrow.setMetadata("hjh_breeze_hit", org.bukkit.metadata.FixedMetadataValue(plugin, true))

                        handleInterceptedArrowHit(arrow, shooter)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L) // 改为每 tick 扫描，确保箭矢万无一失
    }


    /**
     * 处理被 Breeze 风场拦截的玩家箭矢。
     *
     * 设计目标：
     * 1. 仍然绕过 Breeze 原版“弹箭/免疫箭”的问题，让弓箭手能打到沙漠风暴；
     * 2. 先补发 EntityDamageByEntityEvent，且 damager 保持为 AbstractArrow，
     *    这样 yantiegongSkill 这类监听箭矢命中的技能可以正常触发；
     * 3. 最终基础箭伤仍用 HJH_MAGIC_DAMAGE 包一层，由 Player 作为 damager 结算，
     *    避免再次被 Breeze 底层投射物机制吃掉。
     */
    private fun handleInterceptedArrowHit(arrow: AbstractArrow, shooter: Player) {
        val pdc = arrow.persistentDataContainer
        val baseDamage = pdc.get(storedDamageKey, PersistentDataType.DOUBLE) ?: 5.0
        val critChance = pdc.get(storedCritKey, PersistentDataType.DOUBLE) ?: 0.0

        var finalDamage = baseDamage
        if (ThreadLocalRandom.current().nextDouble() < critChance) {
            finalDamage *= 1.5
            boss.world.spawnParticle(Particle.CRIT, boss.location.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.1)
        }

        // 先补发“箭矢命中 Boss”的事件。
        // yantiegongSkill.onArrowHit 里要求 event.damager 是 AbstractArrow，
        // 所以这里必须用 arrow 作为 damager，不能直接用 shooter。
        val damageSource = DamageSource.builder(DamageType.ARROW)
            .withDirectEntity(arrow)
            .withCausingEntity(shooter)
            .build()

        val hitEvent = EntityDamageByEntityEvent(
            arrow,
            boss,
            EntityDamageEvent.DamageCause.PROJECTILE,
            damageSource,
            finalDamage
        )
        plugin.server.pluginManager.callEvent(hitEvent)

        // 如果其他插件/监听器明确取消了这次箭矢命中，则只移除箭，不再造成基础箭伤。
        if (!hitEvent.isCancelled && boss.isValid && !boss.isDead && hitEvent.finalDamage > 0.0) {
            damageBossAsBypassedArrowDamage(shooter, hitEvent.finalDamage)
            boss.world.playSound(boss.location, Sound.ENTITY_ARROW_HIT, 1.0f, 1.0f)
        }

        arrow.remove()
    }

    /**
     * 用玩家作为最终 damager 结算基础箭伤，避免再次触发 Breeze 对 Projectile 的免疫/弹开逻辑。
     * 注意：这里用 try/finally 清理 HJH_MAGIC_DAMAGE，防止这个 metadata 残留到后续其他伤害。
     */
    private fun damageBossAsBypassedArrowDamage(shooter: Player, amount: Double) {
        boss.setMetadata("HJH_MAGIC_DAMAGE", org.bukkit.metadata.FixedMetadataValue(plugin, amount))
        boss.noDamageTicks = 0
        try {
            boss.damage(amount, shooter)
        } finally {
            if (boss.hasMetadata("HJH_MAGIC_DAMAGE")) {
                boss.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            boss.noDamageTicks = 0
        }
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
        boss.removePotionEffect(PotionEffectType.SLOWNESS)
        skillCooldownTicks = 15 * 20
    }

    // ==========================================
    // 主动技能 1：吸力场 (Gravity Field)
    // ==========================================
    private fun castSkill1GravityField() {
        isCasting = true
        // 【修复2】移除了这里的减速控制，让 Boss 正常滑行

        val nearby = boss.getNearbyEntities(
            GRAVITY_FIELD_RADIUS,
            GRAVITY_FIELD_RADIUS,
            GRAVITY_FIELD_RADIUS
        ).filterIsInstance<Player>()
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
                drawCircle(boss.location, GRAVITY_FIELD_RADIUS, Particle.FLAME)

                if (ticks <= 80) {
                    // 前 4 秒吟唱，仅显示预警粒子
                }
                else if (ticks <= 140) {
                    // 后 3 秒引力生效
                    if (ticks == 85) {
                        boss.getNearbyEntities(
                            GRAVITY_FIELD_RADIUS,
                            GRAVITY_FIELD_RADIUS,
                            GRAVITY_FIELD_RADIUS
                        ).filterIsInstance<Player>().forEach {
                            it.sendMessage("§c快跑！离开力场，不然你会被甩飞的！")
                        }
                    }

                    drawCircle(boss.location, GRAVITY_FIELD_RADIUS, Particle.CAMPFIRE_COSY_SMOKE)
                    boss.world.playSound(boss.location, Sound.ITEM_ELYTRA_FLYING, 0.5f, 1.5f)

                    boss.getNearbyEntities(
                        GRAVITY_FIELD_RADIUS,
                        GRAVITY_FIELD_RADIUS,
                        GRAVITY_FIELD_RADIUS
                    ).filterIsInstance<Player>().forEach { p ->
                        if (!p.isDead && p.gameMode != GameMode.SPECTATOR && p.gameMode != GameMode.CREATIVE) {
                            val pullDir = boss.location.toVector().subtract(p.location.toVector())
                            if (pullDir.lengthSquared() > 0.0) {
                                p.velocity = pullDir.normalize().multiply(0.12)
                            }
                        }
                    }
                }

                if (ticks >= 140) {
                    boss.world.spawnParticle(Particle.EXPLOSION_EMITTER, boss.location, 1)
                    boss.world.playSound(boss.location, Sound.ENTITY_BREEZE_WIND_BURST, 2f, 0.5f)

                    boss.getNearbyEntities(
                        GRAVITY_FIELD_RADIUS,
                        GRAVITY_FIELD_RADIUS,
                        GRAVITY_FIELD_RADIUS
                    ).filterIsInstance<Player>().forEach { p ->
                        if (!p.isDead && p.gameMode != GameMode.SPECTATOR && p.gameMode != GameMode.CREATIVE) {
                            p.damage(10.0, boss)
                            val pushDir = p.location.toVector().subtract(boss.location.toVector()).setY(0)
                            p.velocity = if (pushDir.lengthSquared() > 0.0) {
                                pushDir.normalize().multiply(0.5).setY(1.3)
                            } else {
                                Vector(0.0, 1.3, 0.0)
                            }
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
            val hitPlayers = mutableSetOf<UUID>()

            override fun run() {
                if (boss.isDead || ticks >= 80) { // 飞 4 秒自动消散
                    cancel()
                    return
                }

                ticks++
                val previousLoc = currentLoc.clone()
                currentLoc.add(direction.clone().multiply(speed))

                currentLoc.world.spawnParticle(Particle.CLOUD, currentLoc, 10, 0.8, 1.5, 0.8, 0.0)
                currentLoc.world.spawnParticle(Particle.GUST, currentLoc, 1, 0.4, 0.8, 0.4, 0.0)

                if (ticks % 5 == 0) {
                    currentLoc.world.playSound(currentLoc, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.5f, 1f)
                }

                damagePlayersAlongTornadoPath(previousLoc, currentLoc, hitPlayers)
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    /**
     * 龙卷刃不与方块碰撞。分段扫描同时避免高速轨迹从玩家身上跨过去。
     */
    private fun damagePlayersAlongTornadoPath(
        from: Location,
        to: Location,
        hitPlayers: MutableSet<UUID>
    ) {
        val movement = to.toVector().subtract(from.toVector())
        val samples = ceil(movement.length() / TORNADO_HIT_SAMPLE_SPACING).toInt().coerceAtLeast(1)

        for (index in 1..samples) {
            val sampleLoc = from.clone().add(movement.clone().multiply(index.toDouble() / samples))
            sampleLoc.world.getNearbyEntities(sampleLoc, 1.2, 2.0, 1.2)
                .filterIsInstance<Player>()
                .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR && it.gameMode != GameMode.CREATIVE }
                .filter { hitPlayers.add(it.uniqueId) }
                .forEach { player ->
                    player.damage(6.0, boss)
                    player.velocity = Vector(0.0, 1.2, 0.0)
                    player.world.playSound(player.location, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f)
                }
        }
    }

    private fun drawCircle(center: Location, radius: Double, particle: Particle) {
        val points = (radius * 6.0).toInt().coerceAtLeast(40)
        for (i in 0 until points) {
            val angle = 2 * Math.PI * i / points
            val x = cos(angle) * radius
            val z = sin(angle) * radius
            center.world.spawnParticle(particle, center.clone().add(x, 0.2, z), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private companion object {
        private const val GRAVITY_FIELD_RADIUS = 12.0
        private const val TORNADO_HIT_SAMPLE_SPACING = 0.25
    }
}
