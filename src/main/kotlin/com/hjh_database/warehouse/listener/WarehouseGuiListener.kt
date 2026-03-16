package com.hjh_database.warehouse.listener

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.AsyncPlayerChatEvent

class WarehouseGuiListener(private val plugin: Hjh_database) : Listener {

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val view = e.view
        val title = view.title

        // --- 1. 处理主菜单 ---
        if (title.startsWith("§0个人仓库 - ")) {
            e.isCancelled = true // 主菜单全盘禁止拿取
            val clickedSlot = e.rawSlot

            // 8个子仓库的槽位
            val slots = intArrayOf(19, 21, 23, 25, 28, 30, 32, 34)
            val subId = slots.indexOf(clickedSlot)

            if (subId != -1) {
                if (e.click == ClickType.LEFT) {
                    // 左键打开第一页
                    plugin.warehouseManager.openSubMenu(player, player, subId, 0)
                } else if (e.click == ClickType.RIGHT) {
                    // 右键重命名
                    player.closeInventory()
                    plugin.warehouseManager.renamingPlayers[player.uniqueId] = subId
                    player.sendMessage("§e请输入该子仓库的新名称 (支持 & 颜色代码)，输入 '取消' 放弃修改。")
                }
            }
            return
        }

        // --- 2. 处理子菜单 (翻页与保护) ---
        if (title.startsWith("§0仓库: ")) {
            val clickedSlot = e.rawSlot

            // 拦截点击首尾行的动作
            if (clickedSlot in 0..8 || clickedSlot in 45..53) {
                e.isCancelled = true

                val item = e.currentItem ?: return
                if (item.type != Material.GRAY_STAINED_GLASS_PANE && item.type != Material.AIR) {
                    val data = plugin.warehouseManager.getCachedData(player.uniqueId) ?: return
                    // 解析当前是哪个子仓库和页码
                    val namePart = title.substringAfter("§0仓库: ").substringBefore(" - 第")
                    val subId = data.categoryNames.indexOf(namePart)
                    if (subId == -1) return

                    val currentPageStr = title.substringAfter(" - 第").substringBefore("页")
                    val currentPage = (currentPageStr.toIntOrNull() ?: 1) - 1
                    // 处理按钮点击
                    when (clickedSlot) {
                        45 -> { // 上一页
                            if (currentPage > 0) {
                                savePageItems(player, e.inventory, subId, currentPage)
                                plugin.warehouseManager.openSubMenu(player, player, subId, currentPage - 1)
                            }
                        }
                        49 -> { // 返回主菜单
                            savePageItems(player, e.inventory, subId, currentPage)
                            plugin.warehouseManager.openMainMenu(player, player)
                        }
                        53 -> { // 下一页
                            if (currentPage < 2) {
                                savePageItems(player, e.inventory, subId, currentPage)
                                plugin.warehouseManager.openSubMenu(player, player, subId, currentPage + 1)
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val title = e.view.title

        // 关闭子仓库时，保存物品到缓存，并触发异步数据库保存
        if (title.startsWith("§0") && title.contains(" - 第") && title.endsWith("页")) {
            val data = plugin.warehouseManager.getCachedData(player.uniqueId) ?: return
            val namePart = title.substring(2).substringBefore(" - 第")
            val subId = data.categoryNames.indexOf(namePart)
            if (subId == -1) return

            val currentPageStr = title.substringAfter(" - 第").substringBefore("页")
            val currentPage = (currentPageStr.toIntOrNull() ?: 1) - 1

            // 提取中间 36 格物品保存到对应页码
            savePageItems(player, e.inventory, subId, currentPage)

            // 触发异步保存 (防崩档)
            // 异步保存数据
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    // 【关键修复】先从 dataSource 获取连接 conn，然后再一起传给 saveWarehouse
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        plugin.databaseManager.saveWarehouse(conn, data)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("GUI异步保存仓库数据失败: ${e.message}")
                }
            })
        }
    }

    @EventHandler
    fun onChat(e: AsyncPlayerChatEvent) {
        val player = e.player
        val uuid = player.uniqueId

        // 拦截重命名操作
        if (plugin.warehouseManager.renamingPlayers.containsKey(uuid)) {
            e.isCancelled = true
            val subId = plugin.warehouseManager.renamingPlayers.remove(uuid) ?: return

            val msg = e.message
            if (msg.equals("取消", ignoreCase = true)) {
                player.sendMessage("§c已取消重命名。")
                // 回到主线程打开主菜单
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.warehouseManager.openMainMenu(player, player)
                })
                return
            }

            // 更新名称
            val data = plugin.warehouseManager.getCachedData(uuid)
            if (data != null) {
                val coloredName = ChatColor.translateAlternateColorCodes('&', msg)
                data.categoryNames[subId] = coloredName
                player.sendMessage("§a成功将子仓库重命名为: $coloredName")

                // 异步保存数据
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        // 【关键修复】先从 dataSource 获取连接 conn，然后再一起传给 saveWarehouse
                        plugin.databaseManager.dataSource?.connection?.use { conn ->
                            plugin.databaseManager.saveWarehouse(conn, data)
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("GUI异步保存仓库数据失败: ${e.message}")
                    }
                })

                // 回到主线程打开主菜单
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.warehouseManager.openMainMenu(player, player)
                })
            }
        }
    }

    // --- 提取重复逻辑：保存当前页面的物品到缓存数据中 ---
    private fun savePageItems(player: Player, inventory: org.bukkit.inventory.Inventory, subId: Int, page: Int) {
        val data = plugin.warehouseManager.getCachedData(player.uniqueId) ?: return
        val startIndex = page * 36
        for (i in 0..35) {
            val item = inventory.getItem(i + 9)
            data.items[subId][startIndex + i] = item
        }
    }
}