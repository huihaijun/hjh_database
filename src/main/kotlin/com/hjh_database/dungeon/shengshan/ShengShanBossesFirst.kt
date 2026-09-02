package com.hjh_database.dungeon.shengshan

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.abs

/**
 * 第一组卦象仍需复用的出场演出与场地建筑。
 * 实际战斗机制全部由 Reworked*Controller 实现。
 */
internal abstract class ThunderBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.THUNDER, boss) {
    private val eyeA = Location(boss.world, 3148.5, 133.0, -1828.0)
    private val eyeB = Location(boss.world, 3148.5, 133.0, -1851.0)
    private val eyeGroup = "shengshan_thunder_eyes"

    override fun showGenericNormalParticle(index: Int): Boolean = index != 0

    override fun onAppearance() {
        terrainGroups += eyeGroup
        boss.teleport(Trigram.THUNDER.spawn(boss.world))
        playEffect(ShengShanEffect.THUNDER_CHARGE, eyeA,
            options = ShengShanEffectOptions(radius = 3.0, height = .35, durationTicks = 14,
                intervalTicks = 2, key = "thunder_eye_a_charge"))
        later(14L) {
            boss.world.strikeLightningEffect(eyeA.clone().add(0.0, 4.0, 0.0))
            playEffect(ShengShanEffect.LIGHTNING_COLUMN, eyeA.clone().add(0.0, 28.0, 0.0), eyeA)
            sound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.25f, .8f, eyeA)
            buildAnimated(eyeGroup, thunderEyeChanges(eyeA), batchSize = 6, periodTicks = 2L)
        }
        later(48L) {
            playEffect(ShengShanEffect.THUNDER_CHARGE, eyeB,
                options = ShengShanEffectOptions(radius = 3.0, height = .35, durationTicks = 14,
                    intervalTicks = 2, key = "thunder_eye_b_charge"))
        }
        later(62L) {
            boss.world.strikeLightningEffect(eyeB.clone().add(0.0, 4.0, 0.0))
            playEffect(ShengShanEffect.LIGHTNING_COLUMN, eyeB.clone().add(0.0, 28.0, 0.0), eyeB)
            sound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.25f, .8f, eyeB)
            buildAnimated(eyeGroup, thunderEyeChanges(eyeB), batchSize = 6, periodTicks = 2L)
        }
        later(100L) {
            playEffect(ShengShanEffect.THUNDER_TRAIL, eyeA.clone().add(0.0, 4.0, 0.0),
                eyeB.clone().add(0.0, 4.0, 0.0),
                ShengShanEffectOptions(durationTicks = 40, intervalTicks = 3, key = "thunder_eye_arc"))
        }
        later(120L) {
            boss.world.strikeLightningEffect(boss.location)
            playEffect(ShengShanEffect.LIGHTNING_COLUMN, boss.location.clone().add(0.0, 30.0, 0.0), boss.location)
            sound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, .65f)
            reveal()
            message("§e§l震雷之相·靐§f裹挟雷光降临了！")
        }
    }

    private fun thunderEyeChanges(center: Location): LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData> {
        val changes = LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData>()
        val world = center.world ?: return changes
        for (dx in -2..2) for (dz in -2..2) {
            if (abs(dx) + abs(dz) <= 3) {
                changes[world.getBlockAt(center.blockX + dx, center.blockY + 4, center.blockZ + dz)] =
                    (if (dx == 0 && dz == 0) Material.GLOWSTONE else Material.YELLOW_STAINED_GLASS).createBlockData()
            }
        }
        listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2).forEach { (dx, dz) ->
            for (dy in 0..3) {
                changes[world.getBlockAt(center.blockX + dx, center.blockY + dy, center.blockZ + dz)] =
                    Material.YELLOW_STAINED_GLASS.createBlockData()
            }
        }
        changes[world.getBlockAt(center.blockX, center.blockY, center.blockZ)] =
            Material.LIGHTNING_ROD.createBlockData()
        return changes
    }
}

internal abstract class SkyBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.SKY, boss) {
    override fun onAppearance() {
        boss.setGravity(false)
        boss.setAI(false)
        playEffect(ShengShanEffect.SKY_CLOUD, boss.location,
            options = ShengShanEffectOptions(radius = 5.0, height = 3.0, durationTicks = 60,
                intervalTicks = 3, key = "sky_appearance_cloud"))
        later(60L) {
            playEffect(ShengShanEffect.SKY_LIGHT, boss.location.clone().add(0.0, 22.0, 0.0), boss.location,
                ShengShanEffectOptions(durationTicks = 15, intervalTicks = 2, key = "sky_appearance_light"))
            sound(Sound.ENTITY_PHANTOM_FLAP, 1.0f, .65f)
            sound(Sound.ENTITY_GHAST_AMBIENT, .9f, .62f)
            boss.setAI(true)
            reveal()
            message("§6§l乾天之相·晶§f从云雾中振翅诞生！")
        }
    }

    override fun onTick() {
        if (!boss.isValid || boss.isDead) return
        val location = boss.location
        val hoverY = (manager.groundLocation(boss.world, location.x, location.z).y + 2.2).coerceAtMost(SKY_MAX_Y)
        if (location.y > SKY_MAX_Y || location.y < ARENA_FLOOR_Y || abs(location.y - hoverY) > .6) {
            boss.teleport(location.clone().apply { y = hoverY.coerceIn(ARENA_FLOOR_Y, SKY_MAX_Y) })
            boss.velocity = boss.velocity.clone().apply { y = 0.0 }
        } else if (boss.velocity.y > 0.0) {
            boss.velocity = boss.velocity.clone().apply { y = 0.0 }
        }
    }

