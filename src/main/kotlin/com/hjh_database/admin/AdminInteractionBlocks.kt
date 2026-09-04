package com.hjh_database.admin

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.type.EndPortalFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** 位置标记随区块 PDC 保存，不扫描世界，也不在构造阶段注册监听器。 */
class AdminInteractionBlocks(private val plugin: Hjh_database) : Listener {
    enum class Kind(val id: String, val title: String, val material: Material) {
        REBIRTH("rebirth", "转生祭坛", Material.END_PORTAL_FRAME),
        ELEMENT_CRYSTAL("element_crystal", "元素结晶交互台", Material.RESPAWN_ANCHOR);

        companion object {
            fun fromId(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    private val itemKey = NamespacedKey(plugin, "admin_interaction_block")

    private fun positionKey(block: Block) = NamespacedKey(
        plugin, "admin_station_${block.x and 15}_${block.y}_${block.z and 15}"
    )

    fun typeAt(block: Block): Kind? = Kind.fromId(
        block.chunk.persistentDataContainer.get(positionKey(block), PersistentDataType.STRING)
    )?.takeIf { block.type == it.material }

    private fun itemType(item: ItemStack): Kind? = Kind.fromId(
        item.itemMeta?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING)
    )?.takeIf { item.type == it.material }

    fun createItem(kind: Kind): ItemStack = ItemStack(kind.material).apply {
        val meta = itemMeta
        meta.setDisplayName("§d§l${kind.title}")
        meta.lore = listOf("§7管理员放置后，玩家右键即可交互。", "§8位置随区块保存，重启后仍有效。")
        meta.persistentDataContainer.set(itemKey, PersistentDataType.STRING, kind.id)
        itemMeta = meta
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun checkPlacement(event: BlockPlaceEvent) {
        if (itemType(event.itemInHand) != null && !event.player.isOp) {
            event.isCancelled = true
            event.player.sendMessage("§c只有管理员可以放置此交互方块。")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun recordPlacement(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        val data = block.chunk.persistentDataContainer
        val key = positionKey(block)
        // 普通方块重新占用旧位置时也清掉旧标记，不能凭相同材质获得功能。
        data.remove(key)
        val kind = itemType(event.itemInHand) ?: return
        if (!event.player.isOp) return
        data.set(key, PersistentDataType.STRING, kind.id)
        if (kind == Kind.REBIRTH) {
            val frame = block.blockData as EndPortalFrame
            frame.setEye(true)
            block.blockData = frame
        }
        event.player.sendMessage("§a已建立${kind.title}，玩家右键即可交互。")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun checkBreak(event: BlockBreakEvent) {
        if (typeAt(event.block) == null) return
        if (!event.player.isOp) {
            event.isCancelled = true
            event.player.sendMessage("§c只有管理员可以拆除此交互方块。")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun forgetBrokenBlock(event: BlockBreakEvent) {
        val kind = typeAt(event.block)
        event.block.chunk.persistentDataContainer.remove(positionKey(event.block))
        if (kind != null) event.player.sendMessage("§e已移除${kind.title}的位置记录。")
    }

    // 避免爆炸/活塞使已登记的功能方块与位置标记分离。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf { typeAt(it) != null }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().removeIf { typeAt(it) != null }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { typeAt(it) != null }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { typeAt(it) != null }) event.isCancelled = true
    }
}
