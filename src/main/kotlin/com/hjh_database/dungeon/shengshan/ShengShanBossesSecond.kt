package com.hjh_database.dungeon.shengshan

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Zombie
import kotlin.math.sqrt

/** 第二组卦象仍需复用的出场演出与获批保留的巽风祭坛。 */
internal abstract class FireBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.FIRE, boss) {
    private val terrainGroup = "shengshan_fire_floor"

    override fun onAppearance() {
        (boss as? Zombie)?.setShouldBurnInDay(false)
        terrainGroups += terrainGroup
        playEffect(ShengShanEffect.FIRE_CRACK, Trigram.FIRE.spawn(boss.world),
            options = ShengShanEffectOptions(radius = 26.0, height = .3, durationTicks = 35,
                intervalTicks = 4, key = "fire_appearance_cracks"))
        sound(boss.location, Sound.BLOCK_BASALT_BREAK, 1.0f, .55f)
        later(35L) {
            transformFloor()
            playEffect(ShengShanEffect.FIRE_BURST, boss.location,
                options = ShengShanEffectOptions(radius = 5.0, height = 7.0))
            sound(boss.location, Sound.ITEM_FIRECHARGE_USE, 1.0f, .7f)
        }
        later(50L) {
            reveal()
            message("§c§l离火之相·焱§f踏着地底涌出的熔岩降临！")
        }
    }

    private fun transformFloor() {
        val changes = LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData>()
        for (x in 3056..3136) {
            for (z in -1874..-1801) {
                val lower = boss.world.getBlockAt(x, 127, z)
                if (lower.type == Material.OBSIDIAN) changes[lower] = Material.AIR.createBlockData()
                val upper = boss.world.getBlockAt(x, 128, z)
                when (upper.type) {
                    Material.PRISMARINE -> changes[upper] = Material.LAVA.createBlockData()
                    Material.PRISMARINE_BRICKS -> changes[upper] = Material.OBSIDIAN.createBlockData()
                    else -> Unit
                }
            }
        }
        for (x in 3130..3134) {
            for (z in -1871..-1868) {
                val block = boss.world.getBlockAt(x, 127, z)
                if (block.type == Material.AIR || block.type == Material.OBSIDIAN) {
                    changes[block] = Material.OBSIDIAN.createBlockData()
                }
            }
        }
        manager.applyBlocks(terrainGroup, changes)
    }
}

internal abstract class WindBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.WIND, boss) {
    private val altarCenter = shengShanMechanicCenter(boss.world)
    private val altarGroup = "shengshan_wind_altar"

    override fun onAppearance() {
        (boss as? Zombie)?.setShouldBurnInDay(false)
        terrainGroups += altarGroup
        playEffect(ShengShanEffect.WIND_FIELD, altarCenter,
            options = ShengShanEffectOptions(radius = 11.0, height = 4.0, durationTicks = 90,
                intervalTicks = 4, key = "wind_appearance"))
        sound(altarCenter, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, .75f)
        buildAnimated(altarGroup, windAltarChanges(), batchSize = 12, periodTicks = 3L)
        later(75L) {
            boss.teleport(Trigram.WIND.spawn(boss.world))
            playEffect(ShengShanEffect.WIND_FIELD, boss.location,
                options = ShengShanEffectOptions(radius = 4.0, height = 4.0))
            reveal()
            message("§f§l巽风之相·雾§f自风眼祭坛中现身！")
        }
    }

    private fun windAltarChanges(): Map<org.bukkit.block.Block, org.bukkit.block.data.BlockData> {
        val changes = LinkedHashMap<org.bukkit.block.Block, org.bukkit.block.data.BlockData>()
        for (dx in -10..10) for (dz in -10..10) {
            val distance = sqrt((dx * dx + dz * dz).toDouble())
            if (distance in 8.5..10.5) {
                changes[boss.world.getBlockAt(altarCenter.blockX + dx, altarCenter.blockY, altarCenter.blockZ + dz)] =
                    Material.SMOOTH_QUARTZ.createBlockData()
            }
        }
        listOf(-7 to -7, -7 to 7, 7 to -7, 7 to 7).forEach { (dx, dz) ->
            for (dy in 0..4) {
                changes[boss.world.getBlockAt(altarCenter.blockX + dx, altarCenter.blockY + dy, altarCenter.blockZ + dz)] =
                    (if (dy == 4) Material.SEA_LANTERN else Material.CUT_SANDSTONE).createBlockData()
            }
        }
        return changes
    }
}

internal abstract class SwampBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.SWAMP, boss) {
    override fun onAppearance() {
        val surface = Trigram.SWAMP.spawn(boss.world)
        boss.teleport(surface.clone().subtract(0.0, 2.2, 0.0))
        playEffect(ShengShanEffect.SWAMP_BUBBLE, surface,
            options = ShengShanEffectOptions(radius = 5.0, height = 2.5, durationTicks = 65,
                intervalTicks = 4, key = "swamp_appearance"))
        sound(surface, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.0f, .7f)
        var rise = 0
        lateinit var riseTask: org.bukkit.scheduler.BukkitTask
        riseTask = every(0L, 3L) {
            rise++
            boss.teleport(boss.location.clone().add(0.0, .22, 0.0))
            if (rise >= 10) riseTask.cancel()
        }
        later(35L) {
            boss.teleport(surface)
            reveal()
            message("§5§l兑泽之相·恶§f从黏稠的毒泽中翻涌而出！")
        }
    }
}

internal abstract class EarthBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    boss: LivingEntity
) : ShengShanBossController(manager, session, Trigram.EARTH, boss) {
    override fun onAppearance() {
        val surface = Trigram.EARTH.spawn(boss.world)
        boss.teleport(surface.clone().subtract(0.0, 2.0, 0.0))
        playEffect(ShengShanEffect.SOUL_FIELD, surface,
            options = ShengShanEffectOptions(radius = 6.0, height = 4.0, durationTicks = 75,
                intervalTicks = 4, key = "earth_appearance"))
        sound(surface, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, .65f)
        var rise = 0
        lateinit var riseTask: org.bukkit.scheduler.BukkitTask
        riseTask = every(0L, 3L) {
            rise++
            boss.teleport(boss.location.clone().add(0.0, .2, 0.0))
            if (rise >= 10) riseTask.cancel()
        }
        later(40L) {
            boss.teleport(surface)
            sound(surface, Sound.ENTITY_WITHER_AMBIENT, .7f, .65f)
            reveal()
            message("§8§l坤地之相·垚§f携着亡魂从阴土中爬出！")
        }
    }
}
