package com.hjh_database.accessory.element

import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDamageEvent

/** CombatListener 只发布战斗事实，具体的结晶技能由各职业监听器自行处理。 */
class ElementCrystalDamageDealtEvent(
    val player: Player,
    val victim: LivingEntity,
    val isNormalAttack: Boolean,
    val isArrowHit: Boolean,
    val arrow: AbstractArrow?,
    var damage: Double
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** 护甲结算扩展点；各技能只修改自己的效果，不向 CombatListener 泄漏技能常量。 */
class ElementCrystalArmorCalculationEvent(
    val victim: LivingEntity,
    var armor: Double
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

class ElementCrystalDamageTakenEvent(
    val player: Player,
    val damageEvent: EntityDamageEvent
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
