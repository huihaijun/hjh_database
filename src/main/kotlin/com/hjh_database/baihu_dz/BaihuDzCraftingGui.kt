package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import com.hjh_database.util.ItemUtil
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class BaihuDzCraftingGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val recipe: DzRecipe,
    private val manageMode: Boolean = false
) : InventoryHolder, Listener {
    private val inv: Inventory = Bukkit.createInventory(this, 54, "§6虎瘴锻造")
    private val inputSlots = intArrayOf(11, 12, 13, 14, 15)

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName(" ") }
        for (i in 0 until inv.size) if (i !in inputSlots) inv.setItem(i, filler)
        inv.setItem(0, materialInfo())
        inv.setItem(24, recipe.result)
        setButton(45, Material.RED_BED, "§c返回配方预览")
        updateButton()
    }

    private fun materialInfo(): ItemStack {
        val item = ItemStack(Material.BREWING_STAND)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName("§6虎瘴材料")
            lore = recipe.ingredients
                .filter { it.type != Material.AIR }
                .map { "§f${displayName(it)} §7x§e${it.amount}" }
        }
        return item
    }

    private fun checkRequirements(): List<String> {
        val errors = mutableListOf<String>()
        val rpgData = plugin.playerManager.getData(player.uniqueId)
        val dzData = plugin.playerManager.getDzData(player.uniqueId)
        if (rpgData == null || dzData == null) return listOf("§c玩家数据尚未加载")

        if (recipe.reqJob != -1 && rpgData.job != recipe.reqJob) {
            errors.add("§c职业不符，需要${DzUtil.getJobName(recipe.reqJob)}")
        }
        if (dzData.forgeLevel < recipe.reqForgeLevel) {
            errors.add("§c锻造等级不足，需要Lv.${recipe.reqForgeLevel}")
        }
        if (dzData.forgeLicense < recipe.reqLicense) {
            errors.add("§c锻造资质不足，需要${recipe.reqLicense}级")
        }

        var materialOk = true
        recipe.ingredients.take(inputSlots.size).forEachIndexed { i, req ->
            val input = inv.getItem(inputSlots[i])
            if (req.type == Material.AIR) {
                if (input != null && input.type != Material.AIR) materialOk = false
            } else if (input == null || input.type == Material.AIR || !ItemUtil.isMatch(req, input) || input.amount < req.amount) {
                materialOk = false
            }
        }
        if (!materialOk) errors.add("§c材料不足或摆放不匹配")
        return errors
    }

    private fun updateButton() {
        val errors = checkRequirements()
        if (errors.isEmpty()) {
            val item = ItemStack(Material.ANVIL)
            item.itemMeta = item.itemMeta?.apply {
                setDisplayName("§a§l开始锻造虎瘴装")
                lore = if (recipe.category == "material") {
                    listOf("§7材料与锻造要求均已满足")
                } else {
                    listOf("§7材料与锻造要求均已满足", "§6成品仅身负虎瘴时生效")
                }
            }
            inv.setItem(49, item)
        } else {
            val item = ItemStack(Material.BARRIER)
            item.itemMeta = item.itemMeta?.apply {
                setDisplayName("§c无法锻造")
                lore = errors
            }
            inv.setItem(49, item)
        }
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
        if (event.inventory == inv) {
            inputSlots.forEach { slot ->
                val item = inv.getItem(slot)
                if (item != null && item.type != Material.AIR) player.inventory.addItem(item)
            }
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        val slot = event.rawSlot
        if (slot in inputSlots) {
            plugin.server.scheduler.runTask(plugin, Runnable { updateButton() })
            return
        }
        if (slot < inv.size) event.isCancelled = true
        when (slot) {
            45 -> {
                player.closeInventory()
                BaihuDzRecipePreviewGui(plugin, player, category, recipe.id, manageMode).open()
            }
            49 -> {
                val errors = checkRequirements()
                if (errors.isEmpty()) craft() else {
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    errors.forEach { player.sendMessage(it) }
                }
            }
        }
    }

    private fun craft() {
        recipe.ingredients.take(inputSlots.size).forEachIndexed { i, req ->
            if (req.type == Material.AIR) return@forEachIndexed
            val input = inv.getItem(inputSlots[i]) ?: return@forEachIndexed
            input.amount -= req.amount
            inv.setItem(inputSlots[i], input)
        }
        player.inventory.addItem(recipe.result.clone())
        plugin.playerManager.getDzData(player.uniqueId)?.let { dz ->
            if (recipe.expReward > 0) dz.addExp(recipe.expReward, plugin, player)
        }
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
        player.sendMessage("§a虎瘴装锻造成功。")
        updateButton()
    }

    private fun displayName(item: ItemStack): String {
        val raw = item.itemMeta?.displayName ?: item.type.name
        return ChatColor.stripColor(raw) ?: raw
    }
}
