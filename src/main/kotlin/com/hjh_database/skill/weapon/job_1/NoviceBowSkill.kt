package com.hjh_database.skill.weapon.job_1

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class NoviceBowSkill : WeaponSkill {

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        // 修改 2：空值检查 (Guard Clause)
        // 如果关键数据缺失，直接认为技能释放失败，避免后续空指针
        if (player == null || data == null || config == null) {
            return false
        }
        // Kotlin 中 config 不会为 null (根据接口定义)，直接获取
        val damagePercent = config.getDouble("damage_percent", 2.0)
        val speed = config.getDouble("flight_speed", 1.5)
        val duration = config.getDouble("flight_duration", 3.0)
        val detectRadius = config.getDouble("detect_radius", 5.0)

        if (projectile == null || projectile !is Projectile) {
            return false
        }

        // 获取玩家数据
        val baseDmg = data.archerDamage
        val finalDmg = baseDmg * damagePercent

        StarArrowTask(
            JavaPlugin.getPlugin(Hjh_database::class.java),
            player,
            projectile,
            finalDmg,
            speed,
            duration,
            detectRadius
        ).runTaskTimer(JavaPlugin.getPlugin(Hjh_database::class.java), 0L, 1L)

        // 技能启动音效：清脆的充能声
        // 使用 !! 断言 world 非空
        player.world.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 2.0f)
        return true
    }

    private inner class StarArrowTask(
        private val plugin: Hjh_database,
        private val shooter: Player,
        private val leaderArrow: Projectile,
        private val damage: Double,
        private val speed: Double,
        duration: Double,
        private val detectRadius: Double
    ) : BukkitRunnable() {

        private val maxTicks: Int
        private var currentLoc: Location
        private var direction: Vector
        private var ticks = 0
        private var lockedTarget: LivingEntity? = null
        private var lastKnownArrowLoc: Location
        private val launchDelay = 10

        init {
            this.maxTicks = (duration * 20).toInt() + launchDelay
            this.currentLoc = getRightSideLocation(shooter)
            this.direction = shooter.location.direction
            this.lastKnownArrowLoc = leaderArrow.location
        }

        override fun run() {
            // ticks++ 逻辑
            if (ticks++ >= maxTicks || !shooter.isOnline) {
                playFizzEffect()
                this.cancel()
                return
            }

            if (leaderArrow.isValid && !leaderArrow.isDead) {
                lastKnownArrowLoc = leaderArrow.location
            }

            // 1. 蓄力阶段 (星辰汇聚)
            if (ticks <= launchDelay) {
                currentLoc = getRightSideLocation(shooter)
                playChargeEffect()

                // 伴随蓄力的细微铃声
                if (ticks % 3 == 0) {
                    // 使用 !! 断言 world 非空
                    currentLoc.world!!.playSound(currentLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 2.0f)
                }

                if (ticks == launchDelay) launch()
                return
            }

            // 2. 飞行阶段
            if (lockedTarget != null) {
                // 检查 lockedTarget 是否有效
                // 注意：lockedTarget 是可空类型，使用 ?. 访问安全，但在逻辑判断中我们要确保非空
                val target = lockedTarget!!
                if (!target.isDead && target.isValid) {
                    driveTowards(target.location.add(0.0, target.height / 2.0, 0.0))
                } else {
                    lockedTarget = null
                }
            } else {
                if (leaderArrow.isValid && !leaderArrow.isDead && !leaderArrow.isOnGround) {
                    driveTowards(leaderArrow.location)
                } else {
                    val found = findNearestValidTarget(lastKnownArrowLoc, detectRadius)
                    if (found != null) {
                        lockedTarget = found
                        // 锁定目标时的提示音：高音叮一下
                        shooter.playSound(shooter.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 2.0f)
                    } else {
                        if (currentLoc.distance(lastKnownArrowLoc) > 1.0) driveTowards(lastKnownArrowLoc)
                        val passing = findNearestValidTarget(currentLoc, 3.0)
                        if (passing != null) lockedTarget = passing
                    }
                }
            }

            currentLoc.add(direction.clone().multiply(speed))
            playFlyEffect()
            checkCollision()
        }

        private fun launch() {
            // 发射音效：替换为更空灵的三叉戟投掷声 + 紫水晶击打声
            val w = currentLoc.world!!
            w.playSound(currentLoc, Sound.ITEM_TRIDENT_THROW, 1.0f, 2.0f)
            w.playSound(currentLoc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 2.0f)

            if (!leaderArrow.isValid || leaderArrow.isDead || leaderArrow.isOnGround) {
                lockedTarget = findNearestValidTarget(lastKnownArrowLoc, detectRadius)
                if (lockedTarget != null) {
                    direction = lockedTarget!!.location.subtract(currentLoc).toVector().normalize()
                } else {
                    direction = lastKnownArrowLoc.toVector().subtract(currentLoc.toVector()).normalize()
                }
            } else {
                direction = leaderArrow.location.toVector().subtract(currentLoc.toVector()).normalize()
            }
        }

        private fun driveTowards(targetLoc: Location) {
            val toTarget = targetLoc.toVector().subtract(currentLoc.toVector()).normalize()
            direction = direction.add(toTarget.multiply(0.2)).normalize()
        }

        private fun checkCollision() {
            // 使用 !! 断言 world 非空
            for (e in currentLoc.world!!.getNearbyEntities(currentLoc, 1.0, 1.0, 1.0)) {
                if (isValidTarget(e)) {
                    hit(e as LivingEntity)
                    return
                }
            }
            if (currentLoc.block.type.isSolid) {
                playFizzEffect()
                this.cancel()
            }
        }

        private fun hit(victim: LivingEntity) {
            // 伤害逻辑 (保持不变)
            victim.noDamageTicks = 0
            victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
            victim.damage(damage, shooter)

            // === 命中特效 (完全重写) ===
            val w = victim.world // 这里不需要 !!，因为 World 对象本身在 Bukkit 实体中通常非空

            // 1. 核心闪光 (代替爆炸)
            w.spawnParticle(Particle.FLASH, victim.location.add(0.0, 1.0, 0.0), 1)

            // 2. 星屑爆散 (代替烟雾)
            // 使用 FIREWORKS_SPARK 但不加声音，或者用 WAX_ON (星星闪烁)
            w.spawnParticle(Particle.WAX_OFF, victim.location.add(0.0, 1.0, 0.0), 15, 0.5, 0.5, 0.5, 0.1)
            w.spawnParticle(Particle.END_ROD, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.05)

            // 3. 命中音效：紫水晶破碎声 (清脆)
            w.playSound(victim.location, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 1.5f)
            w.playSound(victim.location, Sound.BLOCK_GLASS_BREAK, 0.5f, 2.0f)

            this.cancel()
        }

        // 辅助方法
        private fun getRightSideLocation(p: Player): Location {
            val eye = p.eyeLocation
            val right = eye.direction.crossProduct(Vector(0, 1, 0)).normalize()
            return eye.add(right.multiply(0.8)).add(0.0, -0.3, 0.0)
        }

        // 蓄力特效：金色的星光汇聚
        private fun playChargeEffect() {
            val w = currentLoc.world!!
            for (i in 0 until 2) {
                val offset = Vector.getRandom().subtract(Vector(0.5, 0.5, 0.5)).normalize().multiply(0.5)
                val start = currentLoc.clone().add(offset)
                // 金黄色 (255, 215, 0)
                w.spawnParticle(
                    Particle.DUST, start, 0,
                    -offset.x, -offset.y, -offset.z, 0.1,
                    Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.5f)
                )
            }
            // 偶尔闪烁的白色星光
            if (ticks % 2 == 0) {
                w.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.1, 0.1, 0.1, 0.0)
            }
        }

        // 飞行特效：流星拖尾
        private fun playFlyEffect() {
            val w = currentLoc.world!!
            // 主体：末地烛光 (白色光棒)
            w.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
            // 拖尾：细微的图腾粒子 (绿色/金色的星尘感)
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
        }

        private fun playFizzEffect() {
            // 消散时变成一点点星光消失
            currentLoc.world!!.spawnParticle(Particle.WAX_OFF, currentLoc, 5, 0.1, 0.1, 0.1, 0.05)
        }

        private fun findNearestValidTarget(loc: Location, radius: Double): LivingEntity? {
            // Java: stream().filter().map().min().orElse(null)
            // Kotlin: 使用 filter + map + minByOrNull
            val nearby = loc.world!!.getNearbyEntities(loc, radius, radius, radius)

            return nearby.asSequence()
                .filter { isValidTarget(it) }
                .map { it as LivingEntity }
                .minByOrNull { it.location.distance(loc) }
        }

        private fun isValidTarget(entity: Entity): Boolean {
            if (entity === shooter || entity === leaderArrow) return false
            if (entity !is LivingEntity) return false
            val tags = entity.scoreboardTags
            return tags.contains("panling") && tags.contains("monster")
        }
    }
}