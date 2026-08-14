package com.hjh_database.accessory.element.mastery.archer

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WoodMasteryArcher(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 5000L // 5秒冷却
    }

    private val cd = ConcurrentHashMap<UUID, Long>()
    
    // 追踪每个玩家对怪物的藤蔓标记：Player UUID -> (Victim Entity ID -> Expiration Timestamp)
    private val activeMarks = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Long>>()

    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean = false
    ) {
        // 1. 判断是否命中带有此标记的目标 (任何伤害均可触发回血)
        val playerMarks = activeMarks[player.uniqueId]
        if (playerMarks != null) {
            val expireTime = playerMarks[victim.entityId] ?: 0L
            if (System.currentTimeMillis() < expireTime) {
                // 回复 2 点生命
                val maxHp = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                player.health = (player.health + 2.0).coerceAtMost(maxHp)
                
                // 回血特效
                player.world.spawnParticle(Particle.HEART, player.location.add(0.0, 1.5, 0.0), 2, 0.2, 0.2, 0.2, 0.1)
                player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f)
            }
        }

        // 2. 判定是否满足施加标记的条件
        if (eData.woodPoints < 4) return
        if (!isArrowHit) return
        if (!isValidTarget(victim)) return

        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 触发标记：进入冷却
        cd[uuid] = System.currentTimeMillis() + CD_MS
        
        // 施加标记
        val marks = activeMarks.getOrPut(uuid) { ConcurrentHashMap() }
        marks[victim.entityId] = System.currentTimeMillis() + 8000L // 持续8秒

        // 施加 50% 减速 (缓慢 II) 持续 8秒 (160 ticks)
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 160, 1, false, false, true))

        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.archer.wood")) {
            player.sendMessage("§a[弓] [木·精进] [藤矢] §f已触发")
        }
        
        victim.world.playSound(victim.location, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.2f, 0.8f)
        runVisualTask(victim)
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
        activeMarks.remove(uuid)
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

    private fun runVisualTask(target: LivingEntity) {
        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks >= 16 || !target.isValid || target.isDead) {
                    cancel()
                    return
                }
                val loc = target.location.clone()
                val world = target.world
                // 绘制小圈围绕怪物双脚
                val r = 0.5
                for (i in 0 until 8) {
                    val angle = i * Math.PI * 2.0 / 8.0
                    val pLoc = loc.clone().add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r)
                    world.spawnParticle(
                        Particle.DUST,
                        pLoc,
                        1,
                        0.0, 0.0, 0.0,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(46, 125, 50), 1.0f)
                    )
                    if (i % 2 == 0) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }
}
