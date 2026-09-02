package com.hjh_database.spawner.dungeon.impl

import com.hjh_database.spawner.MobDefinition
import org.bukkit.Material
import org.bukkit.entity.EntityType

/** 圣山副本专用怪物。数值按单机测试服需求硬编码，不读取外部配置。 */
internal object ShengShan {
    val definitions = listOf(
        MobDefinition(
            id = "shengshan_thunder",
            name = "&e震雷之相·靐",
            type = EntityType.VINDICATOR,
            health = 7000.0,
            damage = 35.0,
            armor = 70.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            mainHand = Material.GOLDEN_PICKAXE,
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_sky",
            name = "&6乾天之相·晶",
            type = EntityType.GHAST,
            health = 8000.0,
            damage = 35.0,
            armor = 80.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_water",
            name = "&9坎水之相·淼",
            type = EntityType.ZOMBIE,
            health = 7500.0,
            damage = 35.0,
            armor = 75.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.STONE_SWORD,
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_mountain",
            name = "&6艮山之相·芔",
            type = EntityType.HUSK,
            health = 8000.0,
            damage = 40.0,
            armor = 130.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("instance_boss"),
            mainHand = Material.DIAMOND_PICKAXE
        ),
        MobDefinition(
            id = "shengshan_fire",
            name = "&c离火之相·焱",
            type = EntityType.ZOMBIE,
            health = 6800.0,
            damage = 35.0,
            armor = 68.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            helmet = Material.DIAMOND_HELMET,
            mainHand = Material.IRON_AXE,
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_wind",
            name = "&f巽风之相·雾",
            type = EntityType.ZOMBIE,
            health = 6500.0,
            damage = 30.0,
            armor = 65.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            helmet = Material.IRON_HELMET,
            mainHand = Material.GOLDEN_AXE,
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_swamp",
            name = "&5兑泽之相·恶",
            type = EntityType.SLIME,
            health = 7000.0,
            damage = 25.0,
            armor = 60.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("instance_boss"),
            slimeSize = 7
        ),
        MobDefinition(
            id = "shengshan_earth",
            name = "&8坤地之相·垚",
            type = EntityType.WITHER_SKELETON,
            health = 7800.0,
            damage = 30.0,
            armor = 64.0,
            speed = 0.3,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("instance_boss"),
            mainHand = Material.GOLDEN_SWORD
        ),
        MobDefinition(
            id = "shengshan_pangu_core",
            name = "&c盘古的内核",
            type = EntityType.BLAZE,
            health = 800.0,
            damage = 0.0,
            armor = 0.0,
            speed = 0.0,
            maxNearby = 1,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("instance_boss")
        ),
        MobDefinition(
            id = "shengshan_water_guard",
            name = "&9灵渊守卫",
            type = EntityType.GUARDIAN,
            health = 150.0,
            damage = 22.0,
            armor = 20.0,
            speed = 0.27,
            maxNearby = 16,
            exp = 0,
            drops = emptyList()
        ),
        MobDefinition(
            id = "shengshan_swamp_clone",
            name = "&5息壤分身",
            type = EntityType.SLIME,
            health = 300.0,
            damage = 12.0,
            armor = 50.0,
            speed = 0.22,
            maxNearby = 16,
            exp = 0,
            drops = emptyList(),
            slimeSize = 3
        ),
        MobDefinition(
            id = "shengshan_soul",
            name = "&8无主亡魂",
            type = EntityType.ZOMBIE,
            health = 120.0,
            damage = 20.0,
            armor = 12.0,
            speed = 0.18,
            maxNearby = 16,
            exp = 0,
            drops = emptyList(),
            helmet = Material.CHAINMAIL_HELMET
        ),
        MobDefinition(
            id = "shengshan_earth_projection",
            name = "&8垚的出窍魂体",
            type = EntityType.WITHER_SKELETON,
            health = 600.0,
            damage = 0.0,
            armor = 25.0,
            speed = 0.3,
            maxNearby = 16,
            exp = 0,
            drops = emptyList(),
            scoreboardTags = setOf("shengshan_earth_projection"),
            mainHand = Material.GOLDEN_SWORD
        )
    )
}
