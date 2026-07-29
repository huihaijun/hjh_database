package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.ui.MenuManager.ElementType
import com.hjh_database.ui.MenuManager.Companion.PORTABLE_WAREHOUSE_BUTTON_SLOT
import com.hjh_database.ui.MenuManager.Companion.SUICIDE_BUTTON_SLOT
import com.hjh_database.ui.MenuManager.Companion.TITLE_SYSTEM_BUTTON_SLOT
import com.hjh_database.ui.DustbinMenuHolder
import com.hjh_database.ui.ItemShowcaseMenuHolder
import com.hjh_database.ui.TianjiUtilityMenus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerRecipeBookSettingsChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class MenuListener(private val plugin: Hjh_database) : Listener {
    private val suicideConfirmClicks = mutableMapOf<java.util.UUID, Long>()
    private val dustbinConfirmClicks = mutableMapOf<java.util.UUID, Long>()
    private val showcaseLastDisplays = mutableMapOf<java.util.UUID, Long>()

    // 1. 监听玩家右键 (打开菜单)
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        if (event.hand == EquipmentSlot.OFF_HAND) {
            if (plugin.menuManager.isTianjiToken(player.inventory.itemInMainHand)) {
                cancelTokenUse(event)
                // 主手天机令拥有本次右键的最高优先级，立刻终止副手弓弩的使用状态。
                player.clearActiveItem()
            }
            return
        }

        if (event.hand != EquipmentSlot.HAND) return

        val item = player.inventory.itemInMainHand
        if (plugin.menuManager.isTianjiToken(item)) {
            cancelTokenUse(event)
            player.clearActiveItem()
            plugin.menuManager.openMainMenu(player)
        }
    }

    /**
     * PlayerInteractEvent取消只能阻止正常物品使用；已装填弩或其他监听器仍可能走到
     * EntityShootBowEvent。这里在箭矢进入世界前做最后兜底，且只检查主手天机令。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onTianjiTokenOffhandShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        if (!plugin.menuManager.isTianjiToken(player.inventory.itemInMainHand)) return

        event.isCancelled = true
        player.clearActiveItem()
    }

    private fun cancelTokenUse(event: PlayerInteractEvent) {
        event.isCancelled = true
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)
    }

    // 2. 监听背包点击
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder

        if (holder is DustbinMenuHolder) {
            handleDustbinClick(event, player, holder)
            return
        }

        if (holder is ItemShowcaseMenuHolder) {
            handleShowcaseClick(event, player)
            return
        }

        // 1. 处理主菜单
        if (plugin.menuManager.isMainMenuTitle(title)) {
            val topSize = event.view.topInventory.size
            if (event.rawSlot >= topSize) {
                if (event.isShiftClick) {
                    event.isCancelled = true
                }
                return
            }

            event.isCancelled = true

            // 【新增】Slot 30: 任务记录 -> 打开任务分类 GUI
            if (event.rawSlot == 30) {
                // 调用我们刚刚写好的独立 GUI
                plugin.questGui.openCategoryMenu(player)
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
            }

            // Slot 31: 道天图录
            else if (event.rawSlot == 31) {
                plugin.menuManager.openDaoTianMenu(player)
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
            }

            // Slot 32: 个人饰品栏
            else if (event.rawSlot == 32) {
                player.closeInventory()
                plugin.accessoryManager.openAccessoryMenu(player)
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
            }

            else if (event.rawSlot == SUICIDE_BUTTON_SLOT) {
                handleSuicideButton(player)
            }

            else if (event.rawSlot == PORTABLE_WAREHOUSE_BUTTON_SLOT) {
                openPortableWarehouse(player)
            }

            else if (event.rawSlot == TITLE_SYSTEM_BUTTON_SLOT) {
                plugin.titleManager.openMainMenu(player)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            }

            else if (event.rawSlot == TianjiUtilityMenus.DUSTBIN_BUTTON_SLOT) {
                TianjiUtilityMenus.openDustbin(player)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            }

            else if (event.rawSlot == TianjiUtilityMenus.SHOWCASE_BUTTON_SLOT) {
                TianjiUtilityMenus.openItemShowcase(player)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
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
                        val type = com.hjh_database.ui.MenuManager.ElementType.valueOf(typeName)
                        // 调用你自己定义的处理逻辑 (确保这个方法在 Listener 类里存在)
                        handleElementClick(player, type, event.click)
                    }
                } catch (e: IllegalArgumentException) {
                    // 忽略无效类型
                }
            }
        }
    }

    @EventHandler
    fun onRecipeBookSettingsChange(event: PlayerRecipeBookSettingsChangeEvent) {
        if (event.recipeBookType != PlayerRecipeBookSettingsChangeEvent.RecipeBookType.CRAFTING) return
        if (!event.isOpen) return

        val player = event.player
        if (player.openInventory.type != InventoryType.CRAFTING) return

        val data = plugin.playerManager.getPlayerData(player)
        if (data == null || data.status in setOf(0, 1, 2, 4)) {
            player.sendMessage("§c当前状态不可使用【饰品栏】功能！")
            player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 1f, 1f)
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            player.closeInventory()
            plugin.accessoryManager.openAccessoryMenu(player)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
        })
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        when (event.view.topInventory.holder) {
            is DustbinMenuHolder -> {
                if (event.rawSlots.any { it in TianjiUtilityMenus.DUSTBIN_STORAGE_SIZE until event.view.topInventory.size }) {
                    event.isCancelled = true
                }
                return
            }
            is ItemShowcaseMenuHolder -> {
                event.isCancelled = true
                return
            }
        }

        val title = event.view.title
        if (!plugin.menuManager.isMainMenuTitle(title)) return

        val topSize = event.view.topInventory.size
        if (event.rawSlots.any { it < topSize }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? DustbinMenuHolder ?: return
        val player = event.player as? Player ?: return
        dustbinConfirmClicks.remove(player.uniqueId)
        TianjiUtilityMenus.returnDustbinContents(player, event.inventory, holder)
    }

    private fun handleDustbinClick(event: InventoryClickEvent, player: Player, holder: DustbinMenuHolder) {
        val topSize = event.view.topInventory.size
        if (event.rawSlot == TianjiUtilityMenus.DUSTBIN_CONFIRM_SLOT) {
            event.isCancelled = true
            val now = System.currentTimeMillis()
            val lastClick = dustbinConfirmClicks[player.uniqueId] ?: 0L
            if (now - lastClick > 1500L) {
                dustbinConfirmClicks[player.uniqueId] = now
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f)
                return
            }

            dustbinConfirmClicks.remove(player.uniqueId)
            holder.settled = true
            for (slot in 0 until TianjiUtilityMenus.DUSTBIN_STORAGE_SIZE) {
                event.view.topInventory.setItem(slot, null)
            }
            player.closeInventory()
            player.sendMessage("§7[归尘匣] §f匣中物品已归于尘土。")
            player.playSound(player.location, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1f, 0.8f)
            return
        }

        // 防止从下方背包双击同类物品时，把控制栏玻璃一并收走。
        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }

        // 前五行是投放区；最后一行只作为控制栏使用。
        if (event.rawSlot in TianjiUtilityMenus.DUSTBIN_STORAGE_SIZE until topSize) {
            event.isCancelled = true
        }
    }

    private fun handleShowcaseClick(event: InventoryClickEvent, player: Player) {
        event.isCancelled = true
        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        val item = event.currentItem?.takeUnless { it.type.isAir } ?: return

        val now = System.currentTimeMillis()
        val lastDisplay = showcaseLastDisplays[player.uniqueId] ?: 0L
        if (now - lastDisplay < 2000L) {
            player.sendActionBar(Component.text("世尘镜尚在冷却中……", NamedTextColor.RED))
            return
        }
        showcaseLastDisplays[player.uniqueId] = now

        val itemName = item.itemMeta?.displayName()
            ?: Component.translatable(item.type.translationKey())
        val shownItem = Component.text("[")
            .append(itemName)
            .append(Component.text("]"))
            .hoverEvent(item.asHoverEvent { it })
        Bukkit.broadcast(
            Component.text(player.name, NamedTextColor.YELLOW)
                .append(Component.text("展示了物品——", NamedTextColor.WHITE))
                .append(shownItem)
        )
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
    }

    private fun handleSuicideButton(player: Player) {
        val now = System.currentTimeMillis()
        val lastClick = suicideConfirmClicks[player.uniqueId] ?: 0L

        if (now - lastClick <= 3000L) {
            suicideConfirmClicks.remove(player.uniqueId)
            player.closeInventory()
            player.sendMessage("§4[天机令] §c你服下鹤顶丹，气息渐绝……")
            player.playSound(player.location, Sound.ENTITY_WITHER_DEATH, 0.8f, 1.3f)
            player.health = 0.0
            return
        }

        suicideConfirmClicks[player.uniqueId] = now
        player.sendMessage("§c[天机令] 再次点击 §4自尽 §c确认就义。")
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f)
    }

    private fun openPortableWarehouse(player: Player) {
        if (isInDungeon(player)) {
            player.sendMessage("§c[天机令] 秘境之中天机紊乱，无法开启随身宝箱。")
            player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 1f, 1f)
            return
        }

        player.openInventory(player.enderChest)
        player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f)
    }

    private fun isInDungeon(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        return data.status == 5
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
                // 点击可能连续触发，延迟合并保存，避免主线程等待数据库。
                plugin.databaseManager.queuePlayerSave(data, 40L)
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
                plugin.databaseManager.queuePlayerSave(data, 40L)
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
