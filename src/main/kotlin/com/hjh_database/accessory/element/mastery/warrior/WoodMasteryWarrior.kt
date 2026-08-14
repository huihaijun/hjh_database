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
        const val GROWTH_DURATION_TICKS = 160
        const val ROOT_TRAIL_LIFETIME_TICKS = 40
        const val UPDATE_INTERVAL_TICKS = 20
        const val HEAL_INTERVAL_TICKS = 20
    }

    private val cd = ConcurrentHashMap<UUID, Long>()
    // 以受治疗玩家为维度限制根脉回血；多个战士或多个旧/新根脉重合时不会重复治疗。
    private val lastRootHealTick = ConcurrentHashMap<UUID, Int>()

    private data class RootPatch(
        var center: Location,
        var refreshedAtTicks: Int
    )

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
        val triggerLocation = player.location.clone()
        val world = player.world

        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warrior.wood")) {
            player.sendMessage("§a[木·精进] [生根] §f已触发")
        }
        world.playSound(triggerLocation, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.6f)
        world.playSound(triggerLocation, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.0f, 1.0f)

        val triggerTick = org.bukkit.Bukkit.getCurrentTick()
        lastRootHealTick.entries.removeIf { triggerTick - it.value > 200 }

        object : BukkitRunnable() {
            var elapsedTicks = 0
            val rootPatches = ArrayDeque<RootPatch>()

            override fun run() {
                val isGrowing = elapsedTicks < GROWTH_DURATION_TICKS
                if (!player.isOnline && isGrowing) {
                    cancel()
                    return
                }

                if (isGrowing) {
                    val currentCenter = player.location.clone()
                    val latest = rootPatches.lastOrNull()
                    if (latest != null &&
                        latest.center.world == currentCenter.world &&
                        latest.center.distanceSquared(currentCenter) <= 0.25
                    ) {
                        // 玩家基本未移动时只刷新当前根脉，避免在同一点重复创建。
                        latest.center = currentCenter
                        latest.refreshedAtTicks = elapsedTicks
                    } else {
                        rootPatches.addLast(RootPatch(currentCenter, elapsedTicks))
                    }
                }

                // 玩家离开后，旧中心及其十字根脉保留2秒，形成随玩家移动的生长轨迹。
                rootPatches.removeIf {
                    elapsedTicks - it.refreshedAtTicks >= ROOT_TRAIL_LIFETIME_TICKS
                }
                if (rootPatches.isEmpty() && !isGrowing) {
                    cancel()
                    return
                }

                val playersToHeal = HashMap<UUID, Player>()
                val monstersToSlow = HashMap<UUID, LivingEntity>()
                for (patch in rootPatches) {
                    drawRootParticles(patch.center)
                    val patchWorld = patch.center.world ?: continue
                    val nearby = patchWorld.getNearbyEntities(patch.center, 9.5, 3.0, 9.5)
                    for (entity in nearby) {
                        if (entity !is LivingEntity || !isInRootPath(entity.location, patch.center)) continue
                        if (entity is Player) {
                            playersToHeal.putIfAbsent(entity.uniqueId, entity)
                        } else if (isValidTarget(entity)) {
                            monstersToSlow.putIfAbsent(entity.uniqueId, entity)
                        }
                    }
                }

                monstersToSlow.values.forEach { monster ->
                    monster.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false, true))
                }

                val healTick = org.bukkit.Bukkit.getCurrentTick()
                playersToHeal.values.forEach { target ->
                    val previousHeal = lastRootHealTick[target.uniqueId]
                    if (previousHeal != null && healTick - previousHeal < HEAL_INTERVAL_TICKS) return@forEach
                    lastRootHealTick[target.uniqueId] = healTick
                    val maxHp = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                    target.health = min(maxHp, target.health + 4.0)
                    target.world.spawnParticle(Particle.HEART, target.location.add(0.0, 1.5, 0.0), 2, 0.15, 0.15, 0.15, 0.0)
                }

                elapsedTicks += UPDATE_INTERVAL_TICKS
            }
        }.runTaskTimer(plugin, 0L, UPDATE_INTERVAL_TICKS.toLong())
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
