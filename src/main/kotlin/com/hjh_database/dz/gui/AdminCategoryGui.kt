package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class AdminCategoryGui(private val plugin: Hjh_database, private val player: Player) : InventoryHolder, Listener {
    private val inv: Inventory = Bukkit.createInventory(this, 27, "§c[管理员] 选择配方分类")

    // 定义一个用于存储分类Key的命名空间键
    private val categoryKey = NamespacedKey(plugin, "gui_category_id")

    init {
        setItem(10, Material.IRON_SWORD, "weapon", "§c武器配方管理")
        setItem(12, Material.DIAMOND_CHESTPLATE, "armor", "§b防具配方管理")
        setItem(14, Material.END_CRYSTAL, "artifact", "§6法宝＆饰品配方管理")
        setItem(16, Material.WOODEN_HOE, "misc", "§a杂项配方管理")

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    private fun setItem(slot: Int, mat: Material, key: String, name: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)

            // 【修复 1】不再使用 setLocalizedName(key)
            // 改为使用 PDC 存储 key
            meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, key)

            meta.lore = listOf("§7点击进入管理界面", "§7可新建、编辑、删除")
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            item.itemMeta = meta
        }
        inv.setItem(slot, item)
    }

    fun open() {
        player.openInventory(inv)
    }

    override fun getInventory(): Inventory {
        return inv
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true

        val item = event.currentItem
        if (item == null || item.type == Material.AIR) return

        val meta = item.itemMeta ?: return

        // 【修复 2】不再使用 meta.localizedName
        // 改为从 PDC 读取 key
        if (!meta.persistentDataContainer.has(categoryKey, PersistentDataType.STRING)) return

        val category = meta.persistentDataContainer.get(categoryKey, PersistentDataType.STRING) ?: return

        // 进入列表
        AdminRecipeListGui(plugin, player, category).open()
    }
}
