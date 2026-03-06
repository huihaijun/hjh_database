package com.hjh_database.ui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AccessoryManager(private val plugin: Hjh_database) : Listener {
    private val invKey = NamespacedKey(plugin, "player_accessory_inv")
    val INVENTORY_TITLE = "§8个人饰品栏"

    // 打开饰品栏
    fun openAccessoryMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 9, INVENTORY_TITLE)
        val savedBytes = player.persistentDataContainer.get(invKey, PersistentDataType.BYTE_ARRAY)

        // 反序列化读取玩家此前的饰品
        if (savedBytes != null) {
            try {
                BukkitObjectInputStream(ByteArrayInputStream(savedBytes)).use { ois ->
                    val size = ois.readInt()
                    for (i in 0 until size) {
                        inv.setItem(i, ois.readObject() as ItemStack?)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        // 只在饰品栏界面触发，保证性能
        if (event.view.title == INVENTORY_TITLE) {
            // 延迟 1 Tick 执行，等待物品在内存中真正掉落到新的格子里
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val topInv = event.view.topInventory
                plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, topInv)
            }, 1L)
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return

        // 兼容玩家右键滑动平摊物品的情况
        if (event.view.title == INVENTORY_TITLE) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val topInv = event.view.topInventory
                plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, topInv)
            }, 1L)
        }
    }

    // 监听关闭界面，自动保存物品到玩家 PDC
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (event.view.title == INVENTORY_TITLE) {
            val inv = event.inventory
            try {
                val bos = ByteArrayOutputStream()
                BukkitObjectOutputStream(bos).use { oos ->
                    oos.writeInt(inv.size)
                    for (i in 0 until inv.size) {
                        oos.writeObject(inv.getItem(i))
                    }
                }
                // 保存为 Byte Array
                player.persistentDataContainer.set(invKey, PersistentDataType.BYTE_ARRAY, bos.toByteArray())

                // 【重要】关闭饰品栏后触发一次玩家属性刷新
                // 假设你的 PlayerListener 中有个 public 的刷新方法或者直接调用 plugin.playerManager 刷新
                plugin.playerManager.updateStats(player)
                plugin.playerManager.crystalManager.refreshPlayerCrystals(player)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}