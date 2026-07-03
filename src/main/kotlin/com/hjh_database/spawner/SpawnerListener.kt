package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.CreatureSpawner
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ThreadLocalRandom
import org.bukkit.event.player.PlayerInteractEvent

class SpawnerListener(private val plugin: Hjh_database) : Listener {

    private val keyMobIdItem = NamespacedKey(plugin, "hjh_spawner_mobid")
    private val keyTargetLocItem = NamespacedKey(plugin, "hjh_spawner_target")

    // 用于手动测试方块的 NBT Keys
    private val keyManualMobIdItem = NamespacedKey(plugin, "hjh_spawner_manual_mobid_item")
    private val keyManualMobIdBlock = NamespacedKey(plugin, "hjh_spawner_manual_mobid_block")
    private val keyManualCooldown = NamespacedKey(plugin, "hjh_spawner_manual_cd")

    // 【新增】用于手动测试方块坐标传递的 Keys
    private val keyManualTargetItem = NamespacedKey(plugin, "hjh_spawner_manual_target_item")
    private val keyManualTargetBlock = NamespacedKey(plugin, "hjh_spawner_manual_target_block")
    // 【新增】用于快速铺怪笼的 NBT Key
    private val keyFastItem = NamespacedKey(plugin, "hjh_spawner_fast")

