package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.listener.CombatDamageCalculationEvent
import com.hjh_database.skill.medical.spell.MedicalBannerRegenEvent
import com.hjh_database.skill.medical.spell.MedicalCastEvent
import com.hjh_database.skill.medical.spell.MedicalDamageEvent
import com.hjh_database.skill.medical.spell.MedicalHealEvent
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
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/** 医师(job=3)元素结晶·精进。只消费医术系统发布的专用事件。 */
class MedicalMasterySkills(private val plugin: Hjh_database) : Listener {
    private data class EarthWindow(val spellId: String?, val expiresAt: Long)
    private data class ShieldState(val generation: UUID, val amplifier: Int, val expiresAt: Long)

    private val goldCd = ConcurrentHashMap<UUID, Long>()
    private val woodCd = ConcurrentHashMap<UUID, Long>()
    private val waterCd = ConcurrentHashMap<UUID, Long>()
    private val fireCd = ConcurrentHashMap<UUID, Long>()
    private val earthCd = ConcurrentHashMap<UUID, Long>()
    private val pureFlowEnds = ConcurrentHashMap<UUID, Long>()
    private val meridianEnds = ConcurrentHashMap<UUID, Long>()
    private val earthWindows = ConcurrentHashMap<UUID, EarthWindow>()
    private val shields = ConcurrentHashMap<UUID, ShieldState>()
    private val meridianSpeedKey = NamespacedKey(plugin, "medical_mastery_meridian_speed")

    @EventHandler
    fun onMedicalCast(event: MedicalCastEvent) {
        val context = context(event.caster) ?: return
        triggerGold(event.caster, context.first, context.second)
        triggerWater(event.caster, context.first)
    }

    @EventHandler
    fun onMedicalHeal(event: MedicalHealEvent) {
        if (event.requestedHeal <= 0.0) return
        val context = context(event.caster) ?: return
        if (event.actualHeal > 0.0) triggerWood(event, context.first)
        triggerEarth(event, context.first, context.second.zfStr)
    }

    @EventHandler
    fun onMedicalDamage(event: MedicalDamageEvent) {
        if (!event.triggersMastery || event.actualDamage <= 0.0) return
        val context = context(event.caster) ?: return
        triggerFire(event.caster, event.target, context.first)
    }

    @EventHandler
    fun onBannerRegen(event: MedicalBannerRegenEvent) {
        val context = context(event.player) ?: return
        if (context.first.waterPoints < 4) return
        val end = pureFlowEnds[event.player.uniqueId] ?: return
        if (end <= System.currentTimeMillis()) {
            pureFlowEnds.remove(event.player.uniqueId, end)
            return
        }
        event.intervalTicks = (event.intervalTicks / 1.5).toInt().coerceAtLeast(5)
        event.amount *= 1.2
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onShieldDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val state = shields[player.uniqueId] ?: return
        val effect = player.getPotionEffect(PotionEffectType.ABSORPTION)
        if (effect == null || effect.amplifier != state.amplifier || player.absorptionAmount <= 0.01) {
            expireShield(player, state)
        }
    }

    fun cleanup(uuid: UUID) {
        goldCd.remove(uuid)
        woodCd.remove(uuid)
        waterCd.remove(uuid)
        fireCd.remove(uuid)
        earthCd.remove(uuid)
        pureFlowEnds.remove(uuid)
        earthWindows.remove(uuid)
        shields.remove(uuid)
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

    private fun triggerWood(event: MedicalHealEvent, crystal: ElementCrystalData) {
        if (crystal.woodPoints < 4 || onCooldown(woodCd, event.caster.uniqueId)) return
        val source = event.target
        val used = mutableSetOf<UUID>()
        used += event.caster.uniqueId
        used += source.uniqueId
        val first = nearestTeammate(source, used) ?: return
        woodCd[event.caster.uniqueId] = System.currentTimeMillis() + 15_000L
        if (!plugin.passiveSubtitleManager.showCombatEvent(event.caster, "element.mastery.doctor.wood")) {
            event.caster.sendMessage("§a[医] [木·精进] [花语] §f已触发")
        }
        transferFlower(source, first, event.actualHeal * 0.5, used, 1)
    }

    private fun transferFlower(source: LivingEntity, target: Player, amount: Double, used: MutableSet<UUID>, step: Int) {
        if (amount <= 0.0 || step > 3) return
        used += target.uniqueId
        val display = source.world.spawn(source.location.add(0.0, 1.3, 0.0), ItemDisplay::class.java) {
            it.setItemStack(ItemStack(Material.OXEYE_DAISY))
            val t = it.transformation
            t.scale.set(0.45f, 0.45f, 0.45f)
            it.transformation = t
        }
        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks >= 8 || !target.isOnline || target.isDead) {
                    display.remove()
                    if (target.isOnline && !target.isDead) {
                        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                        val actual = min(amount, maxHealth - target.health).coerceAtLeast(0.0)
                        if (actual > 0.0) {
                            target.health += actual
                            target.world.spawnParticle(Particle.HEART, target.location.add(0.0, 1.5, 0.0), 3, 0.25, 0.25, 0.25, 0.0)
                            if (step < 3) {
                                val next = nearestTeammate(target, used)
                                if (next != null) transferFlower(target, next, actual * 0.5, used, step + 1)
                            }
                        }
                    }
                    cancel()
                    return
                }
                val destination = target.location.add(0.0, 1.2, 0.0)
                val next = display.location.toVector().multiply(0.6).add(destination.toVector().multiply(0.4)).toLocation(target.world)
                display.teleport(next)
                target.world.spawnParticle(Particle.HAPPY_VILLAGER, next, 2, 0.04, 0.04, 0.04, 0.0)
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun triggerWater(player: Player, crystal: ElementCrystalData) {
        if (crystal.waterPoints < 4 || onCooldown(waterCd, player.uniqueId)) return
        waterCd[player.uniqueId] = System.currentTimeMillis() + 20_000L
        pureFlowEnds[player.uniqueId] = System.currentTimeMillis() + 8_000L
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.doctor.water")) {
            player.sendMessage("§9[医] [水·精进] [净流] §f已触发，持续 §b8 §f秒")
        }
        player.world.spawnParticle(Particle.SPLASH, player.location.add(0.0, 1.0, 0.0), 28, 0.7, 0.8, 0.7, 0.1)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RETURN, 0.8f, 1.4f)
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

