package com.hjh_database.farming.util

import com.hjh_database.Hjh_database
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object FarmingItems {
    private val serializer = LegacyComponentSerializer.legacySection()

    fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    fun component(text: String) = serializer.deserialize(color(text))

    fun simple(material: Material, name: String, lore: List<String> = emptyList(), amount: Int = 1): ItemStack {
        val item = ItemStack(material, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(color(name))
            meta.lore = lore.map(::color)
            item.itemMeta = meta
        }
        return item
    }

    fun countResource(plugin: Hjh_database, player: Player, resourceId: String): Int {
        val key = NamespacedKey(plugin, "resource_id")
        return player.inventory.storageContents
            .filter { item ->
                item?.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) == resourceId
            }
            .sumOf { it?.amount ?: 0 }
    }

    fun consumeResource(plugin: Hjh_database, player: Player, resourceId: String, amount: Int): Boolean {
        if (amount <= 0) return true
        if (countResource(plugin, player, resourceId) < amount) return false
        val key = NamespacedKey(plugin, "resource_id")
        var remaining = amount
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val item = inv.getItem(slot) ?: continue
            val id = item.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING)
            if (id != resourceId) continue
            val take = minOf(remaining, item.amount)
            item.amount -= take
            remaining -= take
            inv.setItem(slot, if (item.amount > 0) item else null)
            if (remaining <= 0) return true
        }
        return true
    }

    fun resourceItem(plugin: Hjh_database, resourceId: String, amount: Int = 1): ItemStack? {
        val item = plugin.resourceManager.getItem(resourceId)?.clone() ?: return null
        item.amount = amount.coerceAtLeast(1)
        return item
    }

    fun resourceDisplayName(plugin: Hjh_database, resourceId: String): String {
        val item = plugin.resourceManager.getItem(resourceId)
        return item?.itemMeta?.displayName ?: resourceId
    }

    fun giveResource(plugin: Hjh_database, player: Player, resourceId: String, amount: Int): Boolean {
        var remaining = amount.coerceAtLeast(1)
        val template = plugin.resourceManager.getItem(resourceId)?.clone() ?: return false
        val maxStack = template.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val stackSize = minOf(remaining, maxStack)
            val stack = template.clone()
            stack.amount = stackSize
            player.inventory.addItem(stack).values.forEach { player.world.dropItemNaturally(player.location, it) }
            remaining -= stackSize
        }
        return true
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}时${minutes}分"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}
