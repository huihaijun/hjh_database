package com.hjh_database.listener

import org.bukkit.entity.LivingEntity
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** 通用最终伤害扩展点，具体技能效果不需要硬编码进 CombatListener。 */
class CombatDamageCalculationEvent(
    val attacker: LivingEntity?,
    val victim: LivingEntity,
    var damage: Double
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
