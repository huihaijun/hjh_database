package com.hjh_database.farming.gui

import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

enum class FarmingMenuType {
    MAIN,
    PLANT_SELECT,
    FIELD_ACTION,
    PROTECTION_SHOP
}

class FarmingMenuHolder(
    val type: FarmingMenuType,
    val fieldIndex: Int = -1,
    val slotIds: Map<Int, String> = emptyMap()
) : InventoryHolder {
    private var inventory: Inventory? = null

    fun bind(inv: Inventory): Inventory {
        inventory = inv
        return inv
    }

    override fun getInventory(): Inventory {
        return inventory ?: Bukkit.createInventory(null, 9)
    }
}
