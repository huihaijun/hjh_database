package com.hjh_database.skill.element_zf

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** 阵法伤害完成结算后发布；不依赖任何通用伤害 metadata 判断来源。 */
class FormationDamageEvent(
    val caster: Player,
    val target: LivingEntity,
    val requestedDamage: Double,
    val actualDamage: Double,
    /** 仅元素阵法伤害会携带类型；医术、饰品等共用伤害入口时保持 null。 */
    val element: FormationElement? = null,
    /** 同一次施法的即时、延迟与持续命中共用此对象；纯控制命中的伤害为0。 */
    val cast: FormationCast? = null
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

enum class FormationElement {
    METAL,
    WOOD,
    WATER,
    FIRE,
    EARTH
}
