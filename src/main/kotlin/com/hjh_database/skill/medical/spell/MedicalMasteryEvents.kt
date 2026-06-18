package com.hjh_database.skill.medical.spell

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class MedicalCastEvent(val caster: Player, val spellId: String) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

class MedicalDamageEvent(
    val caster: Player,
    val target: LivingEntity,
    val spellId: String?,
    val requestedDamage: Double,
    val actualDamage: Double,
    val triggersMastery: Boolean
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

class MedicalBannerRegenEvent(
    val player: Player,
    var intervalTicks: Int,
    var amount: Double
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

