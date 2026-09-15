package com.hjh_database.dungeon.zhenyao

import com.hjh_database.Hjh_database
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import kotlin.math.floor

/** 候选点来自建筑交接包；静态坐标只作候选，实际方块始终在游戏线程验证。 */
internal class TowerArena(plugin: Hjh_database) {
    private val candidates: Map<Int, List<Triple<Double, Double, Double>>> =
        requireNotNull(plugin.getResource("dungeon/zhenyao/spawn_candidates_world.csv")) {
            "缺少镇妖塔建筑刷怪坐标资源"
        }.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val c = line.split(',')
                c[0].toInt() to Triple(c[4].toDouble(), c[5].toDouble(), c[6].toDouble())
            }.toList().groupBy({ it.first }, { it.second })
        }.also { data ->
            require(data.values.sumOf { it.size } == 10749)
            TowerFloors.all.forEach { level ->
                require(data[level.number]?.all { it.second == level.feetY } == true)
            }
        }

    fun candidates(world: World, floor: Int) = candidates.getValue(floor).shuffled().map {
        Location(world, it.first, it.second, it.third)
    }

    fun markers(world: World, feetY: Double) = listOf(
        Location(world, -1001.5, feetY, 3015.5), Location(world, -1010.5, feetY, 3006.5),
        Location(world, -1001.5, feetY, 2997.5), Location(world, -992.5, feetY, 3006.5)
    )

    fun inside(location: Location, world: World) = location.world == world &&
        location.x in -1034.0..-970.0 && location.z in 2974.0..3038.0 && location.y in 4.0..134.0

    fun onFloor(location: Location, world: World, feetY: Double) = inside(location, world) &&
        location.y >= feetY - .5 && location.y < feetY + 14

    fun box(location: Location, width: Double, height: Double): BoundingBox = BoundingBox(
        location.x - width / 2, location.y + .01, location.z - width / 2,
        location.x + width / 2, location.y + height, location.z + width / 2
    )

    fun size(type: String): Pair<Double, Double> = when (type) {
        "spider" -> 1.4 to .9
        "magma_cube" -> 1.2 to 1.2 // 固定 size=2，保守预检；生成后再核对实际碰撞箱。
        "wither_skeleton" -> .7 to 2.5
        else -> .7 to 2.0
    }

    fun safe(location: Location, width: Double, height: Double, avoidEntities: Boolean = false): Boolean {
        val world = location.world ?: return false
        val body = box(location, width, height)
        if (!clear(world, body)) return false
        val supportY = floor(location.y - .01).toInt()
        for (x in floor(body.minX).toInt()..floor(body.maxX - .001).toInt()) {
            for (z in floor(body.minZ).toInt()..floor(body.maxZ - .001).toInt()) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
                val block = world.getBlockAt(x, supportY, z)
                val support = block.boundingBox
                if (!block.type.isSolid || block.isLiquid || support.maxY < location.y - .01 ||
                    support.minX > maxOf(body.minX, x.toDouble()) + .001 ||
                    support.maxX < minOf(body.maxX, x + 1.0) - .001 ||
                    support.minZ > maxOf(body.minZ, z.toDouble()) + .001 ||
                    support.maxZ < minOf(body.maxZ, z + 1.0) - .001) return false
            }
        }
        return !avoidEntities || world.getNearbyEntities(body.clone().expand(.3)).none {
            it is LivingEntity && !it.isDead
        }
    }

    fun clear(world: World, body: BoundingBox): Boolean {
        for (x in floor(body.minX).toInt()..floor(body.maxX - .001).toInt()) {
            for (z in floor(body.minZ).toInt()..floor(body.maxZ - .001).toInt()) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
                for (y in floor(body.minY).toInt()..floor(body.maxY - .001).toInt()) {
                    val block = world.getBlockAt(x, y, z)
                    // 保守采用实际方块包围箱，横梁、灯饰、柱面不会被“两格空气”检查漏掉。
                    if (block.isLiquid || !block.isPassable && block.boundingBox.overlaps(body)) return false
                }
            }
        }
        return true
    }
}
