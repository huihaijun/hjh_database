package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayList
import kotlin.math.min

class AdminRecipeListGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String
) : InventoryHolder, Listener {

    private val inv: Inventory = Bukkit.createInventory(this, 54, "配方管理: $category")
    private var page = 1

    // 【核心修复】1.21.3 替代 localizedName 的方案
    private val recipeIdKey = NamespacedKey(plugin, "gui_recipe_id")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        refresh()
    }

    private fun refresh() {
        inv.clear()
        val all = plugin.recipeManager.getRecipesByCategory(category)

        val start = (page - 1) * 45
        val end = min(start + 45, all.size)

        for (i in start until end) {
            val r = all[i]
            val item = r.result.clone() // 假设 getResult() 是 result 属性
            val meta = item.itemMeta

            if (meta != null) {
                var lore = meta.lore
                if (lore == null) lore = ArrayList()

                lore.add(" ")
                lore.add("§8----------------")
                lore.add("§7ID: §f" + r.id)
                lore.add("§7职业要求: §f" + r.reqJob)
                lore.add("§7锻造等级要求: §f" + r.reqForgeLevel)
                lore.add("§7锻造资质要求: §f" + r.reqLicense)
                lore.add(" ")
                lore.add("§a[左键] 编辑")
                lore.add("§c[右键] 删除")

                meta.lore = lore

                // 【核心修复】改为使用 PDC 存储 ID
                // 原代码: meta.setLocalizedName(r.getId());
                meta.persistentDataContainer.set(recipeIdKey, PersistentDataType.STRING, r.id)

                item.itemMeta = meta
                inv.addItem(item)
            }
        }

        if (page > 1) setBtn(45, Material.ARROW, "上一页")
        if (end < all.size) setBtn(53, Material.ARROW, "下一页")

        // 【核心修改】点击直接打开编辑器，不需要参数
        setBtn(49, Material.ANVIL, "§a§l[+ 新建配方]", "§7点击打开编辑器", "§7放入物品后保存即可")
    }

    private fun setBtn(slot: Int, mat: Material, name: String, vararg lore: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = ArrayList(listOf(*lore))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
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
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv) {
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true

        val currentItem = event.currentItem
        if (currentItem == null || currentItem.type == Material.AIR) return

        val slot = event.slot
        if (slot == 45 && page > 1) {
            page--
            refresh()
        } else if (slot == 53) {
            page++
            refresh()
        } else if (slot == 49) {
            // 新建配方：传入 null ID
            RecipeEditorGui(plugin, player, category, null).open()
        } else if (slot < 45) {
            val meta = currentItem.itemMeta ?: return

            // 【核心修复】改为从 PDC 读取 ID
            // 原代码: String id = meta.getLocalizedName();
            if (!meta.persistentDataContainer.has(recipeIdKey, PersistentDataType.STRING)) return
            val id = meta.persistentDataContainer.get(recipeIdKey, PersistentDataType.STRING)

            if (id != null) {
                if (event.click == ClickType.RIGHT) {
                    plugin.recipeManager.deleteRecipe(category, id)
                    player.sendMessage("§c已删除: $id")
                    refresh()
                } else {
                    // 编辑现有配方：传入 ID
                    RecipeEditorGui(plugin, player, category, id).open()
                }
            }
        }
    }
}