    private companion object {
        const val ARENA_FLOOR_Y = 130.0
        const val SKY_MAX_Y = 132.75
    }
}

internal abstract class WaterBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.WATER, boss) {
    private data class WaterEye(val index: Int, val min: Location, val max: Location) {
        val center: Location
            get() = Location(
                min.world,
                (min.x + max.x) / 2.0 + .5,
                (min.y + max.y) / 2.0,
                (min.z + max.z) / 2.0 + .5
            )
        val group: String get() = "shengshan_water_eye_$index"
    }

    private val eyes = listOf(
        eye(0, 3191, 150, -1839, 3191, 151, -1839),
        eye(1, 3166, 168, -1782, 3166, 169, -1782),
        eye(2, 3074, 174, -1870, 3074, 175, -1870),
        eye(3, 3133, 135, -1877, 3134, 135, -1877),
        eye(4, 3064, 175, -1821, 3064, 176, -1821),
        eye(5, 3123, 150, -1826, 3123, 151, -1826),
        eye(6, 3165, 157, -1784, 3165, 158, -1784)
    )

    override fun onAppearance() {
        boss.setGravity(true)
        boss.velocity = Vector()
        eyes.forEachIndexed { index, eye ->
            later(index * 9L) {
                playEffect(ShengShanEffect.WATER_MIST, eye.center,
                    options = ShengShanEffectOptions(radius = 3.0, height = 2.0, durationTicks = 16,
                        intervalTicks = 2, key = "water_eye_open_$index"))
                sound(Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, .55f, .9f + index * .03f, eye.center)
            }
            later(index * 9L + 10L) { openWaterEye(eye) }
        }
        later(72L) {
            eyes.forEachIndexed { index, eye ->
                playEffect(ShengShanEffect.WATER_FLOW, eye.center, boss.location.clone().add(0.0, 1.0, 0.0),
                    ShengShanEffectOptions(durationTicks = 32, intervalTicks = 4,
                        key = "water_gather_$index"))
            }
        }
        later(105L) {
            playEffect(ShengShanEffect.WATER_BURST, boss.location.clone().add(0.0, 1.0, 0.0),
                options = ShengShanEffectOptions(radius = 3.5, height = 3.0))
            sound(Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.1f, .75f)
            reveal()
            message("§9§l坎水之相·淼§f从奔涌的水流中浮现！")
        }
    }

    private fun eye(index: Int, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): WaterEye =
        WaterEye(
            index,
            Location(boss.world, minOf(x1, x2).toDouble(), minOf(y1, y2).toDouble(), minOf(z1, z2).toDouble()),
            Location(boss.world, maxOf(x1, x2).toDouble(), maxOf(y1, y2).toDouble(), maxOf(z1, z2).toDouble())
        )

    private fun openWaterEye(eye: WaterEye) {
        terrainGroups += eye.group
        val changes = LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData>()
        for (x in eye.min.blockX..eye.max.blockX) {
            for (y in eye.min.blockY..eye.max.blockY) {
                for (z in eye.min.blockZ..eye.max.blockZ) {
                    changes[boss.world.getBlockAt(x, y, z)] = Material.WATER.createBlockData()
                }
            }
        }
        manager.applyBlocks(eye.group, changes)
    }
}

internal abstract class MountainBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.MOUNTAIN, boss) {
    private data class AppearanceHill(val index: Int, val center: Location) {
        val group: String get() = "shengshan_mountain_hill_$index"
    }

    private val appearanceHills = listOf(
        3159 to -1856, 3139 to -1853, 3163 to -1842,
        3171 to -1827, 3152 to -1817, 3138 to -1842
    ).mapIndexed { index, (x, z) -> AppearanceHill(index, Location(boss.world, x + .5, 129.0, z + .5)) }

    override fun onAppearance() {
        appearanceHills.forEachIndexed { index, hill ->
            later((index * 8).toLong()) {
                playEffect(ShengShanEffect.MOUNTAIN_DUST, hill.center,
                    options = ShengShanEffectOptions(radius = 5.0, height = 2.0, durationTicks = 70,
                        intervalTicks = 4, key = "mountain_appearance_${hill.index}"))
                sound(hill.center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, .65f)
                terrainGroups += hill.group
                buildAnimated(hill.group, mountainHillChanges(hill), batchSize = 14, periodTicks = 3L,
                    evictionCenter = hill.center, evictionRadius = 5.75, evictionHeight = 6.5)
            }
        }
        later(105L) {
            playEffect(ShengShanEffect.GROUND_RUPTURE, boss.location,
                options = ShengShanEffectOptions(radius = 4.0, height = 2.0))
            sound(boss.location, Sound.ENTITY_RAVAGER_ROAR, 1.0f, .72f)
            reveal()
            message("§6§l艮山之相·芔§f踏碎山石，缓缓站了起来！")
        }
    }

    private fun mountainHillChanges(hill: AppearanceHill): Map<org.bukkit.block.Block, org.bukkit.block.data.BlockData> {
        val changes = LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData>()
        for (dx in -4..4) for (dz in -4..4) {
            val radius = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
            val height = (6.0 - radius * 1.25 + abs(dx * 31 + dz * 17 + hill.index) % 2).toInt().coerceIn(0, 6)
            for (dy in 0 until height) {
                val material = when (abs(dx * 13 + dz * 7 + dy + hill.index) % 3) {
                    0 -> Material.GRAVEL
                    1 -> Material.SANDSTONE
                    else -> Material.COBBLESTONE
                }
                changes[boss.world.getBlockAt(hill.center.blockX + dx, hill.center.blockY + dy, hill.center.blockZ + dz)] =
                    material.createBlockData()
            }
        }
        return changes
    }
}
