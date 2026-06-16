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
    private val clickCooldown = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

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
        infoLore.add("")
        infoLore.add("§7当某元素分配点数：")
        infoLore.add("§7达到§b2§7点，激活其§b[启示]§7技能效果；")
        infoLore.add("§7达到§b4§7点，激活其§b[精进]§7技能效果；")
        infoLore.add("§7达到§b6§7点，激活其§b[共鸣]§7技能效果")
        if (isCrystal) {
            if (isBoundToOther) {
                infoLore.add("§c该结晶已绑定其他玩家，无法分配！")
            } else {
                infoLore.add("§e剩余分配点数: §a$remainingPoints")
                infoLore.add("§7已分配总点数: §f$totalPoints / $rarity")

                
                // === 【新增】显示启示与精进状态 ===
                val statesList = mutableListOf<String>()
                if (data.goldPoints >= 2) statesList.add("§e[金·启示]")
                if (data.goldPoints >= 4) statesList.add("§e[金·精进]")
                if (data.woodPoints >= 2) statesList.add("§a[木·启示]")
                if (data.woodPoints >= 4) statesList.add("§a[木·精进]")
                if (data.waterPoints >= 2) statesList.add("§9[水·启示]")
                if (data.waterPoints >= 4) statesList.add("§9[水·精进]")
                if (data.firePoints >= 2) statesList.add("§c[火·启示]")
                if (data.firePoints >= 4) statesList.add("§c[火·精进]")
                if (data.earthPoints >= 2) statesList.add("§6[土·启示]")
                if (data.earthPoints >= 4) statesList.add("§6[土·精进]")
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

        // 节点 — 传入玩家职业与剩余点数用于显示不同精进文本和剩余可分配点数
        val pData = plugin.playerManager.getPlayerData(player)
        val playerJob = pData?.job ?: -1
        inv.setItem(13, createNodeItem(Material.FLINT, "§e锋锐之金", "进攻属性 +1.5", data.goldPoints, "gold", playerJob, remainingPoints))
        inv.setItem(29, createNodeItem(Material.MELON_SEEDS, "§a生机之木", "最大生命 +6", data.woodPoints, "wood", playerJob, remainingPoints))
        inv.setItem(33, createNodeItem(Material.WHEAT_SEEDS, "§9灵动之水", "冷却缩减 +2%", data.waterPoints, "water", playerJob, remainingPoints))
        inv.setItem(48, createNodeItem(Material.CHARCOAL, "§c暴烈之火", "暴击率 +4%", data.firePoints, "fire", playerJob, remainingPoints))
        inv.setItem(50, createNodeItem(Material.PUMPKIN_SEEDS, "§6坚韧之土", "护甲 +6", data.earthPoints, "earth", playerJob, remainingPoints))
    }

    private fun createNodeItem(mat: Material, name: String, statDesc: String, currentPoints: Int, element: String, job: Int, remainingPoints: Int): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        val lore = mutableListOf<String>()
        lore.add("§7每点属性: §f$statDesc")
        lore.add("§7当前已投入: §a$currentPoints 点")
        lore.add("§7剩余可分配点数: §a$remainingPoints")
        lore.add("")
        lore.add("§e点击投入 1 点")
        
        val qishiSep = if (currentPoints >= 2) "§8===========§2§n§l启示（已激活）§8============" else "§8===========§8§n§l启示（未激活）§8============"
        val jingjinSep = if (currentPoints >= 4) "§8===========§2§n§l精进（已激活）§8============" else "§8===========§8§n§l精进（未激活）§8============"
        val gongmingSep = if (currentPoints >= 6) "§8===========§2§n§l共鸣（已激活）§8============" else "§8===========§8§n§l共鸣（未激活）§8============"
        
        lore.add(qishiSep)
        when (element) {
            "gold" -> {
                lore.add("§e[金·启示] §f冷却:§c无冷却")
                lore.add("§f直接造成伤害时获得§b1§f层§b锋芒§f")
                lore.add("§b[锋芒]§f:每层增加§b5%§f进攻属性,最多§b3§f层")
                lore.add("§f叠满后将不再叠层和刷新持续时间,§b5§f秒后层数消失")
            }
            "wood" -> {
                lore.add("§a[木·启示] §f冷却:§b30§f秒")
                lore.add("§f生命低于§b50%§f时,在§b3§f秒内恢复§b20%§f最大生命")
            }
            "water" -> {
                lore.add("§9[水·启示] §f冷却:§b10§f秒")
                lore.add("§f释放武器技、医术或阵法后,返还该技能§b15%§f冷却")
            }
            "fire" -> {
                lore.add("§c[火·启示] §f冷却:§b6§f秒")
                lore.add("§f直接伤害命中时附加§b余烬§f")
                lore.add("§b余烬§f：在§b3§f秒内造成共计§b150%§f进攻属性伤害")
            }
            "earth" -> {
                lore.add("§6[土·启示] §f冷却:§b12§f秒")
                lore.add("§f受到伤害后获得§b10§f点护甲,持续§b6§f秒")
            }
        }
        lore.add(jingjinSep)
        when (job) {
            0 -> { // 战士
                when (element) {
                    "gold" -> {
                        lore.add("§6[战] §e[金·精进] [金戈] §f冷却:§b15§f秒")
                        lore.add("§f普通攻击命中第§b4§f次怪物时,向前方§b12§f格距离")
                        lore.add("§f斩出一道伤害为§b300%§f近战强度的剑气,贯穿路径上的怪物")
                    }
                    "wood" -> {
                        lore.add("§6[战] §a[木·精进] [生根] §f冷却:§b15§f秒")
                        lore.add("§f受到伤害后,向十字方向生长距离为§b8§f格的§b根脉§f持续§b8§f秒")
                        lore.add("§b[根脉]§f:持续减速路径范围的怪物,并每秒回复路径上队友§b4§f点生命")
                    }
                    "water" -> {
                        lore.add("§6[战] §9[水·精进] [潮返] §f冷却:§b12§f秒")
                        lore.add("§f攻击/受到伤害后,在§b2§f秒内依次向周围§b10§f格扩散三道水波")
                        lore.add("§f前两道水波:造成§b80%最大生命§f的伤害,对怪物造成轻微减速§b3§f秒")
                        lore.add("§f第三道水波:造成§b100%最大生命§f的伤害,并小幅击飞怪物")
                    }
                    "fire" -> {
                        lore.add("§6[战] §c[火·精进] [炎斩] §f冷却:§b15§f秒")
                        lore.add("§f普通攻击造成伤害后,对目标叠加一层§b[炎斩]§f持续§b5§f秒")
                        lore.add("§f叠满三层时,移去所有标记并对其造成§b250%近战强度§f的§b穿甲§f伤害")
                        lore.add("§f并附带其§b最大生命8%§f的斩杀伤害,然后进入冷却")
                    }
                    "earth" -> {
                        lore.add("§6[战] §6[土·精进] [崩山] §f冷却:§b15§f秒")
                        lore.add("§f每受到4次伤害时,将引发崩裂,眩晕周围§b10§f格怪物§b0.8§f秒")
                        lore.add("§f同时降低他们§b50%§f护甲持续§b8§f秒")
                        lore.add("§f并获得§b最大生命50%§f的护盾(最多§b40§f点)持续§b15§f秒")
                    }
                }
            }
            else -> lore.add("§7(尚未添加)")
        }
        lore.add(gongmingSep)
        lore.add("§7(尚未添加)")
        
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

        val allocationSlots = setOf(13, 29, 33, 48, 50)
        if (rawSlot in allocationSlots) {
            val now = System.currentTimeMillis()
            val lastClick = clickCooldown[player.uniqueId] ?: 0L
            if (now - lastClick < 200L) {
                return
            }
            clickCooldown[player.uniqueId] = now
        }

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
