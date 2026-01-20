package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.ui.MenuManager.ElementType
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class MenuListener(private val plugin: Hjh_database) : Listener {

    // 1. 监听玩家右键 (打开菜单)
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        // 增加对物理方块点击的过滤，防止点空气报错（虽然 Bukkit 通常处理得好）
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val item = event.item ?: return // item 可能为 null

            // 配合 MenuManager 中的 PDC 检测逻辑
            if (plugin.menuManager.isTianjiToken(item)) {
                plugin.menuManager.openMainMenu(event.player)
                event.isCancelled = true
            }
        }
    }

    // 2. 监听背包点击
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title
        val player = event.whoClicked as? Player ?: return

        // 1. 处理主菜单
        if (title.contains("天机")) {
            event.isCancelled = true
            if (event.rawSlot == 31) {
                plugin.menuManager.openDaoTianMenu(player)
            }
        }
        // 2. 处理道天图录菜单
        else if (title.contains("道天图录") && title.contains("元素仓库")) {
            event.isCancelled = true

            val clickedItem = event.currentItem
            if (clickedItem == null || !clickedItem.hasItemMeta()) return

            // 返回按钮 (Slot 49)
            if (event.rawSlot == 49) {
                plugin.menuManager.openMainMenu(player)
                return
            }

            // 获取按钮对应的元素类型
            val meta = clickedItem.itemMeta ?: return
            val btnKey = NamespacedKey(plugin, "btn_element")

            if (meta.persistentDataContainer.has(btnKey, PersistentDataType.STRING)) {
                val typeName = meta.persistentDataContainer.get(btnKey, PersistentDataType.STRING)
                try {
                    if (typeName != null) {
                        val type = ElementType.valueOf(typeName)
                        handleElementClick(player, type, event.click)
                    }
                } catch (e: IllegalArgumentException) {
                    // 忽略无效类型
                }
            }
        }
    }

    private fun handleElementClick(player: Player, type: ElementType, click: ClickType) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // === 存入逻辑 (左键) - [已修复严重BUG] ===
        if (click.isLeftClick) {
            var count = 0
            val inventory = player.inventory

            // 遍历所有槽位 (0-35 是背包主区域)
            // 这种方式最安全，不会出现双倍扣除或并发修改异常
            for (i in 0 until inventory.size) {
                val item = inventory.getItem(i)

                // 1. 判空
                // 2. 检查是否为目标物品 (支持 1.21 组件)
                if (plugin.menuManager.isPanlingItem(item, type)) {
                    // 累加数量
                    count += item!!.amount
                    // 清空该槽位 (直接设置为 null 或 空气)
                    inventory.setItem(i, null)
                }
            }

            if (count > 0) {
                addBankAmount(data, type, count)
                player.sendMessage("§a已存入 $count 个 ${type.displayName}")
                // 异步保存数据，防止卡主线程
                plugin.databaseManager.savePlayer(data)
                // 刷新界面以显示最新库存
                plugin.menuManager.openDaoTianMenu(player)
            } else {
                player.sendMessage("§c你的背包里没有 ${type.displayName}")
            }
        }
        // === 取出逻辑 (右键 / Shift右键) ===
        else if (click.isRightClick) {
            val currentStock = getBankAmount(data, type)
            if (currentStock <= 0) {
                player.sendMessage("§c仓库里没有 ${type.displayName} 了")
                return
            }

            var amountToTake = 1
            if (click.isShiftClick) {
                // 取出一组 (这里设定为 64，即便你在组件里设置了 max_stack=99)
                // Minecraft 客户端默认行为也是按 64 一组操作，除非特别定制
                amountToTake = 64
            }

            // 如果库存不足，只能取剩下的
            if (currentStock < amountToTake) {
                amountToTake = currentStock
            }

            // 检查背包空间 (简单检查)
            if (player.inventory.firstEmpty() == -1) {
                player.sendMessage("§c背包已满！")
                return
            }

            // 1. 扣除库存
            addBankAmount(data, type, -amountToTake)

            // 2. 生成新版组件物品
            val item = plugin.menuManager.getPanlingItem(type, amountToTake)

            // 3. 发放物品
            // addItem 返回没放进去的物品 Map (如果背包满了)
            val leftOver = player.inventory.addItem(item)

            // [安全回滚] 如果因为某些原因背包其实满了没放进去，把扣掉的库存加回去
            if (leftOver.isNotEmpty()) {
                for (left in leftOver.values) {
                    addBankAmount(data, type, left.amount)
                    amountToTake -= left.amount // 修正实际取出的数量
                }
            }

            if (amountToTake > 0) {
                player.sendMessage("§e已取出 $amountToTake 个 ${type.displayName}")
                plugin.databaseManager.savePlayer(data)
                plugin.menuManager.openDaoTianMenu(player)
            } else {
                player.sendMessage("§c背包已满，无法取出！")
            }
        }
    }

    // 辅助方法：修改玩家数据
    private fun addBankAmount(data: PlayerData, type: ElementType, amount: Int) {
        when (type) {
            ElementType.METAL -> data.metal = (data.metal ?: 0) + amount
            ElementType.WOOD -> data.wood = (data.wood ?: 0) + amount
            ElementType.WATER -> data.water = (data.water ?: 0) + amount
            ElementType.FIRE -> data.fire = (data.fire ?: 0) + amount
            ElementType.EARTH -> data.earth = (data.earth ?: 0) + amount
            ElementType.RELIVE -> data.reliveStone = (data.reliveStone ?: 0) + amount
        }
    }

    // 辅助方法：获取玩家数据
    private fun getBankAmount(data: PlayerData, type: ElementType): Int {
        return when (type) {
            ElementType.METAL -> data.metal ?: 0
            ElementType.WOOD -> data.wood ?: 0
            ElementType.WATER -> data.water ?: 0
            ElementType.FIRE -> data.fire ?: 0
            ElementType.EARTH -> data.earth ?: 0
            ElementType.RELIVE -> data.reliveStone ?: 0
        }
    }
}