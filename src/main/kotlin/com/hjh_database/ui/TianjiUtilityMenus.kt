package com.hjh_database.ui

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.chest.GoldenChestConfig
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class DustbinMenuHolder : InventoryHolder {
    lateinit var backingInventory: Inventory
    var settled: Boolean = false

    override fun getInventory(): Inventory = backingInventory
}

class ItemShowcaseMenuHolder : InventoryHolder {
    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory = backingInventory
}

class DungeonStatisticsMenuHolder(val page: Int, val totalPages: Int) : InventoryHolder {
    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory = backingInventory
}

object TianjiUtilityMenus {
    private data class DungeonStatisticsGroup(
        val displayName: String,
        val configs: List<GoldenChestConfig>
    )

    private const val DUSTBIN_TITLE = "§8归尘匣"
    private const val SHOWCASE_TITLE = "§8世尘镜"
    const val DUSTBIN_BUTTON_SLOT = 48
    const val SHOWCASE_BUTTON_SLOT = 50
    const val DUSTBIN_CONFIRM_SLOT = 49
    const val DUSTBIN_STORAGE_SIZE = 45
    const val DUNGEON_STATS_PREVIOUS_SLOT = 47
    const val DUNGEON_STATS_BACK_SLOT = 49
    const val DUNGEON_STATS_PAGE_SLOT = 50
    const val DUNGEON_STATS_NEXT_SLOT = 51

    private const val DUNGEON_STATS_TITLE_PREFIX = "§8秘境开箱记录"
    private const val DUNGEONS_PER_PAGE = 8
    private val DUNGEON_STATS_CONTENT_SLOTS = intArrayOf(19, 21, 23, 25, 28, 30, 32, 34)
    private val EXCLUDED_SACRED_BEAST_DUNGEONS = setOf(
        "dragon_test",
        "zhuque_test",
        "baihu_test",
        "xuanwu_test"
    )
    private val DUNGEON_ICON_PALETTE = arrayOf(
        Material.AMETHYST_BLOCK,
        Material.CRYING_OBSIDIAN,
        Material.PRISMARINE_BRICKS,
        Material.NETHER_BRICKS,
        Material.MOSS_BLOCK,
        Material.CUT_SANDSTONE,
        Material.DEEPSLATE_BRICKS,
        Material.COPPER_BLOCK
    )

