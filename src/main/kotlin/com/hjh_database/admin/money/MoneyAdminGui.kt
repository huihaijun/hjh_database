package com.hjh_database.admin.money

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.io.File
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MoneyAdminGui(private val plugin: Hjh_database) : Listener {
    private val repository = MoneyAdminRepository(plugin.databaseManager)
    private val requestVersions = ConcurrentHashMap<UUID, Long>()
    private val configFile = File(plugin.dataFolder, CONFIG_NAME)
    @Volatile
    private var config = loadConfiguration()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun reload() {
        config = loadConfiguration()
    }

    fun open(player: Player, sort: MoneySort = MoneySort.MONEY_DESC, page: Int = 0) {
        val requestVersion = requestVersions.merge(player.uniqueId, 1L, Long::plus) ?: 1L
        val holder = MoneyAdminHolder(page.coerceAtLeast(0), sort, loading = true)
        val inventory = plugin.server.createInventory(holder, INVENTORY_SIZE, color(config.getString("gui.loading-title") ?: "&0正在读取财产数据..."))
        holder.backingInventory = inventory
        inventory.setItem(22, configuredItem("items.loading", Material.CLOCK, emptyMap()))
        player.openInventory(inventory)

        plugin.databaseManager.submitDatabaseOperation {
            repository.loadPage(page, PAGE_SIZE, sort)
        }.whenComplete { result, throwable ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline || requestVersions[player.uniqueId] != requestVersion) return@Runnable
                if (player.openInventory.topInventory.holder !== holder) return@Runnable
                if (throwable != null) {
                    plugin.logger.warning("读取管理员财产分页失败: ${throwable.cause?.message ?: throwable.message}")
                    player.sendMessage(color(config.getString("messages.load-failed") ?: "&c财产数据读取失败，请查看控制台。"))
                    player.closeInventory()
                    return@Runnable
                }
                showResult(player, result)
            })
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MoneyAdminHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.loading || event.rawSlot !in 0 until INVENTORY_SIZE) return

        when (event.rawSlot) {
            SLOT_REFRESH -> open(player, holder.sort, holder.page)
            SLOT_PREVIOUS -> if (holder.page > 0) open(player, holder.sort, holder.page - 1)
            SLOT_NAME_ASC -> open(player, MoneySort.NAME_ASC, 0)
            SLOT_NAME_DESC -> open(player, MoneySort.NAME_DESC, 0)
            SLOT_MONEY_ASC -> open(player, MoneySort.MONEY_ASC, 0)
            SLOT_MONEY_DESC -> open(player, MoneySort.MONEY_DESC, 0)
            SLOT_NEXT -> if (holder.page + 1 < holder.pageCount) open(player, holder.sort, holder.page + 1)
            SLOT_CLOSE -> player.closeInventory()
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MoneyAdminHolder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        requestVersions.remove(event.player.uniqueId)
    }

    private fun showResult(player: Player, page: MoneyAdminPage) {
        val replacements = mapOf(
            "page" to (page.page + 1).toString(),
            "pages" to page.pageCount.toString(),
            "total" to page.totalPlayers.toString(),
            "sort" to page.sort.displayName
        )
        val titleTemplate = config.getString("gui.title") ?: "&0玩家财产 &8- &7{page}/{pages}"
        val holder = MoneyAdminHolder(page.page, page.sort, loading = false).apply { pageCount = page.pageCount }
        val inventory = plugin.server.createInventory(holder, INVENTORY_SIZE, color(replace(titleTemplate, replacements)))
        holder.backingInventory = inventory

        val moneyFormat = DecimalFormat("#,##0.##")
        page.rows.forEachIndexed { slot, row ->
            val online = plugin.server.getPlayer(row.uuid)?.isOnline == true
            val rowValues = replacements + mapOf(
                "name" to row.playerName,
                "uuid" to row.uuid.toString(),
                "money" to moneyFormat.format(row.money),
                "online" to if (online) "&a在线" else "&7离线"
            )
            inventory.setItem(slot, configuredItem("items.player", Material.PLAYER_HEAD, rowValues))
        }

        inventory.setItem(SLOT_REFRESH, configuredItem("items.refresh", Material.SUNFLOWER, replacements))
        inventory.setItem(SLOT_PREVIOUS, configuredItem(if (page.page > 0) "items.previous" else "items.previous-disabled", if (page.page > 0) Material.ARROW else Material.GRAY_DYE, replacements))
        inventory.setItem(SLOT_NAME_ASC, configuredItem("items.name-asc", Material.NAME_TAG, replacements))
        inventory.setItem(SLOT_NAME_DESC, configuredItem("items.name-desc", Material.NAME_TAG, replacements))
        inventory.setItem(SLOT_INFO, configuredItem("items.info", Material.BOOK, replacements))
        inventory.setItem(SLOT_MONEY_ASC, configuredItem("items.money-asc", Material.GOLD_NUGGET, replacements))
        inventory.setItem(SLOT_MONEY_DESC, configuredItem("items.money-desc", Material.GOLD_INGOT, replacements))
        inventory.setItem(SLOT_NEXT, configuredItem(if (page.page + 1 < page.pageCount) "items.next" else "items.next-disabled", if (page.page + 1 < page.pageCount) Material.ARROW else Material.GRAY_DYE, replacements))
        inventory.setItem(SLOT_CLOSE, configuredItem("items.close", Material.BARRIER, replacements))
        player.openInventory(inventory)
    }

    private fun configuredItem(path: String, fallback: Material, replacements: Map<String, String>): ItemStack {
        val material = Material.matchMaterial(config.getString("$path.material").orEmpty()) ?: fallback
        return ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                val name = config.getString("$path.name") ?: path.substringAfterLast('.')
                setDisplayName(color(replace(name, replacements)))
                lore = config.getStringList("$path.lore").map { color(replace(it, replacements)) }
            }
        }
    }

    private fun loadConfiguration(): YamlConfiguration {
        if (!configFile.exists()) {
            plugin.saveResource(CONFIG_NAME, false)
        }
        return YamlConfiguration.loadConfiguration(configFile)
    }

    private fun replace(text: String, values: Map<String, String>): String {
        var result = text
        values.forEach { (key, value) -> result = result.replace("{$key}", value) }
        return result
    }

    @Suppress("DEPRECATION")
    private fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private class MoneyAdminHolder(
        val page: Int,
        val sort: MoneySort,
        val loading: Boolean
    ) : InventoryHolder {
        lateinit var backingInventory: Inventory
        var pageCount: Int = 1
        override fun getInventory(): Inventory = backingInventory
    }

    companion object {
        private const val CONFIG_NAME = "admin_money.yml"
        private const val INVENTORY_SIZE = 54
        private const val PAGE_SIZE = 45
        private const val SLOT_REFRESH = 45
        private const val SLOT_PREVIOUS = 46
        private const val SLOT_NAME_ASC = 47
        private const val SLOT_NAME_DESC = 48
        private const val SLOT_INFO = 49
        private const val SLOT_MONEY_ASC = 50
        private const val SLOT_MONEY_DESC = 51
        private const val SLOT_NEXT = 52
        private const val SLOT_CLOSE = 53
    }
}
