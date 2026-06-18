package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JiangTianGuangSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    // 存储被点亮怪物的状态数据：施法者UUID 和 点亮结束的时间戳
    data class MarkData(val casterId: UUID, val expiryTime: Long)

    companion object {
        val activeMarks = ConcurrentHashMap<UUID, MarkData>()
        val healCooldowns = ConcurrentHashMap<UUID, Long>() // 记录上次触发治疗的时间戳 (防高频)
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val damageMultiplier = config?.getDouble("damage_multiplier", 3.0) ?: 3.0
        val durationSeconds = config?.getInt("duration", 10) ?: 10
        val healRadius = config?.getDouble("heal_radius", 4.0) ?: 4.0

        val damage = zfStr * damageMultiplier
        val durationTicks = durationSeconds * 20L

        // 1. 寻找 10 格内最近的怪物
        var nearestMob: LivingEntity? = null
        var minDistance = Double.MAX_VALUE

        val nearby = player.world.getNearbyEntities(player.location, radius, radius, radius)
        for (entity in nearby) {
            if (entity is LivingEntity && entity.uniqueId != player.uniqueId) {
                val tags = entity.scoreboardTags
                if (tags.contains("panling") && tags.contains("monster")) {
                    val dist = entity.location.distanceSquared(player.location)
                    if (dist < minDistance) {
                        minDistance = dist
                        nearestMob = entity
                    }
                }
            }
        }

        // 如果范围内没有怪物，则释放失败（你可以选择返还灵力，或者直接提示）
        if (nearestMob == null) {
            player.sendMessage("§c[降天光] §7附近没有可标记的怪物！")
            return false // 返回 false 通常不会扣除灵力和进入冷却
        }

        // 2. 播放天光降临的特效
        val targetLoc = nearestMob.location
        player.world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 2.0f)
        player.world.playSound(targetLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f)

        // 绘制从天而降的光柱 (使用 END_ROD 粒子从高空垂直下落)
        for (y in 0..10) {
            player.world.spawnParticle(Particle.END_ROD, targetLoc.clone().add(0.0, y.toDouble(), 0.0), 5, 0.2, 0.5, 0.2, 0.0)
        }

        // 3. 造成初始爆发伤害 (因为这发生在标记之前，所以这下伤害不会触发后续的受击回血)
        plugin.medicalSpellManager.applyMedicalDamage(player, nearestMob, damage, "jiangtianguang")

        // 4. 点亮怪物并记录标记
        // 使用 1.20+ 最新的药水效果枚举名称，GLOWING 让怪物隔墙可见且高亮
        nearestMob.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, durationTicks.toInt(), 0, false, false, true))

        val expiry = System.currentTimeMillis() + (durationSeconds * 1000L)
        activeMarks[nearestMob.uniqueId] = MarkData(player.uniqueId, expiry)

        // 定时清理任务
        object : BukkitRunnable() {
            override fun run() {
                activeMarks.remove(nearestMob.uniqueId)
                healCooldowns.remove(nearestMob.uniqueId)
                if (nearestMob.isValid && !nearestMob.isDead) {
                    nearestMob.removePotionEffect(PotionEffectType.GLOWING)
                }
            }
        }.runTaskLater(plugin, durationTicks)

        return true
    }

    // ==================== 受击触发治疗逻辑 ====================
    // 使用 MONITOR 级别并忽略被取消的伤害，确保只有实际扣血时才触发
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMarkedMobDamage(e: EntityDamageEvent) {
        val victim = e.entity as? LivingEntity ?: return

        // 检查怪物是否被标记
        val markData = activeMarks[victim.uniqueId] ?: return

        // 如果标记已过期（保险机制）
        if (System.currentTimeMillis() > markData.expiryTime) {
            activeMarks.remove(victim.uniqueId)
            healCooldowns.remove(victim.uniqueId)
            return
        }

        // 内置 0.6 秒 CD 判断 (600毫秒)
        val lastHealTime = healCooldowns[victim.uniqueId] ?: 0L
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastHealTime < 600L) {
            return // 冷却中，不触发
        }

        // 更新 CD
        healCooldowns[victim.uniqueId] = currentTime

        // 触发范围治疗
        val center = victim.location
        val healRadius = 4.0
        val nearbyEntities = victim.world.getNearbyEntities(center, healRadius, healRadius, healRadius)

        var healedAny = false

        for (entity in nearbyEntities) {
            if (entity is Player && !entity.isDead && entity.location.distance(center) <= healRadius) {
                // 判断：如果是施法者本人则放大为瞬间治疗 2 级 (1 代表等级2)；否则为 1 级 (0 代表等级1)
                val isCaster = (entity.uniqueId == markData.casterId)
                val amp = if (isCaster) 1 else 0

                // 1.21.3 标准 API 中的瞬间治疗是 INSTANT_HEALTH
                entity.addPotionEffect(PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, amp, false, false, true))

                // 飘出代表治疗的爱心粒子
                entity.world.spawnParticle(Particle.HEART, entity.location.clone().add(0.0, 2.0, 0.0), 1, 0.3, 0.3, 0.3, 0.0)
                healedAny = true
            }
        }

        if (healedAny) {
            // 播放恩典触发的轻柔音效
            victim.world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)
            // 在怪物身上爆开一圈金色粒子
            victim.world.spawnParticle(Particle.WAX_ON, center.clone().add(0.0, 1.0, 0.0), 20, 1.0, 1.0, 1.0, 0.1)
        }
    }
}
