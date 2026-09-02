package com.hjh_database.dungeon.chest

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

class VaultChestListener(private val plugin: Hjh_database) : Listener {

    private val ownerKey = NamespacedKey(plugin, "chest_owner")
    private val expiresAtKey = NamespacedKey(plugin, "chest_reward_expires_at")
    private val autoCollectAtKey = NamespacedKey(plugin, "chest_reward_auto_collect_at")
    private val dungeonKey = NamespacedKey(plugin, "vault_dungeon_id")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id") // 对应 ResourceManager 里的 keyId
    private val scheduledAutoCollects = ConcurrentHashMap.newKeySet<UUID>()

    init {
        // 插件重载后，为当前已加载区块中尚未消失的旧奖励恢复清理任务。
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.server.worlds.forEach { world ->
                world.loadedChunks.forEach { chunk ->
                    chunk.entities.filterIsInstance<Item>().forEach { item ->
                        scheduleRewardCleanup(item)
                        scheduleRewardAutoCollect(item)
                    }
                }
            }
        })
    }

    @EventHandler
    fun onVaultInteract(event: PlayerInteractEvent) {
        // 1. 【新增】屏蔽副手的交互事件，防止右键时触发两次
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.VAULT) return

        val state = block.state as org.bukkit.block.Vault
        val dungeonId = state.persistentDataContainer.get(dungeonKey, PersistentDataType.STRING) ?: return

        event.isCancelled = true

        val player = event.player
        val config = plugin.goldenChestManager.chestRegistry[dungeonId]
        if (config == null) {
            player.sendMessage("§c该金宝箱配置已丢失！")
            return
        }

        val playerData = plugin.playerManager.getPlayerData(player) ?: return
        val record = playerData.dungeonRecords.computeIfAbsent(dungeonId) { DungeonRecord() }
        val oneTimeRecord = playerData.dungeonRecords.computeIfAbsent(
            plugin.goldenChestManager.oneTimeScopeId(config)
        ) { DungeonRecord() }

        // 验证可开箱次数
        if (record.availableOpens <= 0) {
            val displayName = config.displayName
            player.sendMessage("§7你当前的秘境§b[$displayName]§7开箱次数不足……")
            return
        }

        // 2. 【修改点】要求玩家必须主手持有对应钥匙才能开箱
        val mainHandItem = player.inventory.itemInMainHand
        if (!mainHandItem.hasItemMeta()) {
            player.sendMessage("§c你需要将对应的钥匙拿在主手，对准宝库才可开启！")
            return
        }

        val heldKeyId = mainHandItem.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
        if (heldKeyId != config.keyResourceId) {
            player.sendMessage("§c你需要将秘境 §b[${config.displayName}] §c的专属钥匙拿在主手才能开启！")
            return
        }

        if (mainHandItem.amount < config.keyCost) {
            player.sendMessage("§c你主手中的钥匙数量不足！(需要 ${config.keyCost} 把)")
            return
        }

        // 扣除主手钥匙
        mainHandItem.amount -= config.keyCost

        // 3. 【修改点】消耗1次可开箱次数，并增加1次总开箱次数统计
        record.availableOpens -= 1
        record.opens += 1

        // 4. 计算掉落
        val droppedItems = plugin.goldenChestManager.rollLoot(player, dungeonId, record, oneTimeRecord)

        // 更新保底数据
        val droppedIds = droppedItems.mapTo(HashSet()) { it.resourceId }
        for (itemConf in config.lootTable) {
            if (itemConf.requiredJob != null && itemConf.requiredJob != playerData.job) continue
            if (droppedIds.contains(itemConf.resourceId)) {
                record.dropCounts[itemConf.resourceId] = record.dropCounts.getOrDefault(itemConf.resourceId, 0) + 1
                record.opensSinceLastDrop[itemConf.resourceId] = 0
                if (itemConf.oneTimeOnly && oneTimeRecord !== record) {
                    oneTimeRecord.dropCounts[itemConf.resourceId] =
                        oneTimeRecord.dropCounts.getOrDefault(itemConf.resourceId, 0) + 1
                }
            } else if (itemConf.oneTimeOnly && oneTimeRecord.dropCounts.getOrDefault(itemConf.resourceId, 0) > 0) {
                // 已经取得终身唯一物品后不再无意义地累积保底。
                record.opensSinceLastDrop.remove(itemConf.resourceId)
            } else {
                record.opensSinceLastDrop[itemConf.resourceId] = record.opensSinceLastDrop.getOrDefault(itemConf.resourceId, 0) + 1
            }
        }
        // 数据库保存走现有异步写入队列，不在交互事件主线程执行 SQL。
        plugin.databaseManager.savePlayerAsync(playerData)

        // 强制触发 Vault 开箱物理动画
        try {
            val vaultData = block.blockData as org.bukkit.block.data.type.Vault
            vaultData.vaultState = org.bukkit.block.data.type.Vault.State.UNLOCKING
            block.blockData = vaultData

            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val resetData = block.blockData as org.bukkit.block.data.type.Vault
                resetData.vaultState = org.bukkit.block.data.type.Vault.State.INACTIVE
                block.blockData = resetData
            }, 30L)
        } catch (e: Exception) {
            // 兼容性保护
        }

        // 播放原版宝库的音效
        player.playSound(block.location, Sound.BLOCK_VAULT_ACTIVATE, 1.0f, 1.0f)
        player.playSound(block.location, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 1.0f)

        // Y轴加了1.5，让粒子和物品在箱子上方稍高的地方爆开
        val particleLoc = block.location.clone().add(0.5, 1.5, 0.5)
        // 绿色的幸运星光粒子
        block.world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, particleLoc, 40, 0.5, 0.5, 0.5, 0.1)
        // 紫色的神秘魔法粒子
        block.world.spawnParticle(org.bukkit.Particle.WITCH, particleLoc, 50, 0.5, 0.5, 0.5, 0.1)
        // 橙红色的火焰粒子，增加爆满的视觉张力
        block.world.spawnParticle(org.bukkit.Particle.FLAME, particleLoc, 30, 0.4, 0.4, 0.4, 0.08)
        // 播放额外庆祝音效
        player.playSound(block.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f)

        // 【新增】用于记录本次获得物品的列表
        val obtainedMessages = mutableListOf<String>()

        droppedItems.forEach { loot ->
            val itemStack = plugin.resourceManager.getItem(loot.resourceId)?.clone() ?: return@forEach
            val amount = ThreadLocalRandom.current().nextInt(loot.minAmount, loot.maxAmount + 1)
            itemStack.amount = amount

            val itemName = itemStack.itemMeta?.displayName ?: "神秘物品"
            obtainedMessages.add("$itemName §f× $amount")

            // 4. 【新增】触发全服通告
            if (loot.announceGlobal) {
                broadcastRareDrop(player, config, itemStack)
            }

            val dropLoc = block.location.clone().add(0.5, 1.2, 0.5)
            val itemEntity = block.world.dropItem(dropLoc, itemStack)

            itemEntity.setPickupDelay(20)
            itemEntity.velocity = org.bukkit.util.Vector(0.0, 0.2, 0.0)
            itemEntity.isCustomNameVisible = true
            itemEntity.customName = itemName
            itemEntity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
            itemEntity.persistentDataContainer.set(
                expiresAtKey,
                PersistentDataType.LONG,
                System.currentTimeMillis() + REWARD_LIFETIME_MILLIS
            )
            itemEntity.persistentDataContainer.set(
                autoCollectAtKey,
                PersistentDataType.LONG,
                System.currentTimeMillis() + AUTO_COLLECT_DELAY_MILLIS
            )
            scheduleRewardCleanup(itemEntity)
            scheduleRewardAutoCollect(itemEntity)
        }

        // 3. 【新增】给玩家发送个人开箱提示
        if (obtainedMessages.isNotEmpty()) {
            player.sendMessage("§f你使用秘境钥匙开启了宝箱，获得 ${obtainedMessages.joinToString("§f, ")}")
        } else {
            player.sendMessage("§f你开启了宝箱，但是里面空空如也...")
        }
    }

    private fun broadcastRareDrop(
        player: Player,
        config: GoldenChestConfig,
        itemStack: org.bukkit.inventory.ItemStack
    ) {
        val itemName = itemStack.itemMeta?.displayName()
            ?: Component.translatable(itemStack.type.translationKey())
        val hoveredItem = itemName.hoverEvent(itemStack.asHoverEvent { it })
        org.bukkit.Bukkit.broadcast(
            Component.text("恭喜玩家", NamedTextColor.GOLD)
                .append(Component.space())
                .append(Component.text(player.name, NamedTextColor.YELLOW))
                .append(Component.text(" 在秘境 ", NamedTextColor.GOLD))
                .append(Component.text(config.displayName, NamedTextColor.AQUA))
                .append(Component.text(" 获得了 ", NamedTextColor.GOLD))
                .append(hoveredItem)
        )
    }

    private fun scheduleRewardCleanup(item: Item) {
        if (!item.persistentDataContainer.has(ownerKey, PersistentDataType.STRING)) return

        val now = System.currentTimeMillis()
        val expiresAt = item.persistentDataContainer.get(expiresAtKey, PersistentDataType.LONG)
            ?: (now + REWARD_LIFETIME_MILLIS).also {
                // 兼容升级前已经存在、只有 owner 标记的金宝箱奖励。
                item.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, it)
            }
        val remainingMillis = expiresAt - now
        if (remainingMillis <= 0L) {
            item.remove()
            return
        }

        val delayTicks = ((remainingMillis + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK).coerceAtLeast(1L)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            // 区块卸载时实体暂不可用；重新加载区块后由 ChunkLoadEvent 接续清理。
            if (!item.isValid) return@Runnable
            if (!item.persistentDataContainer.has(ownerKey, PersistentDataType.STRING)) return@Runnable

            val currentExpiresAt = item.persistentDataContainer
                .get(expiresAtKey, PersistentDataType.LONG) ?: return@Runnable
            if (System.currentTimeMillis() >= currentExpiresAt) {
                item.remove()
            } else {
                scheduleRewardCleanup(item)
            }
        }, delayTicks)
    }

    /**
     * 金宝箱奖励生成2秒后仍未被拾取时，自动飞向原开箱人并进入背包。
     * 实体 UUID 去重可避免区块加载、插件重载等路径重复创建回收任务。
     */
    private fun scheduleRewardAutoCollect(item: Item, retryDelayTicks: Long? = null) {
        val ownerUuid = readOwnerUuid(item) ?: return
        val collectAt = item.persistentDataContainer.get(autoCollectAtKey, PersistentDataType.LONG)
            ?: return // 没有此字段的旧奖励保持旧版行为，避免重载后突然追踪玩家。
        if (!scheduledAutoCollects.add(item.uniqueId)) return

        val delayTicks = retryDelayTicks ?: run {
            val remainingMillis = (collectAt - System.currentTimeMillis()).coerceAtLeast(0L)
            ((remainingMillis + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK).coerceAtLeast(1L)
        }

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!item.isValid || item.isDead || !item.persistentDataContainer.has(ownerKey, PersistentDataType.STRING)) {
                scheduledAutoCollects.remove(item.uniqueId)
                return@Runnable
            }

            val expiresAt = item.persistentDataContainer.get(expiresAtKey, PersistentDataType.LONG)
            if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                scheduledAutoCollects.remove(item.uniqueId)
                return@Runnable
            }

            val owner = plugin.server.getPlayer(ownerUuid)
            if (owner == null || !owner.isOnline || owner.isDead) {
                // 玩家暂时不可接收时低频重试；奖励仍由原有三分钟清理机制保护。
                scheduledAutoCollects.remove(item.uniqueId)
                scheduleRewardAutoCollect(item, AUTO_COLLECT_RETRY_TICKS)
                return@Runnable
            }
            startRewardFlight(item, owner)
        }, delayTicks)
    }

    private fun startRewardFlight(item: Item, owner: Player) {
        if (!item.isValid || item.isDead) {
            scheduledAutoCollects.remove(item.uniqueId)
            return
        }
        item.pickupDelay = Int.MAX_VALUE
        item.setGravity(false)

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (!item.isValid || item.isDead) {
                    scheduledAutoCollects.remove(item.uniqueId)
                    cancel()
                    return
                }
                if (!owner.isOnline || owner.isDead) {
                    item.setGravity(true)
                    item.pickupDelay = 0
                    scheduledAutoCollects.remove(item.uniqueId)
                    scheduleRewardAutoCollect(item, AUTO_COLLECT_RETRY_TICKS)
                    cancel()
                    return
                }

                if (item.world != owner.world || ticks++ >= AUTO_COLLECT_FLIGHT_TICKS) {
                    finishRewardAutoCollect(item, owner)
                    cancel()
                    return
                }

                val target = owner.location.clone().add(0.0, 1.0, 0.0)
                val delta = target.toVector().subtract(item.location.toVector())
                if (delta.lengthSquared() <= 0.20) {
                    finishRewardAutoCollect(item, owner)
                    cancel()
                    return
                }

                val distance = delta.length()
                val speed = min(0.85, max(0.22, distance * 0.22))
                item.velocity = delta.normalize().multiply(speed)
                if (ticks % 2 == 0) {
                    item.world.spawnParticle(org.bukkit.Particle.END_ROD, item.location, 1, 0.04, 0.04, 0.04, 0.0)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun finishRewardAutoCollect(item: Item, owner: Player) {
        scheduledAutoCollects.remove(item.uniqueId)
        if (!item.isValid || item.isDead) return

        val reward = item.itemStack.clone()
        val originalExpiry = item.persistentDataContainer.get(expiresAtKey, PersistentDataType.LONG)
            ?: (System.currentTimeMillis() + REWARD_LIFETIME_MILLIS)
        val itemName = item.customName
        item.remove()

        val leftovers = owner.inventory.addItem(reward)
        owner.playSound(owner.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.25f)
        owner.world.spawnParticle(
            org.bukkit.Particle.WAX_ON,
            owner.location.clone().add(0.0, 1.0, 0.0),
            8,
            0.25,
            0.35,
            0.25,
            0.03
        )

        if (leftovers.isEmpty()) return

        // 背包满时不删除剩余奖励：放在玩家脚下并保留专属拾取权与原到期时间。
        leftovers.values.forEach { leftover ->
            val dropped = owner.world.dropItem(owner.location.clone().add(0.0, 0.35, 0.0), leftover)
            dropped.pickupDelay = 0
            dropped.isCustomNameVisible = itemName != null
            dropped.customName = itemName
            dropped.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.uniqueId.toString())
            dropped.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, originalExpiry)
            scheduleRewardCleanup(dropped)
        }
        owner.sendActionBar(Component.text("背包空间不足，未装入的宝箱奖励已落在脚下。", NamedTextColor.RED))
    }

    private fun readOwnerUuid(item: Item): UUID? {
        val raw = item.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.filterIsInstance<Item>().forEach { item ->
            scheduleRewardCleanup(item)
            scheduleRewardAutoCollect(item)
        }
    }

    // 专属权保护：别人无法捡起
    @EventHandler
    fun onItemPickup(event: EntityPickupItemEvent) {
        val item: Item = event.item
        val ownerUuidStr = item.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: return

        val player = event.entity as? Player ?: return
        if (player.uniqueId.toString() != ownerUuidStr) {
            event.isCancelled = true
        }
    }

    /**
     * 掉落实体的 PDC 不参与原版物品合并判定。专属奖励一旦合并，只会保留
     * 一个实体的 owner，且无法再为每份奖励独立计算三分钟存活时间。
     */
    @EventHandler(ignoreCancelled = true)
    fun onChestRewardMerge(event: ItemMergeEvent) {
        val sourceOwner = event.entity.persistentDataContainer
            .get(ownerKey, PersistentDataType.STRING)
        val targetOwner = event.target.persistentDataContainer
            .get(ownerKey, PersistentDataType.STRING)

        // 所有专属奖励均保持独立，避免归属丢失并确保每份奖励单独计时。
        if (sourceOwner != null || targetOwner != null) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onVaultPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (block.type != Material.VAULT) return

        val item = event.itemInHand
        val meta = item.itemMeta ?: return

        // 检查玩家手里放下的 Vault 是否带有我们的副本标记
        val dungeonId = meta.persistentDataContainer.get(dungeonKey, PersistentDataType.STRING) ?: return

        // 如果有，把它转移给放下的方块状态中
        val state = block.state as org.bukkit.block.Vault
        state.persistentDataContainer.set(dungeonKey, PersistentDataType.STRING, dungeonId)
        // 必须 update 才能保存到世界中
        state.update()

        event.player.sendMessage("§a[系统] 成功放置副本 §e[$dungeonId] §a的金宝箱！玩家现在可以开箱了。")
    }

    /**
     * 根据 ResourceManager 存入的 resource_id 检查并扣除玩家背包中的物品
     */
    private fun takeCustomKey(player: Player, resourceId: String, amount: Int): Boolean {
        val inv = player.inventory
        var count = 0

        // 统计数量
        for (item in inv.contents) {
            if (item == null || !item.hasItemMeta()) continue
            val id = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
            if (id == resourceId) {
                count += item.amount
            }
        }

        if (count < amount) return false

        // 执行扣除
        var remain = amount
        for (i in 0 until inv.size) {
            if (remain <= 0) break
            val item = inv.getItem(i) ?: continue
            if (!item.hasItemMeta()) continue

            val id = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
            if (id == resourceId) {
                if (item.amount > remain) {
                    item.amount -= remain
                    remain = 0
                } else {
                    remain -= item.amount
                    inv.setItem(i, null)
                }
            }
        }
        return true
    }

    private companion object {
        const val REWARD_LIFETIME_MILLIS = 3L * 60L * 1000L
        const val AUTO_COLLECT_DELAY_MILLIS = 2_000L
        const val AUTO_COLLECT_RETRY_TICKS = 20L
        const val AUTO_COLLECT_FLIGHT_TICKS = 10
        const val MILLIS_PER_TICK = 50L
    }
}
