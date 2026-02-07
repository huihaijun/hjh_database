package com.hjh_database.spawner

import com.google.gson.Gson
import com.google.common.reflect.TypeToken
import com.hjh_database.Hjh_database
import com.hjh_database.spawner.gui.SpawnerGui
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.CreatureSpawner
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ThreadLocalRandom

class SpawnerListener(private val plugin: Hjh_database) : Listener {

    private val spawnerKey = NamespacedKey(plugin, "hjh_spawner_item") // 物品上的标记
    private val linkWandKey = NamespacedKey(plugin, "hjh_link_wand")

    // ★★★ 修复点：这里定义 gson 变量 ★★★
    private val gson = Gson()

    // 临时存储玩家选中的坐标
    private val locationCache = HashMap<java.util.UUID, String>()

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return
        val item = event.item

        // === 1. 木锄打开编辑器 ===
        if (event.action == Action.RIGHT_CLICK_BLOCK &&
            block.type == Material.SPAWNER &&
            player.isOp &&
            item != null && item.type == Material.WOODEN_HOE) {

            event.isCancelled = true
            val spawner = block.state as CreatureSpawner

            // 注册并打开 GUI
            val gui = SpawnerGui(plugin, player, spawner)
            plugin.server.pluginManager.registerEvents(gui, plugin) // 注册 GUI 自身的监听器
            gui.openMainMenu()
            return
        }

        // === 2. 链接权杖逻辑 ===
        if (item != null && item.itemMeta?.persistentDataContainer?.has(linkWandKey, PersistentDataType.BYTE) == true) {
            event.isCancelled = true

            // 模式 A: 此时点击的是普通方块 -> 记录坐标
            if (block.type != Material.SPAWNER) {
                // 记录坐标字符串: "world,x,y,z"
                val locStr = "${block.world.name},${block.x + 0.5},${block.y + 1.0},${block.z + 0.5}"
                locationCache[player.uniqueId] = locStr
                player.sendMessage("§a[链接] §7已记录坐标点: [${block.x}, ${block.y}, ${block.z}]")
                player.sendMessage("§7请右键一个自定义刷怪笼以绑定。")

                // 播放粒子提示
                player.spawnParticle(Particle.HAPPY_VILLAGER, block.location.add(0.5, 1.0, 0.5), 10)
            }
            // 模式 B: 点击的是刷怪笼 -> 应用坐标
            else {
                val locStr = locationCache[player.uniqueId]
                if (locStr == null) {
                    player.sendMessage("§c[链接] §7请先右键一个方块作为刷怪点！")
                    return
                }

                val spawner = block.state as CreatureSpawner
                val data = plugin.spawnerBlockManager.getSpawnerData(spawner) ?: SpawnerData()

                data.targetLocationStr = locStr
                plugin.spawnerBlockManager.setSpawnerData(spawner, data)

                player.sendMessage("§a[链接] §7绑定成功！怪物将生成在预设位置。")
                player.spawnParticle(Particle.FLAME, block.location.add(0.5, 0.5, 0.5), 20)
                locationCache.remove(player.uniqueId)
            }
        }
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type == Material.SPAWNER && item.hasItemMeta()) {
            val pdc = item.itemMeta!!.persistentDataContainer
            // 检查是不是我们的特殊刷怪笼
            if (pdc.has(spawnerKey, PersistentDataType.BYTE)) {
                val spawner = event.blockPlaced.state as CreatureSpawner

                // 初始化默认数据
                val defaultData = SpawnerData()
                defaultData.mobName = item.itemMeta!!.displayName

                plugin.spawnerBlockManager.setSpawnerData(spawner, defaultData)
                event.player.sendMessage("§a[HJH] 自定义刷怪笼已放置！请用木锄右键编辑。")
            }
        }
    }

    /**
     * ★★★ 核心修复：怪物死亡掉落处理 ★★★
     */
    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val pdc = entity.persistentDataContainer

        // 检查这个实体是否有我们的“掉落物数据 Key”
        // 注意：这里引用 MobFactory 里的 KEY_MOB_DROPS
        if (pdc.has(MobFactory.KEY_MOB_DROPS, PersistentDataType.STRING)) {

            // 1. 清除原版掉落物 (腐肉、骨头等)
            event.drops.clear()
            // 清除掉落经验
            event.droppedExp = 0

            // 2. 读取自定义掉落数据
            val json = pdc.get(MobFactory.KEY_MOB_DROPS, PersistentDataType.STRING)
            if (json != null) {
                try {
                    // 反序列化 JSON -> List<MobDrop>
                    val type = object : TypeToken<List<MobDrop>>() {}.type
                    val drops: List<MobDrop> = gson.fromJson(json, type)

                    // 3. 计算掉落
                    for (drop in drops) {
                        // 生成 0.0 ~ 1.0 的随机数，如果小于 chance 则掉落
                        if (ThreadLocalRandom.current().nextDouble() <= drop.chance) {
                            // ★★★ 新代码 ★★★
                            val itemStack = if (drop.itemBase64 != null) {
                                try {
                                    ItemSerializer.fromBase64(drop.itemBase64)
                                } catch (e: Exception) {
                                    ItemStack(drop.material, drop.amount)
                                }
                            } else {
                                ItemStack(drop.material, drop.amount)
                            }
                            itemStack.amount = drop.amount // 确保数量正确
                            val meta = itemStack.itemMeta

                            // 处理 CustomModelData
                            if (drop.modelData != 0) {
                                meta?.setCustomModelData(drop.modelData)
                            }

                            itemStack.itemMeta = meta

                            // 添加到掉落列表
                            event.drops.add(itemStack)
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("解析怪物掉落数据失败: ${e.message}")
                }
            }
        }
    }

}