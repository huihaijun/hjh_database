package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.race.impl.XianRace
import com.hjh_database.util.DzUtil
import com.hjh_database.util.ItemUtil
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class BaihuDzCraftingGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val recipe: DzRecipe,
    private val manageMode: Boolean = false
) : InventoryHolder, Listener {
    private val inv: Inventory = Bukkit.createInventory(this, 54, "§6虎瘴锻造")
    private val inputSlots = intArrayOf(11, 12, 13, 14, 15)
    private val outputSlot = 24
    private val outputPlaceholderKey = NamespacedKey(plugin, "baihu_forge_output_placeholder")

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName(" ") }
        for (i in 0 until inv.size) if (i !in inputSlots) inv.setItem(i, filler)
        inv.setItem(0, materialInfo())
        // 红色玻璃占住输出槽，让原版Shift自动寻槽只能落入材料槽。
        inv.setItem(outputSlot, createOutputPlaceholder())
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
        (plugin.raceModule.getRace(1) as? XianRace)?.ensureInitialForgeLevel(player, notify = false)

        val currentOutput = inv.getItem(outputSlot)
        if (currentOutput != null && currentOutput.type != Material.AIR && !isOutputPlaceholder(currentOutput)) {
            errors.add("§c请先取出上一件锻造成品")
        }

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
        ensureOutputPlaceholder()
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
                if (item != null && item.type != Material.AIR) {
                    inv.setItem(slot, null)
                    giveOrDrop(item)
                }
            }
            val output = inv.getItem(outputSlot)
            if (output != null && output.type != Material.AIR && !isOutputPlaceholder(output)) {
                inv.setItem(outputSlot, null)
                giveOrDrop(output)
            }
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory != inv) return

        // 双击背包会从整个视图执行 COLLECT_TO_CURSOR，必须在下方背包点击时也拦截。
        if (event.click == ClickType.DOUBLE_CLICK || event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            return
        }

        val slot = event.rawSlot
        if (slot == outputSlot) {
            val output = inv.getItem(outputSlot)
            if (output != null && !isOutputPlaceholder(output) && isOutputTakeAction(event.action)) {
                plugin.server.scheduler.runTask(plugin, Runnable { updateButton() })
            } else {
                event.isCancelled = true
            }
            return
        }
        if (slot in inputSlots) {
            plugin.server.scheduler.runTask(plugin, Runnable { updateButton() })
            return
        }
        if (slot < inv.size) event.isCancelled = true
        else if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            val output = inv.getItem(outputSlot)
            if (output != null && !isOutputPlaceholder(output)) {
                // 有成品时，原版会把Shift的同类物品合并进输出堆；领取成品前暂时禁止。
                event.isCancelled = true
            } else {
                plugin.server.scheduler.runTask(plugin, Runnable { updateButton() })
            }
        }
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

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory != inv) return

        val topSlots = event.rawSlots.filter { it < inv.size }
        if (topSlots.any { it !in inputSlots }) {
            event.isCancelled = true
            return
        }
        if (topSlots.isNotEmpty()) {
            plugin.server.scheduler.runTask(plugin, Runnable { updateButton() })
        }
    }

    private fun craft() {
        recipe.ingredients.take(inputSlots.size).forEachIndexed { i, req ->
            if (req.type == Material.AIR) return@forEachIndexed
            val input = inv.getItem(inputSlots[i]) ?: return@forEachIndexed
            input.amount -= req.amount
            inv.setItem(inputSlots[i], input)
        }
        inv.setItem(outputSlot, recipe.result.clone())
        if (recipe.expReward > 0) {
            val xianRace = plugin.raceModule.getRace(1) as? XianRace
            val reward = xianRace?.grantForgeSuccessRewards(player, recipe.expReward)
            if (reward != null) {
                player.sendMessage("§a获得 ${reward.forgeExp} 点锻造经验。")
                if (reward.playerExp > 0) {
                    player.sendMessage("§e[百器之智] §f额外获得 §b${reward.playerExp} §f点经验值。")
                }
            } else {
                plugin.playerManager.getDzData(player.uniqueId)?.addExp(recipe.expReward, plugin, player)
            }
        }
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
        player.sendMessage("§a虎瘴装锻造成功。")
        updateButton()
    }

    private fun isOutputTakeAction(action: InventoryAction): Boolean {
        return action == InventoryAction.PICKUP_ALL ||
            action == InventoryAction.PICKUP_HALF ||
            action == InventoryAction.PICKUP_ONE ||
            action == InventoryAction.PICKUP_SOME ||
            action == InventoryAction.MOVE_TO_OTHER_INVENTORY ||
            action == InventoryAction.DROP_ALL_SLOT ||
            action == InventoryAction.DROP_ONE_SLOT
    }

    private fun giveOrDrop(item: ItemStack) {
        val leftovers = player.inventory.addItem(item.clone())
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun createOutputPlaceholder(): ItemStack {
        val item = ItemStack(Material.RED_STAINED_GLASS_PANE)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName("§c§l成品位置")
            lore = listOf("§7锻造完成后，成品会出现在这里", "§c此槽位禁止放入物品")
            persistentDataContainer.set(outputPlaceholderKey, PersistentDataType.BYTE, 1)
        }
        return item
    }

    private fun isOutputPlaceholder(item: ItemStack): Boolean {
        return item.itemMeta?.persistentDataContainer
            ?.has(outputPlaceholderKey, PersistentDataType.BYTE) == true
    }

    private fun ensureOutputPlaceholder() {
        val output = inv.getItem(outputSlot)
        if (output == null || output.type == Material.AIR) {
            inv.setItem(outputSlot, createOutputPlaceholder())
        }
    }

    private fun displayName(item: ItemStack): String {
        val raw = item.itemMeta?.displayName ?: item.type.name
        return ChatColor.stripColor(raw) ?: raw
    }
}
