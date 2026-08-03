package com.hjh_database.warehouse.manager

import com.hjh_database.Hjh_database
import com.hjh_database.warehouse.data.WarehouseData
import com.hjh_database.warehouse.gui.WarehouseGUI
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class WarehouseManager(private val plugin: Hjh_database) {

    // 缓存玩家的仓库数据
    private val cache = ConcurrentHashMap<UUID, WarehouseData>()

    // GUI 实例
    val gui = WarehouseGUI()

    // 记录正在重命名子仓库的玩家 UUID -> 操作的子仓库索引 (0-7)
    val renamingPlayers = ConcurrentHashMap<UUID, Int>()

    /**
     * 玩家进服时异步加载数据并缓存
     */
    fun loadAndCache(player: Player) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                // 【修改】从外层获取连接，并传给 loadWarehouse
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    val data = plugin.databaseManager.loadWarehouse(conn, player.uniqueId, player.name)
                    // 管理员可能正在编辑该玩家的离线仓库。此时保留同一份内存数据，
                    // 防止玩家上线加载出的旧快照覆盖管理员尚未保存的修改。
                    cache.compute(player.uniqueId) { _, current ->
                        current?.apply { playerName = player.name } ?: data
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("加载仓库数据失败: ${e.message}")
            }
        })
    }

    /**
     * 玩家退服时保存数据并清除缓存
     */
    fun saveAndRemove(player: Player) {
        val data = cache.remove(player.uniqueId) ?: return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                // 【修改】从外层获取连接，并传给 saveWarehouse
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    plugin.databaseManager.saveWarehouse(conn, data)
                }
            } catch (e: Exception) {
                plugin.logger.severe("退服保存仓库失败: ${e.message}")
            }
        })
    }

    /**
     * 关服时同步保存所有在线玩家数据
     */
    fun saveAllOnline() {
        try {
            // 【修改】批量保存时，外层获取一次连接传进去，效率更高
            plugin.databaseManager.dataSource?.connection?.use { conn ->
                cache.values.forEach { data ->
                    plugin.databaseManager.saveWarehouse(conn, data)
                }
            }
            plugin.logger.info("已保存所有在线玩家的仓库数据。")
        } catch (e: Exception) {
            plugin.logger.severe("批量保存仓库数据失败: ${e.message}")
        }
    }

    fun getCachedData(uuid: UUID): WarehouseData? {
        return cache[uuid]
    }

    /** 将离线读取的数据纳入统一缓存；若玩家已在线，则优先返回在线缓存。 */
    fun adoptAdminData(loaded: WarehouseData): WarehouseData {
        return cache.putIfAbsent(loaded.uuid, loaded) ?: loaded
    }

    /** 管理员结束离线编辑后释放临时缓存；在线玩家的数据必须继续保留。 */
    fun releaseAdminData(data: WarehouseData) {
        if (plugin.server.getPlayer(data.uuid)?.isOnline == true) return
        cache.remove(data.uuid, data)
    }

    /**
     * 在主线程克隆快照，再把序列化与 JDBC 写入统一数据库队列。
     * 这样异步线程不会一边遍历 ItemStack 数组，一边与 GUI 修改竞争。
     */
    fun saveAsync(data: WarehouseData): CompletableFuture<Unit> {
        val snapshot = snapshot(data)
        return plugin.databaseManager.submitDatabaseOperation {
            plugin.databaseManager.dataSource?.connection?.use { conn ->
                plugin.databaseManager.saveWarehouse(conn, snapshot)
            }
            Unit
        }
    }

    fun getAllCachedData(): List<WarehouseData> {
        return cache.values.toList()
    }

    fun resetCachedData(player: Player): WarehouseData {
        val data = WarehouseData(player.uniqueId, player.name)
        cache[player.uniqueId] = data
        renamingPlayers.remove(player.uniqueId)
        return data
    }

    /**
     * 打开主菜单
     */
    fun openMainMenu(viewer: Player, target: Player) {
        val targetData = getCachedData(target.uniqueId)
        if (targetData == null) {
            viewer.sendMessage("§c该玩家的仓库数据尚未加载！")
            return
        }
        gui.openMainMenu(viewer, targetData)
    }

    /**
     * 打开子仓库
     */
    fun openSubMenu(viewer: Player, target: Player, subId: Int, page: Int) {
        val targetData = getCachedData(target.uniqueId) ?: return
        gui.openSubMenu(viewer, targetData, subId, page)
    }

    /**
     * 重载插件时，为所有在线玩家主动加载数据
     */
    fun reloadOnlinePlayers() {
        for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!cache.containsKey(player.uniqueId)) {
                loadAndCache(player)
            }
        }
    }

    private fun snapshot(source: WarehouseData): WarehouseData {
        return WarehouseData(source.uuid, source.playerName).also { copy ->
            copy.categoryNames = source.categoryNames.copyOf()
            copy.items = Array(8) { category ->
                Array<org.bukkit.inventory.ItemStack?>(108) { slot -> source.items[category][slot]?.clone() }
            }
        }
    }
}
