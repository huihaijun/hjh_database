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
        inv.setItem(13, createNodeItem(Material.FLINT, "§e锋锐之金", "进攻属性 +1", data.goldPoints, "gold", playerJob, remainingPoints))
        inv.setItem(29, createNodeItem(Material.MELON_SEEDS, "§a生机之木", "最大生命 +6", data.woodPoints, "wood", playerJob, remainingPoints))
        inv.setItem(33, createNodeItem(Material.WHEAT_SEEDS, "§9灵动之水", "冷却缩减 +2%", data.waterPoints, "water", playerJob, remainingPoints))
        val fireStat = if (playerJob == 2 || playerJob == 3) "法穿率 +4%" else "暴击率 +4%"
        inv.setItem(48, createNodeItem(Material.CHARCOAL, "§c暴烈之火", fireStat, data.firePoints, "fire", playerJob, remainingPoints))
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
                lore.add("§f造成伤害时获得§b1§f层§b锋芒§f")
                lore.add("§b[锋芒]§f:每层增加§b5%§f进攻属性,最多§b4§f层")
                lore.add("§f叠满后将不再叠层和刷新持续时间,§b10§f秒后层数消失")
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
                lore.add("§f直接伤害命中时附加§b[余烬]§f")
                lore.add("§b[余烬]§f:在§b3§f秒内造成共计§b150%§f进攻属性伤害")
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
                        lore.add("§6[战] §e[金·精进] [金戈] §f冷却:§b12§f秒")
                        lore.add("§f普通攻击命中第§b3§f次怪物时,向前方§b8§f格距离")
                        lore.add("§f斩出一道伤害为§b200%§f近战强度的剑气,贯穿路径上的怪物")
                    }
                    "wood" -> {
                        lore.add("§6[战] §a[木·精进] [生根] §f冷却:§b15§f秒")
                        lore.add("§f受到伤害后生成§b根脉§f持续生长§b8§f秒,生长中心会跟随自身移动")
                        lore.add("§f旧中心的十字根脉会在§b2§f秒后消失,并持续减速路径内怪物")
                        lore.add("§f每秒回复路径上队友§b4§f点生命,不同根脉的恢复不会叠加")
                    }
                    "water" -> {
                        lore.add("§6[战] §9[水·精进] [潮返] §f冷却:§b12§f秒")
                        lore.add("§f攻击/受到伤害后,在§b2§f秒内依次向周围§b10§f格扩散三道水波")
                        lore.add("§f前两道水波:造成§b50%最大生命§f的伤害,对怪物造成轻微减速§b3§f秒")
                        lore.add("§f第三道水波:造成§b75%最大生命§f的伤害,并小幅击飞怪物")
                    }
                    "fire" -> {
                        lore.add("§6[战] §c[火·精进] [炎斩] §f冷却:§b15§f秒")
                        lore.add("§f普通攻击造成伤害后,对目标叠加一层§b[炎斩]§f持续§b5§f秒")
                        lore.add("§f叠满三层时,移去所有标记并造成必定§b暴击§f的§b穿甲§f伤害")
                        lore.add("§f基础为§b250%近战强度§f并附带§b最大生命8%§f斩杀伤害")
                        lore.add("§f斩杀伤害最高§b150§f点,然后进入冷却")
                    }
                    "earth" -> {
                        lore.add("§6[战] §6[土·精进] [崩山] §f冷却:§b15§f秒")
                        lore.add("§f每受到4次伤害时,将引发崩裂,眩晕周围§b10§f格怪物§b0.8§f秒")
                        lore.add("§f同时降低他们§b50%§f护甲持续§b8§f秒")
                        lore.add("§f并获得§b最大生命50%§f的护盾(最多§b40§f点)持续§b15§f秒")
                    }
                }
            }
            1 -> { // 弓箭手
                when (element) {
                    "gold" -> {
                        lore.add("§6[弓] §e[金·精进] [鸣镝] §f冷却:§b10§f秒")
                        lore.add("§f箭矢命中目标后,若与其距离超过§b10§f格")
                        lore.add("§f则对其追加一段§b200%箭矢强度§f的伤害")
                    }
                    "wood" -> {
                        lore.add("§6[弓] §a[木·精进] [藤矢] §f冷却:§b5§f秒")
                        lore.add("§f箭矢命中目标后,对目标附带§b[藤蔓]§f标记持续§b8§f秒")
                        lore.add("§b[藤蔓]§f:减速50%,命中带有此标记的目标后,会为自己恢复§b2§f点生命")
                    }
                    "water" -> {
                        lore.add("§6[弓] §9[水·精进] [水月] §f冷却:§b8§f秒")
                        lore.add("§f箭矢命中目标后,复制一根§b水箭§f")
                        lore.add("§f对其附近§b5§f格的最近一名怪物造成同等伤害")
                        lore.add("§b水箭§f命中后,令自己移速增加§b30%§f持续§b3§f秒")
                        lore.add("§f若其身旁没有怪物,则§b水箭§f会攻击原目标")
                    }
                    "fire" -> {
                        lore.add("§6[弓] §c[火·精进] [爆燃] §f冷却:§b15§f秒")
                        lore.add("§f箭矢命中怪物后,以其为中心引发一次半径为§b5§f格的烈火爆炸")
                        lore.add("§f造成§b150%箭矢强度§f的伤害")
                    }
                    "earth" -> {
                        lore.add("§6[弓] §6[土·精进] [岩钉] §f冷却:§b15§f秒")
                        lore.add("§f箭矢命中目标后,对目标施加§b[定身]§f持续§b1.5§f秒")
                        lore.add("§b[定身]§f结束时岩钉会爆裂,削弱目标§b50%§f护甲持续§b5§f秒")
                    }
                }
            }
            2 -> { // 术士
                when (element) {
                    "gold" -> {
                        lore.add("§6[术] §e[金·精进] [金印] §f冷却:§b15§f秒")
                        lore.add("§f元素阵法命中目标§b造成伤害§f后,为目标打下§b[金印]§f标记,持续§b3§f秒")
                        lore.add("§b[金印]§f:持续时间结束后爆炸,对目标§b2§f格范围内怪物造成持有印记期间")
                        lore.add("§f受到的伤害总数的§b50%§f的伤害,最多§b200§f点")
                    }
                    "wood" -> {
                        lore.add("§6[术] §a[木·精进] [溯生] §f冷却:§b20§f秒")
                        lore.add("§f受到伤害后,在§b1.5§f秒内回复§b70%此伤害值§f的生命")
                    }
                    "water" -> {
                        lore.add("§6[术] §9[水·精进] [回潮] §f冷却:§b15§f秒")
                        lore.add("§f释放元素阵法后,从身旁§b5§f格的怪物内吸取灵力")
                        lore.add("§f每1只怪物会为自己额外回复§b2§f点灵力,至多§b20§f点")
                        lore.add("§f并获得§b5§f秒速度提升,每1只怪物延长§b2§f秒,至多§b20§f秒")
                    }
                    "fire" -> {
                        lore.add("§6[术] §c[火·精进] [阵焚] §f冷却:§b20§f秒")
                        lore.add("§f元素阵法造成伤害后,在目标脚底生成焚阵,在§b1§f秒后喷发")
                        lore.add("§b击飞§f§b2§f格范围内怪物并造成§b250%阵法强度§f的伤害")
                    }
                    "earth" -> {
                        lore.add("§6[术] §6[土·精进] [镇石] §f冷却:§b20§f秒")
                        lore.add("§f受到伤害后,额外受到§b20%此次伤害值§f的§b真实伤害§f")
                        lore.add("§f(若此伤害让你§c致死§f,则改为体力§c降为1§f)")
                        lore.add("§f然后获得等同于§b200%此次伤害值§f的§b护盾§f,持续§b15§f秒")
                        lore.add("§f护盾消失时,对周围§b4§f格怪物造成§b0.5§f秒晕眩效果")
                    }
                }
            }
            3 -> { // 医师
                when (element) {
                    "gold" -> {
                        lore.add("§6[医] §e[金·精进] [金针] §f冷却:§b15§f秒")
                        lore.add("§f释放医术后,向§b15格§f内离你最近的§b2§f只怪物飞出金针")
                        lore.add("§f造成§b150%阵法强度§f伤害并定身其§b1§f秒")
                    }
                    "wood" -> {
                        lore.add("§6[医] §a[木·精进] [花语] §f冷却:§b15§f秒")
                        lore.add("§f使用医术回复生命后,将§b50%此次治愈值§f传递给身旁最近的一名队友")
                        lore.add("§f最多以此法传递§b三§f次且无法传递给相同玩家")
                    }
                    "water" -> {
                        lore.add("§6[医] §9[水·精进] [净流] §f冷却:§b20§f秒")
                        lore.add("§f释放医术后进入§b[净流]§f状态,持续§b8§f秒")
                        lore.add("§b[净流]§f:医旗回复灵力速度增加§b50%§f,且回复量增加§b20%§f")
                    }
                    "fire" -> {
                        lore.add("§6[医] §c[火·精进] [灼脉] §f冷却:§b20§f秒")
                        lore.add("§f释放医术造成伤害后,令目标进入§b[经脉受损]§f持续§b5§f秒")
                        lore.add("§b[经脉受损]§f:移速降低§c50%§f,伤害降低§b30%§f")
                    }
                    "earth" -> {
                        lore.add("§6[医] §6[土·精进] [厚土] §f冷却:§b20§f秒")
                        lore.add("§f释放医术治愈友军时,会为这些友军叠加§b100%阵法强度§f的护盾持续§b5§f秒")
                        lore.add("§f护盾消失时,会令其获得持续§b5§f秒的生命回复效果")
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
