package com.hjh_database.market

import com.hjh_database.Hjh_database
import com.hjh_database.warehouse.utils.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletionException
import kotlin.math.max

class GlobalMarketManager(private val plugin: Hjh_database) : Listener {
    companion object {
        private const val MINIMUM_LEVEL = 15
        private const val MARKET_X = 701
        private const val MARKET_Y = 59
        private const val MARKET_Z = -493
        private const val SELL_INPUT_SLOT = 10
        private const val PAGE_SIZE = 45
    }

    private val repository = MarketRepository(plugin)
    private val listings = linkedMapOf<Long, MarketListing>()
    private val purchaseHistory = ConcurrentHashMap<UUID, ArrayDeque<MarketPurchaseHistory>>()
    private val awaitingPrice = ConcurrentHashMap<UUID, SellDraft>()
    private val pendingSellers = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingListingOperations = ConcurrentHashMap.newKeySet<Long>()
    private val rarityKey = NamespacedKey(plugin, "rarity")
    private val ignoreRefreshKey = NamespacedKey(plugin, "hjh_ignore_refresh")
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    @Volatile private var marketReady = false
    @Volatile private var marketLoadFailed = false

    init {
        plugin.databaseManager.submitDatabaseOperation {
            repository.initialize()
            repository.loadAll()
        }.whenComplete { loaded, error ->
            runOnMain {
                if (error != null) {
                    marketLoadFailed = true
                    plugin.logger.severe("全球市场异步加载失败: ${rootCause(error).message}")
                    return@runOnMain
                }
                loaded.forEach { listings[it.id] = it }
                marketReady = true
                plugin.logger.info("全球市场已异步加载 ${listings.size} 件商品。")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMarketBannerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.RED_WALL_BANNER) return
        if (block.world.name != "world") return
        if (block.x != MARKET_X || block.y != MARKET_Y || block.z != MARKET_Z) return

        event.isCancelled = true
        if (!canUseMarket(event.player)) return
        openMainMenu(event.player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        when (val holder = event.view.topInventory.holder) {
            is MarketMainHolder -> {
                event.isCancelled = true
                if (!isTopSlot(event)) return
                when (event.rawSlot) {
                    19 -> openMarket(player, 0, MarketSort.TIME, false)
                    21 -> if (pendingSellers.contains(player.uniqueId)) {
                        player.sendMessage("§e[全球市场] §7上一件商品正在写入数据库，请稍候。")
                    } else {
                        openSellMenu(player, SellDraft())
                    }
                    23 -> openOwnListings(player, 0)
                    25 -> openHistory(player)
                }
            }

            is MarketListHolder -> {
                event.isCancelled = true
                if (!isTopSlot(event)) return
                val listingId = holder.slotToListing[event.rawSlot]
                if (listingId != null) {
                    openPurchaseConfirmation(player, listingId, holder)
                    return
                }
                when (event.rawSlot) {
                    45 -> if (holder.page > 0) openMarket(player, holder.page - 1, holder.sort, holder.ascending)
                    47 -> changeSort(player, holder, MarketSort.NAME)
                    48 -> changeSort(player, holder, MarketSort.TIME)
                    49 -> changeSort(player, holder, MarketSort.RARITY)
                    50 -> openMainMenu(player)
                    53 -> if ((holder.page + 1) * PAGE_SIZE < sortedListings(holder.sort, holder.ascending).size) {
                        openMarket(player, holder.page + 1, holder.sort, holder.ascending)
                    }
                }
            }

            is PurchaseHolder -> {
                event.isCancelled = true
                if (!isTopSlot(event)) return
                when (event.rawSlot) {
                    15 -> purchase(player, holder.listingId)
                    17 -> openMarket(player, holder.returnPage, holder.returnSort, holder.returnAscending)
                }
            }

            is SellHolder -> handleSellClick(event, player, holder)

            is OwnListingsHolder -> {
                event.isCancelled = true
                if (!isTopSlot(event)) return
                val listingId = holder.slotToListing[event.rawSlot]
                if (listingId != null) {
                    if (event.click == ClickType.DOUBLE_CLICK) {
                        cancelListing(player, listingId, holder.page)
                    } else {
                        player.sendMessage("§e[全球市场] §7双击该商品可取消上架并收回货物。")
                    }
                    return
                }
                when (event.rawSlot) {
                    45 -> if (holder.page > 0) openOwnListings(player, holder.page - 1)
                    49 -> openMainMenu(player)
                    53 -> if ((holder.page + 1) * PAGE_SIZE < ownListings(player.uniqueId).size) {
                        openOwnListings(player, holder.page + 1)
                    }
                }
            }

            is HistoryHolder -> {
                event.isCancelled = true
                if (isTopSlot(event) && event.rawSlot == 22) openMainMenu(player)
            }
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val holder = event.view.topInventory.holder
        if (holder is SellHolder) {
            if (event.rawSlots.any { it < event.view.topInventory.size && it != SELL_INPUT_SLOT }) {
                event.isCancelled = true
            }
        } else if (holder is MarketHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? SellHolder ?: return
        if (holder.transitioning || holder.finalized) return
        val player = event.player as? Player ?: return
        returnSellInput(player, holder)
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPriceChat(event: AsyncPlayerChatEvent) {
        val uuid = event.player.uniqueId
        val draft = awaitingPrice.remove(uuid) ?: return
        event.isCancelled = true
        val input = event.message.trim()

        if (input.equals("取消", true) || input.equals("cancel", true)) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                giveItem(event.player, draft.item)
                event.player.sendMessage("§e[全球市场] §7已取消售卖。")
                if (event.player.isOnline && canUseMarket(event.player, false)) openMainMenu(event.player)
            })
            return
        }

        val price = input.toDoubleOrNull()
        if (price == null || !price.isFinite() || price < 0.0) {
            awaitingPrice[uuid] = draft
            event.player.sendMessage("§c[全球市场] 请输入大于等于 0 的有效数字，或输入“取消”。")
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!event.player.isOnline) {
                giveItem(event.player, draft.item)
                return@Runnable
            }
            draft.price = price
            openSellMenu(event.player, draft)
            event.player.sendMessage("§a[全球市场] 售价已设置为 ${formatMoney(price)} 铜钱。")
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        awaitingPrice.remove(event.player.uniqueId)?.let { giveItem(event.player, it.item) }
        purchaseHistory.remove(event.player.uniqueId)
    }

    fun shutdown() {
        plugin.server.onlinePlayers.forEach { player ->
            val holder = player.openInventory.topInventory.holder
            if (holder is SellHolder) {
                returnSellInput(player, holder)
                player.closeInventory()
            } else if (holder is MarketHolder) {
                player.closeInventory()
            }
        }
        awaitingPrice.forEach { (uuid, draft) ->
            plugin.server.getPlayer(uuid)?.let { giveItem(it, draft.item) }
        }
        awaitingPrice.clear()
        purchaseHistory.clear()
    }

    private fun canUseMarket(player: Player, sendMessage: Boolean = true): Boolean {
        if (!marketReady) {
            if (sendMessage) {
                player.sendMessage(if (marketLoadFailed) "§c[全球市场] 市场数据库加载失败，请联系管理员。" else "§e[全球市场] §7市场数据正在加载，请稍后再试。")
            }
            return false
        }
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            if (sendMessage) player.sendMessage("§e[全球市场] §7玩家数据库资料仍在加载，请稍后再试。")
            return false
        }
        if (data.lv < MINIMUM_LEVEL) {
            if (sendMessage) player.sendMessage("§c[全球市场] 角色等级达到 15 级后才可使用。你当前为 ${data.lv} 级。")
            return false
        }
        return true
    }

    private fun openMainMenu(player: Player) {
        if (!canUseMarket(player)) return
        val holder = MarketMainHolder()
        val inventory = Bukkit.createInventory(holder, 54, "§8全球市场")
        holder.menu = inventory
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE)
        inventory.setItem(19, button(Material.EMERALD, "§a进入市场", listOf("§7浏览所有玩家正在售卖的商品")))
        inventory.setItem(21, button(Material.CHEST, "§e售卖物品", listOf("§7单次上架一种物品，可包含多个数量")))

        val data = plugin.playerManager.getData(player.uniqueId)!!
        val head = ItemStack(Material.PLAYER_HEAD)
        (head.itemMeta as? SkullMeta)?.let { meta ->
            meta.owningPlayer = player
            meta.setDisplayName("§b个人信息")
            meta.lore = listOf(
                "§7玩家: §f${player.name}",
                "§7财产: §6${formatMoney(data.money)} §7铜钱",
                "",
                "§e点击查看自己已上架的物品"
            )
            head.itemMeta = meta
        }
        inventory.setItem(23, head)
        inventory.setItem(25, button(Material.WRITABLE_BOOK, "§d购买历史", listOf(
            "§7最多保留最近 9 条购买记录",
            "§c离开服务器后将清空"
        )))
        player.openInventory(inventory)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.7f, 1.1f)
    }

    private fun openMarket(player: Player, requestedPage: Int, sort: MarketSort, ascending: Boolean) {
        val sorted = sortedListings(sort, ascending)
        val maxPage = max(0, (sorted.size - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, maxPage)
        val holder = MarketListHolder(page, sort, ascending)
        val inventory = Bukkit.createInventory(holder, 54, "§8全球市场 · 第 ${page + 1} 页")
        holder.menu = inventory

        sorted.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { slot, listing ->
            holder.slotToListing[slot] = listing.id
            inventory.setItem(slot, displayListing(listing, own = listing.sellerUuid == player.uniqueId))
        }
        fillFunctionRow(inventory)
        inventory.setItem(45, navButton(Material.ARROW, "§e上一页", page > 0))
        inventory.setItem(47, sortButton("名称", MarketSort.NAME, holder))
        inventory.setItem(48, sortButton("上架时间", MarketSort.TIME, holder))
        inventory.setItem(49, sortButton("稀有度", MarketSort.RARITY, holder, "§7无稀有度的商品会排在最后"))
        inventory.setItem(50, button(Material.BARRIER, "§c返回主菜单"))
        inventory.setItem(53, navButton(Material.ARROW, "§e下一页", (page + 1) * PAGE_SIZE < sorted.size))
        player.openInventory(inventory)
    }

    private fun openPurchaseConfirmation(player: Player, listingId: Long, source: MarketListHolder) {
        val listing = listings[listingId]
        if (listing == null) {
            player.sendMessage("§c[全球市场] 该商品已不在售，请刷新市场。")
            openMarket(player, source.page, source.sort, source.ascending)
            return
        }
        if (listing.sellerUuid == player.uniqueId) {
            player.sendMessage("§e[全球市场] §7不能购买自己的商品，请在“个人信息”中双击收回。")
            return
        }
        val holder = PurchaseHolder(listingId, source.page, source.sort, source.ascending)
        val inventory = Bukkit.createInventory(holder, 27, "§8确认购买")
        holder.menu = inventory
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE)
        inventory.setItem(11, displayListing(listing, own = false))
        inventory.setItem(15, button(Material.LIME_CONCRETE, "§a确认购买", listOf("§7将支付 §6${formatMoney(listing.price)} §7铜钱")))
        inventory.setItem(17, button(Material.RED_CONCRETE, "§c取消"))
        player.openInventory(inventory)
    }

    private fun openSellMenu(player: Player, draft: SellDraft) {
        if (!canUseMarket(player)) {
            giveItem(player, draft.item)
            return
        }
        val holder = SellHolder(draft)
        val inventory = Bukkit.createInventory(holder, 27, "§8售卖物品")
        holder.menu = inventory
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE)
        inventory.setItem(SELL_INPUT_SLOT, draft.item?.clone())
        inventory.setItem(13, button(
            Material.NAME_TAG,
            "§e设置价格",
            listOf(
                "§7点击后在聊天框输入数字",
                "§7允许范围: §f0 至无上限",
                "§7当前价格: §6${draft.price?.let(::formatMoney) ?: "未设置"}"
            )
        ))
        inventory.setItem(15, button(Material.LIME_CONCRETE, "§a确认上架", listOf("§7物品与价格设置完成后点击")))
        inventory.setItem(17, button(Material.RED_CONCRETE, "§c取消上架", listOf("§7物品会退回背包")))
        player.openInventory(inventory)
    }

    private fun handleSellClick(event: InventoryClickEvent, player: Player, holder: SellHolder) {
        val topSize = event.view.topInventory.size
        if (event.rawSlot < 0) return

        if (event.rawSlot >= topSize) {
            if (event.isShiftClick) {
                event.isCancelled = true
                if (holder.menu.getItem(SELL_INPUT_SLOT) != null) {
                    player.sendMessage("§c[全球市场] 单次只允许放入一种物品。")
                    return
                }
                val clicked = event.currentItem ?: return
                holder.menu.setItem(SELL_INPUT_SLOT, clicked.clone())
                event.clickedInventory?.setItem(event.slot, null)
            }
            return
        }

        when (event.rawSlot) {
            SELL_INPUT_SLOT -> Unit
            13 -> {
                event.isCancelled = true
                val item = holder.menu.getItem(SELL_INPUT_SLOT)
                if (item == null || item.type.isAir) {
                    player.sendMessage("§c[全球市场] 请先在左侧空格放入要售卖的物品。")
                    return
                }
                holder.transitioning = true
                holder.menu.setItem(SELL_INPUT_SLOT, null)
                holder.draft.item = item.clone()
                awaitingPrice[player.uniqueId] = holder.draft
                player.closeInventory()
                player.sendMessage("§e[全球市场] 请在聊天框输入售价（0 至无上限），输入“取消”放弃售卖。")
            }
            15 -> {
                event.isCancelled = true
                confirmListing(player, holder)
            }
            17 -> {
                event.isCancelled = true
                holder.finalized = true
                val item = holder.menu.getItem(SELL_INPUT_SLOT)
                holder.menu.setItem(SELL_INPUT_SLOT, null)
                giveItem(player, item)
                player.sendMessage("§e[全球市场] §7已取消上架。")
                openMainMenu(player)
            }
            else -> event.isCancelled = true
        }
    }

    private fun confirmListing(player: Player, holder: SellHolder) {
        val item = holder.menu.getItem(SELL_INPUT_SLOT)
        val price = holder.draft.price
        if (item == null || item.type.isAir) {
            player.sendMessage("§c[全球市场] 请放入要售卖的物品。")
            return
        }
        if (price == null) {
            player.sendMessage("§c[全球市场] 请先设置售价。")
            return
        }

        val storedItem = item.clone()
        val listedAt = System.currentTimeMillis()
        val rarity = findRarity(storedItem)
        val serialized = try {
            // ItemStack 只在主线程序列化；真正的 SQLite I/O 会在数据库队列中执行。
            ItemSerializer.itemsToBase64(arrayOf(storedItem))
        } catch (ex: Exception) {
            plugin.logger.severe("${player.name} 序列化市场商品失败: ${ex.message}")
            player.sendMessage("§c[全球市场] 无法读取该物品，物品仍在售卖界面中。")
            return
        }

        if (!pendingSellers.add(player.uniqueId)) {
            player.sendMessage("§e[全球市场] §7已有商品正在上架，请稍候。")
            return
        }
        holder.finalized = true
        holder.menu.setItem(SELL_INPUT_SLOT, null)
        player.closeInventory()
        player.sendMessage("§e[全球市场] §7正在写入数据库，请稍候……")

        val sellerUuid = player.uniqueId
        val sellerName = player.name
        plugin.databaseManager.submitDatabaseOperation {
            repository.insert(sellerUuid, sellerName, serialized, price, listedAt, rarity)
        }.whenComplete { id, error ->
            runOnMain {
                pendingSellers.remove(sellerUuid)
                val online = plugin.server.getPlayer(sellerUuid)
                if (error != null) {
                    plugin.logger.severe("$sellerName 上架市场商品失败: ${rootCause(error).message}")
                    if (online != null) {
                        giveItem(online, storedItem)
                        online.sendMessage("§c[全球市场] 上架失败，物品已退回背包。")
                    } else {
                        plugin.logger.severe("$sellerName 已离线，上架失败的物品无法即时退回。")
                    }
                    return@runOnMain
                }
                listings[id] = MarketListing(id, sellerUuid, sellerName, storedItem, price, listedAt, rarity)
                if (online != null) {
                    online.sendMessage("§a[全球市场] 商品已上架，售价 ${formatMoney(price)} 铜钱。")
                    online.playSound(online.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f)
                    openOwnListings(online, 0)
                }
            }
        }
    }

    private fun purchase(player: Player, listingId: Long) {
        val listing = listings[listingId]
        if (listing == null) {
            player.sendMessage("§c[全球市场] 该商品刚刚已被购买或下架。")
            openMarket(player, 0, MarketSort.TIME, false)
            return
        }
        if (listing.sellerUuid == player.uniqueId) {
            player.sendMessage("§c[全球市场] 不能购买自己的商品。")
            return
        }
        val buyerData = plugin.playerManager.getData(player.uniqueId)
        if (buyerData == null) {
            player.sendMessage("§e[全球市场] §7玩家数据库资料仍在加载，请稍后再试。")
            return
        }
        if (buyerData.money + 1.0e-9 < listing.price) {
            player.sendMessage("§c[全球市场] 财产不足，需要 ${formatMoney(listing.price)} 铜钱。")
            return
        }

        if (!pendingListingOperations.add(listing.id)) {
            player.sendMessage("§e[全球市场] §7该商品正在交易中，请稍候。")
            return
        }

        val newBalance = max(0.0, buyerData.money - listing.price)
        val sellerData = plugin.playerManager.getData(listing.sellerUuid)
        // 主线程只改内存余额；数据库转账稍后在单线程队列中原子提交。
        buyerData.money = newBalance
        if (sellerData != null) sellerData.money += listing.price
        listings.remove(listing.id)
        player.closeInventory()
        player.sendMessage("§e[全球市场] §7正在处理交易，请稍候……")

        val buyerUuid = player.uniqueId
        val buyerName = player.name
        plugin.databaseManager.submitDatabaseOperation {
            repository.completePurchase(listing.id, buyerUuid, listing.sellerUuid, newBalance, listing.price)
        }.whenComplete { completed, error ->
            runOnMain {
                pendingListingOperations.remove(listing.id)
                val onlineBuyer = plugin.server.getPlayer(buyerUuid)
                if (error != null || completed != true) {
                    // 用增量回滚，避免覆盖交易等待期间发生的其他 money 变化。
                    buyerData.money += listing.price
                    if (sellerData != null) sellerData.money = max(0.0, sellerData.money - listing.price)
                    listings[listing.id] = listing
                    plugin.databaseManager.savePlayerAsync(buyerData)
                    if (sellerData != null) plugin.databaseManager.savePlayerAsync(sellerData)
                    if (error != null) {
                        plugin.logger.severe("$buyerName 购买市场商品 #${listing.id} 失败: ${rootCause(error).message}")
                    }
                    onlineBuyer?.sendMessage("§c[全球市场] 交易失败，财产已退回，请稍后重试。")
                    if (onlineBuyer != null) openMarket(onlineBuyer, 0, MarketSort.TIME, false)
                    return@runOnMain
                }

                if (onlineBuyer != null) {
                    giveItem(onlineBuyer, listing.item.clone())
                    addHistory(buyerUuid, MarketPurchaseHistory(
                        listing.item.clone(), listing.sellerName, listing.price, System.currentTimeMillis()
                    ))
                    onlineBuyer.sendMessage("§a[全球市场] 购买成功，支付 ${formatMoney(listing.price)} 铜钱。")
                    onlineBuyer.playSound(onlineBuyer.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f)
                    openMarket(onlineBuyer, 0, MarketSort.TIME, false)
                }
                plugin.server.getPlayer(listing.sellerUuid)?.sendMessage(
                    "§a[全球市场] $buyerName 购买了你的商品，${formatMoney(listing.price)} 铜钱已到账。"
                )
            }
        }
    }

    private fun openOwnListings(player: Player, requestedPage: Int) {
        val own = ownListings(player.uniqueId)
        val maxPage = max(0, (own.size - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, maxPage)
        val holder = OwnListingsHolder(page)
        val inventory = Bukkit.createInventory(holder, 54, "§8我的上架商品 · 第 ${page + 1} 页")
        holder.menu = inventory
        own.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { slot, listing ->
            holder.slotToListing[slot] = listing.id
            val shown = displayListing(listing, own = true)
            val meta = shown.itemMeta
            if (meta != null) {
                val lore = meta.lore?.toMutableList() ?: mutableListOf()
                lore += "§c双击取消上架并收回货物"
                meta.lore = lore
                shown.itemMeta = meta
            }
            inventory.setItem(slot, shown)
        }
        fillFunctionRow(inventory)
        inventory.setItem(45, navButton(Material.ARROW, "§e上一页", page > 0))
        inventory.setItem(49, button(Material.BARRIER, "§c返回主菜单"))
        inventory.setItem(53, navButton(Material.ARROW, "§e下一页", (page + 1) * PAGE_SIZE < own.size))
        player.openInventory(inventory)
    }

    private fun cancelListing(player: Player, listingId: Long, currentPage: Int) {
        val listing = listings[listingId]
        if (listing == null || listing.sellerUuid != player.uniqueId) {
            player.sendMessage("§c[全球市场] 该商品已不在售。")
            openOwnListings(player, currentPage)
            return
        }
        if (!pendingListingOperations.add(listingId)) {
            player.sendMessage("§e[全球市场] §7该商品正在处理中，请稍候。")
            return
        }
        listings.remove(listingId)
        player.closeInventory()
        player.sendMessage("§e[全球市场] §7正在取消上架，请稍候……")
        val sellerUuid = player.uniqueId
        val sellerName = player.name
        plugin.databaseManager.submitDatabaseOperation {
            repository.delete(listingId)
        }.whenComplete { deleted, error ->
            runOnMain {
                pendingListingOperations.remove(listingId)
                val online = plugin.server.getPlayer(sellerUuid)
                if (error != null || deleted != true) {
                    listings[listingId] = listing
                    if (error != null) {
                        plugin.logger.severe("$sellerName 取消市场商品 #$listingId 失败: ${rootCause(error).message}")
                    }
                    online?.sendMessage("§c[全球市场] 取消上架失败，请稍后重试。")
                    if (online != null) openOwnListings(online, currentPage)
                    return@runOnMain
                }
                if (online != null) {
                    giveItem(online, listing.item.clone())
                    online.sendMessage("§a[全球市场] 已取消上架，货物已退回。")
                    online.playSound(online.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f)
                    openOwnListings(online, currentPage)
                }
            }
        }
    }

    private fun openHistory(player: Player) {
        val holder = HistoryHolder()
        val inventory = Bukkit.createInventory(holder, 27, "§8购买历史（离线清空）")
        holder.menu = inventory
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE)
        val history = purchaseHistory[player.uniqueId]?.toList().orEmpty()
        history.take(9).forEachIndexed { index, entry ->
            val item = entry.item.clone()
            val meta = item.itemMeta
            if (meta != null) {
                val lore = meta.lore?.toMutableList() ?: mutableListOf()
                lore += ""
                lore += "§7卖家: §f${entry.sellerName}"
                lore += "§7购买时间: §f${formatTime(entry.purchasedAt)}"
                lore += "§6购买价: ${formatMoney(entry.price)} 铜钱"
                meta.lore = lore
                meta.persistentDataContainer.set(ignoreRefreshKey, PersistentDataType.INTEGER, 1)
                item.itemMeta = meta
            }
            inventory.setItem(9 + index, item)
        }
        inventory.setItem(4, button(Material.WRITABLE_BOOK, "§d最近购买记录", listOf(
            "§7最多保留 9 条",
            "§c离开服务器后将清空"
        )))
        inventory.setItem(22, button(Material.BARRIER, "§c返回主菜单"))
        player.openInventory(inventory)
    }

    private fun displayListing(listing: MarketListing, own: Boolean): ItemStack {
        val item = listing.item.clone()
        val meta = item.itemMeta ?: return item
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        lore += ""
        lore += "§7卖家: §f${listing.sellerName}${if (own) " §b(你)" else ""}"
        lore += "§7上架时间: §f${formatTime(listing.listedAt)}"
        lore += "§6售价: ${formatMoney(listing.price)} 铜钱"
        meta.lore = lore
        // 只标记 GUI 展示用克隆；数据库原物品和成交/下架后返还的物品不会携带该标记。
        meta.persistentDataContainer.set(ignoreRefreshKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    private fun sortedListings(sort: MarketSort, ascending: Boolean): List<MarketListing> {
        val direction = if (ascending) 1 else -1
        return listings.values.sortedWith { first, second ->
            val compared = when (sort) {
                MarketSort.NAME -> itemName(first.item).compareTo(itemName(second.item), ignoreCase = true)
                MarketSort.TIME -> first.listedAt.compareTo(second.listedAt)
                MarketSort.RARITY -> when {
                    first.rarity == null && second.rarity == null -> 0
                    first.rarity == null -> 1
                    second.rarity == null -> -1
                    else -> first.rarity.compareTo(second.rarity)
                }
            }
            if (sort == MarketSort.RARITY && (first.rarity == null || second.rarity == null)) {
                if (compared != 0) compared else first.id.compareTo(second.id)
            } else {
                val directed = compared * direction
                if (directed != 0) directed else first.id.compareTo(second.id)
            }
        }
    }

    private fun ownListings(uuid: UUID): List<MarketListing> =
        listings.values.filter { it.sellerUuid == uuid }.sortedByDescending { it.listedAt }

    private fun changeSort(player: Player, holder: MarketListHolder, selected: MarketSort) {
        val ascending = if (holder.sort == selected) !holder.ascending else selected == MarketSort.NAME
        openMarket(player, 0, selected, ascending)
    }

    private fun sortButton(label: String, sort: MarketSort, holder: MarketListHolder, extra: String? = null): ItemStack {
        val selected = holder.sort == sort
        val lore = mutableListOf("§7点击按$label 排序")
        if (selected) lore += "§a当前: ${if (holder.ascending) "升序" else "降序"}"
        if (extra != null) lore += extra
        return button(if (selected) Material.LIME_DYE else Material.GRAY_DYE, "§e按$label 排序", lore)
    }

    private fun findRarity(item: ItemStack): Int? {
        item.itemMeta?.persistentDataContainer?.get(rarityKey, PersistentDataType.INTEGER)?.let { return it }
        val rarityLine = item.itemMeta?.lore?.firstOrNull {
            ChatColor.stripColor(it)?.contains("稀有度") == true
        } ?: return null
        val stars = ChatColor.stripColor(rarityLine)?.count { it == '★' } ?: 0
        return stars.takeIf { it > 0 }
    }

    private fun itemName(item: ItemStack): String =
        ChatColor.stripColor(item.itemMeta?.displayName)?.takeIf { it.isNotBlank() }
            ?: item.type.name

    private fun addHistory(uuid: UUID, entry: MarketPurchaseHistory) {
        val history = purchaseHistory.computeIfAbsent(uuid) { ArrayDeque() }
        synchronized(history) {
            history.addFirst(entry)
            while (history.size > 9) history.removeLast()
        }
    }

    private fun returnSellInput(player: Player, holder: SellHolder) {
        if (holder.finalized) return
        holder.finalized = true
        val item = holder.menu.getItem(SELL_INPUT_SLOT)
        holder.menu.setItem(SELL_INPUT_SLOT, null)
        giveItem(player, item)
    }

    private fun giveItem(player: Player, item: ItemStack?) {
        if (item == null || item.type.isAir) return
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
        }
        player.updateInventory()
    }

    private fun isTopSlot(event: InventoryClickEvent): Boolean =
        event.rawSlot in 0 until event.view.topInventory.size

    private fun button(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }

    private fun navButton(material: Material, name: String, enabled: Boolean): ItemStack =
        button(if (enabled) material else Material.GRAY_DYE, if (enabled) name else "§8无可用页面")

    private fun fill(inventory: Inventory, material: Material) {
        val filler = button(material, " ")
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
    }

    private fun fillFunctionRow(inventory: Inventory) {
        val filler = button(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (slot in 45..53) inventory.setItem(slot, filler)
    }

    private fun runOnMain(block: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }

    private fun formatTime(epochMillis: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMillis))

    private fun formatMoney(value: Double): String = when {
        !value.isFinite() -> "0"
        value % 1.0 == 0.0 && value <= Long.MAX_VALUE.toDouble() -> String.format(Locale.ROOT, "%,d", value.toLong())
        else -> String.format(Locale.ROOT, "%,.2f", value)
    }
}

