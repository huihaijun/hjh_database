package com.hjh_database.dungeon.huomo

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

internal data class HuomoCell(val x: Int, val y: Int, val z: Int) {
    fun location(world: World, above: Double = 0.0) = Location(world, x + .5, y + above, z + .5)
}

internal fun atomicHuomoWrite(file: File, text: String) {
    file.parentFile.mkdirs()
    val temp = file.toPath().resolveSibling(file.name + ".tmp")
    FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
        val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
        while (bytes.hasRemaining()) it.write(bytes)
        it.force(true)
    }
    try { Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}

/** 建筑掩码来自 huomo.schem；血管坐标逐项取自用户原稿。仅修改白名单方块。 */
internal class HuomoArena(private val plugin: Hjh_database) {
    private fun rows(name: String) = requireNotNull(plugin.getResource("dungeon/huomo/$name.csv"))
        .bufferedReader().use { it.readLines().drop(1).filter(String::isNotBlank) }
    val shelters = rows("shelters").groupBy { it.substringBefore(',').toInt() }.toSortedMap().values.map { group ->
        group.map { row -> val c = row.split(',', limit = 5); HuomoCell(c[1].toInt(), c[2].toInt(), c[3].toInt()) }
            .sortedWith(compareBy<HuomoCell> { it.y }.thenBy { it.x }.thenBy { it.z })
    }
    val veins = rows("veins").groupBy { it.substringBefore(',').toInt() }.toSortedMap().values.map { group ->
        group.sortedBy { it.split(',')[1].toInt() }.map { row ->
            val c = row.split(','); HuomoCell(c[2].toInt(), c[3].toInt(), c[4].toInt())
        }
    }
    val floorCells = rows("floor").map { val c = it.split(','); HuomoCell(c[0].toInt(), 14, c[1].toInt()) }
    val spawnCells = listOf(1099 to 904, 1092 to 904, 1079 to 904, 1079 to 897,
        1079 to 884, 1086 to 884, 1099 to 884, 1099 to 891).map { HuomoCell(it.first, 14, it.second) }
    val coreCells = listOf(HuomoCell(1095, 20, 914), HuomoCell(1089, 17, 912),
        HuomoCell(1083, 20, 914), HuomoCell(1089, 25, 914))
    val commands = setOf(HuomoCell(1090, 14, 913), HuomoCell(1085, 19, 914),
        HuomoCell(1097, 19, 914), HuomoCell(1091, 24, 914))
    val mask = (shelters.flatten() + veins.flatten()).toSet()
    private val file = File(plugin.dataFolder, "dungeon/huomo-terrain-recovery.tsv")
    private val saved = linkedMapOf<HuomoCell, String>()
    private var worldId: UUID? = null
    init {
        require(shelters.size == 2 && shelters.all { it.isNotEmpty() })
        require(veins.map { it.size } == listOf(16, 15, 17, 18, 16, 19, 20))
        if (file.exists()) {
            val lines = file.readLines()
            worldId = UUID.fromString(lines.first())
            lines.drop(1).filter(String::isNotBlank).forEach { line ->
                val c = line.split('\t')
                saved[HuomoCell(c[0].toInt(), c[1].toInt(), c[2].toInt())] =
                    String(Base64.getDecoder().decode(c[3]), Charsets.UTF_8)
            }
            require(mask.containsAll(saved.keys)) { "火魔恢复文件含白名单外坐标" }
        }
    }
    fun inside(location: Location) = location.x in 1054.0..1123.0 && location.z in 866.0..927.0 && location.y in 13.0..57.0
    fun safe(world: World, location: Location, width: Double = .6, height: Double = 1.8): Boolean {
        val half = width / 2
        for (x in floor(location.x - half).toInt()..floor(location.x + half - .001).toInt()) {
            for (z in floor(location.z - half).toInt()..floor(location.z + half - .001).toInt()) {
                val support = world.getBlockAt(x, floor(location.y - .01).toInt(), z)
                if (!support.type.isSolid || support.isLiquid || support.boundingBox.maxY < location.y - .05) return false
                for (y in floor(location.y).toInt()..floor(location.y + height - .001).toInt()) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.isPassable || block.isLiquid || block.type == Material.FIRE) return false
                }
            }
        }
        return true
    }
    fun prepare(world: World) {
        check(saved.isEmpty()) { "火魔场地尚未恢复" }
        val snapshot = mask.associateWith { c ->
            val block = world.getBlockAt(c.x, c.y, c.z)
            check(block.state !is TileState) { "火魔可变地形含方块实体：$c" }
            block.blockData.asString
        }
        worldId = world.uid
        saved.putAll(snapshot)
        persist(saved) // 所有修改之前先持久化。
    }
    private fun persist(records: Map<HuomoCell, String>) = atomicHuomoWrite(file, buildString {
        appendLine(worldId)
        records.forEach { (c, data) ->
            appendLine("${c.x}\t${c.y}\t${c.z}\t${Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))}")
        }
    })
    fun set(world: World, c: HuomoCell, material: Material) {
        check(world.uid == worldId && c in saved)
        world.getBlockAt(c.x, c.y, c.z).setType(material, false)
    }
    fun restoreVeins(world: World) {
        veins.indices.forEach { restoreVein(world, it) }
    }
    fun restoreVein(world: World, index: Int) {
        veins[index].forEach { c -> saved[c]?.let { world.getBlockAt(c.x, c.y, c.z).setBlockData(Bukkit.createBlockData(it), false) } }
    }
    fun damageShelter(world: World, index: Int, used: Int) {
        val cells = shelters[index]
        cells.take(ceil(cells.size * used.coerceIn(0, 3) / 3.0).toInt()).forEach { set(world, it, Material.RED_MUSHROOM_BLOCK) }
    }
    /** 必须位于残骸实际顶盖下，站在残骸顶上或旁边不会获得保护。 */
    fun shelter(player: Player): Int? {
        val p = player.location
        return shelters.indices.firstOrNull { i -> shelters[i].any {
            it.x == p.blockX && it.z == p.blockZ && it.y + 1.0 > player.boundingBox.maxY && it.y > p.y
        } }
    }
    fun restore(): Boolean {
        if (saved.isEmpty()) return true
        val world = worldId?.let(Bukkit::getWorld) ?: return false
        return try {
            saved.forEach { (c, data) ->
                val b = world.getBlockAt(c.x, c.y, c.z)
                b.setBlockData(Bukkit.createBlockData(data), false)
                check(b.blockData.asString == data)
            }
            world.save() // 先落世界数据，再将日志标记为已恢复。
            persist(emptyMap())
            saved.clear()
            true
        } catch (error: Exception) {
            plugin.logger.warning("火魔建筑恢复失败，保留日志与入口锁：${error.message}")
            false
        }
    }
}
