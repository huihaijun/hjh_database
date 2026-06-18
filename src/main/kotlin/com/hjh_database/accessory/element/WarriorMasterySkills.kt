package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.mastery.warrior.*
import com.hjh_database.data.PlayerData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/**
 * 战士(job=0)的元素结晶·精进技能 (4点时解锁) 协调中心
 * 委托至 5 个元素的独立实现类
 */
class WarriorMasterySkills(private val plugin: Hjh_database) : Listener {

    private val gold = GoldMasteryWarrior(plugin)
    private val wood = WoodMasteryWarrior(plugin)
    private val water = WaterMasteryWarrior(plugin)
    private val fire = FireMasteryWarrior(plugin)
    private val earth = EarthMasteryWarrior(plugin)

    @EventHandler
    fun handleDamageDealt(event: ElementCrystalDamageDealtEvent) {
        val player = event.player
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return
        val pData = plugin.playerManager.getPlayerData(player) ?: return
        if (pData.job != 0) return
        val eData = plugin.elementCrystalManager.getData(player.uniqueId)
        onDamageDealt(player, event.victim, eData, pData, event.isNormalAttack)
    }

    @EventHandler
    fun handleDamageTaken(event: ElementCrystalDamageTakenEvent) {
        val player = event.player
        if (!plugin.elementCrystalManager.isCrystalActive(player)) return
        val pData = plugin.playerManager.getPlayerData(player) ?: return
        if (pData.job != 0) return
        val eData = plugin.elementCrystalManager.getData(player.uniqueId)
        onDamageTaken(player, event.damageEvent, eData, pData)
    }

    @EventHandler
    fun handleArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val victim = event.victim
        if (event.armor <= 0.0 || !victim.hasMetadata(EarthMasteryWarrior.META_ARMOR_REDUCE)) return
        val until = victim.getMetadata(EarthMasteryWarrior.META_ARMOR_REDUCE).firstOrNull()?.asLong() ?: 0L
        if (System.currentTimeMillis() < until) {
            event.armor *= 0.5
        } else {
            victim.removeMetadata(EarthMasteryWarrior.META_ARMOR_REDUCE, plugin)
        }
    }

    /** 玩家造成伤害时调用 */
    fun onDamageDealt(player: Player, victim: LivingEntity, eData: ElementCrystalData, pData: PlayerData, isNormalAttack: Boolean) {
        gold.onDamageDealt(player, victim, eData, pData, isNormalAttack)
        fire.onDamageDealt(player, victim, eData, pData, isNormalAttack)
        water.onDamageDealt(player, victim, eData, pData)
    }

    /** 玩家受到伤害时调用 */
    fun onDamageTaken(player: Player, event: EntityDamageEvent, eData: ElementCrystalData, pData: PlayerData) {
        wood.onDamageTaken(player, event, eData, pData)
        earth.onDamageTaken(player, event, eData, pData)
        water.onDamageTaken(player, event, eData, pData)
    }

    /** 玩家下线时清理数据 */
    fun cleanup(uuid: UUID) {
        gold.cleanup(uuid)
        wood.cleanup(uuid)
        water.cleanup(uuid)
        fire.cleanup(uuid)
        earth.cleanup(uuid)
    }
}
