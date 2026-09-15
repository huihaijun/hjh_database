package com.hjh_database.dungeon.zhenyao

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob

internal object ChiyouMotion {
    /** NoAI 会跳过 LivingEntity.travel；仅暂停自主行为，保留速度和方块碰撞处理。 */
    fun controlled(mob: LivingEntity) {
        mob.setAI(true)
        if (mob is Mob) {
            mob.pathfinder.stopPathfinding()
            mob.target = null
            mob.isAware = false
        }
        mob.isJumping = false
        mob.setGravity(false)
    }

    fun autonomous(mob: LivingEntity) {
        mob.setAI(true)
        if (mob is Mob) mob.isAware = true
        mob.setGravity(true)
    }
}
