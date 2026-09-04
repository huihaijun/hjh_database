package com.hjh_database.alchemy.gui

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class AlchemyAdminListGui(private val plugin: Hjh_database, val player: Player) : InventoryHolder {

    private val inventory: Inventory
    // 缓存 Slot -> RecipeID 的映射，方便点击处理
    val slotMap = HashMap<Int, String>()

    init {
        inventory = Bukkit.createInventory(this, 54, Component.text("§6管理员配方管理"))
        loadRecipes()
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun open() {
        player.openInventory(inventory)
    }

    private fun loadRecipes() {
        val recipes = plugin.alchemyManager.recipes.values.sortedBy { it.id }

        var slot = 0
        for (recipe in recipes) {
            if (slot >= 45) break // 留最后一行给功能按钮

            // 找个代表性的图标
            val icon = recipe.tierData.values.firstOrNull()?.result?.clone() ?: ItemStack(Material.PAPER)
            val meta = icon.itemMeta
            meta.setDisplayName("§eID: ${recipe.id}")
            val lore = ArrayList<String>()
            lore.add("§7显示名称: ${recipe.displayName}")
            lore.add(" ")
            lore.add("§b[左键] §f编辑配方")
            lore.add("§c[右键] §f删除配方")
            meta.lore = lore
            icon.itemMeta = meta

            // 隐藏多余属性
            try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES) } catch (e:Error){}
            try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP) } catch (e:Error){}
            inventory.setItem(slot, icon)
            slotMap[slot] = recipe.id
            slot++
        }

        // 底部功能按钮
        // Slot 49: 新增配方
        val addBtn = ItemStack(Material.EMERALD_BLOCK)
        val addMeta = addBtn.itemMeta
        addMeta.setDisplayName("§a[+] 新增配方")
        addMeta.lore = listOf("§7点击创建一个新配方")
        addBtn.itemMeta = addMeta
        inventory.setItem(49, addBtn)
    }
}