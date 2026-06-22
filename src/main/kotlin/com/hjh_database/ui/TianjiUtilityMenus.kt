package com.hjh_database.ui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class DustbinMenuHolder : InventoryHolder {
    lateinit var backingInventory: Inventory
    var settled: Boolean = false

    override fun getInventory(): Inventory = backingInventory
}

class ItemShowcaseMenuHolder : InventoryHolder {
    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory = backingInventory
}

object TianjiUtilityMenus {
    private const val DUSTBIN_TITLE = "§8归尘匣"
    private const val SHOWCASE_TITLE = "§8世尘镜"
    const val DUSTBIN_BUTTON_SLOT = 48
    const val SHOWCASE_BUTTON_SLOT = 50
    const val DUSTBIN_CONFIRM_SLOT = 49
    const val DUSTBIN_STORAGE_SIZE = 45

    fun openDustbin(player: Player) {
        val holder = DustbinMenuHolder()
        val inventory = Bukkit.createInventory(holder, 54, DUSTBIN_TITLE)
        holder.backingInventory = inventory

        val border = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in DUSTBIN_STORAGE_SIZE until inventory.size) {
            inventory.setItem(slot, border)
        }
        inventory.setItem(
            DUSTBIN_CONFIRM_SLOT,
            menuItem(
                Material.LIGHT_GRAY_DYE,
                "§c§l清除匣中物品",
                listOf("§7快速双击以永久清除", "§c此操作不可撤销")
            )
        )
        player.openInventory(inventory)
    }

    fun openItemShowcase(player: Player) {
        val holder = ItemShowcaseMenuHolder()
        val inventory = Bukkit.createInventory(holder, 36, SHOWCASE_TITLE)
        holder.backingInventory = inventory

        // 按原版背包的视觉顺序排列：三行背包在上，快捷栏在最下方。
        for (sourceSlot in 9..35) {
            player.inventory.getItem(sourceSlot)?.let {
                inventory.setItem(sourceSlot - 9, it.clone())
            }
        }
        for (sourceSlot in 0..8) {
            player.inventory.getItem(sourceSlot)?.let {
                inventory.setItem(27 + sourceSlot, it.clone())
            }
        }
        player.openInventory(inventory)
    }

    /**
     * 插件卸载时监听器随即失效，因此必须在 onDisable 内主动结算并关闭这些菜单。
     * 世尘镜先清空克隆，归尘匣先返还玩家真实投入的物品。
     */
    fun closeOpenMenusForDisable() {
        for (player in Bukkit.getOnlinePlayers()) {
            val topInventory = player.openInventory.topInventory
            when (val holder = topInventory.holder) {
                is DustbinMenuHolder -> {
                    returnDustbinContents(player, topInventory, holder)
                    player.closeInventory()
                }
                is ItemShowcaseMenuHolder -> {
                    topInventory.clear()
                    player.closeInventory()
                }
            }
        }
    }

    /** 清理由旧版类加载器留下的菜单，保障安装本修复后的第一次重载。 */
    fun closeStaleMenusOnEnable() {
        for (player in Bukkit.getOnlinePlayers()) {
            val view = player.openInventory
            when (view.title) {
                SHOWCASE_TITLE -> {
                    view.topInventory.clear()
                    player.closeInventory()
                }
                DUSTBIN_TITLE -> {
                    returnDustbinSlots(player, view.topInventory)
                    player.closeInventory()
                }
            }
        }
    }

    fun returnDustbinContents(player: Player, inventory: Inventory, holder: DustbinMenuHolder) {
        if (holder.settled) return
        holder.settled = true

        returnDustbinSlots(player, inventory)
    }

    private fun returnDustbinSlots(player: Player, inventory: Inventory) {
        val returnedItems = mutableListOf<ItemStack>()
        for (slot in 0 until DUSTBIN_STORAGE_SIZE) {
            val item = inventory.getItem(slot) ?: continue
            if (item.type.isAir) continue
            returnedItems.add(item.clone())
            inventory.setItem(slot, null)
        }
        if (returnedItems.isEmpty()) return

        val leftovers = player.inventory.addItem(*returnedItems.toTypedArray())
        for (item in leftovers.values) {
            player.world.dropItemNaturally(player.location, item)
        }
    }

    fun menuItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        if (lore.isNotEmpty()) meta?.lore = lore
        item.itemMeta = meta
        return item
    }
}
