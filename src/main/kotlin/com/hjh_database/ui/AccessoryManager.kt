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
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AccessoryManager(private val plugin: Hjh_database) : Listener {
    private val invKey = NamespacedKey(plugin, "player_accessory_inv")
    val INVENTORY_TITLE = "§8个人饰品栏"

    // 鎵撳紑楗板搧鏍?
    fun openAccessoryMenu(player: Player) {

        val inv = Bukkit.createInventory(null, 9, INVENTORY_TITLE)
        val savedBytes = player.persistentDataContainer.get(invKey, PersistentDataType.BYTE_ARRAY)

        // 鍙嶅簭鍒楀寲璇诲彇鐜╁姝ゅ墠鐨勯グ鍝?
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
        if (event.view.title == INVENTORY_TITLE) {
            // 銆愪慨澶?銆戝睆钄藉壇鎵?鎸塅閿?鍜屾暟瀛楅敭(鎸?-9)蹇嵎浜ゆ崲锛岄槻姝㈠埛鐗╁搧鍜屼簩娆¤Е鍙態ug
            if (event.click == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND ||
                event.click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                event.isCancelled = true
                return
            }
            val clickType = event.click
            if (clickType == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT || clickType == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) {
                val item = event.currentItem
                if (item != null) {
                    val meta = item.itemMeta
                    val crystalKey = NamespacedKey(plugin, "crystal_id")
                    val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
                    if (baihuArtifact != null) {
                        val slotKey = "accessory_${event.rawSlot}"
                        if (!plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)) {
                            event.isCancelled = true
                            return
                        }
                        val isExtract = (clickType == org.bukkit.event.inventory.ClickType.SHIFT_LEFT)
                        if (plugin.accessorySkillManager.routeAccessoryClick(player, item, isExtract, plugin.baihuDzManager.toCrystalData(baihuArtifact))) {
                            plugin.baihuDzManager.consumeDurability(player, item, baihuArtifact)
                            event.isCancelled = true
                            return
                        }
                    }
                    if (meta != null && meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                        val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)
                        val cData = plugin.playerManager.crystalManager.loadedCrystals[cid]

                        // 銆愪慨鏀广€戝垽鏂綋鍓嶇偣鍑荤殑妲戒綅锛屾槸鍚﹀湪璇ラグ鍝佺殑婵€娲诲垪琛ㄩ噷
                        val slotKey = "accessory_${event.rawSlot}"
                        if (cData != null && cData.activations.containsKey(slotKey)) {

                            val pData = plugin.playerManager.getPlayerData(player)
                            if (pData != null) {
                                if (!cData.isActivated(pData)) {
                                    player.sendMessage("§c⚠ 该饰品未激活（等级不足或职业不符），无法使用饰品技能！")
                                    event.isCancelled = true
                                    return
                                }
                            }

                            // 璺敱鎺ョ
                            val isExtract = (clickType == org.bukkit.event.inventory.ClickType.SHIFT_LEFT)
                            if (plugin.accessorySkillManager.routeAccessoryClick(player, item, isExtract, cData)) {
                                event.isCancelled = true
                                return
                            }
                        }
                    }
                }
            }

            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val topInv = event.view.topInventory
                plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, topInv)
            }, 1L)
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return

        // 鍏煎鐜╁鍙抽敭婊戝姩骞虫憡鐗╁搧鐨勬儏鍐?
        if (event.view.title == INVENTORY_TITLE) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val topInv = event.view.topInventory
                plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, topInv)
            }, 1L)
        }
    }

    // 鐩戝惉鍏抽棴鐣岄潰锛岃嚜鍔ㄤ繚瀛樼墿鍝佸埌鐜╁ PDC
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
                // 淇濆瓨涓?Byte Array
                player.persistentDataContainer.set(invKey, PersistentDataType.BYTE_ARRAY, bos.toByteArray())

                // 銆愰噸瑕併€戝叧闂グ鍝佹爮鍚庤Е鍙戜竴娆＄帺瀹跺睘鎬у埛鏂?
                // 鍋囪浣犵殑 PlayerListener 涓湁涓?public 鐨勫埛鏂版柟娉曟垨鑰呯洿鎺ヨ皟鐢?plugin.playerManager 鍒锋柊
                plugin.playerManager.updateStats(player)
                plugin.playerManager.crystalManager.refreshPlayerCrystals(player)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    // ==========================================
    // 鍚庡彴璇诲啓楗板搧鏁版嵁锛屼緵鎶€鑳?濡傜琚?鍦ㄤ笉鎵撳紑鐣岄潰鏃惰皟鐢?
    // ==========================================
    fun getAccessoryContents(player: Player): Array<ItemStack?>? {
        val savedBytes = player.persistentDataContainer.get(invKey, PersistentDataType.BYTE_ARRAY) ?: return null
        return try {
            BukkitObjectInputStream(ByteArrayInputStream(savedBytes)).use { ois ->
                val size = ois.readInt()
                val array = arrayOfNulls<ItemStack>(size)
                for (i in 0 until size) {
                    array[i] = ois.readObject() as? ItemStack
                }
                array
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveAccessoryContents(player: Player, contents: Array<ItemStack?>) {
        try {
            val bos = ByteArrayOutputStream()
            BukkitObjectOutputStream(bos).use { oos ->
                oos.writeInt(contents.size)
                for (item in contents) {
                    oos.writeObject(item)
                }
            }
            player.persistentDataContainer.set(invKey, PersistentDataType.BYTE_ARRAY, bos.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAccessoryContents(player: Player) {
        player.persistentDataContainer.remove(invKey)
    }
}

