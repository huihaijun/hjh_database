package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.mastery.archer.*
import com.hjh_database.data.PlayerData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractArrow
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/**
 * 弓箭手(job=1)的元素结晶·精进技能 (4点时解锁) 协调中心
 * 委托至 5 个元素的独立实现类
 */
class ArcherMasterySkills(private val plugin: Hjh_database) : Listener {

    private val gold = GoldMasteryArcher(plugin)
    private val wood = WoodMasteryArcher(plugin)
    private val water = WaterMasteryArcher(plugin)
    private val fire = FireMasteryArcher(plugin)
    private val earth = EarthMasteryArcher(plugin)

    @EventHandler
    fun handleDamageDealt(event: ElementCrystalDamageDealtEvent) {
        val player = event.player
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return
        val pData = plugin.playerManager.getPlayerData(player) ?: return
        if (pData.job != 1) return
        val eData = plugin.elementCrystalManager.getData(player.uniqueId)
        onDamageDealt(
            player,
            event.victim,
            eData,
            pData,
            event.isArrowHit,
            event.arrow,
            event.damage
        )
    }

    @EventHandler
    fun handleArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val victim = event.victim
        if (event.armor <= 0.0 || !victim.hasMetadata(EarthMasteryArcher.META_ARMOR_REDUCE)) return
        val until = victim.getMetadata(EarthMasteryArcher.META_ARMOR_REDUCE).firstOrNull()?.asLong() ?: 0L
        if (System.currentTimeMillis() < until) {
            event.armor *= 0.7
        } else {
            victim.removeMetadata(EarthMasteryArcher.META_ARMOR_REDUCE, plugin)
        }
    }

    /** 玩家造成伤害时调用 */
    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean,
        arrow: AbstractArrow?,
        eventDamage: Double
    ) {
        gold.onDamageDealt(player, victim, eData, pData, isArrowHit, arrow)
        wood.onDamageDealt(player, victim, eData, pData, isArrowHit)
        water.onDamageDealt(player, victim, eData, pData, isArrowHit, arrow, eventDamage)
        fire.onDamageDealt(player, victim, eData, pData, isArrowHit)
        earth.onDamageDealt(player, victim, eData, pData, isArrowHit)
    }

    /** 玩家下线或清理时调用 */
    fun cleanup(uuid: UUID) {
        gold.cleanup(uuid)
        wood.cleanup(uuid)
        water.cleanup(uuid)
        fire.cleanup(uuid)
        earth.cleanup(uuid)
    }
}
