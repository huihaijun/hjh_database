package com.hjh_database.dz.listener

import com.hjh_database.Hjh_database
import com.hjh_database.dz.gui.AdminCategoryGui
import com.hjh_database.dz.gui.CategoryGui
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.TileState
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class StationListener(private val plugin: Hjh_database) : Listener {
    private val stationKey: NamespacedKey = NamespacedKey(plugin, "hjh_forge_station")

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        // 只处理主手交互，防止触发两次
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock
        if (block == null || block.type != Material.DISPENSER) return

        // 检查是否为锻造台 (检查 NBT)
        // Kotlin 写法：获取 state 并尝试强转为 TileState，失败则返回
        val tileState = block.state as? TileState ?: return

        if (!tileState.persistentDataContainer.has(stationKey, PersistentDataType.STRING)) return

        event.isCancelled = true // 阻止打开原版发射器
        val player = event.player
        val handItem = player.inventory.itemInMainHand

        // === 1. 管理员逻辑：手持木锄右键 ===
        if (player.isOp && handItem.type == Material.WOODEN_HOE) {
            player.sendMessage("${ChatColor.GREEN}[管理员] 已打开配方管理界面")
            AdminCategoryGui(plugin, player).open()
            return
        }

        // === 2. 普通玩家逻辑：打开锻造界面 ===
        // 注意：根据之前的 Hjh_database.kt，playerManager 已经是属性了
        val data = plugin.playerManager.getData(player.uniqueId)
        // Java: Integer job = (data != null) ? data.getJob() : null;
        // Kotlin: 安全调用 ?.
        val job: Int? = data?.job
        CategoryGui(plugin, player, job).open()
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type == Material.DISPENSER) {
            val meta = item.itemMeta
            // Kotlin 中 meta 可能为空，需要检查
            if (meta != null && meta.persistentDataContainer.has(stationKey, PersistentDataType.STRING)) {
                val block = event.blockPlaced
                // 检查放置后的方块状态
                val state = block.state
                if (state is TileState) {
                    val data = state.persistentDataContainer
                    data.set(stationKey, PersistentDataType.STRING, "true")
                    state.update()
                    event.player.sendMessage("${ChatColor.GREEN}成功放置锻造台 (发射器)！")
                }
            }
        }
    }
}