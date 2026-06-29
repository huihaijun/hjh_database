package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import org.bukkit.Bukkit
import org.bukkit.ChatColor
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

class BaihuDzRecipeListGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val manageMode: Boolean = false
) : InventoryHolder, Listener {
    private val inv: Inventory = Bukkit.createInventory(
        this,
        54,
        if (category == "weapon") "§6虎瘴武器配方" else "§6虎瘴法宝配方"
    )
    private val slotMap = mutableMapOf<Int, String>()

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        inv.clear()
        slotMap.clear()
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName(" ") }
        for (i in 45 until 54) inv.setItem(i, filler)
        setButton(49, Material.BARRIER, "§c返回虎瘴锻造台")

        val recipes = plugin.baihuDzManager.getRecipesByCategory(category)
            .filter { manageMode || it.reqJob == -1 || it.reqJob == (plugin.playerManager.getData(player.uniqueId)?.job ?: -999) }
            .sortedBy { ChatColor.stripColor(it.result.itemMeta?.displayName ?: it.id) ?: it.id }

        if (recipes.isEmpty()) {
            setButton(22, Material.GRAY_DYE, "§7暂无可查看配方")
            return
        }

        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        recipes.take(slots.size).forEachIndexed { index, recipe ->
            val slot = slots[index]
            slotMap[slot] = recipe.id
            inv.setItem(slot, iconFor(recipe))
        }
    }

    private fun iconFor(recipe: DzRecipe): ItemStack {
        val item = recipe.result.clone()
        val meta = item.itemMeta ?: return item
        val lore = (meta.lore ?: mutableListOf()).toMutableList()
        val dzData = plugin.playerManager.getDzData(player.uniqueId)
        val rpgData = plugin.playerManager.getData(player.uniqueId)
        lore.add("")
        lore.add("§8§m------------------")
        lore.add("§6虎瘴要求: ${if (plugin.baihuDzManager.hasMiasma(player)) "§a已身负虎瘴" else "§c未身负虎瘴"}")
        if (recipe.reqJob != -1) {
            val ok = rpgData?.job == recipe.reqJob
            lore.add("§7职业: §f${DzUtil.getJobName(recipe.reqJob)} ${if (ok) "§a✓" else "§c✗"}")
        } else {
            lore.add("§7职业: §f通用")
        }
        lore.add("§7锻造等级: §fLv.${recipe.reqForgeLevel} ${if ((dzData?.forgeLevel ?: 0) >= recipe.reqForgeLevel) "§a✓" else "§c✗"}")
        if (recipe.reqLicense > 0) {
            lore.add("§7锻造资质: §f${recipe.reqLicense}级 ${if ((dzData?.forgeLicense ?: 0) >= recipe.reqLicense) "§a✓" else "§c✗"}")
        }
        if (recipe.expReward > 0) lore.add("§7锻造经验: §e+${recipe.expReward}")
        lore.add("")
        lore.add("§e点击查看虎瘴配方")
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun setButton(slot: Int, material: Material, name: String) {
        val item = ItemStack(material)
        item.itemMeta = item.itemMeta?.apply { setDisplayName(name) }
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
        when (event.slot) {
            49 -> {
                player.closeInventory()
                BaihuDzCategoryGui(plugin, player).open()
            }
            else -> {
                val recipeId = slotMap[event.slot] ?: return
                player.closeInventory()
                BaihuDzRecipePreviewGui(plugin, player, category, recipeId, manageMode).open()
            }
        }
    }
}
