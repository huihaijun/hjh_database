package com.hjh_database.accessory.element.mastery.warrior

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class GoldMasteryWarrior(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 12000L
    }

    private val cd = ConcurrentHashMap<UUID, Long>()
    private val hitCount = ConcurrentHashMap<UUID, Int>()

    fun onDamageDealt(player: Player, victim: LivingEntity, eData: ElementCrystalData, pData: PlayerData, isNormalAttack: Boolean) {
        if (eData.goldPoints < 4) return
        if (!isNormalAttack) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 判定目标是否为合法怪物
        if (!isValidTarget(victim)) return

        val count = (hitCount[uuid] ?: 0) + 1
        if (count >= 3) {
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
        val damage = pData.attack * 2.0
        val dir = player.location.direction.clone()
        dir.y = 0.0
        if (dir.length() < 0.001) return
        dir.normalize()

        val origin = player.location.clone().add(0.0, 1.0, 0.0)
        val world = player.world

        player.sendMessage("§e[金·精进] [金戈] §f已触发")
        world.playSound(player.location, Sound.ITEM_TRIDENT_THROW, 1.2f, 1.2f)
        world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f)

        val perp = org.bukkit.util.Vector(-dir.z, 0.0, dir.x).normalize()
        val hitMonsters = HashSet<UUID>()

        object : BukkitRunnable() {
            var step = 1
            val maxSteps = 8
            val speedPerStep = 1.0

            override fun run() {
                if (!player.isOnline || step > maxSteps) {
                    cancel()
                    return
                }

                val distance = step * speedPerStep
                val currentCenter = origin.clone().add(dir.clone().multiply(distance))

                // 豪迈的半月形渐进斩击波绘制 (宽度左右各 1.0 格，边缘向后弯曲)
                for (offsetDouble in -10..10) {
                    val offset = offsetDouble * 0.1 // -1.0 to 1.0
                    val backShift = abs(offset) * abs(offset) * 0.5
                    val particleLoc = currentCenter.clone()
                        .add(perp.clone().multiply(offset))
                        .subtract(dir.clone().multiply(backShift))

                    // 金色 Dust 粒子
                    world.spawnParticle(
                        Particle.DUST,
                        particleLoc,
                        1,
                        0.0, 0.0, 0.0,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.2f)
                    )
                    // 混杂少许 Crit 增强质感
                    if (step % 2 == 0) {
                        world.spawnParticle(Particle.CRIT, particleLoc, 1, 0.02, 0.02, 0.02, 0.0)
                    }
                }

                // 每一 tick 沿途检测怪物并造成伤害
                val checkLoc = currentCenter.clone()
                val nearby = world.getNearbyEntities(checkLoc, 1.5, 2.0, 1.5)
                for (entity in nearby) {
                    if (entity is LivingEntity && isValidTarget(entity) && !hitMonsters.contains(entity.uniqueId)) {
                        // 判定是否在剑气轨迹的宽度范围之内
                        val toEntity = entity.location.toVector().subtract(currentCenter.toVector())
                        toEntity.y = 0.0
                        val lateralDist = abs(toEntity.dot(perp))
                        val forwardDist = toEntity.dot(dir)

                        if (lateralDist <= 1.2 && abs(forwardDist) <= 0.8) {
                            hitMonsters.add(entity.uniqueId)
                            magicDamage(player, entity, damage)
                            // 受击粒子
                            entity.world.spawnParticle(Particle.CRIT, entity.location.add(0.0, entity.height * 0.5, 0.0), 10, 0.2, 0.2, 0.2, 0.1)
                        }
                    }
                }

                step++
            }
        }.runTaskTimer(plugin, 0L, 1L)
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
