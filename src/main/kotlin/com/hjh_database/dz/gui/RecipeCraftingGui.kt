package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
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
import java.util.ArrayList

class RecipeCraftingGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val recipe: DzRecipe? // 允许传入 null 以配合原逻辑检查
) : InventoryHolder, Listener {

    private var inv: Inventory? = null // 为了配合原逻辑(异常时为null)，这里必须是可空

    private val INPUT_SLOTS = intArrayOf(11, 12, 13, 14, 15)
    private val MATERIAL_INFO_SLOT = 0
    private val OUTPUT_SLOT = 24
    private val BACK_BUTTON_SLOT = 45
    private val BUTTON_SLOT = 49
    private val outputPlaceholderKey = NamespacedKey(plugin, "forge_output_placeholder")

    init {
        if (recipe == null) {
            player.sendMessage("${ChatColor.RED}配方数据异常！")
            this.inv = null
            // Kotlin init 块无法像 Java构造函数那样直接 return 停止对象创建，
            // 但 inv 为 null 会导致 open() 不执行，逻辑效果一致。
        } else {
            this.inv = Bukkit.createInventory(this, 54, "锻造:${plainItemName(recipe.result)}")
            setupGui()
            plugin.server.pluginManager.registerEvents(this, plugin)
        }
    }

    private fun setupGui() {
        val inventory = inv ?: return // 安全检查

        val bg = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val m = bg.itemMeta
        if (m != null) {
            m.setDisplayName(" ")
            bg.itemMeta = m
        }
        for (i in 0 until 54) {
            if (!isInputSlot(i)) inventory.setItem(i, bg)
        }

        // 红色玻璃占住输出槽，让原版Shift自动寻槽只能落入材料槽。
        inventory.setItem(MATERIAL_INFO_SLOT, createMaterialInfoButton())
        inventory.setItem(OUTPUT_SLOT, createOutputPlaceholder())
        inventory.setItem(BACK_BUTTON_SLOT, createBackButton())

        updateButtonState()
    }

    private fun isInputSlot(slot: Int): Boolean {
        for (s in INPUT_SLOTS) {
            if (s == slot) return true
        }
        return false
    }

    /**
     * 核心逻辑：检查玩家是否满足锻造的所有条件
     * @return 错误原因列表，如果为空则表示满足条件
     */
    private fun checkRequirements(): List<String> {
        val errors: MutableList<String> = ArrayList()

        // 确保 recipe 不为空，虽然调用此方法时理论上已检查，但为了安全
        if (recipe == null) return errors

        val currentOutput = inv?.getItem(OUTPUT_SLOT)
        if (currentOutput != null && currentOutput.type != Material.AIR && !isOutputPlaceholder(currentOutput)) {
            errors.add("§c请先取出上一件锻造成品")
        }

        // 1. 获取玩家数据
        val rpgData = plugin.playerManager.getData(player.uniqueId)
        val forgeData = plugin.playerManager.getDzData(player.uniqueId)

        if (rpgData == null || forgeData == null) {
            errors.add("§c数据加载中...")
            return errors
        }

        // 2. 检查职业 (reqJob != -1 才检查)
        if (recipe.reqJob != -1) {
            // 这里假设 PlayerData.getJob() 返回 Int?
            val myJob = rpgData.job
            if (myJob == null || myJob != recipe.reqJob) {
                errors.add("§c职业不符 (需要: " + DzUtil.getJobName(recipe.reqJob) + ")")
            }
        }

        // 3. 检查锻造等级
        if (forgeData.forgeLevel < recipe.reqForgeLevel) {
            errors.add("§c锻造等级不足 (需要: Lv." + recipe.reqForgeLevel + ")")
        }

        // 4. 检查锻造资质
        if (forgeData.forgeLicense < recipe.reqLicense) {
            errors.add("§c锻造资质不足 (需要: " + recipe.reqLicense + "级)")
        }

        // 5. 检查材料 (ItemUtil ID对比)
        val required = recipe.ingredients
        var ingredientsOk = true
        val inventory = inv ?: return errors // 安全检查

        for (i in required.indices) {
            if (i >= INPUT_SLOTS.size) break
            val reqItem = required[i]
            val inputItem = inventory.getItem(INPUT_SLOTS[i])

            // 配方空则空，配方有则有
            if (reqItem == null || reqItem.type == Material.AIR) {
                if (inputItem != null && inputItem.type != Material.AIR) ingredientsOk = false
            } else {
                if (inputItem == null || inputItem.type == Material.AIR) {
                    ingredientsOk = false
                } else if (!ItemUtil.isMatch(reqItem, inputItem)) {
                    ingredientsOk = false
                } else if (inputItem.amount < reqItem.amount) {
                    ingredientsOk = false
                }
            }
        }
        if (!ingredientsOk) {
            errors.add("§c材料不足或不匹配")
        }

        return errors
    }

    private fun createMaterialInfoButton(): ItemStack {
        val item = ItemStack(Material.BREWING_STAND)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName("§6锻造材料一览")
            val lore = ArrayList<String>()
            val safeRecipe = recipe
            if (safeRecipe == null || safeRecipe.ingredients.isEmpty()) {
                lore.add("§7无额外材料")
            } else {
                for (ingredient in safeRecipe.ingredients) {
                    if (ingredient.type == Material.AIR) continue
                    lore.add("§f${getItemDisplayName(ingredient)} §7x§e${ingredient.amount}")
                }
                if (lore.isEmpty()) lore.add("§7无额外材料")
            }
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }

    private fun createBackButton(): ItemStack {
        val item = ItemStack(Material.RED_BED)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName("§c返回配方预览")
            meta.lore = listOf("§7退还已放入的材料并返回")
            item.itemMeta = meta
        }
        return item
    }

    private fun getItemDisplayName(item: ItemStack): String {
        val meta = item.itemMeta
        return if (meta != null && meta.hasDisplayName()) {
            meta.displayName
        } else {
            "§f${item.type.name.lowercase().split("_").joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }}"
        }
    }

    private fun plainItemName(item: ItemStack): String {
        val meta = item.itemMeta
        val name = if (meta != null && meta.hasDisplayName()) meta.displayName else item.type.name
        return ChatColor.stripColor(name) ?: name
    }

    private fun updateButtonState() {
        val inventory = inv ?: return
        ensureOutputPlaceholder(inventory)
        val errors = checkRequirements()
        val btn: ItemStack

        if (errors.isEmpty()) {
            // 条件满足
            btn = ItemStack(Material.ANVIL)
            val meta = btn.itemMeta
            if (meta != null) {
                meta.setDisplayName("§a§l[点击开始锻造]")
                meta.lore = listOf(
                    "§7材料充足，条件符合",
                    "§e成功奖励经验: " + recipe!!.expReward
                )
                btn.itemMeta = meta
            }
        } else {
            // 条件不满足
            btn = ItemStack(Material.BARRIER)
            val meta = btn.itemMeta
            if (meta != null) {
                meta.setDisplayName("§c§l无法锻造")
                meta.lore = errors // 直接把错误原因显示在 Lore 里
                btn.itemMeta = meta
            }
        }
        inventory.setItem(BUTTON_SLOT, btn)
    }

    fun open() {
        if (inv != null) {
            player.openInventory(inv!!)
        }
    }

    // 这里必须强行返回 Inventory 类型以符合接口，虽然 inv 可能是 null
    // 如果 inv 为 null，这里会抛出异常，这与原 Java 代码 behavior 一致（原代码如果 inv 为 null 也会导致后续 NPE）
    override fun getInventory(): Inventory {
        return inv!!
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv && inv != null) {
            // 退还材料
            for (slot in INPUT_SLOTS) {
                val item = inv!!.getItem(slot)
                if (item != null && item.type != Material.AIR) {
                    inv!!.setItem(slot, null)
                    giveOrDrop(item)
                }
            }
            // 玩家没有主动取出成品时，关闭界面也必须安全发放，不能留在即将销毁的GUI中。
            val output = inv!!.getItem(OUTPUT_SLOT)
            if (output != null && output.type != Material.AIR && !isOutputPlaceholder(output)) {
                inv!!.setItem(OUTPUT_SLOT, null)
                giveOrDrop(output)
            }
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val inventory = inv ?: return
        if (event.view.topInventory != inventory) return

        // DOUBLE_CLICK/COLLECT_TO_CURSOR 会从整个 InventoryView 收集同类物品，
        // 即使双击发生在玩家背包，也必须在锻造界面统一拦截。
        if (event.click == ClickType.DOUBLE_CLICK || event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            return
        }

        val slot = event.rawSlot

        if (slot == OUTPUT_SLOT) {
            val output = inventory.getItem(OUTPUT_SLOT)
            if (output != null && !isOutputPlaceholder(output) && isOutputTakeAction(event.action)) {
                plugin.server.scheduler.runTask(plugin, Runnable { this.updateButtonState() })
            } else {
                event.isCancelled = true
            }
            return
        }

        // 允许操作输入槽
        if (isInputSlot(slot)) {
            plugin.server.scheduler.runTask(plugin, Runnable { this.updateButtonState() })
            return
        } else if (slot < inventory.size) {
            event.isCancelled = true
        } else if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            val output = inventory.getItem(OUTPUT_SLOT)
            if (output != null && !isOutputPlaceholder(output)) {
                // 有成品时，原版会把Shift的同类物品合并进输出堆；领取成品前暂时禁止。
                event.isCancelled = true
            } else {
                plugin.server.scheduler.runTask(plugin, Runnable { this.updateButtonState() })
            }
        }

        if (slot == BACK_BUTTON_SLOT) {
            val safeRecipe = recipe ?: return
            player.closeInventory()
            RecipePreviewGui(plugin, player, category, safeRecipe.id).open()
            return
        }

        if (slot == BUTTON_SLOT) {
            val errors = checkRequirements()
            if (errors.isEmpty()) {
                doCraft()
            } else {
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                player.sendMessage("§c无法锻造：")
                for (err in errors) player.sendMessage(err)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val inventory = inv ?: return
        if (event.view.topInventory != inventory) return

        val topSlots = event.rawSlots.filter { it < inventory.size }
        if (topSlots.any { !isInputSlot(it) }) {
            event.isCancelled = true
            return
        }
        if (topSlots.isNotEmpty()) {
            plugin.server.scheduler.runTask(plugin, Runnable { this.updateButtonState() })
        }
    }

    private fun doCraft() {
        val inventory = inv ?: return
        val safeRecipe = recipe ?: return

        // 1. 消耗材料
        val required = safeRecipe.ingredients
        for (i in required.indices) {
            if (i >= INPUT_SLOTS.size) break
            val req = required[i]
            if (req != null && req.type != Material.AIR) {
                val input = inventory.getItem(INPUT_SLOTS[i])
                if (input != null) {
                    input.amount = input.amount - req.amount
                    inventory.setItem(INPUT_SLOTS[i], input)
                }
            }
        }

        // 2. 成品进入受保护的输出槽，由玩家取走；关闭界面时会自动发放。
        inventory.setItem(OUTPUT_SLOT, safeRecipe.result.clone())

        // 3. 增加经验 (修复为奖励)
        val forgeData = plugin.playerManager.getDzData(player.uniqueId)
        if (forgeData != null && safeRecipe.expReward > 0) {
            // 【修改后】传入 plugin 和 player 以触发升级特效和保存
            forgeData.addExp(safeRecipe.expReward, plugin, player)
            // 这一行原本的 sendMessage 可以保留也可以去掉，因为 addExp 里已经有了升级提示
            player.sendMessage("§a锻造成功！获得 " + safeRecipe.expReward + " 点锻造经验。")
        }

        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
        updateButtonState()
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

    private fun ensureOutputPlaceholder(inventory: Inventory) {
        val output = inventory.getItem(OUTPUT_SLOT)
        if (output == null || output.type == Material.AIR) {
            inventory.setItem(OUTPUT_SLOT, createOutputPlaceholder())
        }
    }
}
