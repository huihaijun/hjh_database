package com.hjh_database.market

import org.bukkit.inventory.ItemStack
import java.util.UUID

data class MarketListing(
    val id: Long,
    val sellerUuid: UUID,
    val sellerName: String,
    val item: ItemStack,
    val price: Double,
    val listedAt: Long,
    val rarity: Int?
)

data class MarketPurchaseHistory(
    val item: ItemStack,
    val sellerName: String,
    val price: Double,
    val purchasedAt: Long
)
