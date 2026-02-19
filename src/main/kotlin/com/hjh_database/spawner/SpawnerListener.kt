package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.CreatureSpawner
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ThreadLocalRandom

class SpawnerListener(private val plugin: Hjh_database) : Listener {

    private val keyMobIdItem = NamespacedKey(plugin, "hjh_spawner_mobid")
    private val keyTargetLocItem = NamespacedKey(plugin, "hjh_spawner_target")

    // 放置刷怪笼逻辑 (保持不变)
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type != Material.SPAWNER) return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        if (pdc.has(keyMobIdItem, PersistentDataType.STRING)) {
            val mobId = pdc.get(keyMobIdItem, PersistentDataType.STRING) ?: return
            val targetStr = pdc.get(keyTargetLocItem, PersistentDataType.STRING)
            val spawner = event.blockPlaced.state as? CreatureSpawner ?: return

            // 写入方块数据
            plugin.spawnerBlockManager.writeToSpawner(spawner, mobId, targetStr)
            event.player.sendMessage("§a成功放置定点刷怪笼！(ID: $mobId)")
        }
    }

    // ★★★ 怪物死亡掉落逻辑 (已适配 ResourceManager.getItem) ★★★
    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val pdc = entity.persistentDataContainer

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