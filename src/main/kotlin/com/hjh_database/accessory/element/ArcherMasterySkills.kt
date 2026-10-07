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
 * 保留金精进入口；重构的木水火土由 ReworkedMasterySkills 在最终结算阶段处理。
 */
class ArcherMasterySkills(private val plugin: Hjh_database) : Listener {

    private val gold = GoldMasteryArcher(plugin)
    private val fire = FireMasteryArcher(plugin)

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
        fire.onDamageDealt(player, victim, eData, pData, isArrowHit)
    }

    /** 玩家下线或清理时调用 */
    fun cleanup(uuid: UUID) {
        gold.cleanup(uuid)
        fire.cleanup(uuid)
    }
}
