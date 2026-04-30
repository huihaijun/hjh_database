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
        val title = org.bukkit.ChatColor.stripColor(view.title) ?: return

        // --- 1. 处理主菜单 ---
        if (title.startsWith("个人仓库")) {
            e.isCancelled = true // 全盘禁止拿取
            val clickedSlot = e.rawSlot
            val slots = intArrayOf(19, 21, 23, 25, 28, 30, 32, 34)
            val subId = slots.indexOf(clickedSlot)

            if (subId != -1) {
                if (e.click == ClickType.LEFT) {
                    plugin.warehouseManager.openSubMenu(player, player, subId, 0)
                } else if (e.click == ClickType.RIGHT) {
                    player.closeInventory()
                    plugin.warehouseManager.renamingPlayers[player.uniqueId] = subId
                    player.sendMessage("§e请输入该子仓库的新名称（支持&颜色代码），输入 '取消' 可放弃操作。")
                }
            }
            return
        }

        // --- 2. 处理二级子菜单 ---
        if (title.startsWith("仓库")) {
            val data = plugin.warehouseManager.getCachedData(player.uniqueId) ?: return
            val parsed = parseSubMenuData(title, data)

            // 如果真的解析失败了，启动安全锁：只要点的是最上面或最下面一排，统统取消！
            if (parsed == null) {
                if (e.rawSlot in 0..8 || e.rawSlot in 45..53) {
                    e.isCancelled = true
                }
                return
            }

            val subId = parsed.first
            val page = parsed.second
            val clickedSlot = e.rawSlot

            // 拦截顶部玻璃和底部导航栏区域
            if (clickedSlot in 0..8 || clickedSlot in 45..53) {
                e.isCancelled = true // 必须取消，防止玩家拿走玻璃和翻页按钮

                // 处理底部按钮的点击功能
                if (clickedSlot == 45 && page > 0) {
                    savePageItems(player, view.topInventory, subId, page)
                    plugin.warehouseManager.openSubMenu(player, player, subId, page - 1)
                } else if (clickedSlot == 49) {
                    savePageItems(player, view.topInventory, subId, page)
                    plugin.warehouseManager.openMainMenu(player, player)
                } else if (clickedSlot == 53 && page < 2) { // 默认最大3页，也就是 page 最大为 2
                    savePageItems(player, view.topInventory, subId, page)
                    plugin.warehouseManager.openSubMenu(player, player, subId, page + 1)
                }
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val view = e.view
        val title = org.bukkit.ChatColor.stripColor(view.title) ?: return

        // 只有在关闭二级子菜单时，才需要触发保存物品的逻辑
        if (title.startsWith("仓库")) {
            val data = plugin.warehouseManager.getCachedData(player.uniqueId) ?: return
            val parsed = parseSubMenuData(title, data) ?: return // 解析失败则安全跳过

            val subId = parsed.first
            val page = parsed.second

            // 1. 将界面中的物品同步到内存缓存里
            savePageItems(player, view.topInventory, subId, page)

            // 2. 异步将最新数据写入数据库
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        plugin.databaseManager.saveWarehouse(conn, data)
                    }
                } catch (ex: Exception) {
                    plugin.logger.severe("关闭界面保存仓库时出错: ${ex.message}")
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

    // --- 万能标题解析器：无视玩家奇葩命名、无视空格干扰 --
    private fun parseSubMenuData(title: String, data: com.hjh_database.warehouse.data.WarehouseData): Pair<Int, Int>? {
        try {
            // 此时传入的 title 已经被去除了颜色代码，格式如: "仓库: 子仓库 1-第1页"

            // 1. 获取页码 (通过截取最后一个 "-第" 和 "页" 之间的数字)
            val pageStr = title.substringAfterLast("-第").substringBefore("页").trim()
            val page = pageStr.toIntOrNull()?.minus(1) ?: return null

            // 2. 获取子仓库名称 (截取 "仓库:" 和最后一个 "-第" 之间的内容)
            val namePart = title.substringAfter("仓库:").substringBeforeLast("-第").trim()

            // 3. 找出对应的 subId (比对时忽略颜色代码)
            val subId = data.categoryNames.indexOfFirst {
                org.bukkit.ChatColor.stripColor(it)?.trim() == namePart
            }

            if (subId == -1) return null
            return Pair(subId, page)
        } catch (e: Exception) {
            return null
        }
    }
}