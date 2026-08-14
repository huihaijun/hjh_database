package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.skill.element_zf.FormationDamageEvent
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 术士(job=2)的元素结晶·精进技能。所有入口均由主线程的战斗/阵法事件调用。 */
class WarlockMasterySkills(private val plugin: Hjh_database) : Listener {
    companion object {
        private const val GOLD_CD_MS = 15_000L
        private const val WOOD_CD_MS = 20_000L
        private const val WATER_CD_MS = 15_000L
        private const val FIRE_CD_MS = 20_000L
        private const val EARTH_CD_MS = 20_000L
    }

    private data class GoldMark(
        val owner: UUID,
        var accumulatedDamage: Double = 0.0
    )

    private val goldCd = ConcurrentHashMap<UUID, Long>()
    private val woodCd = ConcurrentHashMap<UUID, Long>()
    private val waterCd = ConcurrentHashMap<UUID, Long>()
    private val fireCd = ConcurrentHashMap<UUID, Long>()
    private val earthCd = ConcurrentHashMap<UUID, Long>()

    // 以怪物为键，确保不同玩家也不能在同一怪物身上重复叠加金印。
    private val goldMarks = ConcurrentHashMap<UUID, GoldMark>()

    @EventHandler
    fun handleFormationDamage(event: FormationDamageEvent) {
        val player = event.caster
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return
        val pData = plugin.playerManager.getPlayerData(player) ?: return
        if (pData.job != 2) return
        val eData = plugin.elementCrystalManager.getData(player.uniqueId)
        onFormationDamage(player, event.target, event.actualDamage, eData, pData)
    }

