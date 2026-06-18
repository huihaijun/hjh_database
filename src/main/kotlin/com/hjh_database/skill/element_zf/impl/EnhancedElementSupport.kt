package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.FormationDamageEvent
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val META_METAL_WEAKEN_UNTIL = "HJH_METAL_WEAKEN_UNTIL"
internal const val META_WATER_VULN_UNTIL = "HJH_WATER_VULN_UNTIL"
internal const val META_WATER_VULN_MULT = "HJH_WATER_VULN_MULT"
internal const val META_WATER_VULN_PARTICLE_TASK = "HJH_WATER_VULN_PARTICLE_TASK"
internal const val META_EARTH_VULN_UNTIL = "HJH_EARTH_VULN_UNTIL"
internal const val META_EARTH_VULN_MULT = "HJH_EARTH_VULN_MULT"
internal const val META_EARTH_VULN_PARTICLE_TASK = "HJH_EARTH_VULN_PARTICLE_TASK"

internal fun isEnhancedMonster(entity: LivingEntity): Boolean {
    val tags = entity.scoreboardTags
    return tags.contains("panling") && tags.contains("monster")
}

internal fun enhancedMagicDamage(plugin: Hjh_database, attacker: Player, target: LivingEntity, damage: Double) {
    formationMagicDamage(plugin, attacker, target, damage)
}

/** 统一的阵法伤害入口：标记伤害来源，并彻底取消本次伤害的无敌帧。 */
internal fun formationMagicDamage(plugin: Hjh_database, attacker: Player, target: LivingEntity, damage: Double) {
    if (damage <= 0.0 || !target.isValid || target.isDead) return
    val effectiveHealthBefore = target.health + target.absorptionAmount
    target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
    val previousMaximum = target.maximumNoDamageTicks
    target.noDamageTicks = 0
    target.maximumNoDamageTicks = 0
    try {
        target.damage(damage, attacker)
    } finally {
        if (target.hasMetadata("HJH_MAGIC_DAMAGE")) {
            target.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
        }
        target.noDamageTicks = 0
        target.maximumNoDamageTicks = previousMaximum
    }
    val effectiveHealthAfter = if (target.isDead) 0.0 else target.health + target.absorptionAmount
    val actualDamage = (effectiveHealthBefore - effectiveHealthAfter).coerceIn(0.0, effectiveHealthBefore)
    if (actualDamage > 0.0) {
        plugin.server.pluginManager.callEvent(FormationDamageEvent(attacker, target, damage, actualDamage))
    }
}

internal fun broadcastOriginMessage(player: Player, color: String, elementName: String, spellName: String) {
    val message = "§e${player.name} §f极尽炼化了 $color$elementName§f，释放了始源法术 —— $color$spellName§f！"
    for (entity in player.world.getNearbyEntities(player.location, 10.0, 10.0, 10.0)) {
        val nearby = entity as? Player ?: continue
        if (nearby.uniqueId == player.uniqueId) continue
        if (nearby.location.distanceSquared(player.location) <= 100.0) {
            nearby.sendMessage(message)
        }
    }
    player.sendMessage(message)
}

internal fun getEnhancedSightLocation(
    player: Player,
    range: Double,
    fluidMode: FluidCollisionMode = FluidCollisionMode.NEVER
): Location {
    val eye = player.eyeLocation
    val direction = eye.direction.normalize()
    val result = player.world.rayTrace(
        eye,
        direction,
        range,
        fluidMode,
        true,
        0.5
    ) { entity -> entity !== player }

    return when {
        result?.hitPosition != null -> result.hitPosition.toLocation(player.world)
        else -> eye.add(direction.multiply(range))
    }
}

internal fun applyEnhancedVulnerability(
    plugin: Hjh_database,
    target: LivingEntity,
    untilKey: String,
    multKey: String,
    taskKey: String,
    multiplier: Double,
    durationMillis: Long,
    color: Color
) {
    val until = System.currentTimeMillis() + durationMillis
    target.setMetadata(untilKey, FixedMetadataValue(plugin, until))
    target.setMetadata(multKey, FixedMetadataValue(plugin, multiplier))
    startVulnerabilityParticles(plugin, target, untilKey, taskKey, color)
}

private fun startVulnerabilityParticles(
    plugin: Hjh_database,
    target: LivingEntity,
    untilKey: String,
    taskKey: String,
    color: Color
) {
    if (target.hasMetadata(taskKey)) return

    val task = object : BukkitRunnable() {
        override fun run() {
            if (!target.isValid || target.isDead) {
                target.removeMetadata(taskKey, plugin)
                cancel()
                return
            }

            val until = target.getMetadata(untilKey).firstOrNull()?.asLong() ?: 0L
            if (until <= System.currentTimeMillis()) {
                target.removeMetadata(taskKey, plugin)
                cancel()
                return
            }

            val base = target.location.add(0.0, target.height * 0.55, 0.0)
            val phase = System.currentTimeMillis() / 260.0
            target.world.spawnParticle(Particle.DUST, base, 5, 0.22, 0.35, 0.22, 0.0, Particle.DustOptions(color, 1.05f))
            for (i in 0 until 8) {
                val angle = phase + (PI * 2.0 / 8.0) * i
                val ring = base.clone().add(cos(angle) * 0.55, 0.08 * sin(angle * 2.0), sin(angle) * 0.55)
                target.world.spawnParticle(Particle.DUST, ring, 1, 0.015, 0.015, 0.015, 0.0, Particle.DustOptions(color, 0.85f))
            }
            if (Math.random() < 0.55) {
                target.world.spawnParticle(Particle.ENCHANT, base, 3, 0.4, 0.45, 0.4, 0.0)
            }
        }
    }
    task.runTaskTimer(plugin, 0L, 10L)
    target.setMetadata(taskKey, FixedMetadataValue(plugin, task.taskId))
}

internal fun spawnParticleLine(
    start: Location,
    end: Location,
    stepSize: Double,
    particle: Particle,
    count: Int = 1,
    extra: Double = 0.0
) {
    val world = start.world ?: return
    val distance = start.distance(end)
    if (distance <= 0.05) return
    val dir = end.toVector().subtract(start.toVector()).normalize()
    var d = 0.0
    while (d <= distance) {
        world.spawnParticle(particle, start.clone().add(dir.clone().multiply(d)), count, 0.0, 0.0, 0.0, extra)
        d += stepSize
    }
}

internal fun horizontalDistanceSquared(a: Location, b: Location): Double {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return dx * dx + dz * dz
}

internal fun normalizedFlatVector(from: Location, to: Location): Vector {
    val vec = to.toVector().subtract(from.toVector())
    vec.y = 0.0
    return if (vec.lengthSquared() > 0.0001) vec.normalize() else Vector(0.0, 0.0, 0.0)
}
