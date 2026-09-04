package com.hjh_database.warehouse.data

import org.bukkit.inventory.ItemStack
import java.util.UUID

class WarehouseData(val uuid: UUID, var playerName: String) {
    // 8个子仓库的名称，默认命名
    var categoryNames = Array(8) { "§a子仓库 ${it + 1}" }
    // 8个子仓库，每个子仓库最多3页，每页36格 (共108格)
    var items = Array(8) { arrayOfNulls<ItemStack>(108) }
}