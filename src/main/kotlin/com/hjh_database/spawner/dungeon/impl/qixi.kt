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
                MobDrop("queqiaojinghe", 1, 1, 1.0),
                MobDrop("tongxinsuo", 1, 1, 0.08)
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
                MobDrop("queqiaojinghe", 1, 1, 1.0),
                MobDrop("tongxinsuo", 1, 1, 0.08)
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
            drops = listOf(
                MobDrop("xingheling", 1, 1, 0.20),
                MobDrop("tongxinsuo", 1, 1, 0.08)
            ),
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
            drops = listOf(
                MobDrop("xingheling", 1, 1, 0.20),
                MobDrop("tongxinsuo", 1, 1, 0.08)
            ),
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
            drops = listOf(
                MobDrop("xingheling", 1, 1, 0.20),
                MobDrop("tongxinsuo", 1, 1, 0.08)
            ),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            mainHand = Material.IRON_SWORD
        ),
        MobDefinition(
            id = "yinlingzhu",
            name = "&c银灵蛛",
            type = EntityType.SPIDER,
            health = 105.0,
            damage = 1.0,
            armor = 12.0,
            speed = 0.34,
            maxNearby = 6,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList()
        ),
        MobDefinition(
            id = "xinghetongling",
            name = "&c星河统领",
            type = EntityType.VINDICATOR,
            health = 360.0,
            damage = 48.0,
            armor = 58.0,
            speed = 0.23,
            maxNearby = 3,
            exp = 0,
            drops = listOf(
                MobDrop("xingheling", 1, 1, 0.20),
                MobDrop("tongxinsuo", 1, 1, 0.08)
            ),
            affixes = emptyList(),
            helmet = Material.DIAMOND_HELMET,
            chestplate = Material.DIAMOND_CHESTPLATE,
            mainHand = Material.DIAMOND_AXE
        ),
        MobDefinition(
            id = "luanxingzhe",
            name = "&c乱星者",
            type = EntityType.BLAZE,
            health = 85.0,
            damage = 0.0,
            armor = 5.0,
            speed = 0.30,
            maxNearby = 3,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList()
        ),
        MobDefinition(
            id = "wangmuniangniang",
            name = "&4&n王母娘娘",
            type = EntityType.EVOKER,
            health = 10000.0,
            damage = 0.0,
            armor = 100.0,
            speed = 0.45,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            affixes = emptyList()
        ),
        MobDefinition(
            id = "qixi_chilingzhe",
            name = "&e持令者",
            type = EntityType.HUSK,
            health = 420.0,
            damage = 0.0,
            armor = 70.0,
            speed = 0.01,
            maxNearby = 2,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            chestplate = Material.GOLDEN_CHESTPLATE,
            mainHand = Material.BOOK
        ),
        MobDefinition(
            id = "qixi_tianheshouhengzhe",
            name = "&c天河守恒者",
            type = EntityType.HUSK,
            health = 160.0,
            damage = 0.0,
            armor = 25.0,
            speed = 0.01,
            maxNearby = 3,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList(),
            helmet = Material.DIAMOND_HELMET,
            chestplate = Material.CHAINMAIL_CHESTPLATE
        ),
        MobDefinition(
            id = "qixi_qianghuayinhebuwei",
            name = "&c天河精锐步卫",
            type = EntityType.VINDICATOR,
            health = 180.0,
            damage = 35.0,
            armor = 45.0,
            speed = 0.23,
            maxNearby = 6,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList(),
            helmet = Material.DIAMOND_HELMET,
            chestplate = Material.DIAMOND_CHESTPLATE,
            mainHand = Material.DIAMOND_AXE
        ),
        MobDefinition(
            id = "qixi_qianghuayinyugongwei",
            name = "&c天河精锐弓卫",
            type = EntityType.SKELETON,
            health = 130.0,
            damage = 35.0,
            armor = 55.0,
            speed = 0.23,
            maxNearby = 6,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList(),
            helmet = Material.GOLDEN_HELMET,
            chestplate = Material.GOLDEN_CHESTPLATE,
            mainHand = Material.BOW
        ),
        MobDefinition(
            id = "qixi_xishoujingheyinlingzhu",
            name = "&c吸收了晶核的银灵蛛",
            type = EntityType.SPIDER,
            health = 105.0,
            damage = 1.0,
            armor = 12.0,
            speed = 0.34,
            maxNearby = 6,
            exp = 0,
            drops = listOf(MobDrop("tongxinsuo", 1, 1, 0.08)),
            affixes = emptyList()
        ),

    )
}
