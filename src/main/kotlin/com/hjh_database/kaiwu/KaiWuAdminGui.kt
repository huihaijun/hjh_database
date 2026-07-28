package com.hjh_database.kaiwu

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.UUID

class KaiWuAdminGui(
    private val plugin: Hjh_database,
    private val manager: KaiWuManager
) : Listener {
    companion object {
        private const val TITLE_PREFIX = "§8开物资源点 §7("
        private const val PAGE_SIZE = 45
    }

    private val nodeKey = NamespacedKey(plugin, "kaiwu_admin_node")
    private val pageKey = NamespacedKey(plugin, "kaiwu_admin_page")
    private val sortKey = NamespacedKey(plugin, "kaiwu_admin_sort")
    private val rarityKey = NamespacedKey(plugin, "rarity")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val ignoreRefreshKey = NamespacedKey(plugin, "hjh_ignore_refresh")
    private val sortStates: MutableMap<UUID, SortState> = HashMap()
    private val editor = KaiWuEditor(plugin, manager)

    fun open(player: Player, requestedPage: Int = 0) {
        val sortState = sortStates.getOrPut(player.uniqueId) { SortState(SortMode.NAME, true) }
        val nodes = sortedNodes(sortState)
        val pageCount = ((nodes.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val inventory = plugin.server.createInventory(null, 54, "$TITLE_PREFIX${page + 1}/$pageCount§7)")

        nodes.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { slot, (key, node) ->
            inventory.setItem(slot, createNodeIcon(key, node))
        }

        val filler = item(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 45..53) inventory.setItem(slot, filler)
        if (page > 0) inventory.setItem(45, pageButton(Material.ARROW, "§e上一页", page - 1))
        inventory.setItem(46, sortButton(Material.NAME_TAG, SortMode.NAME, sortState))
        inventory.setItem(47, sortButton(Material.NETHER_STAR, SortMode.RARITY, sortState))
        inventory.setItem(49, item(Material.BOOK, "§6资源点总览", listOf(
            "§7共 §f${nodes.size} §7个资源点",
            "§7当前第 §f${page + 1}§7/§f$pageCount §7页",
            "§7当前排序: §f${sortState.mode.label} ${if (sortState.ascending) "§a升序" else "§c降序"}"
        )))
        inventory.setItem(51, sortButton(Material.CLOCK, SortMode.MINING_TIME, sortState))
        inventory.setItem(52, sortButton(Material.EXPERIENCE_BOTTLE, SortMode.REQUIRED_LEVEL, sortState))
        if (page + 1 < pageCount) inventory.setItem(53, pageButton(Material.ARROW, "§e下一页", page + 1))
        player.openInventory(inventory)
    }

    fun refreshOpenMenus() {
        for (player in plugin.server.onlinePlayers) {
            val title = player.openInventory.title
            if (!title.startsWith(TITLE_PREFIX)) continue
            val page = title
                .substringAfter(TITLE_PREFIX, "1")
                .substringBefore("/")
                .toIntOrNull()
                ?.minus(1)
                ?: 0
            open(player, page)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (!event.view.title.startsWith(TITLE_PREFIX)) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        val clicked = event.currentItem ?: return
        val meta = clicked.itemMeta ?: return

        meta.persistentDataContainer.get(pageKey, PersistentDataType.INTEGER)?.let {
            open(player, it)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
            return
        }

        meta.persistentDataContainer.get(sortKey, PersistentDataType.STRING)?.let { rawMode ->
            val mode = runCatching { SortMode.valueOf(rawMode) }.getOrNull() ?: return
            val ascending = when {
                event.isLeftClick -> true
                event.isRightClick -> false
                else -> return
            }
            sortStates[player.uniqueId] = SortState(mode, ascending)
            open(player, 0)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, if (ascending) 1.3f else 0.9f)
            return
        }

        val key = meta.persistentDataContainer.get(nodeKey, PersistentDataType.STRING) ?: return
        val node = manager.getNode(key) ?: run {
            player.sendMessage("§c该资源点已不存在，列表已刷新。")
            open(player)
            return
        }

        when {
            event.isShiftClick && event.isRightClick -> {
                manager.removeNode(player, key)
                player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.8f)
                open(player)
            }
            event.isLeftClick -> editor.openEditor(player, key)
            event.isRightClick -> {
                val location = node.cachedLoc ?: return
                player.teleport(location.clone().add(0.5, 1.0, 0.5))
                player.sendMessage("§a已传送至资源点：§f${displayName(node)}")
                player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
            }
        }
    }

    private fun sortedNodes(sortState: SortState): List<Pair<String, KaiWuManager.NodeConfig>> {
        val direction = if (sortState.ascending) 1 else -1
        return manager.getAllNodes().sortedWith { first, second ->
            val compared = when (sortState.mode) {
                SortMode.NAME -> sortableName(first.second).compareTo(sortableName(second.second))
                SortMode.RARITY -> nodeRarity(first.second).compareTo(nodeRarity(second.second))
                SortMode.MINING_TIME -> first.second.timeSeconds.compareTo(second.second.timeSeconds)
                SortMode.REQUIRED_LEVEL -> first.second.reqLevel.compareTo(second.second.reqLevel)
            }
            val directed = compared * direction
            if (directed != 0) {
                directed
            } else {
                val byName = sortableName(first.second).compareTo(sortableName(second.second))
                if (byName != 0) byName else first.first.compareTo(second.first)
            }
        }
    }

    private fun createNodeIcon(key: String, node: KaiWuManager.NodeConfig): ItemStack {
        val sourceItem = node.drops.firstOrNull { it.type != Material.AIR }
        val drops = if (node.drops.isEmpty()) listOf("§8无掉落配置") else node.drops.map {
            val name = it.itemMeta?.takeIf { meta -> meta.hasDisplayName() }?.displayName ?: readableMaterial(it.type)
            "§7- §f$name §8(1-${it.amount.coerceAtLeast(1)})"
        }
        val lore = mutableListOf<String>()
        lore += "§7坐标: §f${node.worldName} ${node.x.toInt()}, ${node.y.toInt()}, ${node.z.toInt()}"
        lore += "§7可获得资源:"
        lore += drops
        lore += ""
        lore += "§7采集耗时: §f${format(node.timeSeconds)} 秒"
        lore += "§7精力消耗: §f${format(node.energyCost)}"
        lore += "§7开物经验: §f${node.exp}"
        lore += "§7需求等级: §f${node.reqLevel}"
        lore += "§7枯竭时间: §f${node.depletedSec} 秒"
        lore += "§7重生冷却: §f${node.cooldownSec} 秒"
        lore += ""
        lore += "§e左键 §7编辑  §b右键 §7传送"
        lore += "§c下蹲+右键 §7删除资源点及方块"

        // 克隆真实掉落物，保留 CustomModelData、药水颜色及其他贴图相关组件。
        val icon = sourceItem?.clone() ?: ItemStack(Material.CHEST)
        icon.amount = 1
        val meta = icon.itemMeta ?: return icon
        meta.setDisplayName("§6${displayName(node)}")
        meta.lore = lore
        meta.persistentDataContainer.set(nodeKey, PersistentDataType.STRING, key)
        // GUI 展示物保留 resource_id 时，防止 ResourceListener 打开界面后覆盖自定义 Lore 和点击标记。
        meta.persistentDataContainer.set(ignoreRefreshKey, PersistentDataType.INTEGER, 1)
        icon.itemMeta = meta
        return icon
    }

    private fun displayName(node: KaiWuManager.NodeConfig): String {
        val drop = node.drops.firstOrNull { it.type != Material.AIR }
        return drop?.itemMeta?.takeIf { it.hasDisplayName() }?.displayName
            ?: drop?.let { readableMaterial(it.type) }
            ?: "未配置资源 ${node.x.toInt()},${node.y.toInt()},${node.z.toInt()}"
    }

    private fun readableMaterial(material: Material): String = material.name
        .lowercase(Locale.ROOT)
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun sortableName(node: KaiWuManager.NodeConfig): String =
        ChatColor.stripColor(displayName(node))?.lowercase(Locale.ROOT) ?: ""

    private fun nodeRarity(node: KaiWuManager.NodeConfig): Int {
        val item = node.drops.firstOrNull { it.type != Material.AIR } ?: return 0
        val meta = item.itemMeta ?: return 0

        meta.persistentDataContainer.get(rarityKey, PersistentDataType.INTEGER)?.let { return it }
        val resourceId = meta.persistentDataContainer.get(resourceIdKey, PersistentDataType.STRING)
        if (resourceId != null) {
            plugin.resourceManager.getLocalResource(resourceId)?.rarity?.let { return it }
        }

        val rarityLine = meta.lore?.firstOrNull {
            ChatColor.stripColor(it)?.contains("稀有度") == true
        } ?: return 0
        return ChatColor.stripColor(rarityLine)?.count { it == '★' } ?: 0
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    private fun pageButton(material: Material, name: String, page: Int): ItemStack = item(material, name).also {
        val meta = it.itemMeta ?: return@also
        meta.persistentDataContainer.set(pageKey, PersistentDataType.INTEGER, page)
        it.itemMeta = meta
    }

    private fun sortButton(material: Material, mode: SortMode, state: SortState): ItemStack {
        val selected = state.mode == mode
        val lore = mutableListOf(
            "§e左键 §7升序排列",
            "§e右键 §7降序排列"
        )
        if (selected) lore += "§a当前: ${if (state.ascending) "升序" else "降序"}"
        return item(material, "${if (selected) "§a" else "§e"}按${mode.label}排序", lore).also {
            val meta = it.itemMeta ?: return@also
            meta.persistentDataContainer.set(sortKey, PersistentDataType.STRING, mode.name)
            it.itemMeta = meta
        }
    }

    private fun item(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val result = ItemStack(material)
        val meta = result.itemMeta
        meta?.setDisplayName(name)
        meta?.lore = lore
        result.itemMeta = meta
        return result
    }

    private data class SortState(
        val mode: SortMode,
        val ascending: Boolean
    )

    private enum class SortMode(val label: String) {
        NAME("名称"),
        RARITY("稀有度"),
        MINING_TIME("采集时间"),
        REQUIRED_LEVEL("需求开物术等级")
    }
}
