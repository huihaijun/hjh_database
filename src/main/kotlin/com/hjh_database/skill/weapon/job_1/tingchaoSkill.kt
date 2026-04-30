package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class tingchaoSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 数据结构：浪的层数与过期时间
    data class WaveData(var layers: Int, var expireTime: Long)

    // 状态记录表
    private val waveMarks = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, WaveData>>()
    private val tideLines = ConcurrentHashMap<UUID, UUID>()
    private val lastPassiveHit = ConcurrentHashMap<UUID, Long>()
    private val activeCooldowns = ConcurrentHashMap<UUID, Long>()
    private val activeComboDR = ConcurrentHashMap<UUID, Long>()
    private val lastPlayerLocs = ConcurrentHashMap<UUID, Location>()

    // 【修改位置 1】新增：记录玩家当前连接的海潮线目标，用于切换发亮特效
    private val currentTideTarget = ConcurrentHashMap<UUID, Entity>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        object : BukkitRunnable() {
            override fun run() {
                val now = System.currentTimeMillis()

                for (player in Bukkit.getOnlinePlayers()) {
                    val uuid = player.uniqueId
                    val waves = waveMarks[uuid]

                    // 1. 清理过期的浪
                    waves?.entries?.removeIf { now > it.value.expireTime }

                    // 2. 检查或重新建立海潮线
                    var currentTargetId = tideLines[uuid]

                    if (currentTargetId == null && waves != null) {
                        var closestDist = 10.0
                        var closestTarget: UUID? = null

                        for ((entId, wData) in waves) {
                            if (wData.layers >= 5) {
                                val ent = Bukkit.getEntity(entId) as? LivingEntity
                                if (ent != null && !ent.isDead) {
                                    val dist = player.location.distance(ent.location)
                                    if (dist <= 10.0 && dist < closestDist) {
                                        closestDist = dist
                                        closestTarget = entId
                                    }
                                }
                            }
                        }
                        if (closestTarget != null) {
                            tideLines[uuid] = closestTarget
                            currentTargetId = closestTarget
                        }
                    }

                    // 3. 处理海潮线逻辑 (绘制与发亮)
                    if (currentTargetId != null) {
                        val target = Bukkit.getEntity(currentTargetId) as? LivingEntity
                        val targetWaves = waves?.get(currentTargetId)?.layers ?: 0

                        if (target == null || target.isDead || player.location.distance(target.location) > 10.0 || targetWaves < 5) {
                            tideLines.remove(uuid)
                            updateSpeedBuff(player, false)
                            // 【修改位置 2】目标断开连接，取消发光
                            target?.isGlowing = false
                            currentTideTarget.remove(uuid)
                        } else {
                            // 【修改位置 2】切换海潮线目标时，自动应用原版发光 (Glow)
                            val oldTarget = currentTideTarget[uuid]
                            if (oldTarget != target) {
                                oldTarget?.isGlowing = false
                                target.isGlowing = true
                                currentTideTarget[uuid] = target
                                // 切换时的水滴提示音
                                player.playSound(player.location, Sound.BLOCK_WATER_AMBIENT, 1.0f, 2.0f)
                            }

                            // 绘制水滴特效连线
                            drawTideLine(player.eyeLocation.clone().subtract(0.0, 0.5, 0.0), target.location.clone().add(0.0, 1.0, 0.0))

                            val lastLoc = lastPlayerLocs[uuid]
                            if (lastLoc != null) {
                                val oldDist = lastLoc.distance(target.location)
                                val newDist = player.location.distance(target.location)
                                if (newDist < oldDist - 0.02) {
                                    updateSpeedBuff(player, true)
                                } else {
                                    updateSpeedBuff(player, false)
                                }
                            }
                        }
                    } else {
                        updateSpeedBuff(player, false)
                        // 保底清理发光
                        currentTideTarget.remove(uuid)?.isGlowing = false
                    }
                    lastPlayerLocs[uuid] = player.location.clone()
                }
            }
        }.runTaskTimer(plugin, 0L, 4L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        if (now < activeCooldowns.getOrDefault(uuid, 0L)) return false

        val targetId = tideLines[uuid] ?: return false
        val target = Bukkit.getEntity(targetId) as? LivingEntity ?: return false

        activeCooldowns[uuid] = now + 2000L

        val msg = "&a&l武器技【斩海断浪】发动！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', msg)))

        // 移除该目标所有的浪与海潮线
        tideLines.remove(uuid)
        waveMarks[uuid]?.remove(targetId)
        updateSpeedBuff(player, false)

        // 释放技能时取消怪物的发光
        target.isGlowing = false
        currentTideTarget.remove(uuid)

        activeComboDR[uuid] = now + 3000L

        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || target.isDead) return

                // 【修改位置 3】距离智能动量判断，防止贴脸冲刺滑出去
                val dist = player.location.distance(target.location)
                val speedMultiplier = when {
                    dist <= 1.5 -> 0.05  // 如果就在脸前，几乎不产生向前的位移
                    dist <= 3.0 -> 0.4   // 中等距离，稍微加速贴近
                    else -> 1.5          // 远距离，全速突进
                }

                // 设置 Y 轴稍微高一点防卡墙，但距离近时不跳太高
                val yBoost = if (dist <= 1.5) 0.1 else 0.3
                val dir = target.location.toVector().subtract(player.location.toVector()).normalize()
                player.velocity = dir.multiply(speedMultiplier).setY(yBoost)
                player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.5f)

                target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 10, 255, false, false))
                target.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 10, 1, false, false))
            }
        }.runTaskLater(plugin, 1L)

        object : BukkitRunnable() {
            var hits = 0
            override fun run() {
                if (hits >= 4 || !player.isOnline || target.isDead) {
                    activeComboDR.remove(uuid)
                    cancel()
                    return
                }

                if (player.location.distance(target.location) > 3.0) {
                    activeComboDR.remove(uuid)
                    cancel()
                    return
                }

                val multiplier = 2.0 + (hits * 0.5)
                val damageAmount = data.archerDamage * multiplier

                val playerDir = player.location.direction.normalize()
                val nearby = player.world.getNearbyEntities(player.location, 3.0, 3.0, 3.0)
                var hitAny = false

                for (ent in nearby) {
                    if (ent is LivingEntity && ent != player && ent.scoreboardTags.contains("panling") && ent.scoreboardTags.contains("monster")) {
                        val toEnt = ent.location.toVector().subtract(player.location.toVector()).normalize()
                        if (playerDir.dot(toEnt) > 0.4 || player.location.distance(ent.location) < 1.2) {

                            ent.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                            ent.noDamageTicks = 0
                            try {
                                ent.damage(damageAmount, player)
                            } finally {
                                ent.removeMetadata("hjh_physical_skill", plugin)
                                ent.noDamageTicks = 0
                            }

                            val pWaves = waveMarks.computeIfAbsent(uuid) { ConcurrentHashMap() }
                            val waveData = pWaves.computeIfAbsent(ent.uniqueId) { WaveData(0, 0L) }
                            if (waveData.layers < 5) waveData.layers++
                            waveData.expireTime = System.currentTimeMillis() + 4000L

                            // 【修改位置 4】1.21.3 专属气势斩击特效
                            val pLoc = ent.location.add(0.0, ent.height / 2, 0.0)

                            // 1. 横扫剑气
                            ent.world.spawnParticle(Particle.SWEEP_ATTACK, pLoc, 1, 0.2, 0.2, 0.2, 0.0)
                            // 2. 基础飞溅水花
                            ent.world.spawnParticle(Particle.SPLASH, pLoc, 10, 0.4, 0.4, 0.4, 0.2)

                            // 3. 1.21.3 彩色水汽 (Cyan 青色 和 DodgerBlue 躲避蓝 结合)
                            val cyanDust = Particle.DustOptions(org.bukkit.Color.fromRGB(0, 255, 255), 1.5f)
                            val blueDust = Particle.DustOptions(org.bukkit.Color.fromRGB(30, 144, 255), 1.2f)
                            ent.world.spawnParticle(Particle.DUST, pLoc, 8, 0.4, 0.4, 0.4, 1.0, cyanDust)
                            ent.world.spawnParticle(Particle.DUST, pLoc, 6, 0.4, 0.4, 0.4, 1.0, blueDust)

                            // 4. 1.21 特有的深色重击粒子 (不祥探测)，增强力量感
                            ent.world.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, pLoc, 4, 0.3, 0.3, 0.3, 0.02)

                            hitAny = true
                        }
                    }
                }

                if (hitAny) {
                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.2f)
                }
                hits++
            }
        }.runTaskTimer(plugin, 5L, 15L)

        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCombatInteractions(event: EntityDamageByEntityEvent) {
        val now = System.currentTimeMillis()

        if (event.entity is Player) {
            val player = event.entity as Player
            val damager = event.damager as? LivingEntity ?: return
            val uuid = player.uniqueId

            var reductionPercent = 0.0

            val comboExpire = activeComboDR[uuid]
            if (comboExpire != null) {
                if (now < comboExpire) {
                    reductionPercent += 0.20
                } else {
                    activeComboDR.remove(uuid)
                }
            }

            val pWaves = waveMarks[uuid]
            if (pWaves != null) {
                val waveData = pWaves[damager.uniqueId]
                if (waveData != null && now < waveData.expireTime) {
                    reductionPercent += (waveData.layers * 0.05)
                }
            }

            if (reductionPercent > 0.0) {
                if (reductionPercent > 1.0) reductionPercent = 1.0
                event.damage = event.damage * (1.0 - reductionPercent)
            }
            return
        }

        val arrow = event.damager as? AbstractArrow ?: return
        val shooter = arrow.shooter as? Player ?: return
        val target = event.entity as? LivingEntity ?: return

        if (!target.scoreboardTags.contains("panling") || !target.scoreboardTags.contains("monster")) return

        val weaponKey = NamespacedKey(plugin, "weapon_id")
        val item = shooter.inventory.itemInMainHand
        if (!item.hasItemMeta()) return
        val weaponId = item.itemMeta?.persistentDataContainer?.get(weaponKey, PersistentDataType.STRING)

        if (weaponId == "tingchao") {
            if (now - lastPassiveHit.getOrDefault(shooter.uniqueId, 0L) < 1000L) return
            lastPassiveHit[shooter.uniqueId] = now

            val pWaves = waveMarks.computeIfAbsent(shooter.uniqueId) { ConcurrentHashMap() }
            val waveData = pWaves.computeIfAbsent(target.uniqueId) { WaveData(0, 0L) }

            if (waveData.layers < 5) waveData.layers++
            waveData.expireTime = now + 4000L

            target.world.spawnParticle(Particle.DRIPPING_WATER, target.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.1)
        }
    }

    private fun updateSpeedBuff(player: Player, state: Boolean) {
        val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        if (state) {
            if (pData.tempBonuses["tingchao_speed"] != 0.50) {
                pData.tempBonuses["tingchao_speed"] = 0.50
                pluginMain.playerManager.updateStats(player)
            }
        } else {
            if (pData.tempBonuses.containsKey("tingchao_speed")) {
                pData.tempBonuses.remove("tingchao_speed")
                pluginMain.playerManager.updateStats(player)
            }
        }
    }

    private fun drawTideLine(loc1: Location, loc2: Location) {
        val dist = loc1.distance(loc2)
        if (dist <= 0) return
        val vec = loc2.toVector().subtract(loc1.toVector()).normalize().multiply(0.5)
        var currentLoc = loc1.clone()

        val steps = (dist / 0.5).toInt()
        for (i in 0..steps) {
            currentLoc.world?.spawnParticle(Particle.DRIPPING_WATER, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
            currentLoc.add(vec)
        }
    }

    override fun deactivate(player: Player) {
        val uuid = player.uniqueId

        waveMarks.remove(uuid)
        tideLines.remove(uuid)
        activeComboDR.remove(uuid)
        lastPassiveHit.remove(uuid)
        lastPlayerLocs.remove(uuid)

        updateSpeedBuff(player, false)

        // 【修改位置 5】失活时清理发光状态
        currentTideTarget.remove(uuid)?.isGlowing = false
    }
}