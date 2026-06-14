package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ElementCrystalGui(private val plugin: Hjh_database) : Listener {

    private val title = "§8元素结晶分配"
    private val crystalKey = NamespacedKey(plugin, "crystal_id")
    private val bindUuidKey = NamespacedKey(plugin, "element_bind_uuid")
    private val bindNameKey = NamespacedKey(plugin, "element_bind_name")

    private fun isElementCrystal(crystalId: String?): Boolean {
        return crystalId != null && crystalId.startsWith("yuansujiejing")
    }

    fun openGui(player: Player) {
        val inv = Bukkit.createInventory(null, 54, title)
        refreshGui(inv, player, null)
        player.openInventory(inv)
    }

    private fun refreshGui(inv: Inventory, player: Player, crystalItem: ItemStack?) {
        val data = plugin.elementCrystalManager.getData(player.uniqueId)

        var rarity = 0
        var isBoundToMe = false
        var isCrystal = false
        var isBoundToOther = false

        if (crystalItem != null && crystalItem.type != Material.AIR) {
            val meta = crystalItem.itemMeta
            if (meta != null && meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)
                if (isElementCrystal(cid)) {
                    isCrystal = true
                    val cData = plugin.playerManager.crystalManager.loadedCrystals[cid]
                    if (cData != null) {
                        rarity = cData.rarity
                        // === 【核心修复点】实时刷新中心格子里元素结晶的 Lore ===
                        val pData = plugin.playerManager.getPlayerData(player)
                        if (pData != null) {
                            val clonedItem = crystalItem.clone()
                            plugin.playerManager.crystalManager.updateCrystalLore(clonedItem, cData, pData, "accessory_0")
                            inv.setItem(31, clonedItem)
                            player.updateInventory()
                        }
                    }
                    if (meta.persistentDataContainer.has(bindUuidKey, PersistentDataType.STRING)) {
                        val boundUuid = meta.persistentDataContainer.get(bindUuidKey, PersistentDataType.STRING)
                        if (boundUuid == player.uniqueId.toString()) {
                            isBoundToMe = true
                        } else {
                            isBoundToOther = true
                        }
                    } else {
                        // 未绑定，视为可操作，将在后续操作中绑定
                        isBoundToMe = true
                    }
                }
            }
        }

        val totalPoints = data.getTotalPoints()
        val remainingPoints = if (isCrystal && isBoundToMe) (rarity - totalPoints).coerceAtLeast(0) else 0

        for (i in 0 until 54) {
            if (i == 31) continue // 结晶槽位跳过
            
            val item = ItemStack(Material.RED_STAINED_GLASS_PANE)
            val meta = item.itemMeta
            meta?.setDisplayName(" ")
            item.itemMeta = meta
            inv.setItem(i, item)
        }

        // Slot 0: 提示酿造台
        val infoItem = ItemStack(Material.BREWING_STAND)
        val infoMeta = infoItem.itemMeta
        infoMeta?.setDisplayName("§a元素分配提示")
        val infoLore = mutableListOf<String>()
        infoLore.add("§7当前放入的元素结晶稀有度决定了可用点数。")
        infoLore.add("§7每分配一点，将获得对应元素的属性加成。")
        if (isCrystal) {
            if (isBoundToOther) {
                infoLore.add("§c该结晶已绑定其他玩家，无法分配！")
            } else {
                infoLore.add("§e剩余分配点数: §a$remainingPoints")
                infoLore.add("§7已分配总点数: §f$totalPoints / $rarity")
                
                // === 【新增】显示启示状态 ===
                val statesList = mutableListOf<String>()
                if (data.goldPoints >= 2) statesList.add("§e[金元素·启示]")
                if (data.woodPoints >= 2) statesList.add("§a[木元素·启示]")
                if (data.waterPoints >= 2) statesList.add("§9[水元素·启示]")
                if (data.firePoints >= 2) statesList.add("§c[火元素·启示]")
                if (data.earthPoints >= 2) statesList.add("§6[土元素·启示]")
                if (statesList.isNotEmpty()) {
                    infoLore.add("§b当前状态: " + statesList.joinToString(" "))
                }
            }
        } else {
            infoLore.add("§c请在中间放入元素结晶以开始分配。")
        }
        infoMeta?.lore = infoLore
        infoItem.itemMeta = infoMeta
        inv.setItem(0, infoItem)

        // Slot 8: 重置地图
        val resetItem = ItemStack(Material.MAP)
        val resetMeta = resetItem.itemMeta
        resetMeta?.setDisplayName("§e重置分配点")
        resetMeta?.lore = listOf("§7双击以重置当前元素结晶的所有分配点数。")
        resetItem.itemMeta = resetMeta
        inv.setItem(8, resetItem)

        // 节点
        inv.setItem(13, createNodeItem(Material.FLINT, "§e锋锐之金", "进攻属性 +1.5", data.goldPoints))
        inv.setItem(29, createNodeItem(Material.MELON_SEEDS, "§a生机之木", "最大生命 +6", data.woodPoints))
        inv.setItem(33, createNodeItem(Material.WHEAT_SEEDS, "§9灵动之水", "冷却缩减 +2%", data.waterPoints))
        inv.setItem(48, createNodeItem(Material.CHARCOAL, "§c暴烈之火", "暴击率 +4%", data.firePoints))
        inv.setItem(50, createNodeItem(Material.PUMPKIN_SEEDS, "§6坚韧之土", "护甲 +6", data.earthPoints))
    }

    private fun createNodeItem(mat: Material, name: String, statDesc: String, currentPoints: Int): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        val lore = mutableListOf<String>()
        lore.add("§7每点属性: §f$statDesc")
        lore.add("§7当前已投入: §a$currentPoints 点")
        lore.add("")
        lore.add("§e点击投入 1 点")
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title != title) return
        val player = event.whoClicked as? Player ?: return

        // 防止快捷键等导致物品异常
        if (event.click == ClickType.SWAP_OFFHAND || event.click == ClickType.NUMBER_KEY) {
            event.isCancelled = true
            return
        }

        val inv = event.inventory
        val rawSlot = event.rawSlot
        val crystalItem = inv.getItem(31)

        // 点击的是玩家自己背包
        if (rawSlot >= 54) {
            // 如果玩家按 Shift 将物品移入
            if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) {
                event.isCancelled = true
                val clickedItem = event.currentItem ?: return
                if (clickedItem.type == Material.AIR) return

                val meta = clickedItem.itemMeta
                if (meta != null && meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                    val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)
                    if (isElementCrystal(cid)) {
                        if (inv.getItem(31) == null || inv.getItem(31)!!.type == Material.AIR) {
                            inv.setItem(31, clickedItem.clone())
                            event.currentItem!!.amount = 0
                            plugin.server.scheduler.runTask(plugin, Runnable {
                                bindCrystalIfNeeded(inv.getItem(31), player)
                                refreshGui(inv, player, inv.getItem(31))
                            })
                        }
                    }
                }
            }
            return
        }

        // 点击GUI内的物品
        if (rawSlot != 31) {
            event.isCancelled = true
        }

        if (rawSlot == 31) {
            // 延迟1tick刷新GUI，以获取最新的槽位物品状态
            plugin.server.scheduler.runTask(plugin, Runnable {
                bindCrystalIfNeeded(inv.getItem(31), player)
                refreshGui(inv, player, inv.getItem(31))
            })
            return
        }

        // 处理加点逻辑
        if (crystalItem == null || crystalItem.type == Material.AIR) return
        val meta = crystalItem.itemMeta ?: return
        val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)
        if (!isElementCrystal(cid)) return
        
        if (meta.persistentDataContainer.has(bindUuidKey, PersistentDataType.STRING)) {
            val boundUuid = meta.persistentDataContainer.get(bindUuidKey, PersistentDataType.STRING)
            if (boundUuid != player.uniqueId.toString()) {
                player.sendMessage("§c该结晶已绑定其他玩家！")
                return
            }
        }

        val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return
        val data = plugin.elementCrystalManager.getData(player.uniqueId)
        
        val rarity = cData.rarity
        val totalPoints = data.getTotalPoints()
        
        var pointsChanged = false

        when (rawSlot) {
            8 -> { // 重置
                if (event.click == ClickType.DOUBLE_CLICK || event.click == ClickType.LEFT) {
                    // 只要点击就重置
                    data.resetPoints()
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
                    player.sendMessage("§a已重置元素结晶分配点数。")
                    pointsChanged = true
                }
            }
            13 -> { if (totalPoints < rarity) { data.goldPoints++; pointsChanged = true } }
            29 -> { if (totalPoints < rarity) { data.woodPoints++; pointsChanged = true } }
            33 -> { if (totalPoints < rarity) { data.waterPoints++; pointsChanged = true } }
            48 -> { if (totalPoints < rarity) { data.firePoints++; pointsChanged = true } }
            50 -> { if (totalPoints < rarity) { data.earthPoints++; pointsChanged = true } }
        }

        if (pointsChanged) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                plugin.elementCrystalManager.savePlayerAsync(player)
            })
            refreshGui(inv, player, inv.getItem(31))
        }
    }

    private fun bindCrystalIfNeeded(item: ItemStack?, player: Player) {
        if (item == null || item.type == Material.AIR) return
        val meta = item.itemMeta ?: return
        if (!isElementCrystal(meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING))) return
        
        if (!meta.persistentDataContainer.has(bindUuidKey, PersistentDataType.STRING)) {
            meta.persistentDataContainer.set(bindUuidKey, PersistentDataType.STRING, player.uniqueId.toString())
            meta.persistentDataContainer.set(bindNameKey, PersistentDataType.STRING, player.name)
            
            val lore = meta.lore ?: mutableListOf()
            lore.add("§6已绑定玩家：§e${player.name}")
            meta.lore = lore
            item.itemMeta = meta
            player.sendMessage("§a该元素结晶已成功与你绑定！")
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.view.title == title) {
            val player = event.player as? Player ?: return
            val inv = event.inventory
            val crystalItem = inv.getItem(31)
            
            // 将槽位内的结晶退还给玩家
            if (crystalItem != null && crystalItem.type != Material.AIR) {
                val left = player.inventory.addItem(crystalItem)
                if (left.isNotEmpty()) {
                    for (item in left.values) {
                        player.world.dropItem(player.location, item)
                    }
                }
            }
            // 刷新属性
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.playerManager.updateStats(player)
                plugin.playerManager.crystalManager.refreshPlayerCrystals(player)
            })
        }
    }
}
