package com.hjh_database.warehouse.admin

import com.hjh_database.Hjh_database
import com.hjh_database.warehouse.data.WarehouseData
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 管理员专用的离线仓库编辑器。所有 JDBC 均在数据库队列中执行。 */
class AdminWarehouseGui(private val plugin: Hjh_database) : Listener {
    private val requestVersions = ConcurrentHashMap<UUID, Long>()
    private val ownerLocks = ConcurrentHashMap<UUID, UUID>()
    private val viewerOwners = ConcurrentHashMap<UUID, UUID>()
    private val viewerData = ConcurrentHashMap<UUID, WarehouseData>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** 在插件关闭、数据库连接仍可用时，把所有尚未关闭的页面先同步回缓存。 */
    fun flushAllToMemory() {
        viewerData.keys.forEach { viewerId ->
            val viewer = plugin.server.getPlayer(viewerId) ?: return@forEach
            val holder = viewer.openInventory.topInventory.holder
            if (holder is SubHolder) syncPage(holder, viewer.openInventory.topInventory)
        }
    }

    fun open(viewer: Player, requestedName: String) {
        val cleanName = requestedName.trim()
        if (cleanName.isEmpty()) {
            viewer.sendMessage("§c请输入数据库中的玩家名。")
            return
        }
        finishSession(viewer.uniqueId)
        val version = requestVersions.merge(viewer.uniqueId, 1L, Long::plus) ?: 1L
        val loadingHolder = LoadingHolder()
        val loading = plugin.server.createInventory(loadingHolder, 27, "§0正在读取离线仓库...")
        loadingHolder.backingInventory = loading
        loading.setItem(13, item(Material.CLOCK, "§e正在读取 $cleanName 的仓库", listOf("§7数据库操作不会阻塞服务器主线程")))
        viewer.openInventory(loading)

        plugin.databaseManager.submitDatabaseOperation {
            loadByPlayerName(cleanName)
        }.whenComplete { loaded, throwable ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!viewer.isOnline || requestVersions[viewer.uniqueId] != version) return@Runnable
                if (viewer.openInventory.topInventory.holder !== loadingHolder) return@Runnable
                if (throwable != null) {
                    plugin.logger.warning("读取管理员离线仓库失败: ${throwable.cause?.message ?: throwable.message}")
                    viewer.sendMessage("§c仓库读取失败，请查看控制台。")
                    viewer.closeInventory()
                    return@Runnable
                }
                if (loaded == null) {
                    viewer.sendMessage("§c数据库内没有名为 $cleanName 的玩家。")
                    viewer.closeInventory()
                    return@Runnable
                }
                beginSession(viewer, loaded)
            })
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        when (val holder = event.view.topInventory.holder) {
            is LoadingHolder -> event.isCancelled = true
            is MainHolder -> {
                event.isCancelled = true
                if (viewerOwners[player.uniqueId] != holder.data.uuid) return
                val subId = CATEGORY_SLOTS.indexOf(event.rawSlot)
                if (subId >= 0) openSubMenu(player, holder.data, subId, 0)
            }
            is SubHolder -> {
                if (viewerOwners[player.uniqueId] != holder.data.uuid) {
                    event.isCancelled = true
                    return
                }
                val raw = event.rawSlot
                if (raw in 0..8 || raw in 45..53) {
                    event.isCancelled = true
                    when (raw) {
                        45 -> if (holder.page > 0) {
                            syncPage(holder, event.view.topInventory)
                            openSubMenu(player, holder.data, holder.subId, holder.page - 1)
                        }
                        49 -> {
                            syncPage(holder, event.view.topInventory)
                            openMainMenu(player, holder.data)
                        }
                        53 -> if (holder.page < 2) {
                            syncPage(holder, event.view.topInventory)
                            openSubMenu(player, holder.data, holder.subId, holder.page + 1)
                        }
                    }
                }
                // 9..44 是唯一可写区域；玩家背包区域保持原版交互，支持 Shift 快速存取。
            }
            else -> Unit
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        when (event.view.topInventory.holder) {
            is LoadingHolder, is MainHolder -> if (event.rawSlots.any { it < event.view.topInventory.size }) event.isCancelled = true
            is SubHolder -> if (event.rawSlots.any { it < event.view.topInventory.size && it !in 9..44 }) event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val holder = event.inventory.holder
        if (holder is SubHolder) syncPage(holder, event.inventory)
        if (holder is MainHolder || holder is SubHolder) {
            // 翻页/返回会先关闭旧 GUI；延后一 Tick 判断，避免把正常导航误认为结束编辑。
            plugin.server.scheduler.runTask(plugin, Runnable { finishIfLeft(player) })
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val holder = event.player.openInventory.topInventory.holder
        if (holder is SubHolder) syncPage(holder, event.player.openInventory.topInventory)
        requestVersions.remove(event.player.uniqueId)
        finishSession(event.player.uniqueId)
    }

    private fun beginSession(viewer: Player, loaded: WarehouseData) {
        val currentEditor = ownerLocks.putIfAbsent(loaded.uuid, viewer.uniqueId)
        if (currentEditor != null && currentEditor != viewer.uniqueId) {
            viewer.sendMessage("§c该玩家的仓库正在被另一名管理员编辑，请稍后再试。")
            viewer.closeInventory()
            return
        }
        val data = plugin.warehouseManager.adoptAdminData(loaded)
        viewerOwners[viewer.uniqueId] = data.uuid
        viewerData[viewer.uniqueId] = data
        openMainMenu(viewer, data)
        viewer.sendMessage("§a正在编辑数据库玩家 ${data.playerName} 的个人仓库。")
    }

    private fun openMainMenu(viewer: Player, data: WarehouseData) {
        val holder = MainHolder(data)
        val inventory = plugin.server.createInventory(holder, 54, "§0管理仓库-${data.playerName}")
        holder.backingInventory = inventory
        val glass = item(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 0..8) inventory.setItem(slot, glass)
        for (slot in 45..53) inventory.setItem(slot, glass)
        CATEGORY_SLOTS.forEachIndexed { subId, slot ->
            inventory.setItem(slot, item(Material.CHEST, data.categoryNames[subId], listOf("§e点击 §7打开并编辑", "§7支持 Shift 快速存取物品")))
        }
        viewer.openInventory(inventory)
        viewer.playSound(viewer.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
    }

    private fun openSubMenu(viewer: Player, data: WarehouseData, subId: Int, page: Int) {
        val holder = SubHolder(data, subId, page)
        val inventory = plugin.server.createInventory(holder, 54, "§0管理: ${data.categoryNames[subId]}-第${page + 1}页")
        holder.backingInventory = inventory
        val glass = item(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 0..8) inventory.setItem(slot, glass)
        for (slot in 45..53) inventory.setItem(slot, glass)
        if (page > 0) inventory.setItem(45, item(Material.ARROW, "§a上一页"))
        inventory.setItem(49, item(Material.OAK_DOOR, "§c返回仓库列表"))
        if (page < 2) inventory.setItem(53, item(Material.ARROW, "§a下一页"))
        val start = page * 36
        for (slot in 0 until 36) inventory.setItem(slot + 9, data.items[subId][start + slot])
        viewer.openInventory(inventory)
        viewer.playSound(viewer.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
    }

    private fun syncPage(holder: SubHolder, inventory: Inventory) {
        val start = holder.page * 36
        for (slot in 0 until 36) {
            holder.data.items[holder.subId][start + slot] = inventory.getItem(slot + 9)?.clone()
        }
    }

    private fun finishIfLeft(viewer: Player) {
        val owner = viewerOwners[viewer.uniqueId] ?: return
        val current = viewer.openInventory.topInventory.holder
        val stillEditing = when (current) {
            is MainHolder -> current.data.uuid == owner
            is SubHolder -> current.data.uuid == owner
            else -> false
        }
        if (!stillEditing) finishSession(viewer.uniqueId)
    }

    private fun finishSession(viewerId: UUID) {
        val owner = viewerOwners.remove(viewerId) ?: return
        val data = viewerData.remove(viewerId)
        ownerLocks.remove(owner, viewerId)
        if (data != null) {
            // 写入完成前继续保留缓存；若目标此时上线，会直接复用这份最新数据，
            // 不会从尚未来得及更新的数据库读回旧仓库。
            plugin.warehouseManager.saveAsync(data).whenComplete { _, throwable ->
                if (throwable != null) {
                    plugin.logger.severe("保存管理员编辑的仓库失败: ${throwable.cause?.message ?: throwable.message}")
                }
                if (plugin.isEnabled) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.warehouseManager.releaseAdminData(data)
                    })
                }
            }
        }
    }

    private fun loadByPlayerName(playerName: String): WarehouseData? {
        val source = plugin.databaseManager.dataSource ?: error("数据库连接池尚未初始化")
        source.connection.use { connection ->
            var uuid: UUID? = null
            var storedName: String? = null
            val lookupSql = "SELECT uuid, player_name FROM player_data WHERE player_name = ? COLLATE NOCASE LIMIT 1"
            connection.prepareStatement(lookupSql).use { statement ->
                statement.setString(1, playerName)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        uuid = runCatching { UUID.fromString(result.getString("uuid")) }.getOrNull()
                        storedName = result.getString("player_name")
                    }
                }
            }
            if (uuid == null) {
                connection.prepareStatement("SELECT uuid, player_name FROM player_warehouse WHERE player_name = ? COLLATE NOCASE LIMIT 1").use { statement ->
                    statement.setString(1, playerName)
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            uuid = runCatching { UUID.fromString(result.getString("uuid")) }.getOrNull()
                            storedName = result.getString("player_name")
                        }
                    }
                }
            }
            val resolvedUuid = uuid ?: return null
            return plugin.databaseManager.loadWarehouse(connection, resolvedUuid, storedName?.ifBlank { playerName } ?: playerName)
        }
    }

    private fun item(material: Material, name: String, loreLines: List<String> = emptyList()): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(name)
                lore = loreLines
            }
        }

    private class LoadingHolder : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }
    private class MainHolder(val data: WarehouseData) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }
    private class SubHolder(val data: WarehouseData, val subId: Int, val page: Int) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    companion object {
        private val CATEGORY_SLOTS = intArrayOf(19, 21, 23, 25, 28, 30, 32, 34)
    }
}
