package com.hjh_database.dungeon.zhenyao

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.TileState
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID

/** 写前日志。只接收交接包的精确楼板掩码，未恢复成功的条目绝不删除。 */
internal class TowerTerrainJournal(private val plugin: Hjh_database) {
    data class Cell(val floor: Int, val x: Int, val y: Int, val z: Int)
    private val file = File(plugin.dataFolder, "dungeon/zhenyao-terrain-recovery.tsv")
    private var worldId: UUID? = null
    private val saved = linkedMapOf<Cell, String>()
    val mask = requireNotNull(plugin.getResource("dungeon/zhenyao/floor_blocks_world.csv")).bufferedReader().useLines { lines ->
        lines.drop(1).filter(String::isNotBlank).map { row ->
            val c = row.split(','); Cell(c[0].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt())
        }.toList()
    }.also { require(it.size == 12429 && it.toSet().size == 12429) }
    init {
        if (file.exists()) {
            val lines = file.readLines()
            worldId = UUID.fromString(lines.first())
            lines.drop(1).filter(String::isNotBlank).forEach { line ->
                val c = line.split('\t'); require(c.size == 5)
                saved[Cell(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt())] =
                    String(Base64.getDecoder().decode(c[4]), Charsets.UTF_8)
            }
            require(mask.toHashSet().containsAll(saved.keys)) { "镇妖塔恢复日志含掩码外坐标" }
        }
    }
    fun prepare(world: World) {
        check(saved.isEmpty()) { "镇妖塔仍有未恢复楼板" }
        val snapshot = linkedMapOf<Cell, String>()
        mask.forEach { cell ->
            val block = world.getBlockAt(cell.x, cell.y, cell.z)
            check(block.state !is TileState) { "楼板 ${cell.x},${cell.y},${cell.z} 含方块实体，请先移出拆除范围" }
            snapshot[cell] = block.blockData.asString
        }
        worldId = world.uid
        saved.putAll(snapshot)
        persist()
    }
    fun removeFloor(world: World, floor: Int) {
        require(floor in TowerFinalRules.DEMOLITION_FLOORS) { "不得拆除镇妖塔最底层楼板" }
        check(world.uid == worldId && saved.isNotEmpty())
        val cells = mask.filter { it.floor == floor }
        // 整批先校验后修改，不能用原始石砖材质覆盖新增萤石或不明改动。
        check(cells.all { world.getBlockAt(it.x, it.y, it.z).blockData.asString == saved[it] }) { "拆除前发现第${floor}层楼板被改动" }
        cells.forEach { world.getBlockAt(it.x, it.y, it.z).setType(Material.AIR, false) }
    }
    fun restore(): Boolean {
        if (saved.isEmpty()) return true
        val world = worldId?.let(Bukkit::getWorld) ?: return false
        return try {
            restoreTowerSnapshot(saved, { cell, data ->
                try {
                    val block = world.getBlockAt(cell.x, cell.y, cell.z)
                    block.setBlockData(Bukkit.createBlockData(data), false)
                    block.blockData.asString == data
                } catch (error: Exception) {
                    plugin.logger.warning("镇妖塔楼板恢复失败 ${cell.x},${cell.y},${cell.z}: ${error.message}")
                    false
                }
            }, { world.save() }, { persist(emptyMap()) })
        } catch (error: Exception) {
            plugin.logger.warning("镇妖塔恢复提交失败，保留完整快照等待重试: ${error.message}")
            false
        }
    }
    private fun persist(records: Map<Cell, String> = saved) {
        file.parentFile.mkdirs()
        val text = buildString {
            appendLine(worldId.toString())
            records.forEach { (c, data) ->
                appendLine("${c.floor}\t${c.x}\t${c.y}\t${c.z}\t${Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))}")
            }
        }
        val temp = file.toPath().resolveSibling(file.name + ".tmp")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
