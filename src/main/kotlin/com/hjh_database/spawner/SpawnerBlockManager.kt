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
        // 禁用原版生成逻辑
        spawner.spawnCount = 0
        spawner.requiredPlayerRange = 0
        spawner.maxNearbyEntities = 0

        // === 【修改】放置刷怪笼时，根据怪物的配置给一个初始随机冷却 ===
        val def = MobRegistry.get(mobId)
        val minDelay = def?.minSpawnDelay ?: 20
        val maxDelay = (def?.maxSpawnDelay ?: 50).coerceAtLeast(minDelay) // 确保 max >= min
        val delaySeconds = if (minDelay == maxDelay) minDelay else java.util.concurrent.ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)

        spawner.persistentDataContainer.set(keyNextSpawn, PersistentDataType.LONG, System.currentTimeMillis() + delaySeconds * 1000L)
        spawner.update()
    }

    private fun startSpawnerTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in Bukkit.getOnlinePlayers()) {
                    // === 【新增修改】忽略创造模式和旁观模式的玩家，不触发他们周围的刷怪笼 ===
                    if (player.gameMode == org.bukkit.GameMode.CREATIVE || player.gameMode == org.bukkit.GameMode.SPECTATOR) {
                        continue
                    }
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

        // =========================================================================
        // 1. 检查玩家激活距离 (对应原版 NBT: RequiredPlayerRange: 18s)
        // =========================================================================
        val loc = spawner.location
        // 这里的 16.0 控制激活距离。它代表检测以刷怪笼为中心，正负 16 格半径内的玩家。
        val hasPlayerNearby = loc.world!!.getNearbyEntities(loc, 16.0, 16.0, 16.0)
            .any { it is org.bukkit.entity.Player && !it.isDead &&
                    it.gameMode != org.bukkit.GameMode.SPECTATOR &&
                    it.gameMode != org.bukkit.GameMode.CREATIVE }
        // 如果周围没玩家，直接 return。这会让刷怪笼“休眠”卡在当前冷却状态，不会重置时间也不会刷怪
        if (!hasPlayerNearby) return

        // 2. 检查冷却
        val nextSpawn = pdc.get(keyNextSpawn, PersistentDataType.LONG) ?: 0L
        if (System.currentTimeMillis() < nextSpawn) return

        // =========================================================================
        // 3. 确定生成位置
        // =========================================================================
        val targetStr = pdc.get(keyTarget, PersistentDataType.STRING)
        val spawnLoc = if (targetStr != null) {
            parseLocation(targetStr) ?: spawner.location.clone().add(0.5, 1.0, 0.5) // 解析失败则回退
        } else {
            // 【修改点】：移除了在此处计算随机坐标偏移的代码。
            // 因为你在 SpawnerListener 已经规定好了坐标，这里直接返回基础坐标即可。
            // 注意加上 clone() 防止误修改原方块的 Location 对象
            spawner.location.clone().add(0.5, 1.0, 0.5)
        }

        if (spawnLoc.world == null) return

        // =========================================================================
        // 4. 检查数量上限
        // =========================================================================
        // 【修改点】：检测中心重新改为 spawnLoc (怪物生成点)！
        // 【修改点】：检测半径从 4.0 扩大至 20.0 格！适用于同一区域拥有密集刷怪笼的情况。
        val nearby = spawnLoc.world!!.getNearbyEntities(spawnLoc, 20.0, 20.0, 20.0)
            .count { it.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) == mobId }

        if (nearby >= def.maxNearby) {
            val minDelay = def.minSpawnDelay
            val maxDelay = def.maxSpawnDelay.coerceAtLeast(minDelay)
            val delaySeconds = if (minDelay == maxDelay) {
                minDelay
            } else {
                java.util.concurrent.ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)
            }
            spawner.persistentDataContainer.set(keyNextSpawn, PersistentDataType.LONG, System.currentTimeMillis() + delaySeconds * 1000L)
            spawner.update()

            return // 退出本次生成
        }

        // 5. 生成怪物 (传入 plugin 以便 Factory 访问 ResourceManager 等)
        MobFactory.spawnMob(plugin, spawnLoc, mobId)

        // =========================================================================
        // 6. 设置下次冷却时间
        // =========================================================================
        val minDelay = def.minSpawnDelay
        val maxDelay = def.maxSpawnDelay.coerceAtLeast(minDelay) // 确保最大值不小于最小值
        // 计算随机秒数，对应在 minDelay 和 maxDelay 之间 roll 冷却时间
        val delaySeconds = if (minDelay == maxDelay) {
            minDelay
        } else {
            java.util.concurrent.ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)
        }
        // 写入下次生成的时间戳 = 当前时间 + 随机秒数 * 1000毫秒
        spawner.persistentDataContainer.set(keyNextSpawn, PersistentDataType.LONG, System.currentTimeMillis() + delaySeconds * 1000L)
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