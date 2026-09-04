package com.hjh_database.dungeon.shengshan

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.LinkedHashMap

/**
 * 精确记录圣山副本改动前的方块状态。恢复文件在改动发生前落盘，崩服后也能回滚。
 */
internal class ShengShanTerrainJournal(private val plugin: Hjh_database) {
    private data class Key(val world: String, val x: Int, val y: Int, val z: Int)
    private data class Snapshot(val group: String, val blockData: String)

    private val snapshots = LinkedHashMap<Key, Snapshot>()
    private val recoveryFile = File(plugin.dataFolder, "dungeon/shengshan-block-recovery.txt")

    fun recoverOnStartup() {
        if (!recoveryFile.exists()) return
        loadFile()
        if (snapshots.isEmpty()) {
            recoveryFile.delete()
            return
        }
        plugin.logger.warning("发现圣山副本未完成的地形恢复记录，共 ${snapshots.size} 个方块，正在恢复。")
        restoreEntries(snapshots.keys.toList())
        snapshots.clear()
        recoveryFile.delete()
        plugin.logger.info("圣山副本遗留地形已经恢复。")
    }

    /** 先记录并持久化，再由调用方修改这些方块。 */
    fun prepare(group: String, blocks: Collection<Block>) {
        var changed = false
        blocks.forEach { block ->
            val key = Key(block.world.name, block.x, block.y, block.z)
            if (key !in snapshots) {
                snapshots[key] = Snapshot(group, block.blockData.asString)
                changed = true
            }
        }
        if (changed) persist()
    }

    fun restoreGroup(group: String) {
        val keys = snapshots.filterValues { it.group == group }.keys.toList()
        restoreEntries(keys)
        keys.forEach(snapshots::remove)
        persist()
    }

    fun restoreBlocks(group: String, blocks: Collection<Block>) {
        val keys = blocks.map { Key(it.world.name, it.x, it.y, it.z) }
            .filter { snapshots[it]?.group == group }
        restoreEntries(keys)
        keys.forEach(snapshots::remove)
        persist()
    }

    fun restoreAll() {
        restoreEntries(snapshots.keys.toList())
        snapshots.clear()
        persist()
    }

    private fun restoreEntries(keys: List<Key>) {
        keys.forEach { key ->
            val snapshot = snapshots[key] ?: return@forEach
            val world = Bukkit.getWorld(key.world) ?: return@forEach
            runCatching {
                world.getBlockAt(key.x, key.y, key.z).blockData = Bukkit.createBlockData(snapshot.blockData)
            }.onFailure {
                plugin.logger.warning("恢复圣山方块 ${key.world}/${key.x},${key.y},${key.z} 失败: ${it.message}")
            }
        }
    }

    private fun persist() {
        recoveryFile.parentFile.mkdirs()
        if (snapshots.isEmpty()) {
            recoveryFile.delete()
            return
        }
        val temp = File(recoveryFile.parentFile, recoveryFile.name + ".tmp")
        temp.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            snapshots.forEach { (key, value) ->
                val encoded = Base64.getEncoder().encodeToString(value.blockData.toByteArray(StandardCharsets.UTF_8))
                writer.append(value.group).append('\t')
                    .append(key.world).append('\t')
                    .append(key.x.toString()).append('\t')
                    .append(key.y.toString()).append('\t')
                    .append(key.z.toString()).append('\t')
                    .append(encoded).appendLine()
            }
        }
        runCatching {
            Files.move(
                temp.toPath(), recoveryFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            Files.move(temp.toPath(), recoveryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private fun loadFile() {
        snapshots.clear()
        recoveryFile.useLines(StandardCharsets.UTF_8) { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 6) return@forEach
                val x = parts[2].toIntOrNull() ?: return@forEach
                val y = parts[3].toIntOrNull() ?: return@forEach
                val z = parts[4].toIntOrNull() ?: return@forEach
                val blockData = runCatching {
                    String(Base64.getDecoder().decode(parts[5]), StandardCharsets.UTF_8)
                }.getOrNull() ?: return@forEach
                snapshots[Key(parts[1], x, y, z)] = Snapshot(parts[0], blockData)
            }
        }
    }
}
