package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.listener.CombatDamageCalculationEvent
import com.hjh_database.skill.medical.spell.MedicalCastEvent
import com.hjh_database.skill.medical.spell.MedicalDamageEvent
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 医师(job=3)元素结晶·精进。只消费医术系统发布的专用事件。 */
class MedicalMasterySkills(private val plugin: Hjh_database) : Listener {

    private val goldCd = ConcurrentHashMap<UUID, Long>()
    private val fireCd = ConcurrentHashMap<UUID, Long>()
    private val meridianEnds = ConcurrentHashMap<UUID, Long>()
    private val meridianSpeedKey = NamespacedKey(plugin, "medical_mastery_meridian_speed")

    @EventHandler
    fun onMedicalCast(event: MedicalCastEvent) {
        val context = context(event.caster) ?: return
        triggerGold(event.caster, context.first, context.second)
    }

    @EventHandler
    fun onMedicalDamage(event: MedicalDamageEvent) {
        if (!event.triggersMastery || event.actualDamage <= 0.0) return
        val context = context(event.caster) ?: return
        triggerFire(event.caster, event.target, context.first)
    }

    @EventHandler
    fun onOutgoingDamage(event: CombatDamageCalculationEvent) {
        val attacker = event.attacker ?: return
        val end = meridianEnds[attacker.uniqueId] ?: return
        if (end > System.currentTimeMillis()) {
            event.damage *= 0.7
        } else {
            meridianEnds.remove(attacker.uniqueId, end)
            removeMeridianSpeed(attacker)
        }
    }

    fun cleanup(uuid: UUID) {
        goldCd.remove(uuid)
        fireCd.remove(uuid)
    }

    private fun triggerGold(player: Player, crystal: ElementCrystalData, data: com.hjh_database.data.PlayerData) {
        if (crystal.goldPoints < 4 || onCooldown(goldCd, player.uniqueId)) return
        val targets = nearbyMonsters(player, 15.0).take(2)
        if (targets.isEmpty()) return
        val shots = if (targets.size == 1) listOf(targets[0], targets[0]) else targets
        goldCd[player.uniqueId] = System.currentTimeMillis() + 15_000L
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.doctor.metal")) {
            player.sendMessage("§e[医] [金·精进] [金针] §f已触发")
        }
        shots.forEachIndexed { index, target ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (target.isValid && !target.isDead) launchGoldNeedle(player, target, data.zfStr * 1.5)
            }, index * 5L)
        }
    }

    private fun launchGoldNeedle(player: Player, target: LivingEntity, damage: Double) {
        val start = player.eyeLocation.clone().add(player.location.direction.clone().multiply(0.5))
        val display = player.world.spawn(start, ItemDisplay::class.java) {
            it.setItemStack(ItemStack(Material.GOLD_NUGGET))
            it.transformation = Transformation(
                Vector3f(0f, 0f, 0f), AxisAngle4f(0f, 0f, 0f, 1f),
                Vector3f(0.35f, 0.12f, 0.12f), AxisAngle4f(0f, 0f, 0f, 1f)
            )
        }
        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks >= 8 || !target.isValid || target.isDead) {
                    display.remove()
                    if (target.isValid && !target.isDead) {
                        plugin.medicalSpellManager.applyMedicalDamage(player, target, damage, "mastery_gold_needle", false)
                        target.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
                        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 255, false, false, true))
                        target.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 20, 255, false, false, true))
                        target.world.spawnParticle(Particle.WAX_ON, target.location.add(0.0, target.height * 0.5, 0.0), 14, 0.3, 0.4, 0.3, 0.06)
                        target.world.playSound(target.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f)
                    }
                    cancel()
                    return
                }
                val destination = target.location.add(0.0, target.height * 0.55, 0.0)
                val next = display.location.toVector().multiply(0.55).add(destination.toVector().multiply(0.45)).toLocation(player.world)
                display.teleport(next)
                player.world.spawnParticle(Particle.DUST, next, 2, 0.03, 0.03, 0.03, 0.0, Particle.DustOptions(Color.fromRGB(255, 210, 35), 0.75f))
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun triggerFire(player: Player, target: LivingEntity, crystal: ElementCrystalData) {
        if (crystal.firePoints < 4 || !isMonster(target) || onCooldown(fireCd, player.uniqueId)) return
        fireCd[player.uniqueId] = System.currentTimeMillis() + 20_000L
        meridianEnds[target.uniqueId] = System.currentTimeMillis() + 5_000L
        applyMeridianSpeed(target)
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.doctor.fire")) {
            player.sendMessage("§c[医] [火·精进] [灼脉] §f已触发")
        }
        target.world.spawnParticle(Particle.DUST, target.location.add(0.0, target.height * 0.5, 0.0), 16, 0.3, 0.45, 0.3, 0.0, Particle.DustOptions(Color.fromRGB(180, 20, 20), 1.0f))
        target.world.playSound(target.location, Sound.ENTITY_BLAZE_HURT, 0.8f, 0.7f)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val end = meridianEnds[target.uniqueId] ?: return@Runnable
            if (end <= System.currentTimeMillis()) {
                meridianEnds.remove(target.uniqueId, end)
                removeMeridianSpeed(target)
            }
        }, 101L)
    }

    private fun applyMeridianSpeed(target: LivingEntity) {
        val attribute = target.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(meridianSpeedKey)?.let(attribute::removeModifier)
        attribute.addTransientModifier(AttributeModifier(meridianSpeedKey, -0.5, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
    }

    private fun removeMeridianSpeed(target: LivingEntity) {
        val attribute = target.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(meridianSpeedKey)?.let(attribute::removeModifier)
    }

    private fun context(player: Player): Pair<ElementCrystalData, com.hjh_database.data.PlayerData>? {
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return null
        val data = plugin.playerManager.getPlayerData(player) ?: return null
        if (data.job != 3) return null
        return plugin.elementCrystalManager.getData(player.uniqueId) to data
    }

    private fun nearbyMonsters(player: Player, radius: Double): List<LivingEntity> =
        player.world.getNearbyEntities(player.location, radius, radius, radius).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter(::isMonster)
            .filter { it.location.distanceSquared(player.location) <= radius * radius }
            .sortedBy { it.location.distanceSquared(player.location) }
            .toList()

    private fun isMonster(entity: LivingEntity) = MasterySupport.monster(entity)

    private fun onCooldown(map: ConcurrentHashMap<UUID, Long>, uuid: UUID): Boolean {
        val end = map[uuid] ?: return false
        if (end <= System.currentTimeMillis()) {
            map.remove(uuid, end)
            return false
        }
        return true
    }
}
