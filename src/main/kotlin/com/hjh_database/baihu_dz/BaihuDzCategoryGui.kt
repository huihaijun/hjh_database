package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class BaihuDzCategoryGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val manageMode: Boolean = false
) : InventoryHolder, Listener {
    private val inv: Inventory = Bukkit.createInventory(this, 27, "§8§l白虎锻造台")
    private val categoryKey = NamespacedKey(plugin, "baihu_dz_category")

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName(" ") }
        for (i in 0 until inv.size) inv.setItem(i, filler)

        setCategory(
            11,
            Material.IRON_SWORD,
            "equipment",
            "§c§l武器&法器",
            listOf("§7白虎洞虎瘴浸染的兵刃与法器", "§6释放技能时消耗虎瘴耐久")
        )
        setCategory(
            15,
            Material.AMETHYST_SHARD,
            "material",
            "§b§l材料",
            listOf("§7锻造白虎装备所需的特殊材料")
        )
    }

    private fun setCategory(slot: Int, material: Material, id: String, name: String, lore: List<String>) {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return
        meta.setDisplayName(name)
        meta.lore = lore + listOf("", "§e点击查看配方")
        meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, id)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        item.itemMeta = meta
        inv.setItem(slot, item)
    }

    fun open() = player.openInventory(inv)
    override fun getInventory(): Inventory = inv

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv) HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true
        val meta = event.currentItem?.itemMeta ?: return
        val category = meta.persistentDataContainer.get(categoryKey, PersistentDataType.STRING) ?: return
        player.closeInventory()
        BaihuDzRecipeListGui(plugin, player, category, manageMode).open()
    }
}
