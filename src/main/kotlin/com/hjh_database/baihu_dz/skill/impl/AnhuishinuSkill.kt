package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillResult
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

class AnhuishinuSkill(
    private val plugin: Hjh_database
) : BaihuWeaponSkill {
    override fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult {
        val arrow = projectile as? AbstractArrow ?: return BaihuWeaponSkillResult.FAIL
        val direction = arrow.velocity.clone().takeIf { it.lengthSquared() > 0.0001 }
            ?.normalize()
            ?: player.eyeLocation.direction.normalize()

        arrow.remove()

        val range = config.getDouble("range", 15.0)
        val halfWidth = config.getDouble("half_width", 0.5)
        val verticalTolerance = config.getDouble("vertical_tolerance", 1.35)
        val damage = data.archerDamage * config.getDouble("damage_multiplier", 2.4)
        val slowTicks = (config.getDouble("slow_seconds", 5.0) * 20.0).toInt().coerceAtLeast(1)
        val slowAmplifier = config.getInt("slow_amplifier", 1)
        val origin = player.eyeLocation.clone().add(direction.clone().multiply(0.6))

        val targets = findTargets(player, origin.toVector(), direction, range, halfWidth, verticalTolerance)
        targets.forEach { target ->
            damageTarget(player, target, damage)
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowAmplifier, false, true, true))
            spawnHitFrost(target)
        }

        spawnFrostPath(player, origin.toVector(), direction, range)
        player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 0.75f, 0.55f)
        player.world.playSound(player.location, Sound.ENTITY_SKELETON_SHOOT, 0.85f, 0.8f)

        return BaihuWeaponSkillResult.success(message = config.getString("message", "&b&l武器技【寒骨箭】已发动"))
    }

    private fun findTargets(
        player: Player,
        origin: Vector,
        direction: Vector,
        range: Double,
        halfWidth: Double,
        verticalTolerance: Double
    ): List<LivingEntity> {
        val hit = linkedMapOf<UUID, LivingEntity>()
        val center = origin.clone().add(direction.clone().multiply(range * 0.5)).toLocation(player.world)

        player.world.getNearbyEntities(center, range * 0.5 + halfWidth + 1.0, verticalTolerance + 2.0, range * 0.5 + halfWidth + 1.0)
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && isValidTarget(it) }
            .forEach { target ->
                val targetPoint = target.location.clone().add(0.0, target.height * 0.5, 0.0).toVector()
                val offset = targetPoint.clone().subtract(origin)
                val forwardDistance = offset.dot(direction)
                if (forwardDistance < 0.0 || forwardDistance > range) return@forEach

                val lineY = origin.y + direction.y * forwardDistance
                if (abs(targetPoint.y - lineY) > verticalTolerance + target.height * 0.25) return@forEach

                val horizontalOffset = Vector(offset.x, 0.0, offset.z)
                val horizontalDirection = Vector(direction.x, 0.0, direction.z)
                if (horizontalDirection.lengthSquared() < 0.0001) return@forEach
                horizontalDirection.normalize()
                val horizontalForward = horizontalOffset.dot(horizontalDirection)
                val sideDistance = horizontalOffset
                    .subtract(horizontalDirection.multiply(horizontalForward))
                    .length()

                if (sideDistance <= halfWidth) {
                    hit[target.uniqueId] = target
                }
            }

        return hit.values.toList()
    }

    private fun spawnFrostPath(player: Player, origin: Vector, direction: Vector, range: Double) {
        val world = player.world
        val darkBlue = Particle.DustOptions(Color.fromRGB(11, 34, 74), 1.05f)
        val blackBlue = Particle.DustOptions(Color.fromRGB(3, 8, 18), 0.85f)
        val right = Vector(-direction.z, 0.0, direction.x).let {
            if (it.lengthSquared() < 0.0001) Vector(1.0, 0.0, 0.0) else it.normalize()
        }

        var distance = 0.0
        while (distance <= range) {
            val center = origin.clone().add(direction.clone().multiply(distance))
            val loc = center.toLocation(world)
            world.spawnParticle(Particle.DUST, loc, 1, 0.015, 0.015, 0.015, 0.0, darkBlue)
            world.spawnParticle(Particle.DUST, loc, 1, 0.01, 0.01, 0.01, 0.0, blackBlue)

            if ((distance * 10).toInt() % 4 == 0) {
                world.spawnParticle(Particle.SNOWFLAKE, loc, 1, 0.05, 0.05, 0.05, 0.0)
                world.spawnParticle(Particle.DUST, center.clone().add(right.clone().multiply(0.5)).toLocation(world), 1, 0.01, 0.01, 0.01, 0.0, darkBlue)
                world.spawnParticle(Particle.DUST, center.clone().add(right.clone().multiply(-0.5)).toLocation(world), 1, 0.01, 0.01, 0.01, 0.0, darkBlue)
            }
            distance += 0.35
        }
    }

    private fun spawnHitFrost(target: LivingEntity) {
        val loc = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        val dust = Particle.DustOptions(Color.fromRGB(22, 55, 112), 1.25f)
        target.world.spawnParticle(Particle.DUST, loc, 14, 0.35, 0.45, 0.35, 0.0, dust)
        target.world.spawnParticle(Particle.SNOWFLAKE, loc, 10, 0.32, 0.35, 0.32, 0.02)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun damageTarget(player: Player, target: LivingEntity, amount: Double) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            if (target.hasMetadata("hjh_physical_skill")) {
                target.removeMetadata("hjh_physical_skill", plugin)
            }
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
            target.noDamageTicks = 0
        }
    }
}
