package com.hjh_database.spawner.dungeon.impl

import com.hjh_database.dungeon.zhenyao.TowerFloors
import com.hjh_database.spawner.MobDefinition
import org.bukkit.Material
import org.bukkit.entity.EntityType

/** 仅声明数值与外观，生成和战斗属性完全复用 MobFactory / CombatListener。 */
internal object Zhenyao {
    const val MOB_TAG = "zhenyao_tower_mob"
    val definitions = TowerFloors.all.flatMap { floor ->
        floor.types.flatMap { type ->
            listOf(true, false).map { strong ->
                val name = when (type) {
                    "zombie" -> "镇塔妖尸"
                    "spider" -> "镇塔妖蛛"
                    "skeleton" -> "镇塔骨弓"
                    "magma_cube" -> "镇塔熔妖"
                    "blaze" -> "镇塔炎灵"
                    "husk" -> "镇塔枯尸"
                    else -> "镇塔凋骨"
                }
                MobDefinition(
                    id = floor.mobId(type, strong),
                    name = "${if (strong) "&c精锐" else "&7"}$name·${floor.number}层",
                    type = EntityType.valueOf(type.uppercase()),
                    health = floor.health * if (strong) 1.0 else .65,
                    damage = floor.damage * if (strong) 1.0 else .70,
                    armor = floor.armor * if (strong) 1.0 else .70,
                    speed = floor.speed * if (strong) 1.0 else .90,
                    maxNearby = 30, exp = 0, drops = emptyList(), affixes = emptyList(),
                    helmet = if (type in setOf("zombie", "skeleton", "husk", "wither_skeleton")) Material.CHAINMAIL_HELMET else null,
                    mainHand = when (type) {
                        "skeleton" -> Material.BOW
                        "wither_skeleton" -> Material.STONE_SWORD
                        else -> null
                    },
                    scoreboardTags = setOf(MOB_TAG), slimeSize = if (type == "magma_cube") 2 else null
                )
            }
        }
    } + listOf(
        MobDefinition("zhenyao_chiyou", "&4&l妖族大长老-蚩尤", EntityType.ZOMBIE,
            10000.0, 50.0, 100.0, .4, 1, emptyList(), exp = 0,
            chestplate = Material.IRON_CHESTPLATE, leggings = Material.IRON_LEGGINGS, boots = Material.IRON_BOOTS,
            scoreboardTags = setOf(MOB_TAG, "instance_boss"), knockbackResistance = 1.0,
            headTexture = "eyJ0aW1lc3RhbXAiOjE0MTEyOTc5MTE2MjQsInByb2ZpbGVJZCI6IjViYjE5ZjBjNDBjNjQzMmZhMGY0NTQyZDAzY2YzZGNjIiwicHJvZmlsZU5hbWUiOiJBdWRpYWNlMDgwOSIsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jNTFlNjc0MzY1YTUyOGRmNTE4YThlZDFkODdmN2NjZWJjMzA1ODNiZjZjOTlmOTI4N2ZhYjA0ZTI5NDViMWEifX19"),
        MobDefinition("zhenyao_black", "&8&l黑无常", EntityType.WITHER_SKELETON,
            400.0, 35.0, 40.0, .28, 1, emptyList(), exp = 0, mainHand = Material.STONE_SWORD,
            scoreboardTags = setOf(MOB_TAG), knockbackResistance = 1.0),
        MobDefinition("zhenyao_white", "&f&l白无常", EntityType.SKELETON,
            400.0, 35.0, 40.0, .28, 1, emptyList(), exp = 0, mainHand = Material.BOW,
            helmet = Material.IRON_HELMET, scoreboardTags = setOf(MOB_TAG), knockbackResistance = 1.0),
        MobDefinition("zhenyao_eye", "&5妖力阵眼", EntityType.BLAZE,
            100.0, 0.0, 0.0, 0.0, 4, emptyList(), exp = 0,
            scoreboardTags = setOf(MOB_TAG), knockbackResistance = 1.0)
    )
}