    private fun triggerEarth(event: MedicalHealEvent, crystal: ElementCrystalData, formationStrength: Double) {
        val target = event.target as? Player ?: return
        if (crystal.earthPoints < 4) return
        val uuid = event.caster.uniqueId
        val now = System.currentTimeMillis()
        val window = earthWindows[uuid]
        val belongsToCurrentBatch = window != null && window.expiresAt >= now && window.spellId == event.spellId
        if (!belongsToCurrentBatch) {
            if (onCooldown(earthCd, uuid)) return
            earthCd[uuid] = now + 20_000L
            earthWindows[uuid] = EarthWindow(event.spellId, now + 500L)
            if (!plugin.passiveSubtitleManager.showCombatEvent(event.caster, "element.mastery.doctor.earth")) {
                event.caster.sendMessage("§6[医] [土·精进] [厚土] §f已触发")
            }
        }
        grantShield(target, formationStrength.coerceAtLeast(0.0))
    }

    private fun grantShield(target: Player, amount: Double) {
        if (amount <= 0.0) return
        val amplifier = (ceil(amount / 4.0).toInt() - 1).coerceAtLeast(0)
        target.removePotionEffect(PotionEffectType.ABSORPTION)
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 100, amplifier, false, true, true), true)
        target.absorptionAmount = amount
        val state = ShieldState(UUID.randomUUID(), amplifier, System.currentTimeMillis() + 5_000L)
        shields[target.uniqueId] = state
        target.world.spawnParticle(Particle.BLOCK, target.location.add(0.0, 1.0, 0.0), 16, 0.45, 0.7, 0.45, 0.0, Material.MOSS_BLOCK.createBlockData())

        object : BukkitRunnable() {
            override fun run() {
                if (!target.isOnline || target.isDead || shields[target.uniqueId] !== state) {
                    cancel()
                    return
                }
                val effect = target.getPotionEffect(PotionEffectType.ABSORPTION)
                if (target.absorptionAmount <= 0.01 || effect == null || effect.amplifier != state.amplifier || System.currentTimeMillis() >= state.expiresAt) {
                    expireShield(target, state)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun expireShield(target: Player, state: ShieldState) {
        if (!shields.remove(target.uniqueId, state)) return
        val effect = target.getPotionEffect(PotionEffectType.ABSORPTION)
        if (effect?.amplifier == state.amplifier && System.currentTimeMillis() >= state.expiresAt) {
            target.removePotionEffect(PotionEffectType.ABSORPTION)
        }
        target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true))
        target.world.spawnParticle(Particle.COMPOSTER, target.location.add(0.0, 1.0, 0.0), 14, 0.45, 0.65, 0.45, 0.04)
        target.world.playSound(target.location, Sound.BLOCK_GRASS_BREAK, 0.8f, 0.8f)
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

    private fun nearestTeammate(source: LivingEntity, excluded: Set<UUID>): Player? =
        source.world.getNearbyEntities(source.location, 15.0, 15.0, 15.0).asSequence()
            .filterIsInstance<Player>()
            .filter { it.isOnline && !it.isDead && it.uniqueId !in excluded }
            .filter { it.location.distanceSquared(source.location) <= 225.0 }
            .minByOrNull { it.location.distanceSquared(source.location) }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity !is Player && entity.isValid && !entity.isDead && tags.contains("panling") && tags.contains("monster")
    }

    private fun onCooldown(map: ConcurrentHashMap<UUID, Long>, uuid: UUID): Boolean {
        val end = map[uuid] ?: return false
        if (end <= System.currentTimeMillis()) {
            map.remove(uuid, end)
            return false
        }
        return true
    }
}
