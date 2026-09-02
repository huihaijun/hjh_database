package com.hjh_database.dungeon.shengshan

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Arrow
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.EulerAngle
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.max

/**
 * 圣山最终收尾演出。
 * 参考旧数据包的三段换位、燃烧箭雨、雷击与连续爆炸坐标，但使用批量阶段任务实现。
 */
internal class ShengShanEndShow(
    private val manager: ShengShanDungeonManager,
    private val session: ShengShanSession,
    private val onComplete: () -> Unit
) {
    private val world = Bukkit.getWorld(session.worldName)
    private val temporaryEntities = LinkedHashSet<UUID>()
    private var elder: ArmorStand? = null

    fun start() {
        val world = world ?: return onComplete()
        val first = Location(world, 3157.0, 166.0, -1840.0, 90.0f, 0.0f)
        val second = Location(world, 3160.0, 180.0, -1852.0, 55.0f, 0.0f)
        val third = Location(world, 3157.0, 177.5, -1829.0, 125.0f, 0.0f)

        session.elderEntityId?.let(Bukkit::getEntity)?.remove()
        elder = manager.spawnElder(session, first)?.also { temporaryEntities += it.uniqueId }
        manager.playDungeonEffect(session, ShengShanEffect.DIVINE_DESCENT,
            first.clone().add(0.0, 14.0, 0.0), first,
            ShengShanEffectOptions(durationTicks = 32, intervalTicks = 2, key = "shengshan_end_elder_arrive"))
        manager.playDungeonSound(session, first, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.0f)

        later(25L) { manager.sendElderLine(session, "自不量力的仿冒品，闹剧结束了！", finalReveal = true) }
        later(75L) { manager.sendElderLine(session, "我这就让你安息！", finalReveal = true) }

        later(95L) {
            poseForCast(90.0f)
            fireVolley(Location(world, 3155.0, 168.0, -1840.0), Location(world, 3139.0, 173.0, -1840.0), 12, 0xFF5B24)
        }
        later(135L) { teleportElder(second) }
        later(155L) {
            poseForCast(55.0f)
            fireVolley(Location(world, 3158.0, 181.0, -1851.0), Location(world, 3147.0, 182.0, -1845.0), 12, 0xFF9A2E)
        }
        later(195L) { teleportElder(third) }
        later(215L) {
            poseForCast(125.0f)
            fireVolley(Location(world, 3156.0, 179.0, -1830.0), Location(world, 3147.0, 182.0, -1835.0), 12, 0xFFD05A)
        }

        later(255L) {
            teleportElder(first)
            listOf(
                Location(world, 3155.0, 163.0, -1834.0),
                Location(world, 3157.0, 168.0, -1836.0),
                Location(world, 3161.0, 165.0, -1839.0),
                Location(world, 3161.0, 167.0, -1844.0),
                Location(world, 3154.0, 165.0, -1842.0),
                Location(world, 3158.0, 164.0, -1847.0)
            ).forEachIndexed { index, strike ->
                world.strikeLightningEffect(strike)
                manager.playDungeonEffect(session, ShengShanEffect.LIGHTNING_COLUMN,
                    strike.clone().add(0.0, 18.0, 0.0), strike,
                    ShengShanEffectOptions(durationTicks = 10, intervalTicks = 2,
                        key = "shengshan_end_lightning_$index"))
            }
            manager.playDungeonSound(session, first, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, .7f)
        }

        val explosions = listOf(
            Location(world, 3139.0, 173.0, -1840.0),
            Location(world, 3147.0, 182.0, -1845.0),
            Location(world, 3147.0, 182.0, -1835.0),
            Location(world, 3146.0, 178.0, -1843.0),
            Location(world, 3146.0, 178.0, -1837.0),
            Location(world, 3139.0, 173.0, -1840.0),
            Location(world, 3147.0, 182.0, -1845.0),
            Location(world, 3147.0, 182.0, -1835.0)
        )
        explosions.forEachIndexed { index, location -> later(285L + index * 8L) { explodeAt(location, index) } }

        later(365L) {
            elder?.let { stand ->
                val from = stand.location.clone()
                val to = from.clone().add(0.0, 30.0, 0.0)
                manager.playDungeonEffect(session, ShengShanEffect.DIVINE_DESCENT, from, to,
                    ShengShanEffectOptions(durationTicks = 28, intervalTicks = 2, key = "shengshan_end_elder_leave"))
                stand.teleport(to)
            }
            manager.playDungeonSound(session, first, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.15f)
        }
        later(395L) {
            cleanupEntities()
            onComplete()
        }
    }

    private fun poseForCast(yaw: Float) {
        elder?.apply {
            setRotation(yaw, 0.0f)
            leftArmPose = EulerAngle(Math.toRadians(30.0), 0.0, Math.toRadians(30.0))
            rightArmPose = EulerAngle(Math.toRadians(270.0), 0.0, Math.toRadians(-15.0))
        }
    }

    private fun teleportElder(destination: Location) {
        val stand = elder ?: return
        val from = stand.location.clone()
        manager.playDungeonEffect(session, ShengShanEffect.DIVINE_DESCENT, from, destination,
            ShengShanEffectOptions(durationTicks = 16, intervalTicks = 2))
        manager.playDungeonSound(session, destination, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.15f)
        stand.teleport(destination)
    }

    private fun fireVolley(origin: Location, target: Location, count: Int, color: Int) {
        val world = world ?: return
        manager.playDungeonEffect(session, ShengShanEffect.FIRE_LINE, origin, target,
            ShengShanEffectOptions(color = color, durationTicks = 34, intervalTicks = 2))
        manager.playDungeonSound(session, origin, Sound.ITEM_FIRECHARGE_USE, 1.35f, .72f)
        repeat(count) { index ->
            val direction = target.toVector().subtract(origin.toVector()).normalize()
            direction.x += (-12..12).random() / 100.0
            direction.y += (4..34).random() / 100.0
            direction.z += (-12..12).random() / 100.0
            val arrow = world.spawn(origin.clone().add(0.0, index % 3 * .18, 0.0), Arrow::class.java) { entity ->
                entity.velocity = direction.normalize().multiply(1.25 + (0..45).random() / 100.0)
                entity.fireTicks = 400
                entity.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                entity.isPersistent = false
                entity.damage = 0.0
            }
            manager.trackEntity(session, arrow, "end_show_fire_arrow")
            temporaryEntities += arrow.uniqueId
        }
    }

    private fun explodeAt(location: Location, index: Int) {
        val world = world ?: return
        manager.playDungeonEffect(session, ShengShanEffect.FIRE_BURST, location,
            options = ShengShanEffectOptions(radius = 5.5, height = 5.0, color = 0xFF6A22))
        manager.playDungeonEffect(session, ShengShanEffect.COLLAPSE, location,
            options = ShengShanEffectOptions(radius = 4.0, height = 4.0))
        manager.playDungeonSound(session, location, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, .55f + index * .025f)
    }

    private fun cleanupEntities() {
        temporaryEntities.mapNotNull(Bukkit::getEntity).forEach { it.remove() }
        temporaryEntities.clear()
        elder = null
        session.elderEntityId = null
    }

    private fun later(delay: Long, action: () -> Unit): BukkitTask {
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskLater(manager.plugin, Runnable {
            session.tasks.remove(task)
            if (session.ending) action()
        }, max(0L, delay))
        session.tasks += task
        return task
    }
}
