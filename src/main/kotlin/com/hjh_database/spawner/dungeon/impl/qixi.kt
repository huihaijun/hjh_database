package com.hjh_database.spawner.dungeon.impl

import com.hjh_database.spawner.MobDefinition
import com.hjh_database.spawner.MobDrop
import org.bukkit.Material
import org.bukkit.entity.EntityType

internal object Qixi {
    val definitions = listOf(
        MobDefinition(
            id = "qixi_zombie",
            name = "&c鹊桥近卫",
            type = EntityType.ZOMBIE,
            health = 165.0,
            damage = 27.0,
            armor = 45.0,
            speed = 0.24,
            maxNearby = 3,
            exp = 0,
            drops = listOf(
                MobDrop("queqiaojinghe", 1, 1, 1.0)
            ),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            mainHand = Material.IRON_AXE
        ),
        MobDefinition(
            id = "qixi_skeleton",
            name = "&c鹊桥弓手",
            type = EntityType.SKELETON,
            health = 130.0,
            damage = 25.0,
            armor = 37.0,
            speed = 0.22,
            maxNearby = 3,
            exp = 0,
            drops = listOf(
                MobDrop("queqiaojinghe", 1, 1, 1.0)
            ),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            mainHand = Material.BOW
        ),
        MobDefinition(
            id = "yinhebuwei",
            name = "&c银河步卫",
            type = EntityType.VINDICATOR,
            health = 180.0,
            damage = 35.0,
            armor = 45.0,
            speed = 0.23,
            maxNearby = 3,
            exp = 0,
            drops = emptyList(),
            affixes = emptyList(),
            helmet = Material.DIAMOND_HELMET,
            chestplate = Material.DIAMOND_CHESTPLATE,
            mainHand = Material.DIAMOND_AXE
        ),
        MobDefinition(
            id = "yinyugongwei",
            name = "&c银羽弓卫",
            type = EntityType.SKELETON,
            health = 130.0,
            damage = 35.0,
            armor = 55.0,
            speed = 0.23,
            maxNearby = 3,
            exp = 0,
            drops = emptyList(),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            chestplate = Material.GOLDEN_CHESTPLATE,
            mainHand = Material.BOW
        ),
        MobDefinition(
            id = "xingsuoshouwei",
            name = "&c星锁守卫",
            type = EntityType.HUSK,
            health = 255.0,
            damage = 27.0,
            armor = 45.0,
            speed = 0.01,
            maxNearby = 3,
            exp = 0,
            drops = emptyList(),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            mainHand = Material.IRON_SWORD
        ),

    )
}