    @EventHandler
    fun handleDamageTaken(event: ElementCrystalDamageTakenEvent) {
        val player = event.player
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return
        val pData = plugin.playerManager.getPlayerData(player) ?: return
        if (pData.job != 2) return
        val eData = plugin.elementCrystalManager.getData(player.uniqueId)
        onDamageTaken(player, event.damageEvent, eData, pData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun handleMarkedTargetDamage(event: EntityDamageEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (event.finalDamage > 0.0) onDamageResolved(victim, event.finalDamage)
    }

    fun onFormationDamage(
        player: Player,
        victim: LivingEntity,
        damage: Double,
        eData: ElementCrystalData,
        pData: PlayerData
    ) {
        if (damage <= 0.0 || !isMonster(victim)) return
        triggerGold(player, victim, damage, eData)
        triggerFire(player, victim.location.clone(), eData, pData)
    }

    fun onFormationCast(player: Player, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.waterPoints < 4 || onCooldown(waterCd, player.uniqueId)) return

        waterCd[player.uniqueId] = System.currentTimeMillis() + WATER_CD_MS
        val nearbyCount = player.world.getNearbyEntities(player.location, 5.0, 5.0, 5.0).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter(::isMonster)
            .count { it.location.distanceSquared(player.location) <= 25.0 }
        val restored = min(20.0, nearbyCount * 2.0)
        val speedSeconds = min(20, 5 + nearbyCount * 2)
        val before = pData.lingli
        pData.addLingli(restored)
        val actual = pData.lingli - before
        plugin.databaseManager.queuePlayerSave(pData)
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, speedSeconds * 20, 0, false, true, true))

        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.water")) {
            player.sendMessage("§9[术] [水·精进] [回潮] §f已触发，回复 §b${format(actual)} §f点灵力并加速 §b$speedSeconds §f秒")
        }
        player.world.spawnParticle(Particle.NAUTILUS, player.location.add(0.0, 1.0, 0.0), 24, 1.2, 0.8, 1.2, 0.05)
        player.world.playSound(player.location, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.0f, 1.25f)
    }

    fun onDamageTaken(player: Player, event: EntityDamageEvent, eData: ElementCrystalData, pData: PlayerData) {
        if (event.isCancelled || event.finalDamage <= 0.0) return
        val damage = event.finalDamage
        triggerWood(player, damage, eData, pData)
        triggerEarth(player, event, damage, eData)
    }

    /** 在 MONITOR 阶段记录实际通过事件结算的所有伤害。 */
    fun onDamageResolved(victim: LivingEntity, damage: Double) {
        if (damage <= 0.0) return
        goldMarks[victim.uniqueId]?.let { it.accumulatedDamage += damage }
    }

    fun cleanup(uuid: UUID) {
        goldCd.remove(uuid)
        woodCd.remove(uuid)
        waterCd.remove(uuid)
        fireCd.remove(uuid)
        earthCd.remove(uuid)
        goldMarks.entries.removeIf { it.value.owner == uuid }
    }

    private fun triggerGold(player: Player, victim: LivingEntity, damage: Double, eData: ElementCrystalData) {
        if (eData.goldPoints < 4 || onCooldown(goldCd, player.uniqueId)) return
        if (goldMarks.containsKey(victim.uniqueId)) return
        // 本次阵法直接击杀时不挂印、不进入冷却。
        if (damage >= victim.health + victim.absorptionAmount) return

        val mark = GoldMark(player.uniqueId, damage)
        if (goldMarks.putIfAbsent(victim.uniqueId, mark) != null) return
        goldCd[player.uniqueId] = System.currentTimeMillis() + GOLD_CD_MS
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.metal")) {
            player.sendMessage("§e[术] [金·精进] [金印] §f已触发")
        }
        victim.world.playSound(victim.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.6f)

        object : BukkitRunnable() {
            var elapsed = 0

            override fun run() {
                if (!victim.isValid || victim.isDead || goldMarks[victim.uniqueId] !== mark) {
                    goldMarks.remove(victim.uniqueId, mark)
                    cancel()
                    return
                }
                drawGoldMark(victim, elapsed)
                elapsed += 5
                if (elapsed >= 60) {
                    goldMarks.remove(victim.uniqueId, mark)
                    explodeGoldMark(player, victim, mark.accumulatedDamage)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun explodeGoldMark(owner: Player, centerTarget: LivingEntity, accumulatedDamage: Double) {
        if (!centerTarget.isValid || centerTarget.isDead) return
        val center = centerTarget.location.clone()
        val damage = min(200.0, accumulatedDamage * 0.5)
        center.world?.spawnParticle(Particle.FLASH, center.clone().add(0.0, 1.0, 0.0), 2)
        center.world?.spawnParticle(Particle.WAX_ON, center.clone().add(0.0, 0.8, 0.0), 42, 1.0, 0.7, 1.0, 0.12)
        center.world?.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.45f)
        if (damage <= 0.0) return

        for (entity in centerTarget.world.getNearbyEntities(center, 2.0, 2.0, 2.0)) {
            val mob = entity as? LivingEntity ?: continue
            if (!isMonster(mob) || mob.location.distanceSquared(center) > 4.0) continue
            masteryDamage(owner, mob, damage)
        }
    }

    private fun drawGoldMark(victim: LivingEntity, tick: Int) {
        val center = victim.location.add(0.0, victim.height * 0.65, 0.0)
        val phase = tick * 0.18
        for (i in 0 until 8) {
            val angle = phase + Math.PI * 2.0 * i / 8.0
            val point = center.clone().add(Math.cos(angle) * 0.55, Math.sin(angle * 2.0) * 0.12, Math.sin(angle) * 0.55)
            victim.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.fromRGB(255, 210, 45), 0.9f))
        }
        victim.world.spawnParticle(Particle.ENCHANT, center, 3, 0.25, 0.35, 0.25, 0.0)
    }

    private fun triggerWood(player: Player, damage: Double, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.woodPoints < 4 || onCooldown(woodCd, player.uniqueId)) return
        woodCd[player.uniqueId] = System.currentTimeMillis() + WOOD_CD_MS
        val healPerTick = damage * 0.7 / 3.0
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.wood")) {
            player.sendMessage("§a[术] [木·精进] [溯生] §f已触发")
        }

        object : BukkitRunnable() {
            var pulses = 0
            override fun run() {
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }
                val maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: pData.maxHealth
                player.health = min(maxHealth, player.health + healPerTick)
                pData.currentHealth = player.health
                player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 8, 0.35, 0.5, 0.35, 0.05)
                pulses++
                if (pulses >= 3) cancel()
            }
        }.runTaskTimer(plugin, 10L, 10L)
    }

    private fun triggerFire(player: Player, center: org.bukkit.Location, eData: ElementCrystalData, pData: PlayerData) {
        if (eData.firePoints < 4 || onCooldown(fireCd, player.uniqueId)) return
        fireCd[player.uniqueId] = System.currentTimeMillis() + FIRE_CD_MS
        val damage = pData.zfStr * 2.5
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.fire")) {
            player.sendMessage("§c[术] [火·精进] [阵焚] §f已触发")
        }
        drawBurningFormation(center)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val world = center.world ?: return@Runnable
            world.spawnParticle(Particle.FLAME, center.clone().add(0.0, 0.4, 0.0), 55, 1.1, 0.35, 1.1, 0.12)
            world.spawnParticle(Particle.LAVA, center.clone().add(0.0, 0.2, 0.0), 15, 0.8, 0.15, 0.8, 0.0)
            world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f)
            for (entity in world.getNearbyEntities(center, 2.0, 2.0, 2.0)) {
                val mob = entity as? LivingEntity ?: continue
                if (!isMonster(mob) || mob.location.distanceSquared(center) > 4.0) continue
                masteryDamage(player, mob, damage)
                mob.velocity = mob.velocity.setY(max(mob.velocity.y, 0.55))
            }
        }, 20L)
    }

    private fun drawBurningFormation(center: org.bukkit.Location) {
        val world = center.world ?: return
        for (i in 0 until 24) {
            val angle = Math.PI * 2.0 * i / 24.0
            val point = center.clone().add(Math.cos(angle) * 1.8, 0.12, Math.sin(angle) * 1.8)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.fromRGB(255, 65, 15), 1.15f))
            if (i % 3 == 0) world.spawnParticle(Particle.SMALL_FLAME, point, 1, 0.0, 0.03, 0.0, 0.0)
        }
    }

    private fun triggerEarth(player: Player, event: EntityDamageEvent, damage: Double, eData: ElementCrystalData) {
        if (eData.earthPoints < 4 || onCooldown(earthCd, player.uniqueId)) return
        earthCd[player.uniqueId] = System.currentTimeMillis() + EARTH_CD_MS

        val extraTrueDamage = damage * 0.2
        // 原伤害本身不致死、但追加真实伤害会致死时，保留1点生命。
        event.damage = if (damage < player.health && damage + extraTrueDamage >= player.health) {
            max(0.0, event.damage - (damage - (player.health - 1.0)))
        } else {
            event.damage + extraTrueDamage
        }

        val shieldAmount = damage * 2.0
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.earth")) {
            player.sendMessage("§6[术] [土·精进] [镇石] §f已触发")
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !player.isDead) grantEarthShield(player, shieldAmount)
        }, 1L)
    }

    private fun grantEarthShield(player: Player, shieldAmount: Double) {
        val amplifier = (ceil(shieldAmount / 4.0).toInt() - 1).coerceAtLeast(0)
        player.removePotionEffect(PotionEffectType.ABSORPTION)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 300, amplifier, false, true, true))
        player.absorptionAmount = min(shieldAmount, (amplifier + 1) * 4.0)
        player.world.spawnParticle(Particle.BLOCK, player.location.add(0.0, 1.0, 0.0), 22, 0.5, 0.8, 0.5, 0.0, Material.STONE.createBlockData())
        player.world.playSound(player.location, Sound.BLOCK_STONE_PLACE, 1.0f, 0.65f)

        object : BukkitRunnable() {
            var elapsed = 0
            override fun run() {
                elapsed++
                if (!player.isOnline || player.isDead) {
                    cancel()
                    return
                }
                if (player.absorptionAmount <= 0.01 || elapsed >= 300) {
                    if (elapsed < 300) player.removePotionEffect(PotionEffectType.ABSORPTION)
                    stunAround(player)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun stunAround(player: Player) {
        val center = player.location
        for (entity in player.world.getNearbyEntities(center, 4.0, 4.0, 4.0)) {
            val mob = entity as? LivingEntity ?: continue
            if (!isMonster(mob) || mob.location.distanceSquared(center) > 16.0) continue
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 10, 255, false, false, true))
            mob.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 10, 255, false, false, true))
        }
        player.world.spawnParticle(Particle.GUST, center.add(0.0, 0.3, 0.0), 5, 1.5, 0.25, 1.5, 0.0)
        player.world.playSound(center, Sound.BLOCK_STONE_BREAK, 1.0f, 0.6f)
    }

    private fun masteryDamage(attacker: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0.0 || !isMonster(target)) return
        FormationMagicDamage.deal(plugin, attacker, target, damage)
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity !is Player && entity.isValid && !entity.isDead &&
            tags.contains("panling") && tags.contains("monster")
    }

    private fun onCooldown(map: ConcurrentHashMap<UUID, Long>, uuid: UUID): Boolean {
        val end = map[uuid] ?: return false
        if (end <= System.currentTimeMillis()) {
            map.remove(uuid, end)
            return false
        }
        return true
    }

    private fun format(value: Double): String = String.format("%.1f", value)
}
