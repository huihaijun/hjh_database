package com.hjh_database.alchemy.gui

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class AlchemyAdminGui(private val plugin: Hjh_database, val player: Player, val recipe: AlchemyRecipe? = null) : InventoryHolder {

    private val inventory: Inventory
    // 如果传入 recipe 为空，说明是新建配方
    // 如果不为空，直接使用引用的对象进行编辑
    val editingRecipe: AlchemyRecipe = recipe ?: AlchemyRecipe("new_${System.currentTimeMillis() / 1000}")

    init {
        inventory = Bukkit.createInventory(this, 54, Component.text("§5配方编辑器: ${editingRecipe.id}"))
        setupLayout()

        // 【新增】如果不是新建的，从传入的 recipe 加载物品到 GUI 上
        if (recipe != null) {
            loadItemsFromRecipe()
        }
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun open() {
        player.openInventory(inventory)
    }

    private fun setupLayout() {
        // 1. 背景黑色玻璃板
        val bg = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = bg.itemMeta
        meta.setDisplayName(" ")
        bg.itemMeta = meta
        for (i in 0 until 54) inventory.setItem(i, bg)

        // 2. 红色玻璃板作为分界线阻挡 (【修改】区分初、中、高级)
        fun getDivider(name: String): ItemStack {
            val pane = ItemStack(Material.RED_STAINED_GLASS_PANE)
            val paneMeta = pane.itemMeta
            paneMeta.setDisplayName(name)
            pane.itemMeta = paneMeta
            return pane
        }

        val dividerLow = getDivider("§c=> 【初级】炼制结果 =>")
        val dividerMid = getDivider("§c=> 【中级】炼制结果 =>")
        val dividerHigh = getDivider("§c=> 【高级】炼制结果 =>")

        // 3. 布局：初级 (第1行)
        for (i in 0..4) inventory.setItem(i, null) // 材料区 0-4
        for (i in 5..7) inventory.setItem(i, dividerLow) // 分界线 5-7
        inventory.setItem(8, null) // 成品区 8

        // 布局：中级 (第2行)
        for (i in 9..13) inventory.setItem(i, null) // 材料区 9-13
        for (i in 14..16) inventory.setItem(i, dividerMid) // 分界线 14-16
        inventory.setItem(17, null) // 成品区 17

        // 布局：高级 (第3行)
        for (i in 18..22) inventory.setItem(i, null) // 材料区 18-22
        for (i in 23..25) inventory.setItem(i, dividerHigh) // 分界线 23-25
        inventory.setItem(26, null) // 成品区 26

        // 4. 【找回的】保存按钮 (放在右下角)
        val saveBtn = ItemStack(Material.EMERALD_BLOCK)
        val saveMeta = saveBtn.itemMeta
        saveMeta.setDisplayName("§a[ 保存配方 ]")
        saveMeta.lore = listOf("§7点击保存当前编辑的配方")
        saveBtn.itemMeta = saveMeta
        inventory.setItem(53, saveBtn)
    }

    // 【新增】从配方数据回显到界面
    private fun loadItemsFromRecipe() {
        // 加载 Low (Start 0)
        loadTierToGui(AlchemyTier.LOW, 0)
        loadTierToGui(AlchemyTier.MID, 9)
        loadTierToGui(AlchemyTier.HIGH, 18)
    }

    private fun loadTierToGui(tier: AlchemyTier, startSlot: Int) {
        val config = editingRecipe.tierData[tier] ?: return
        // 放置材料到 0-4
        for ((index, item) in config.ingredients.withIndex()) {
            if (index < 5) inventory.setItem(startSlot + index, item.clone())
        }
        // 放置成品到第9格 (即 startSlot + 8)
        if (config.result.type != Material.AIR) {
            inventory.setItem(startSlot + 8, config.result.clone())
        }
    }


    // 辅助方法
    private fun createButton(mat: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun hexToColor(hex: String): org.bukkit.Color {
        return try {
            val clean = hex.replace("#", "")
            org.bukkit.Color.fromRGB(clean.toInt(16))
        } catch (e: Exception) { org.bukkit.Color.WHITE }
    }

    // 保存逻辑：从 GUI 读取物品回写到 Recipe 对象
    fun saveFromGui() {
        saveTierFromGui(AlchemyTier.LOW, 0)
        saveTierFromGui(AlchemyTier.MID, 9)
        saveTierFromGui(AlchemyTier.HIGH, 18)

        // 真正保存到文件
        plugin.alchemyManager.recipes[editingRecipe.id] = editingRecipe
        plugin.alchemyManager.saveRecipes() // 记得在 Manager 里把 saveRecipes 设为 public

        player.sendMessage("§a配方 ${editingRecipe.id} (${editingRecipe.displayName}) 已保存！")
        player.closeInventory()
        // 保存后重新打开列表，方便继续操作
        AlchemyAdminListGui(plugin, player).open()
    }

    private fun saveTierFromGui(tier: AlchemyTier, startSlot: Int) {
        val ingredients = ArrayList<ItemStack>()
        // 扫描材料格
        for (i in 0 until 5) {
            val item = inventory.getItem(startSlot + i)
            if (item != null && item.type != Material.AIR && !item.type.name.contains("STAINED_GLASS")) {
                ingredients.add(item.clone())
            }
        }

        // 读取成品格 (startSlot + 8)
        val resultItem = inventory.getItem(startSlot + 8)?.clone() ?: ItemStack(Material.AIR)

        if (ingredients.isNotEmpty() || resultItem.type != Material.AIR) {
            editingRecipe.tierData[tier] = TierConfig(ingredients, resultItem)
        } else {
            editingRecipe.tierData.remove(tier) // 如果全空，则删除该品阶
        }
    }
}