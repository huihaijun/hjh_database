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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class WoodMasteryWarrior(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 15000L
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageTaken(player: Player, event: org.bukkit.event.entity.EntityDamageEvent, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.woodPoints < 4) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        cd[uuid] = System.currentTimeMillis() + CD_MS
        trigger(player)
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

    private fun trigger(player: Player) {
        val rootCenter = player.location.clone()
        val world = player.world

        player.sendMessage("§a[木·精进] [生根] §f已触发")
        world.playSound(rootCenter, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.6f)
        world.playSound(rootCenter, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.0f, 1.0f)

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks >= 8 || !player.isOnline) {
                    cancel()
                    return
                }

                // 绘制盘根错节的根脉粒子
                drawRootParticles(rootCenter)

                // 获取并过滤十字方向（宽度 1.5 格，长度 8 格）范围内的实体
                val nearby = world.getNearbyEntities(rootCenter, 9.5, 3.0, 9.5)
                for (entity in nearby) {
                    if (entity !is LivingEntity) continue
                    if (!isInRootPath(entity.location, rootCenter)) continue

                    if (entity is Player) {
                        // 治疗队友或自己：每秒 4 HP
                        val maxHp = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                        entity.health = min(maxHp, entity.health + 4.0)
                        entity.world.spawnParticle(Particle.HEART, entity.location.add(0.0, 1.5, 0.0), 2, 0.15, 0.15, 0.15, 0.0)
                    } else if (isValidTarget(entity)) {
                        // 减速怪物
                        entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false, true))
                    }
                }

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun isInRootPath(entityLoc: Location, rootCenter: Location): Boolean {
        if (entityLoc.world != rootCenter.world) return false
        val dx = entityLoc.x - rootCenter.x
        val dz = entityLoc.z - rootCenter.z
        val absDx = abs(dx)
        val absDz = abs(dz)

        // 南北线 (Z轴方向): |dx| <= 1.5 且 |dz| <= 8.0
        if (absDx <= 1.5 && absDz <= 8.0) return true
        // 东西线 (X轴方向): |dz| <= 1.5 且 |dx| <= 8.0
        if (absDz <= 1.5 && absDx <= 8.0) return true

        return false
    }

    private fun drawRootParticles(center: Location) {
        val world = center.world ?: return
        val directions = listOf(
            org.bukkit.util.Vector(1.0, 0.0, 0.0),
            org.bukkit.util.Vector(-1.0, 0.0, 0.0),
            org.bukkit.util.Vector(0.0, 0.0, 1.0),
            org.bukkit.util.Vector(0.0, 0.0, -1.0)
        )

        for (dir in directions) {
            val perp = org.bukkit.util.Vector(-dir.z, 0.0, dir.x).normalize()
            var dist = 0.5
            while (dist <= 8.0) {
                val basePoint = center.clone().add(dir.clone().multiply(dist)).add(0.0, 0.15, 0.0)

                // 绘制 3 条并行的根线（一条中央，两条左右绕弯的交错藤蔓）
                // 中央线
                world.spawnParticle(
                    Particle.DUST,
                    basePoint,
                    1,
                    0.05, 0.02, 0.05,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(80, 130, 45), 1.0f)
                )

                // 交错藤蔓 1 和 2 (使用正弦曲线使其呈现缠绕感)
                val waveOffset = sin(dist * 1.5) * 0.6
                val point1 = basePoint.clone().add(perp.clone().multiply(waveOffset))
                val point2 = basePoint.clone().add(perp.clone().multiply(-waveOffset))

                world.spawnParticle(
                    Particle.DUST,
                    point1,
                    1,
                    0.02, 0.02, 0.02,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(50, 100, 30), 0.9f)
                )
                world.spawnParticle(
                    Particle.DUST,
                    point2,
                    1,
                    0.02, 0.02, 0.02,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(110, 160, 60), 0.8f)
                )

                // 随机生成叶子
                if (Math.random() < 0.08) {
                    world.spawnParticle(Particle.HAPPY_VILLAGER, basePoint.add(0.0, 0.1, 0.0), 1, 0.2, 0.1, 0.2, 0.0)
                }
                if (Math.random() < 0.08) {
                    world.spawnParticle(Particle.COMPOSTER, basePoint.add(0.0, 0.2, 0.0), 1, 0.1, 0.1, 0.1, 0.0)
                }

                dist += 0.8
            }
        }
    }
}
