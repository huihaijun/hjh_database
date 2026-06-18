package com.hjh_database.accessory.element.mastery.archer

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class FireMasteryArcher(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 15000L // 15秒冷却
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean
    ) {
        if (eData.firePoints < 4) return
        if (!isArrowHit) return
        if (!isValidTarget(victim)) return

        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 触发爆燃：进入冷却
        cd[uuid] = System.currentTimeMillis() + CD_MS
        player.sendMessage("§c[弓] [火·精进] [爆燃] §f已触发")

        val center = victim.location.clone().add(0.0, 0.5, 0.0)
        val world = victim.world

        // 播放爆炸音效
        world.playSound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.2f, 1.0f)
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.8f)

        // 伤害：150% 箭矢强度 (魔法伤害)
        val damage = pData.archerDamage * 1.5

        // 获取 5 格内所有怪物并造成伤害
        val nearby = world.getNearbyEntities(center, 5.0, 5.0, 5.0)
        for (entity in nearby) {
            if (entity is LivingEntity && isValidTarget(entity)) {
                magicDamage(player, entity, damage)
            }
        }

        // 绘制爆炸粒子效果 (无 EXPLODE 粒子，采用火焰、熔岩与烟雾交织)
        for (r in listOf(1.0, 2.5, 4.0, 5.0)) {
            val count = (r * 12).toInt()
            for (i in 0 until count) {
                val angle = i * Math.PI * 2.0 / count
                val pLoc = center.clone().add(cos(angle) * r, (Math.random() - 0.5) * 1.5, sin(angle) * r)
                
                world.spawnParticle(Particle.FLAME, pLoc, 1, 0.1, 0.1, 0.1, 0.05)
                if (i % 3 == 0) {
                    world.spawnParticle(Particle.LAVA, pLoc, 1, 0.1, 0.1, 0.1, 0.0)
                }
                if (i % 4 == 0) {
                    world.spawnParticle(Particle.LARGE_SMOKE, pLoc, 1, 0.1, 0.1, 0.1, 0.02)
                }
            }
        }
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
