package com.hjh_database.passbook

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/** 钱庄掌柜提供的存折界面与货币存取逻辑。 */
class PassbookListener(private val plugin: Hjh_database) : Listener {
    companion object {
        private const val BANKER_NAME = "钱庄掌柜"
        private const val TITLE = "§6钱庄存折"
        private const val HEAD_SLOT = 10
        private const val COPPER_SLOT = 12
        private const val GOLD_SLOT = 14
        private const val NOTE_SLOT = 16
        private const val COPPER_ID = "hjh_tongqian"
        private const val GOLD_ID = "jinyuanbao"
        private const val NOTE_ID = "yinpiao"
    }

    private data class Currency(
        val resourceId: String,
        val name: String,
        val copperValue: Int,
        val slot: Int,
        val depositLore: String,
        val withdrawOneLore: String,
        val withdrawTenLore: String
    )

    private val currencies = listOf(
        Currency(COPPER_ID, "铜钱", 1, COPPER_SLOT, "左键存入所有铜钱", "右键取出1枚铜钱", "下蹲+右键取出10枚铜钱"),
        Currency(GOLD_ID, "金元宝", 10, GOLD_SLOT, "左键存入所有金元宝", "右键取出1枚金元宝", "下蹲+右键取出10枚金元宝"),
        // 10 铜钱 = 1 金元宝，10 金元宝 = 1 银票；银票是最高面额。
        Currency(NOTE_ID, "银票", 100, NOTE_SLOT, "左键存入所有银票", "右键取出1张银票", "下蹲+右键取出10张银票")
    )
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val ignoreRefreshKey = NamespacedKey(plugin, "hjh_ignore_refresh")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBankerInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        // OP 的两种 NPC 管理工具拥有最高业务优先级，钱庄不能抢先打开交易界面。
        val heldMaterial = event.player.inventory.itemInMainHand.type
        if (event.player.isOp && (heldMaterial == Material.WOODEN_HOE || heldMaterial == Material.STONE_HOE)) return
        val villager = event.rightClicked as? Villager ?: return
        if (!isBanker(villager.customName)) return

        event.isCancelled = true
        open(event.player)
    }

    /** 供神识等远程交互入口复用钱庄掌柜的专属 GUI。 */
    fun isBanker(displayName: String?): Boolean {
        val plainName = ChatColor.stripColor(displayName ?: return false) ?: return false
        return plainName.contains(BANKER_NAME)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PassbookHolder ?: return
        event.isCancelled = true

        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        val player = event.whoClicked as? Player ?: return
        if (holder.owner != player.uniqueId) return

        val currency = currencies.firstOrNull { it.slot == event.rawSlot } ?: return
        when (event.click) {
            ClickType.LEFT -> depositAll(player, currency)
            ClickType.RIGHT -> withdraw(player, currency, 1)
            ClickType.SHIFT_RIGHT -> withdraw(player, currency, 10)
            else -> return
        }
        refreshHead(event.view.topInventory, player)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is PassbookHolder) {
            event.isCancelled = true
        }
    }

    fun open(player: Player) {
        val holder = PassbookHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, 27, TITLE)
        holder.menu = inventory
        refreshHead(inventory, player)
        currencies.forEach { inventory.setItem(it.slot, createCurrencyButton(it)) }
        player.openInventory(inventory)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f)
    }

    private fun refreshHead(inventory: Inventory, player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId)
        val head = ItemStack(Material.PLAYER_HEAD)
        (head.itemMeta as? SkullMeta)?.let { meta ->
            meta.owningPlayer = player
            meta.setDisplayName("§e${player.name}")
            meta.lore = listOf("§7财产: §6${formatMoney(data?.money ?: 0.0)} §7铜钱")
            head.itemMeta = meta
        }
        inventory.setItem(HEAD_SLOT, head)
    }

    private fun createCurrencyButton(currency: Currency): ItemStack {
        val item = plugin.resourceManager.getItem(currency.resourceId)?.clone() ?: ItemStack(Material.GOLD_NUGGET)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName("§e存入/取出${currency.name}")
        meta.lore = listOf("§7${currency.depositLore}", "§7${currency.withdrawOneLore}", "§7${currency.withdrawTenLore}")
        // 资源监听器会在 GUI 打开时刷新带 resource_id 的物品；标记该按钮以保留专用 lore。
        meta.persistentDataContainer.set(ignoreRefreshKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    private fun depositAll(player: Player, currency: Currency) {
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage("§e[钱庄] §7玩家数据仍在加载，请稍后再试。")
            return
        }

        val amount = countCurrency(player, currency.resourceId)
        if (amount <= 0) {
            player.sendMessage("§e[钱庄] §7你的背包中没有${currency.name}。")
            return
        }

        removeCurrency(player, currency.resourceId, amount)
        val credited = amount.toDouble() * currency.copperValue
        data.money += credited
        plugin.databaseManager.savePlayerAsync(data)
        player.updateInventory()
        player.sendMessage("§e[钱庄] §a已存入 $amount ${currency.name}，折合 ${formatMoney(credited)} 铜钱。")
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f)
    }

    private fun withdraw(player: Player, currency: Currency, amount: Int) {
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage("§e[钱庄] §7玩家数据仍在加载，请稍后再试。")
            return
        }
        val template = plugin.resourceManager.getItem(currency.resourceId)
        if (template == null) {
            player.sendMessage("§c[钱庄] 未找到货币物品配置：${currency.resourceId}")
            return
        }

        val cost = amount.toDouble() * currency.copperValue
        if (data.money + 1.0e-9 < cost) {
            player.sendMessage("§e[钱庄] §c存款不足，需要 ${formatMoney(cost)} 铜钱。")
            return
        }

        giveCurrency(player, template, amount)
        data.money = max(0.0, data.money - cost)
        plugin.databaseManager.savePlayerAsync(data)
        player.updateInventory()
        player.sendMessage("§e[钱庄] §a已取出 $amount ${currency.name}，扣除 ${formatMoney(cost)} 铜钱。")
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
    }

    private fun countCurrency(player: Player, resourceId: String): Int =
        player.inventory.storageContents.sumOf { item ->
            if (resourceId(item) == resourceId) item?.amount ?: 0 else 0
        }

    private fun removeCurrency(player: Player, resourceId: String, amount: Int) {
        var remaining = amount
        val inventory = player.inventory
        for (slot in 0 until inventory.storageContents.size) {
            val item = inventory.getItem(slot) ?: continue
            if (resourceId(item) != resourceId) continue
            val removed = minOf(remaining, item.amount)
            item.amount -= removed
            remaining -= removed
            inventory.setItem(slot, item.takeIf { it.amount > 0 })
            if (remaining == 0) return
        }
    }

    private fun giveCurrency(player: Player, template: ItemStack, amount: Int) {
        var remaining = amount
        val maxStackSize = template.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val stackSize = minOf(remaining, maxStackSize)
            val stack = template.clone().apply { this.amount = stackSize }
            player.inventory.addItem(stack).values.forEach { overflow ->
                player.world.dropItemNaturally(player.location, overflow)
            }
            remaining -= stackSize
        }
    }

    private fun resourceId(item: ItemStack?): String? =
        item?.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)

    private fun formatMoney(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "0"
        value % 1.0 == 0.0 -> String.format(Locale.ROOT, "%,d", value.toLong())
        else -> String.format(Locale.ROOT, "%,.2f", value)
    }
}

private class PassbookHolder(val owner: UUID) : InventoryHolder {
    lateinit var menu: Inventory

    override fun getInventory(): Inventory = menu
}
