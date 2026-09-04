package com.hjh_database.accessory.element.mastery.warrior

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class EarthMasteryWarrior(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 15000L
        const val META_ARMOR_REDUCE = "HJH_EARTH_MASTERY_ARMOR_REDUCE"
    }

    private val cd = ConcurrentHashMap<UUID, Long>()
    private val hitCount = ConcurrentHashMap<UUID, Int>()

    fun onDamageTaken(player: Player, event: org.bukkit.event.entity.EntityDamageEvent, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.earthPoints < 4) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        val count = (hitCount[uuid] ?: 0) + 1
        if (count >= 4) {
            hitCount[uuid] = 0
            cd[uuid] = System.currentTimeMillis() + CD_MS
            trigger(player, pData)
        } else {
            hitCount[uuid] = count
        }
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
        hitCount.remove(uuid)
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

    private fun trigger(player: Player, pData: PlayerData) {
        val center = player.location.clone()
        val world = player.world

        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warrior.earth")) {
            player.sendMessage("§6[土·精进] [崩山] §f已触发")
        }
        world.playSound(center, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.5f)
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.5f)

        // 地面跺地冲击环粒子 (无 EXPLODE)
        drawGroundSlam(center)

        // 眩晕 10 格怪物 0.8 秒并降低 50% 护甲 8 秒
        for (entity in world.getNearbyEntities(center, 10.0, 4.0, 10.0)) {
            val mob = entity as? LivingEntity ?: continue
            if (!isValidTarget(mob)) continue

            // 眩晕 0.8秒 (16 ticks) — 使用高等级减速+缓慢下落模拟
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 16, 127, false, false, false))
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 16, 0, false, false, false))

            // 降低护甲 50% 持续 8秒 — 用 metadata 标记时间戳
            mob.setMetadata(META_ARMOR_REDUCE, FixedMetadataValue(plugin, System.currentTimeMillis() + 8000L))

            // 减甲受击提示粒子 (大地尘埃)
            mob.world.spawnParticle(
                Particle.DUST,
                mob.location.add(0.0, mob.height * 0.5, 0.0),
                8,
                0.25, 0.25, 0.25,
                0.0,
                Particle.DustOptions(Color.fromRGB(150, 100, 40), 1.0f)
            )
        }

        // 自身获得吸收护盾 = min(maxHealth * 50%, 40) 持续 15秒
        val shieldAmount = min(pData.maxHealth * 0.5, 40.0)
        val absorptionLevel = ceil(shieldAmount / 4.0).toInt().coerceAtLeast(1)
        player.addPotionEffect(PotionEffect(
            PotionEffectType.ABSORPTION,
            300,                          // 15秒 = 300 ticks
            absorptionLevel - 1,          // amplifier (0-based)
            false, true, true
        ))
        val shieldDisplayAmount = absorptionLevel * 4
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warrior.earth.shield_$shieldDisplayAmount")) {
            player.sendMessage("§6获得 §b$shieldDisplayAmount §6点吸收护盾，持续 §b15 §6秒")
        }
    }

    private fun drawGroundSlam(center: Location) {
        val world = center.world ?: return

        // 冲击波环 (10格范围，由内到外绘制)
        for (r in listOf(2.0, 4.0, 6.0, 8.0, 10.0)) {
            val points = (r * 6).toInt().coerceAtLeast(8)
            for (i in 0 until points) {
                val angle = Math.PI * 2.0 * i / points
                val point = center.clone().add(cos(angle) * r, 0.15, sin(angle) * r)

                // 棕褐色/灰色尘埃
                world.spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    0.05, 0.02, 0.05,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(130, 95, 40), 1.1f)
                )

                if (i % 3 == 0) {
                    try {
                        world.spawnParticle(
                            Particle.BLOCK,
                            point,
                            2,
                            0.1, 0.05, 0.1,
                            0.0,
                            Material.COARSE_DIRT.createBlockData()
                        )
                    } catch (_: Exception) {
                        world.spawnParticle(Particle.CRIT, point, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }
        }

        // 中心震撼效果 (无 EXPLODE)
        try {
            world.spawnParticle(
                Particle.BLOCK,
                center.clone().add(0.0, 0.5, 0.0),
                25,
                0.8, 0.3, 0.8,
                0.0,
                Material.STONE.createBlockData()
            )
        } catch (_: Exception) {}
        world.spawnParticle(Particle.GUST, center.clone().add(0.0, 0.5, 0.0), 3, 0.2, 0.2, 0.2, 0.0)
    }
}
