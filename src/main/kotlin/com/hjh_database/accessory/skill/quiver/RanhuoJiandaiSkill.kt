package com.hjh_database.accessory.skill.quiver

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.data.PlayerData
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.NamespacedKey
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.min

class RanhuoJiandaiSkill(plugin: Hjh_database) : BaseQuiverSkill(plugin) {

    companion object {
        const val ARMOR_REDUCE_META = "HJH_RANHUO_ARMOR_REDUCE"
        private const val COOLDOWN_MS = 5000L
        private const val MAX_FLIGHT_TICKS = 100
        private const val SCAN_INTERVAL_TICKS = 2L
        private const val RANGE = 3.0
    }

    private val activeArrowTasks = HashMap<UUID, BukkitTask>()
    private val emberDust = Particle.DustOptions(Color.fromRGB(255, 95, 18), 0.9f)
    private val ranhuoArrowKey = NamespacedKey(plugin, "ranhuo_arrow")

    override fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData) {
        val arrow = event.projectile as? AbstractArrow ?: return
        val now = System.currentTimeMillis()
        val cooldownEnd = getTrackedCooldownEnd(player)
        if (now < cooldownEnd) return

        startTrackedCooldown(player, COOLDOWN_MS, now)
        val damage = data.archerDamage * 1.5
        arrow.persistentDataContainer.set(ranhuoArrowKey, PersistentDataType.BYTE, 1)
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§a§l饰品技【流火矢】已发动！"))
        startFlowingFireArrow(player, arrow, damage)
    }

    fun onArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        if (event.armor <= 0.0) return
        val victim = event.victim
        if (!victim.hasMetadata(ARMOR_REDUCE_META)) return
        val until = victim.getMetadata(ARMOR_REDUCE_META)
            .firstOrNull { it.owningPlugin == plugin }
            ?.asLong() ?: 0L
        if (System.currentTimeMillis() < until) {
            event.armor *= 0.65
        } else {
            victim.removeMetadata(ARMOR_REDUCE_META, plugin)
        }
    }

    private fun startFlowingFireArrow(player: Player, arrow: AbstractArrow, damage: Double) {
        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.55f, 1.35f)
        val hitThisArrow = HashSet<UUID>()
        val taskId = arrow.uniqueId
        var livedTicks = 0

        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!arrow.isValid || arrow.isDead || arrow.isInBlock || livedTicks >= MAX_FLIGHT_TICKS) {
                activeArrowTasks.remove(taskId)?.cancel()
                if (arrow.isValid && !arrow.isDead) arrow.remove()
                return@Runnable
            }

            val loc = arrow.location
            loc.world?.spawnParticle(Particle.FLAME, loc, 2, 0.08, 0.08, 0.08, 0.015)
            if (livedTicks % 4 == 0) {
                loc.world?.spawnParticle(Particle.DUST, loc, 1, 0.04, 0.04, 0.04, 0.0, emberDust)
            }

            val nearby = loc.world?.getNearbyEntities(loc, RANGE, RANGE, RANGE) ?: emptyList()
            for (entity in nearby) {
                val mob = entity as? LivingEntity ?: continue
                if (!isValidTarget(mob)) continue
                if (!hitThisArrow.add(mob.uniqueId)) continue

                igniteTarget(player, mob, damage)
            }

            livedTicks += SCAN_INTERVAL_TICKS.toInt()
        }, 0L, SCAN_INTERVAL_TICKS)

        activeArrowTasks[taskId] = task
    }

    private fun igniteTarget(player: Player, target: LivingEntity, damage: Double) {
        val now = System.currentTimeMillis()
        target.setMetadata(ARMOR_REDUCE_META, FixedMetadataValue(plugin, now + 5000L))
        target.setMetadata("HJH_ARMORED_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))

        /*
         * 流火矢的技能伤害通常先于箭矢本体碰撞。若保留它新产生的受伤间隔，
         * 紧随其后的箭矢伤害会被原版无敌帧吞掉。
         *
         * 这里只临时绕过并移除“本次技能伤害”产生的帧，同时恢复目标原先的
         * noDamageTicks/lastDamage，避免意外清除其他攻击已经建立的合法受伤状态。
         */
        val previousNoDamageTicks = target.noDamageTicks
        val previousLastDamage = target.lastDamage
        target.noDamageTicks = 0
        try {
            target.damage(0.01, player)
        } finally {
            if (target.hasMetadata("HJH_ARMORED_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_ARMORED_MAGIC_DAMAGE", plugin)
            }
            if (target.isValid && !target.isDead) {
                target.noDamageTicks = previousNoDamageTicks.coerceAtMost(target.maximumNoDamageTicks)
                target.lastDamage = previousLastDamage
            }
        }

        val loc = target.location.clone().add(0.0, min(target.height, 1.2), 0.0)
        target.world.spawnParticle(Particle.FLAME, loc, 12, 0.35, 0.45, 0.35, 0.035)
        target.world.spawnParticle(Particle.LAVA, target.location.clone().add(0.0, 0.25, 0.0), 3, 0.25, 0.12, 0.25, 0.0)
        target.world.spawnParticle(Particle.DUST, loc, 5, 0.25, 0.35, 0.25, 0.0, emberDust)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
            entity !is ArmorStand &&
            entity.isValid &&
            !entity.isDead &&
            entity.scoreboardTags.contains("panling") &&
            entity.scoreboardTags.contains("monster")
    }
}
