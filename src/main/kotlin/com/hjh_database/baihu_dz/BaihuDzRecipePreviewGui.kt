package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class BaihuDzRecipePreviewGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    recipeId: String,
    private val manageMode: Boolean = false
) : InventoryHolder, Listener {
    private val recipe: DzRecipe? = plugin.baihuDzManager.getRecipe(category, recipeId)
    private val inv: Inventory = Bukkit.createInventory(this, 54, "§6虎瘴配方预览")
    private val inputSlots = intArrayOf(11, 12, 13, 14, 15)

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName(" ") }
        for (i in 0 until inv.size) inv.setItem(i, filler)
        val safe = recipe ?: return
        safe.ingredients.take(inputSlots.size).forEachIndexed { i, item -> inv.setItem(inputSlots[i], item) }
        inv.setItem(24, safe.result)
        inv.setItem(23, infoItem(safe))
        setButton(45, Material.RED_BED, "§c返回配方列表")
        setButton(49, Material.ANVIL, "§a开始虎瘴锻造")
    }

    private fun infoItem(recipe: DzRecipe): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName("§6虎瘴锻造要求")
            lore = listOf(
                "§7职业: §f${if (recipe.reqJob == -1) "通用" else DzUtil.getJobName(recipe.reqJob)}",
                "§7锻造等级: §fLv.${recipe.reqForgeLevel}",
                "§7锻造资质: §f${recipe.reqLicense}",
                "§7成功经验: §e${recipe.expReward}",
                "§6额外: §f成品仅身负虎瘴时激活"
            )
        }
        return item
    }

    private fun setButton(slot: Int, material: Material, name: String) {
        val item = ItemStack(material)
        item.itemMeta = item.itemMeta?.apply { setDisplayName(name) }
        inv.setItem(slot, item)
    }

    fun open() {
        if (recipe == null) {
            player.sendMessage("§c虎瘴配方不存在。")
            return
        }
        player.openInventory(inv)
    }

    override fun getInventory(): Inventory = inv

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv) HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true
        when (event.slot) {
            45 -> {
                player.closeInventory()
                BaihuDzRecipeListGui(plugin, player, category, manageMode).open()
            }
            49 -> {
                val safe = recipe ?: return
                player.closeInventory()
                BaihuDzCraftingGui(plugin, player, category, safe, manageMode).open()
            }
        }
    }
}
