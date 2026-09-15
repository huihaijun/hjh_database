package com.hjh_database.spawner.dungeon.impl

import com.hjh_database.spawner.MobDefinition
import org.bukkit.Material
import org.bukkit.entity.EntityType

/** 测试数值参照镇妖塔六、七层；只声明定义，统一交给 MobFactory 生成。 */
internal object Huomo {
    const val MOB_TAG = "huomo_mob"
    private fun mob(id: String, name: String, type: EntityType, hp: Double, attack: Double, armor: Double,
                    speed: Double = .27, hand: Material? = null, size: Int? = null, boss: Boolean = false) =
        MobDefinition(id, name, type, hp, attack, armor, speed, 128, emptyList(), exp = 0,
            mainHand = hand, slimeSize = size,
            helmet = if (type == EntityType.ZOMBIE || type == EntityType.SKELETON) Material.CHAINMAIL_HELMET else null,
            scoreboardTags = if (boss) setOf(MOB_TAG, "instance_boss") else setOf(MOB_TAG),
            knockbackResistance = if (speed == 0.0) 1.0 else null)
    val definitions = listOf(
        mob("huomo_guard", "&c火焰魔王的近卫", EntityType.ZOMBIE, 350.0, 38.0, 45.0, hand = Material.IRON_SWORD),
        mob("huomo_archer", "&c火焰魔王的弓卫", EntityType.SKELETON, 350.0, 38.0, 45.0, hand = Material.BOW),
        mob("huomo_pet", "&c火焰魔王的宠物", EntityType.SLIME, 400.0, 42.0, 50.0, .28, size = 4),
        mob("huomo_spirit", "&c火焰魔王的灵物", EntityType.BLAZE, 400.0, 42.0, 50.0, .28),
        mob("huomo_core", "&c火焰魔王的核心", EntityType.BLAZE, 15000.0, 0.0, 100.0, 0.0, boss = true),
        mob("huomo_ember", "&6火种", EntityType.BLAZE, 150.0, 0.0, 0.0, 0.0)
    )
}