private enum class MarketSort { NAME, TIME, RARITY }

private data class SellDraft(var item: ItemStack? = null, var price: Double? = null)

private sealed interface MarketHolder : InventoryHolder

private class MarketMainHolder : MarketHolder {
    lateinit var menu: Inventory
    override fun getInventory(): Inventory = menu
}

private class MarketListHolder(val page: Int, val sort: MarketSort, val ascending: Boolean) : MarketHolder {
    lateinit var menu: Inventory
    val slotToListing = mutableMapOf<Int, Long>()
    override fun getInventory(): Inventory = menu
}

private class PurchaseHolder(
    val listingId: Long,
    val returnPage: Int,
    val returnSort: MarketSort,
    val returnAscending: Boolean
) : MarketHolder {
    lateinit var menu: Inventory
    override fun getInventory(): Inventory = menu
}

private class SellHolder(val draft: SellDraft) : MarketHolder {
    lateinit var menu: Inventory
    var transitioning = false
    var finalized = false
    override fun getInventory(): Inventory = menu
}

private class OwnListingsHolder(val page: Int) : MarketHolder {
    lateinit var menu: Inventory
    val slotToListing = mutableMapOf<Int, Long>()
    override fun getInventory(): Inventory = menu
}

private class HistoryHolder : MarketHolder {
    lateinit var menu: Inventory
    override fun getInventory(): Inventory = menu
}
