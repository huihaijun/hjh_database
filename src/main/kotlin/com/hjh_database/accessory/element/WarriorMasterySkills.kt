package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.mastery.warrior.*
import com.hjh_database.data.PlayerData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/**
 * 战士(job=0)的元素结晶·精进技能 (4点时解锁) 协调中心
 * 保留金精进入口；重构的木水火土由 ReworkedMasterySkills 在最终结算阶段处理。
 */
class WarriorMasterySkills(private val plugin: Hjh_database) : Listener {

    private val gold = GoldMasteryWarrior(plugin)

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
    fun handleArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val victim = event.victim
        if (event.armor <= 0.0 || !victim.hasMetadata(ReworkedMasterySkills.ARMOR_REDUCE)) return
        val until = victim.getMetadata(ReworkedMasterySkills.ARMOR_REDUCE).firstOrNull()?.asLong() ?: 0L
        if (System.currentTimeMillis() < until) {
            event.armor *= 0.5
        } else {
            victim.removeMetadata(ReworkedMasterySkills.ARMOR_REDUCE, plugin)
        }
    }

    /** 玩家造成伤害时调用 */
    fun onDamageDealt(player: Player, victim: LivingEntity, eData: ElementCrystalData, pData: PlayerData, isNormalAttack: Boolean) {
        gold.onDamageDealt(player, victim, eData, pData, isNormalAttack)
    }

    /** 玩家下线时清理数据 */
    fun cleanup(uuid: UUID) {
        gold.cleanup(uuid)
    }
}
