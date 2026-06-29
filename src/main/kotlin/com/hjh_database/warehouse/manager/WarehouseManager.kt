package com.hjh_database.warehouse.manager

import com.hjh_database.Hjh_database
import com.hjh_database.warehouse.data.WarehouseData
import com.hjh_database.warehouse.gui.WarehouseGUI
import org.bukkit.entity.Player
import java.util.UUID
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
                    cache[player.uniqueId] = data
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
}