    // 放置刷怪笼逻辑 (保持不变)
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type != org.bukkit.Material.SPAWNER) return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val spawner = event.blockPlaced.state as? CreatureSpawner ?: return

        // ★★★ 新增：如果这是"快速铺怪"刷怪笼 (Fast) ★★★
        if (pdc.has(keyFastItem, PersistentDataType.STRING)) {
            val mobId = pdc.get(keyFastItem, PersistentDataType.STRING) ?: return
            // 目标坐标：就是当前玩家尝试放置此方块的坐标
            val targetLoc = event.blockPlaced.location
            val targetStr = "${targetLoc.world?.name},${targetLoc.x},${targetLoc.y},${targetLoc.z}"
            // 实际刷怪笼物理坐标：目标坐标正下方2格
            val actualLoc = targetLoc.clone().subtract(0.0, 2.0, 0.0)
            // 取消原本的放置事件 (防止把刷怪笼放在表面)
            event.isCancelled = true
            // 处理物品消耗 (如果玩家不是创造模式)
            if (event.player.gameMode != org.bukkit.GameMode.CREATIVE) {
                event.itemInHand.amount -= 1
            }
            // 强行替换下方2格的方块为刷怪笼
            actualLoc.block.type = org.bukkit.Material.SPAWNER
            val actualSpawner = actualLoc.block.state as? CreatureSpawner
            if (actualSpawner != null) {
                // 使用 SpawnerBlockManager 写入数据
                plugin.spawnerBlockManager?.writeToSpawner(actualSpawner, mobId, targetStr)
                actualSpawner.update()
            }
            event.player.sendMessage("§a成功放置快速铺怪笼！(ID: $mobId)")
            event.player.sendMessage("§7物理刷怪笼已被埋入地下: §f${actualLoc.blockX}, ${actualLoc.blockY}, ${actualLoc.blockZ}")
            event.player.sendMessage("§7生成的怪物将直接刷在你的放置点: §f${targetLoc.blockX}, ${targetLoc.blockY}, ${targetLoc.blockZ}")
            return
        }

        // 1. 如果是“手动测试方块”
        if (pdc.has(keyManualMobIdItem, PersistentDataType.STRING)) {
            val mobId = pdc.get(keyManualMobIdItem, PersistentDataType.STRING) ?: return

            // 将数据写入放在地上的刷怪笼中 (它会随地图保存，不需要数据库)
            spawner.persistentDataContainer.set(keyManualMobIdBlock, PersistentDataType.STRING, mobId)

            // 【恢复】把坐标也传递给放下的方块
            if (pdc.has(keyManualTargetItem, PersistentDataType.STRING)) {
                val targetStr = pdc.get(keyManualTargetItem, PersistentDataType.STRING)!!
                spawner.persistentDataContainer.set(keyManualTargetBlock, PersistentDataType.STRING, targetStr)
            }
            // 禁用原版生成
            spawner.spawnedType = org.bukkit.entity.EntityType.AREA_EFFECT_CLOUD
            spawner.update()

            event.player.sendMessage("§a成功放置手动测试方块！(ID: $mobId) 右键即可测试。")
            return
        }

        // 2. 原本的“自动刷怪笼”逻辑保持不变
        if (pdc.has(keyMobIdItem, PersistentDataType.STRING)) {
            val mobId = pdc.get(keyMobIdItem, PersistentDataType.STRING) ?: return
            val targetStr = pdc.get(keyTargetLocItem, PersistentDataType.STRING)

            // 假设你的 SpawnerBlockManager 有 writeToSpawner 方法
            plugin.spawnerBlockManager?.writeToSpawner(spawner, mobId, targetStr)
            event.player.sendMessage("§a成功放置自动刷怪笼！(ID: $mobId)")
        }
    }

    // ★★★ 新增：玩家右键点击地上的手动测试方块 ★★★
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // 仅监听右键方块
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clickedBlock = event.clickedBlock ?: return
        // 只检查刷怪笼方块
        if (clickedBlock.type != org.bukkit.Material.SPAWNER) return
        val spawner = clickedBlock.state as? CreatureSpawner ?: return
        val pdc = spawner.persistentDataContainer

        // 检查这个刷怪笼是否有手动测试的标记
        if (pdc.has(keyManualMobIdBlock, PersistentDataType.STRING)) {
            event.isCancelled = true // 阻止原版的右键行为

            val mobId = pdc.get(keyManualMobIdBlock, PersistentDataType.STRING) ?: return
            // 冷却时间检查 (3000毫秒 = 3秒)
            val currentTime = System.currentTimeMillis()
            val lastUsed = pdc.get(keyManualCooldown, PersistentDataType.LONG) ?: 0L
            val cooldownMs = 3000L
            if (currentTime - lastUsed < cooldownMs) {
                val timeLeft = (cooldownMs - (currentTime - lastUsed)) / 1000
                event.player.sendMessage("§c生成冷却中，请等待 ${timeLeft + 1} 秒！")
                return
            }
            // 更新该方块的最后使用时间并保存
            pdc.set(keyManualCooldown, PersistentDataType.LONG, currentTime)
            spawner.update()

            // 【修复核心】：读取绑定的目标坐标，如果不存在则兜底在方块上方
            var spawnLoc = clickedBlock.location.add(0.5, 1.0, 0.5)
            val targetStr = pdc.get(keyManualTargetBlock, PersistentDataType.STRING)
            if (targetStr != null) {
                val parts = targetStr.split(",")
                if (parts.size >= 4) {
                    val world = org.bukkit.Bukkit.getWorld(parts[0])
                    if (world != null) {
                        try {
                            spawnLoc = org.bukkit.Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
                        } catch (e: Exception) {
                            // 坐标解析出错时忽略，回退到方块上方
                        }
                    }
                }
            }
            MobFactory.spawnMob(plugin, spawnLoc, mobId, removeWhenFarAway = true)
        }
    }

    // ★★★ 怪物死亡掉落逻辑 (已适配 ResourceManager.getItem) ★★★
    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val pdc = entity.persistentDataContainer
        if (pdc.has(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE)) {
            event.drops.clear()
            event.droppedExp = 0
            return
        }

        // 1. 检查是不是我们的自定义怪物 (读取 MobFactory 写入的 ID)
        val mobId = pdc.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING) ?: return
        val def = MobRegistry.get(mobId) ?: return

        // 2. 清空原版掉落 (如腐肉等)
        event.drops.clear()
        event.droppedExp = 0 // 如果你想控制经验，可以在这里设置

        // 3. 计算自定义掉落
        for (drop in def.drops) {
            val randomVal = ThreadLocalRandom.current().nextDouble()
            // 判断概率
            if (randomVal <= drop.chance) {
                // ★★★ 直接调用你的 getItem 方法 ★★★
                // 假设你的 getItem 返回 ItemStack?
                val itemStack = plugin.resourceManager.getItem(drop.resourceId)

                if (itemStack != null) {
                    // 随机数量
                    val amount = ThreadLocalRandom.current().nextInt(drop.min, drop.max + 1)
                    itemStack.amount = amount

                    if (amount > 0) {
                        event.drops.add(itemStack)
                    }
                } else {
                    // 仅在后台警告，防止刷屏
                    plugin.logger.warning("[Spawner] 怪物 '$mobId' 配置的掉落物 ID '${drop.resourceId}' 在 ResourceManager 中未找到！")
                }
            }
        }
    }
}
