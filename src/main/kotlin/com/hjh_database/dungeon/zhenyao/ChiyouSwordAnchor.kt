package com.hjh_database.dungeon.zhenyao

import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector

/** Derived from chiyou's BB5 sword_summon end-frame socket, in model blocks; regenerate with asset changes. */
internal object ChiyouSwordAnchor {
    fun release(boss: LivingEntity): Location {
        val offset=Vector(-0.375292785, 1.504141822, -0.491438827)
        offset.rotateAroundY(Math.toRadians(180.0-boss.location.yaw))
        offset.multiply(boss.getAttribute(Attribute.SCALE)?.value?:1.0)
        return boss.location.clone().add(offset)
    }
}
