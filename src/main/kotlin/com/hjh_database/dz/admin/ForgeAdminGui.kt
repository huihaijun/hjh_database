package com.hjh_database.dz.admin

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ForgeAdminGui(private val plugin: Hjh_database) : Listener {
    private val repository = ForgeAdminRepository(plugin.databaseManager)
    private val requestVersions = ConcurrentHashMap<UUID, Long>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, sort: ForgeSort = ForgeSort.LEVEL_DESC, page: Int = 0) {
        val version = requestVersions.merge(player.uniqueId, 1L, Long::plus) ?: 1L
        val loadingHolder = ForgeHolder(page.coerceAtLeast(0), sort, true)
        val loading = plugin.server.createInventory(loadingHolder, SIZE, "§0正在读取锻造数据...")
        loadingHolder.backingInventory = loading
        loading.setItem(22, item(Material.CLOCK, "§e正在异步读取数据库", listOf("§7请稍候……")))
        player.openInventory(loading)

        plugin.databaseManager.submitDatabaseOperation {
            repository.loadPage(page, PAGE_SIZE, sort)
        }.whenComplete { result, throwable ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline || requestVersions[player.uniqueId] != version) return@Runnable
                if (player.openInventory.topInventory.holder !== loadingHolder) return@Runnable
                if (throwable != null) {
                    plugin.logger.warning("读取锻造信息分页失败: ${throwable.cause?.message ?: throwable.message}")
                    player.sendMessage("§c锻造信息读取失败，请查看控制台。")
                    player.closeInventory()
                    return@Runnable
                }
                show(player, result)
            })
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ForgeHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.loading || event.rawSlot !in 0 until SIZE) return
        when (event.rawSlot) {
            SLOT_PREVIOUS -> if (holder.page > 0) open(player, holder.sort, holder.page - 1)
            SLOT_NAME -> open(player, holder.sort.toggled(ForgeSortField.NAME), 0)
            SLOT_LEVEL -> open(player, holder.sort.toggled(ForgeSortField.LEVEL), 0)
            SLOT_EXP -> open(player, holder.sort.toggled(ForgeSortField.EXP), 0)
            SLOT_LICENSE -> open(player, holder.sort.toggled(ForgeSortField.LICENSE), 0)
            SLOT_REFRESH -> open(player, holder.sort, holder.page)
            SLOT_NEXT -> if (holder.page + 1 < holder.pageCount) open(player, holder.sort, holder.page + 1)
            SLOT_CLOSE -> player.closeInventory()
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ForgeHolder && event.rawSlots.any { it < SIZE }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        requestVersions.remove(event.player.uniqueId)
    }

    private fun show(player: Player, page: ForgeAdminPage) {
        val holder = ForgeHolder(page.page, page.sort, false).apply { pageCount = page.pageCount }
        val inventory = plugin.server.createInventory(
            holder,
            SIZE,
            "§0锻造信息 §8- §7${page.page + 1}/${page.pageCount}"
        )
        holder.backingInventory = inventory
        page.rows.forEachIndexed { slot, row ->
            val licenseName = plugin.dzLevelManager.getLicenseName(row.license)
            inventory.setItem(slot, item(
                Material.PLAYER_HEAD,
                "§e${row.playerName}",
                listOf(
                    "§7UUID: §8${row.uuid}",
                    "§7锻造等级: §f${row.level}",
                    "§7锻造经验: §f${row.exp}",
                    "§7锻造资质: §f$licenseName §8(${row.license})"
                )
            ))
        }
        inventory.setItem(SLOT_PREVIOUS, item(if (page.page > 0) Material.ARROW else Material.GRAY_DYE, "§a上一页"))
        inventory.setItem(SLOT_NAME, sortButton(Material.NAME_TAG, "玩家名", page.sort, ForgeSortField.NAME))
        inventory.setItem(SLOT_LEVEL, sortButton(Material.ANVIL, "锻造等级", page.sort, ForgeSortField.LEVEL))
        inventory.setItem(SLOT_EXP, sortButton(Material.EXPERIENCE_BOTTLE, "锻造经验", page.sort, ForgeSortField.EXP))
        inventory.setItem(SLOT_REFRESH, item(Material.SUNFLOWER, "§e刷新", listOf("§7共 ${page.totalPlayers} 名玩家", "§7当前排序: §f${page.sort.displayName}")))
        inventory.setItem(SLOT_LICENSE, sortButton(Material.WRITABLE_BOOK, "锻造资质", page.sort, ForgeSortField.LICENSE))
        inventory.setItem(SLOT_NEXT, item(if (page.page + 1 < page.pageCount) Material.ARROW else Material.GRAY_DYE, "§a下一页"))
        inventory.setItem(SLOT_CLOSE, item(Material.BARRIER, "§c关闭"))
        player.openInventory(inventory)
    }

    private fun sortButton(material: Material, label: String, current: ForgeSort, field: ForgeSortField): ItemStack {
        val active = when (field) {
            ForgeSortField.NAME -> current == ForgeSort.NAME_ASC || current == ForgeSort.NAME_DESC
            ForgeSortField.LEVEL -> current == ForgeSort.LEVEL_ASC || current == ForgeSort.LEVEL_DESC
            ForgeSortField.EXP -> current == ForgeSort.EXP_ASC || current == ForgeSort.EXP_DESC
            ForgeSortField.LICENSE -> current == ForgeSort.LICENSE_ASC || current == ForgeSort.LICENSE_DESC
        }
        return item(material, "§e按$label 排序", listOf(if (active) "§a当前: ${current.displayName}" else elseText(field)))
    }

    private fun elseText(field: ForgeSortField) = when (field) {
        ForgeSortField.NAME -> "§7点击切换为玩家名升序"
        ForgeSortField.LEVEL -> "§7点击切换为锻造等级降序"
        ForgeSortField.EXP -> "§7点击切换为锻造经验降序"
        ForgeSortField.LICENSE -> "§7点击切换为锻造资质降序"
    }

    private fun item(material: Material, name: String, loreLines: List<String> = emptyList()): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(name)
                lore = loreLines
            }
        }

    private class ForgeHolder(val page: Int, val sort: ForgeSort, val loading: Boolean) : InventoryHolder {
        lateinit var backingInventory: Inventory
        var pageCount: Int = 1
        override fun getInventory(): Inventory = backingInventory
    }

    companion object {
        private const val SIZE = 54
        private const val PAGE_SIZE = 45
        private const val SLOT_PREVIOUS = 45
        private const val SLOT_NAME = 46
        private const val SLOT_LEVEL = 47
        private const val SLOT_EXP = 48
        private const val SLOT_REFRESH = 49
        private const val SLOT_LICENSE = 50
        private const val SLOT_NEXT = 52
        private const val SLOT_CLOSE = 53
    }
}
