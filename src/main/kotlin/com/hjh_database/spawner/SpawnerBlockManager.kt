package com.hjh_database.spawner

import com.google.gson.Gson
import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.CreatureSpawner
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

class SpawnerBlockManager(private val plugin: Hjh_database) {

    private val gson = Gson()
    private val keySpawnerData = NamespacedKey(plugin, "hjh_spawner_data")

    init {
        startSpawnerTask()
    }

    private fun startSpawnerTask() {
        object : BukkitRunnable() {
            override fun run() {
                // 遍历所有在线玩家
                for (player in Bukkit.getOnlinePlayers()) {
                    processPlayerSurroundings(player)
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // 每秒检查一次
    }

    private fun processPlayerSurroundings(player: Player) {
        val world = player.world
        val chunk = player.location.chunk

        // 简单起见，只扫描玩家当前所在的 Chunk 和周围一圈
        for (x in -1..1) {
            for (z in -1..1) {
                val currentChunk = world.getChunkAt(chunk.x + x, chunk.z + z)
                if (!currentChunk.isLoaded) continue

                // 遍历 Chunk 内的所有 TileEntity
                for (blockState in currentChunk.tileEntities) {
                    if (blockState is CreatureSpawner) {
                        tryTickSpawner(blockState, player)
                    }
                }
            }
        }
    }

    private fun tryTickSpawner(spawner: CreatureSpawner, player: Player) {
        // 1. 检查是否有我们的自定义数据
        val pdc = spawner.persistentDataContainer
        if (!pdc.has(keySpawnerData, PersistentDataType.STRING)) return

        // 2. 反序列化数据
        val json = pdc.get(keySpawnerData, PersistentDataType.STRING)
        val data = try {
            gson.fromJson(json, SpawnerData::class.java)
        } catch (e: Exception) {
            return
        }
        // 核心修复：检查开关状态
        // 如果未启用，直接不执行后续刷怪逻辑
        if (!data.isEnabled) {
            return
        }

        // 3. 检查玩家距离
        if (player.location.distanceSquared(spawner.location) > (data.checkRange * data.checkRange)) {
            return
        }

        // 4. 处理冷却
        val currentTime = System.currentTimeMillis()
        val nextSpawnKey = NamespacedKey(plugin, "next_spawn_time")
        val nextSpawnTime = pdc.get(nextSpawnKey, PersistentDataType.LONG) ?: 0L

        if (currentTime < nextSpawnTime) return

        // 5. 确定刷怪坐标
        val spawnLoc = parseSpawnLocation(spawner.location, data.targetLocationStr) ?: spawner.location.add(0.5, 1.0, 0.5)

        // 6. 检查附近怪物数量
        val nearby = spawnLoc.world?.getNearbyEntities(spawnLoc, 8.0, 8.0, 8.0) ?: return
        val count = nearby.count {
            it.scoreboardTags.contains("hjh_mob_id:${data.internalId}")
        }

        if (count >= data.maxNearby) {
            pdc.set(nextSpawnKey, PersistentDataType.LONG, currentTime + 5000L)
            spawner.update()
            return
        }

        // 7. 生成怪物！
        MobFactory.spawnMob(spawnLoc, data)

        // 8. 设置下一次刷新时间
        val cooldownMs = data.cooldown * 50L // Tick -> Milliseconds
        pdc.set(nextSpawnKey, PersistentDataType.LONG, currentTime + cooldownMs)
        spawner.update()
    }

    /**
     * 解析坐标字符串
     */
    private fun parseSpawnLocation(origin: Location, locStr: String?): Location? {
        if (locStr == null) return null
        try {
            val parts = locStr.split(",")
            if (parts.size != 4) return null

            // 绝对坐标模式: "worldName,x,y,z"
            val world = Bukkit.getWorld(parts[0]) ?: return null
            val x = parts[1].toDouble()
            val y = parts[2].toDouble()
            val z = parts[3].toDouble()
            return Location(world, x, y, z)
        } catch (e: Exception) {
            return null
        }
    }

    // === 提供给外部的工具方法 ===

    fun setSpawnerData(spawner: CreatureSpawner, data: SpawnerData) {
        val json = gson.toJson(data)
        spawner.persistentDataContainer.set(keySpawnerData, PersistentDataType.STRING, json)

        // 禁用原版刷怪逻辑
        spawner.spawnCount = 0
        spawner.maxNearbyEntities = 0
        spawner.update()
    }

    fun getSpawnerData(spawner: CreatureSpawner): SpawnerData? {
        val pdc = spawner.persistentDataContainer
        if (!pdc.has(keySpawnerData, PersistentDataType.STRING)) return null
        return try {
            gson.fromJson(pdc.get(keySpawnerData, PersistentDataType.STRING), SpawnerData::class.java)
        } catch (e: Exception) { null }
    }
}