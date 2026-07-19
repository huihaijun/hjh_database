package com.hjh_database.npc.gui

import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.CustomTrade
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** NPC 管理员编辑器：每页左右两栏，共 10 个交易项。 */
@Suppress("DEPRECATION")
class NpcAdminGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val templateId: String,
    private val entityUuid: UUID?
) : InventoryHolder, Listener {

    private data class TradeSlots(val input1: Int, val input2: Int, val arrow: Int, val result: Int)

    companion object {
        private const val INVENTORY_SIZE = 54
        private const val TRADE_AREA_SIZE = 45
        private const val TRADES_PER_PAGE = 10
        private const val CYCLE_INTERVAL_NANOS = 150_000_000L

        private val PROFESSIONS by lazy { Registry.VILLAGER_PROFESSION.toList() }
        private val VILLAGER_TYPES by lazy { Registry.VILLAGER_TYPE.toList() }
        private val TRADE_SLOTS = buildList {
            for (row in 0 until 5) {
                val start = row * 9
                add(TradeSlots(start, start + 1, start + 2, start + 3))
                add(TradeSlots(start + 5, start + 6, start + 7, start + 8))
            }
        }
        private val EDITABLE_SLOTS = TRADE_SLOTS
            .flatMapTo(HashSet()) { listOf(it.input1, it.input2, it.result) }
    }

    private val inventory: Inventory = Bukkit.createInventory(this, INVENTORY_SIZE, "编辑NPC: $templateId")
    private val localTrades = ArrayList<CustomTrade?>()
    private var currentPage = 0
    private var lastCycleAtNanos = 0L

    init {
        plugin.npcModule.manager.getTemplate(templateId)?.trades?.let(localTrades::addAll)
        loadContent()
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun getInventory(): Inventory = inventory

    fun open() {
        if (!player.isOp) {
            player.sendMessage("§c只有管理员能编辑 NPC。")
            HandlerList.unregisterAll(this)
            return
        }
        player.openInventory(inventory)
    }

    private fun loadContent() {
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return
        for (slot in 0 until TRADE_AREA_SIZE) inventory.setItem(slot, null)

        for (row in 0 until 5) {
            inventory.setItem(row * 9 + 4, createItem(Material.GRAY_STAINED_GLASS_PANE, " "))
        }

        TRADE_SLOTS.forEachIndexed { pageIndex, slots ->
            inventory.setItem(slots.arrow, createItem(Material.ARROW, "§7-->"))
            val trade = localTrades.getOrNull(currentPage * TRADES_PER_PAGE + pageIndex) ?: return@forEachIndexed
            inventory.setItem(slots.input1, trade.ingredient1.clone())
            inventory.setItem(slots.input2, trade.ingredient2?.clone())
            inventory.setItem(slots.result, trade.result.clone())
        }

        updateControlButtons(template)
    }

    private fun updateControlButtons(template: com.hjh_database.npc.data.NpcTemplate) {
        inventory.setItem(45, createItem(
            Material.NAME_TAG,
            "§e修改名字",
            listOf("§7当前: ${template.name}", "§a点击后在聊天栏输入")
        ))
        inventory.setItem(46, createItem(
            Material.LEATHER_CHESTPLATE,
            "§e切换职业",
            listOf("§7当前: ${template.profession.key.key}", "§a左键: 下一个", "§b右键: 上一个")
        ))
        inventory.setItem(47, createItem(
            Material.MAP,
            "§e切换类型",
            listOf("§7当前: ${template.type.key.key}", "§a左键: 下一个", "§b右键: 上一个")
        ))
        inventory.setItem(48, createItem(
            Material.EXPERIENCE_BOTTLE,
            "§b刷新所有实体",
            listOf("§7保存修改并刷新同 ID 的已加载 NPC")
        ))
        inventory.setItem(
            49,
            if (currentPage > 0) createItem(
                Material.PAPER,
                "§a⬅ 上一页",
                listOf("§7当前第 ${currentPage + 1} 页")
            ) else createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        )
        inventory.setItem(50, createItem(
            Material.PAPER,
            "§a下一页 ➡",
            listOf("§7当前第 ${currentPage + 1} 页", "§7本页第 10 项填写后可进入新页")
        ))

        val discountStatus = if (template.allowRaceDiscount) "§a已开启" else "§c已关闭"
        val switchIcon = if (template.allowRaceDiscount) Material.EMERALD else Material.REDSTONE_BLOCK
        inventory.setItem(51, createItem(
            switchIcon,
            "§e种族优惠开关",
            listOf("§7当前状态: $discountStatus", "§e点击切换")
        ))
        inventory.setItem(52, createItem(
            Material.BARRIER,
            "§c§l删除此NPC",
            listOf("§7点击永久删除这个 NPC 实例", "§7不会删除石锄模板库中的副本")
        ))
        inventory.setItem(53, createItem(
            Material.EMERALD_BLOCK,
            "§a§l保存配置",
            listOf("§7保存全部页面的交易项")
        ))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !== this) return
        if (event.whoClicked.uniqueId != player.uniqueId || !player.isOp) {
            event.isCancelled = true
            return
        }

        val slot = event.rawSlot
        if (slot < 0) return
        if (slot < TRADE_AREA_SIZE) {
            if (slot !in EDITABLE_SLOTS) event.isCancelled = true
            return
        }
        if (slot >= INVENTORY_SIZE) {
            // 防止 Shift 点击自动把物品塞进箭头或分隔栏。
            if (event.isShiftClick) event.isCancelled = true
            return
        }

        event.isCancelled = true
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return
        when (slot) {
            45 -> {
                saveTradesFromGui()
                plugin.npcModule.manager.saveData()
                plugin.npcModule.getInteractListener().beginNameEdit(player, templateId, entityUuid)
                player.closeInventory()
                player.sendMessage("§a请在聊天栏输入新的 NPC 名字（支持颜色代码 &）：")
            }

            46 -> cycleValue(event.isLeftClick, event.isRightClick) { direction ->
                template.profession = cycle(PROFESSIONS, template.profession, direction)
                updateControlButtons(template)
            }

            47 -> cycleValue(event.isLeftClick, event.isRightClick) { direction ->
                template.type = cycle(VILLAGER_TYPES, template.type, direction)
                updateControlButtons(template)
            }

            48 -> {
                saveTradesFromGui()
                plugin.npcModule.manager.saveData()
                val count = plugin.npcModule.manager.refreshTemplateEntities(templateId)
                player.sendMessage("§a已刷新 $count 个已加载的 NPC 实例。")
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            }

            49 -> if (currentPage > 0) {
                saveCurrentPage()
                currentPage--
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1f)
                loadContent()
            }

            50 -> {
                saveCurrentPage()
                if (currentPage < lastEditablePage()) {
                    currentPage++
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1f)
                    loadContent()
                } else {
                    player.sendMessage("§e[NPC] 请先填写本页第 10 个交易项再新增一页。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1f)
                }
            }

            51 -> {
                template.allowRaceDiscount = !template.allowRaceDiscount
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1f)
                updateControlButtons(template)
            }

            52 -> {
                if (entityUuid == null) {
                    player.sendMessage("§c无法获取实体信息，请重新用木锄右键该 NPC。")
                    return
                }
                plugin.npcModule.manager.removeNpc(entityUuid)
                player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                player.sendMessage("§c已删除该 NPC 实例。")
                player.closeInventory()
            }

            53 -> {
                saveTradesFromGui()
                plugin.npcModule.manager.saveData()
                player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
                player.sendMessage("§a配置已保存！")
                player.closeInventory()
            }
        }
    }

    private fun cycleValue(leftClick: Boolean, rightClick: Boolean, action: (Int) -> Unit) {
        val direction = when {
            leftClick -> 1
            rightClick -> -1
            else -> return
        }
        val now = System.nanoTime()
        if (now - lastCycleAtNanos < CYCLE_INTERVAL_NANOS) return
        lastCycleAtNanos = now
        action(direction)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, if (direction > 0) 1.2f else 0.9f)
    }

    private fun <T> cycle(values: List<T>, current: T, direction: Int): T {
        if (values.isEmpty()) return current
        val currentIndex = values.indexOf(current).coerceAtLeast(0)
        return values[Math.floorMod(currentIndex + direction, values.size)]
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !== this) return
        if (event.rawSlots.any { it < INVENTORY_SIZE && it !in EDITABLE_SLOTS }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder !== this) return
        saveTradesFromGui()
        HandlerList.unregisterAll(this)
    }

    private fun saveCurrentPage() {
        TRADE_SLOTS.forEachIndexed { pageIndex, slots ->
            val index = currentPage * TRADES_PER_PAGE + pageIndex
            while (localTrades.size <= index) localTrades.add(null)

            val input1 = inventory.getItem(slots.input1)
            val input2 = inventory.getItem(slots.input2)
            val result = inventory.getItem(slots.result)
            if (input1 == null || input1.type.isAir || result == null || result.type.isAir) {
                localTrades[index] = null
                return@forEachIndexed
            }

            val oldTrade = localTrades[index]
            localTrades[index] = CustomTrade(
                result = result.clone(),
                ingredient1 = input1.clone(),
                ingredient2 = input2?.takeUnless { it.type.isAir }?.clone(),
                maxUses = oldTrade?.maxUses ?: 9999,
                experienceReward = oldTrade?.experienceReward ?: false
            )
        }
        trimTrailingEmptyTrades()
    }

    private fun saveTradesFromGui() {
        saveCurrentPage()
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return
        template.trades = ArrayList(localTrades.filterNotNull())
    }

    private fun trimTrailingEmptyTrades() {
        while (localTrades.isNotEmpty() && localTrades.last() == null) {
            localTrades.removeAt(localTrades.lastIndex)
        }
    }

    private fun lastEditablePage(): Int = localTrades.size / TRADES_PER_PAGE

    private fun createItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName(name)
            this.lore = lore
        }
        return item
    }
}