    fun openDustbin(player: Player) {
        val holder = DustbinMenuHolder()
        val inventory = Bukkit.createInventory(holder, 54, DUSTBIN_TITLE)
        holder.backingInventory = inventory

        val border = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in DUSTBIN_STORAGE_SIZE until inventory.size) {
            inventory.setItem(slot, border)
        }
        inventory.setItem(
            DUSTBIN_CONFIRM_SLOT,
            menuItem(
                Material.LIGHT_GRAY_DYE,
                "§c§l清除匣中物品",
                listOf("§7快速双击以永久清除", "§c此操作不可撤销")
            )
        )
        player.openInventory(inventory)
    }

    fun openItemShowcase(player: Player) {
        val holder = ItemShowcaseMenuHolder()
        val inventory = Bukkit.createInventory(holder, 36, SHOWCASE_TITLE)
        holder.backingInventory = inventory

        // 按原版背包的视觉顺序排列：三行背包在上，快捷栏在最下方。
        for (sourceSlot in 9..35) {
            player.inventory.getItem(sourceSlot)?.let {
                inventory.setItem(sourceSlot - 9, it.clone())
            }
        }
        for (sourceSlot in 0..8) {
            player.inventory.getItem(sourceSlot)?.let {
                inventory.setItem(27 + sourceSlot, it.clone())
            }
        }
        player.openInventory(inventory)
    }

    fun openDungeonStatistics(plugin: Hjh_database, player: Player, requestedPage: Int = 0) {
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            player.sendMessage("§c数据加载中，请稍后再试……")
            return
        }

        val groups = plugin.goldenChestManager.chestRegistry.values
            .asSequence()
            .filterNot { it.dungeonId in EXCLUDED_SACRED_BEAST_DUNGEONS }
            .groupBy { dungeonGroupName(it.displayName) }
            .map { (displayName, configs) ->
                DungeonStatisticsGroup(
                    displayName,
                    configs.sortedWith(compareBy<GoldenChestConfig>(
                        { difficultyOrder(it.displayName) },
                        { it.dungeonId }
                    ))
                )
            }
            .sortedBy { it.displayName }
            .toList()
        val totalPages = ((groups.size + DUNGEONS_PER_PAGE - 1) / DUNGEONS_PER_PAGE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val holder = DungeonStatisticsMenuHolder(page, totalPages)
        val inventory = Bukkit.createInventory(
            holder,
            54,
            "$DUNGEON_STATS_TITLE_PREFIX §7(${page + 1}/$totalPages)"
        )
        holder.backingInventory = inventory

        val border = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 0..8) inventory.setItem(slot, border)
        for (slot in 45..53) inventory.setItem(slot, border)

        val startIndex = page * DUNGEONS_PER_PAGE
        groups.drop(startIndex).take(DUNGEONS_PER_PAGE).forEachIndexed { index, group ->
            inventory.setItem(
                DUNGEON_STATS_CONTENT_SLOTS[index],
                createDungeonStatisticsIcon(plugin, data, group, startIndex + index)
            )
        }

        inventory.setItem(
            DUNGEON_STATS_PREVIOUS_SLOT,
            menuItem(
                if (page > 0) Material.ARROW else Material.GRAY_DYE,
                if (page > 0) "§e§l上一页" else "§8上一页",
                listOf(if (page > 0) "§7点击查看第 ${page} 页" else "§8已经是第一页")
            )
        )
        inventory.setItem(
            DUNGEON_STATS_BACK_SLOT,
            menuItem(Material.OAK_DOOR, "§c§l返回天机令", listOf("§7点击返回主菜单"))
        )
        inventory.setItem(
            DUNGEON_STATS_PAGE_SLOT,
            menuItem(Material.PAPER, "§f第 §e${page + 1} §f/ §e$totalPages §f页", listOf("§7每页最多展示 8 个秘境"))
        )
        inventory.setItem(
            DUNGEON_STATS_NEXT_SLOT,
            menuItem(
                if (page + 1 < totalPages) Material.ARROW else Material.GRAY_DYE,
                if (page + 1 < totalPages) "§e§l下一页" else "§8下一页",
                listOf(if (page + 1 < totalPages) "§7点击查看第 ${page + 2} 页" else "§8已经是最后一页")
            )
        )

        player.openInventory(inventory)
    }

    private fun createDungeonStatisticsIcon(
        plugin: Hjh_database,
        data: com.hjh_database.data.PlayerData,
        group: DungeonStatisticsGroup,
        displayIndex: Int
    ): ItemStack {
        val lore = mutableListOf<String>()
        group.configs.forEachIndexed { index, config ->
            val record = data.dungeonRecords[config.dungeonId]
            if (group.configs.size > 1) {
                lore += "§d§l【${difficultyName(config.displayName, group.displayName)}】"
            }
            lore += "§f通过次数：§a${record?.clears ?: 0}"
            lore += "§f已开箱次数：§e${record?.opens ?: 0}"
            lore += "§f可开箱次数：§b${record?.availableOpens ?: 0}"
            if (index != group.configs.lastIndex) lore += ""
        }

        // 同时具有保底和全服公告的奖励统一定义为“终极战利品”。同一秘境的
        // 多个难度合并展示，并按 oneTimeScopeId 兼容跨难度共享的获得记录。
        val ultimateLootScopes = linkedMapOf<String, MutableSet<String>>()
        for (config in group.configs) {
            val scopeId = plugin.goldenChestManager.oneTimeScopeId(config)
            for (loot in config.lootTable) {
                if (!plugin.goldenChestManager.isUltimateLoot(loot)) continue
                if (loot.requiredJob != null && loot.requiredJob != data.job) continue
                ultimateLootScopes.getOrPut(loot.resourceId) { linkedSetOf() }.add(scopeId)
            }
        }

        val obtainedUltimateLoot = ultimateLootScopes.mapValues { (resourceId, scopeIds) ->
            val relatedRecordIds = plugin.goldenChestManager.chestRegistry.values.asSequence()
                .filter { plugin.goldenChestManager.oneTimeScopeId(it) in scopeIds }
                .map { it.dungeonId }
                .plus(scopeIds.asSequence())
                .distinct()
            relatedRecordIds.any { recordId ->
                (data.dungeonRecords[recordId]?.dropCounts?.getOrDefault(resourceId, 0) ?: 0) > 0
            }
        }
        val obtainedUltimateCount = obtainedUltimateLoot.count { it.value }
        val ultimateTotal = ultimateLootScopes.size
        lore += ""
        lore += "§7━━━━━━━━━━━━━━━━"
        if (ultimateTotal > 0) {
            lore += "§6§l终极战利品"
            lore += "§f收集进度：§d$obtainedUltimateCount§7/§d$ultimateTotal"
            for (resourceId in ultimateLootScopes.keys) {
                val obtained = obtainedUltimateLoot[resourceId] == true
                val marker = if (obtained) "§a✔" else "§c✘"
                val status = if (obtained) "§a已获得" else "§c未获得"
                lore += "$marker ${resourceDisplayName(plugin, resourceId)} §7- $status"
            }
        } else {
            lore += "§6终极战利品：§7该秘境暂无"
        }

        return menuItem(
            dungeonIcon(group, displayIndex),
            "§d§l${group.displayName}",
            lore
        )
    }

    private fun dungeonIcon(group: DungeonStatisticsGroup, displayIndex: Int): Material = when (group.displayName) {
        "鹊桥星愿" -> Material.AMETHYST_BLOCK
        else -> DUNGEON_ICON_PALETTE[displayIndex % DUNGEON_ICON_PALETTE.size]
    }

    private fun dungeonGroupName(displayName: String): String = displayName.substringBefore('·')

    private fun difficultyName(displayName: String, groupName: String): String =
        displayName.removePrefix(groupName).trimStart('·').ifBlank { "默认" }

    private fun resourceDisplayName(plugin: Hjh_database, resourceId: String): String {
        val meta = plugin.resourceManager.getItem(resourceId)?.itemMeta
        return if (meta?.hasDisplayName() == true) meta.displayName else "§f$resourceId"
    }

    private fun difficultyOrder(displayName: String): Int = when {
        displayName.contains("简单") -> 0
        displayName.contains("普通") -> 1
        displayName.contains("困难") -> 2
        else -> 1
    }

    /**
     * 插件卸载时监听器随即失效，因此必须在 onDisable 内主动结算并关闭这些菜单。
     * 世尘镜先清空克隆，归尘匣先返还玩家真实投入的物品。
     */
    fun closeOpenMenusForDisable() {
        for (player in Bukkit.getOnlinePlayers()) {
            val topInventory = player.openInventory.topInventory
            when (val holder = topInventory.holder) {
                is DustbinMenuHolder -> {
                    returnDustbinContents(player, topInventory, holder)
                    player.closeInventory()
                }
                is ItemShowcaseMenuHolder -> {
                    topInventory.clear()
                    player.closeInventory()
                }
                is DungeonStatisticsMenuHolder -> player.closeInventory()
            }
        }
    }

    /** 清理由旧版类加载器留下的菜单，保障安装本修复后的第一次重载。 */
    fun closeStaleMenusOnEnable() {
        for (player in Bukkit.getOnlinePlayers()) {
            val view = player.openInventory
            when (view.title) {
                SHOWCASE_TITLE -> {
                    view.topInventory.clear()
                    player.closeInventory()
                }
                DUSTBIN_TITLE -> {
                    returnDustbinSlots(player, view.topInventory)
                    player.closeInventory()
                }
                else -> if (view.title.startsWith(DUNGEON_STATS_TITLE_PREFIX)) {
                    view.topInventory.clear()
                    player.closeInventory()
                }
            }
        }
    }

    fun returnDustbinContents(player: Player, inventory: Inventory, holder: DustbinMenuHolder) {
        if (holder.settled) return
        holder.settled = true

        returnDustbinSlots(player, inventory)
    }

    private fun returnDustbinSlots(player: Player, inventory: Inventory) {
        val returnedItems = mutableListOf<ItemStack>()
        for (slot in 0 until DUSTBIN_STORAGE_SIZE) {
            val item = inventory.getItem(slot) ?: continue
            if (item.type.isAir) continue
            returnedItems.add(item.clone())
            inventory.setItem(slot, null)
        }
        if (returnedItems.isEmpty()) return

        val leftovers = player.inventory.addItem(*returnedItems.toTypedArray())
        for (item in leftovers.values) {
            player.world.dropItemNaturally(player.location, item)
        }
    }

    fun menuItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        if (lore.isNotEmpty()) meta?.lore = lore
        item.itemMeta = meta
        return item
    }
}
