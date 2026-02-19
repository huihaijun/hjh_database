package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.CreatureSpawner
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

class SpawnerBlockManager(private val plugin: Hjh_database) {

    private val keyMobId = NamespacedKey(plugin, "hjh_spawner_mobid_block")
    private val keyTarget = NamespacedKey(plugin, "hjh_spawner_target_block")
    // 下次生成的冷却时间标记
    private val keyNextSpawn = NamespacedKey(plugin, "hjh_spawner_next_spawn")

    init {
        // 记得在 onEnable 调用 MobRegistry.init()
        MobRegistry.init()
        startSpawnerTask()
    }

    // 写入数据工具方法 (给 Listener 用)
    fun writeToSpawner(spawner: CreatureSpawner, mobId: String, targetStr: String?) {
        spawner.persistentDataContainer.set(keyMobId, PersistentDataType.STRING, mobId)
        if (targetStr != null) {
            spawner.persistentDataContainer.set(keyTarget, PersistentDataType.STRING, targetStr)
        } else {
            spawner.persistentDataContainer.remove(keyTarget)
        }
        // 禁用原版生成
        spawner.spawnCount = 0
        spawner.update()
    }

    private fun startSpawnerTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in Bukkit.getOnlinePlayers()) {
                    processPlayerSurroundings(player)
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun processPlayerSurroundings(player: Player) {
        val chunk = player.location.chunk
        // 扫描周围区块
        for (x in -1..1) {
            for (z in -1..1) {
                val currentChunk = player.world.getChunkAt(chunk.x + x, chunk.z + z)
                if (!currentChunk.isLoaded) continue

                for (tile in currentChunk.tileEntities) {
                    if (tile is CreatureSpawner) {
                        attemptSpawn(tile)
                    }
                }
            }
        }
    }

    private fun attemptSpawn(spawner: CreatureSpawner) {
        val pdc = spawner.persistentDataContainer
        val mobId = pdc.get(keyMobId, PersistentDataType.STRING) ?: return
        val def = MobRegistry.get(mobId) ?: return // 如果 ID 不存在则跳过

        // 1. 检查冷却
        val nextSpawn = pdc.get(keyNextSpawn, PersistentDataType.LONG) ?: 0L
        if (System.currentTimeMillis() < nextSpawn) return

        // 2. 确定生成位置
        val targetStr = pdc.get(keyTarget, PersistentDataType.STRING)
        val spawnLoc = if (targetStr != null) {
            parseLocation(targetStr) ?: spawner.location.add(0.5, 1.0, 0.5) // 解析失败则回退
        } else {
            // 默认模式：在刷怪笼周围随机找一点
            spawner.location.add(0.5, 1.0, 0.5).add(
                (Math.random() - 0.5) * 8,
                (Math.random() - 0.5) * 2,
                (Math.random() - 0.5) * 8
            )
        }

        if (spawnLoc.world == null) return

        // 3. 检查数量上限 (def.maxNearby)
        // 简单检查生成点周围 20 格内的同类怪物
        val nearby = spawnLoc.world!!.getNearbyEntities(spawnLoc, 20.0, 20.0, 20.0)
            .count { it.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) == mobId }

        if (nearby >= def.maxNearby) return // 达到上限，不生成，也不重置冷却

        // 4. 生成怪物 (传入 plugin 以便 Factory 访问 ResourceManager 等)
        MobFactory.spawnMob(plugin, spawnLoc, mobId)
        // 5. 设置冷却 (例如 20秒)
        pdc.set(keyNextSpawn, PersistentDataType.LONG, System.currentTimeMillis() + 20000L)
        spawner.update()
    }

    private fun parseLocation(str: String): Location? {
        val parts = str.split(",")
        if (parts.size != 4) return null
        val world = Bukkit.getWorld(parts[0]) ?: return null
        return try {
            Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
        } catch (e: Exception) { null }
    }
}