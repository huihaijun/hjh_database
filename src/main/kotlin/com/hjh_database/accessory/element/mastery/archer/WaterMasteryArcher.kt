package com.hjh_database.accessory.element.mastery.archer

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractArrow
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WaterMasteryArcher(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 8000L // 8秒冷却
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean,
        arrow: AbstractArrow?,
        eventDamage: Double
    ) {
        if (eData.waterPoints < 4) return
        if (!isArrowHit) return
        if (!isValidTarget(victim)) return

        // 索敌：搜索 5 格内最近的怪物（排除原目标）
        val world = victim.world
        val nearby = world.getNearbyEntities(victim.location, 5.0, 5.0, 5.0)
        var target: LivingEntity? = null
        var minDistance = Double.MAX_VALUE
        
        for (entity in nearby) {
            if (entity is LivingEntity && isValidTarget(entity) && entity != victim) {
                val dist = entity.location.distance(victim.location)
                if (dist < minDistance) {
                    minDistance = dist
                    target = entity
                }
            }
        }

        // 若身旁没有怪物，则水箭会攻击原目标
        if (target == null) {
            if (victim.isValid && !victim.isDead) {
                target = victim
            }
        }

        // 若原目标也死亡且无其他怪物，则直接消失，不触发也不进入CD
        if (target == null) return

        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 触发水月：进入冷却
        cd[uuid] = System.currentTimeMillis() + CD_MS
        player.sendMessage("§9[弓] [水·精进] [水月] §f已触发")

        val startLoc = victim.location.add(0.0, victim.height * 0.5, 0.0)
        world.playSound(startLoc, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 1.2f)

        // 生成 ItemDisplay 蓝水晶作为飞行的水箭
        val display = world.spawn(startLoc, ItemDisplay::class.java) {
            it.setItemStack(ItemStack(org.bukkit.Material.PRISMARINE_CRYSTALS))
            val transform = it.transformation
            transform.scale.set(Vector3f(1.0f, 1.0f, 1.0f))
            it.transformation = transform
        }

        val finalTarget = target
        object : BukkitRunnable() {
            var tick = 0
            val maxTicks = 10 // 0.5秒飞达

            override fun run() {
                // 如果目标死亡/玩家下线/达到最大步数，则结束任务
                if (!finalTarget.isValid || finalTarget.isDead || !player.isOnline || tick > maxTicks) {
                    display.remove()
                    if (tick > maxTicks && finalTarget.isValid && !finalTarget.isDead) {
                        // 命中目标：造成同等魔法伤害
                        magicDamage(player, finalTarget, eventDamage)
                        
                        // 玩家获得 Speed II 持续 3 秒
                        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 60, 1, false, false, true))
                        
                        // 播放命中音效与粒子
                        finalTarget.world.spawnParticle(Particle.SPLASH, finalTarget.location.add(0.0, finalTarget.height * 0.5, 0.0), 10, 0.2, 0.2, 0.2, 0.1)
                        finalTarget.world.playSound(finalTarget.location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 1.2f)
                    }
                    cancel()
                    return
                }

                // 物理飞行插值，支持追踪移动的目标
                val targetLoc = finalTarget.location.add(0.0, finalTarget.height * 0.5, 0.0)
                val t = tick.toDouble() / maxTicks
                val currentLoc = startLoc.clone().add(targetLoc.clone().subtract(startLoc).multiply(t))
                display.teleport(currentLoc)

                // 水花与气泡尾迹
                world.spawnParticle(Particle.SPLASH, currentLoc, 2, 0.1, 0.1, 0.1, 0.01)
                world.spawnParticle(Particle.BUBBLE, currentLoc, 2, 0.1, 0.1, 0.1, 0.01)
                world.spawnParticle(
                    Particle.DUST,
                    currentLoc,
                    1,
                    0.0, 0.0, 0.0,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(0, 191, 255), 0.8f)
                )

                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
    }

    private fun isOnCd(uuid: UUID): Boolean {
        val end = cd[uuid] ?: return false
        if (System.currentTimeMillis() >= end) {
            cd.remove(uuid)
            return false
        }
        return true
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
               entity !is ArmorStand &&
               !entity.isDead &&
               entity.isValid &&
               entity.scoreboardTags.contains("panling") &&
               entity.scoreboardTags.contains("monster")
    }

    private fun magicDamage(attacker: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.noDamageTicks = 0
        try {
            target.damage(damage, attacker)
        } finally {
            if (target.hasMetadata("HJH_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            target.noDamageTicks = 0
        }
    }
}
