package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.skill.element_zf.FormationDamageEvent
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** 术士(job=2)的元素结晶·精进技能。所有入口均由主线程的战斗/阵法事件调用。 */
class WarlockMasterySkills(private val plugin: Hjh_database) : Listener {
    companion object {
        private const val GOLD_CD_MS = 15_000L
        private const val FIRE_CD_MS = 20_000L
    }

    private data class GoldMark(
        val owner: UUID,
        val cap: Double,
        var accumulatedDamage: Double = 0.0
    )

    private val goldCd = ConcurrentHashMap<UUID, Long>()
    private val fireCd = ConcurrentHashMap<UUID, Long>()

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

    /** 在 MONITOR 阶段记录实际通过事件结算的所有伤害。 */
    fun onDamageResolved(victim: LivingEntity, damage: Double) {
        if (damage <= 0.0) return
        goldMarks[victim.uniqueId]?.let { it.accumulatedDamage += damage }
    }

    fun cleanup(uuid: UUID) {
        goldCd.remove(uuid)
        fireCd.remove(uuid)
        goldMarks.entries.removeIf { it.value.owner == uuid }
    }

    private fun triggerGold(player: Player, victim: LivingEntity, damage: Double, eData: ElementCrystalData) {
        if (eData.goldPoints < 4 || onCooldown(goldCd, player.uniqueId)) return
        if (goldMarks.containsKey(victim.uniqueId)) return
        // 本次阵法直接击杀时不挂印、不进入冷却。
        if (!victim.isValid || victim.isDead || victim.health <= 0.0) return

        val mark = GoldMark(player.uniqueId, MasteryRules.goldCap(plugin.elementCrystalManager.reworkedMastery.weaponTier(player)), damage)
        if (goldMarks.putIfAbsent(victim.uniqueId, mark) != null) return
        goldCd[player.uniqueId] = System.currentTimeMillis() + GOLD_CD_MS
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warlock.metal")) {
            player.sendMessage("§e[术] [金·精进] [金印] §f已触发")
        }
        victim.world.playSound(victim.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.6f)

        plugin.elementCrystalManager.masterySupport.animate(player, 60, 5, { elapsed ->
            if (isMonster(victim) && goldMarks[victim.uniqueId] === mark) {
                drawGoldMark(victim, elapsed)
                if (elapsed == 60) {
                    goldMarks.remove(victim.uniqueId, mark)
                    explodeGoldMark(player, victim, mark.accumulatedDamage, mark.cap)
                }
            } else goldMarks.remove(victim.uniqueId, mark)
        }, { goldMarks.remove(victim.uniqueId, mark) })
    }

    private fun explodeGoldMark(owner: Player, centerTarget: LivingEntity, accumulatedDamage: Double, cap: Double) {
        if (!centerTarget.isValid || centerTarget.isDead) return
        val center = centerTarget.location.clone()
        val damage = min(cap, accumulatedDamage * 0.5)
        center.world?.spawnParticle(Particle.FLASH, center.clone().add(0.0, 1.0, 0.0), 2)
        center.world?.spawnParticle(Particle.WAX_ON, center.clone().add(0.0, 0.8, 0.0), 42, 1.0, 0.7, 1.0, 0.12)
        center.world?.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.45f)
        if (damage <= 0.0) return

        for (entity in centerTarget.world.getNearbyEntities(center, 3.0, 3.0, 3.0)) {
            val mob = entity as? LivingEntity ?: continue
            if (!isMonster(mob) || mob.location.distanceSquared(center) > 9.0) continue
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
