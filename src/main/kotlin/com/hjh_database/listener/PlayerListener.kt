package com.hjh_database.listener

import com.hjh_database.Hjh_database
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.*
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class PlayerListener(private val plugin: Hjh_database) : Listener {

    private val pendingDeathEffects = mutableSetOf<java.util.UUID>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.playerManager.loadAndCache(event.player)
        // 【新增】进服时，异步加载玩家的个人仓库数据
        plugin.warehouseManager.loadAndCache(event.player)
        plugin.elementCrystalManager.loadPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            data.currentHealth = player.health
        }
        plugin.playerManager.unloadAndSave(player.uniqueId)
        // 【新增】退服时，异步保存并清理玩家的个人仓库数据
        plugin.warehouseManager.saveAndRemove(player)
        plugin.elementCrystalManager.unloadPlayer(player)
    }

    // =================================================================
    //  ⚡️ 核心：全方位状态同步监听
    //  任何可能导致物品栏变动的事件，都会触发 refreshPlayerStatus
    // =================================================================

    // 1. 切换快捷栏 (滚轮/数字键)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        // 切换选中槽不会改变装备位置，只需同步当前手持武器攻速。
        // 避免每次滚轮切换都扫描整包并重建所有装备 Lore。
        plugin.playerManager.syncHeldAttackSpeed(event.player, event.newSlot)
    }

    // 2. 交换双手物品 (按F)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        refreshPlayerStatus(event.player)
    }

    // 3. 丢弃物品 (按Q)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        refreshPlayerStatus(event.player)
    }

    // 4. 捡起物品
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        if (event.entity is Player) {
            refreshPlayerStatus(event.entity as Player)
        }
    }

    // 5. 点击背包 (移动/穿戴/丢弃)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val who = event.whoClicked
        if (who is Player) {
            // 只处理玩家自己的背包，或者涉及到装备栏的操作
            refreshPlayerStatus(who)
        }
    }

    // 6. 关闭背包 (作为兜底检查)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player
        if (player is Player) {
            refreshPlayerStatus(player)
        }
    }

    // 7. 玩家复活 (根据 Status, Race, Job 动态设置复活点)
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            // 获取世界，如果世界不存在则使用玩家当前死亡的世界兜底
            val world = org.bukkit.Bukkit.getWorld("world") ?: player.world
            var targetLoc: org.bukkit.Location? = null
            when (data.status) {
                0 -> {
                    targetLoc = org.bukkit.Location(world, 1315.5, 76.5, 42.5, -90.0f, 0.0f)
                }
                1 -> {
                    targetLoc = org.bukkit.Location(world, 1248.05, 35.00, -364.01, 89.40f, 2.10f)
                }
                2 -> {
                    // 根据种族分配
                    targetLoc = when (data.race) {
                        0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                        1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                        2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                        3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                        4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                        else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 默认去种族0
                    }
                }
                3, 5, 6 -> {
                    // 3, 5, 6 复活点一致
                    targetLoc = org.bukkit.Location(world, -407.5, 67.5, 1565.5, -180f, 0f)
                    // 状态为 3 或 5 时，改写为 6
                    if (data.status == 3 || data.status == 5) {
                        data.updateStatus(6)
                        // 发送消息提示玩家 (可选)
                        // player.sendMessage("§c你在大陆/副本中陨落，已被打入奈何桥...")
                    }
                }
                4 -> {
                    // 根据职业分配 (请确保左侧 0, 1, 2, 3 对应你数据库中实际的职业 ID)
                    targetLoc = when (data.job) {
                        0 -> org.bukkit.Location(world, 1247.5, 36.0, -391.5, 90.0f, 0.0f) // 战士
                        1 -> org.bukkit.Location(world, 1247.5, 36.0, -411.5, 90.0f, 0.0f) // 弓箭手
                        2 -> org.bukkit.Location(world, 1247.5, 36.0, -429.5, 90.0f, 0.0f) // 术士
                        3 -> org.bukkit.Location(world, 1247.5, 36.0, -447.5, 90.0f, 0.0f) // 医师
                        else -> org.bukkit.Location(world, 1247.5, 36.0, -391.5, 90.0f, 0.0f) // 默认丢给战士
                    }
                }
            }

            // 如果成功匹配到了目标坐标，则设置复活点
            if (targetLoc != null) {
                event.respawnLocation = targetLoc
            }
        }

        // 刷新玩家状态 (原有的逻辑)
        refreshPlayerStatus(player)
        applyDeathEffectsAfterRespawn(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        pendingDeathEffects.add(event.entity.uniqueId)
    }

    /**
     * 统一刷新方法
     * 延迟 1 Tick 执行，确保事件已经处理完毕，物品已经在新位置中
     */
    private fun refreshPlayerStatus(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            // 刷新所有物品的 Lore (视觉反馈)
            // 注意：这里调用的是属性 weaponManager (对应 Java 的 getWeaponManager())
            plugin.playerManager.weaponManager.refreshPlayerWeapons(player)

            // 刷新护甲 Lore (状态显示)
            plugin.playerManager.armorManager.refreshPlayerArmors(player)

            // 刷新结晶 Lore (状态显示)
            plugin.playerManager.crystalManager.refreshPlayerCrystals(player)

            // 刷新虎瘴装 Lore (瘴气、耐久与激活状态)
            plugin.baihuDzManager.refreshPlayerEquipment(player)

            // 刷新普通法宝 Lore（槽位、职业与等级激活状态）
            plugin.artifactManager.refreshPlayerArtifacts(player)

            // 重新计算所有属性 (数值反馈)
            plugin.playerManager.updateStats(player)

            // 3. (可选) 强制客户端刷新背包显示，解决偶尔的 Lore 显示延迟
            // 注意：频繁调用 updateInventory 在高版本通常没问题，但在极旧版本可能有性能损耗
            // 如果你发现 Lore 还是偶尔不刷新，取消下面这行的注释
            // player.updateInventory()

        }, 1L)
    }

    private fun applyDeathEffectsAfterRespawn(player: Player) {
        if (!pendingDeathEffects.remove(player.uniqueId)) return

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false))
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 4, false, false))
            player.sendTitle("§c§l死！", "", 0, 40, 10)
        }, 1L)
    }
}

