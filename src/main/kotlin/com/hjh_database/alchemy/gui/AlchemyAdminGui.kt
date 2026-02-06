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
        updateButtons() // 刷新一次按钮状态
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun open() {
        player.openInventory(inventory)
    }

    private fun setupLayout() {
        // 1. 铺设背景板（第4,5行分隔符）
        val pane = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = pane.itemMeta
        meta.setDisplayName("§8分隔符")
        pane.itemMeta = meta

        for (i in 27 until 45) {
            inventory.setItem(i, pane)
        }

        // 2. 标记三个等级区域的提示 (可选)
        // 0-8: LOW, 9-17: MID, 18-26: HIGH
    }

    // 【新增】从配方数据回显到界面
    private fun loadItemsFromRecipe() {
        // 加载 Low (Start 0)
        editingRecipe.tierData[AlchemyTier.LOW]?.let { loadTierToGui(it, 0) }
        // 加载 Mid (Start 9)
        editingRecipe.tierData[AlchemyTier.MID]?.let { loadTierToGui(it, 9) }
        // 加载 High (Start 18)
        editingRecipe.tierData[AlchemyTier.HIGH]?.let { loadTierToGui(it, 18) }
    }

    private fun loadTierToGui(config: TierConfig, startSlot: Int) {
        // 填充原材料 (0-4 格)
        for ((index, ingredient) in config.ingredients.withIndex()) {
            if (index < 5) {
                inventory.setItem(startSlot + index, ingredient.clone())
            }
        }
        // 填充成品 (第8格 -> startSlot + 8)
        inventory.setItem(startSlot + 8, config.result.clone())
    }

    // 更新按钮显示状态 (复用你之前的逻辑，这里只展示关键部分)
    fun updateButtons() {
        // 45: 医师限制
        inventory.setItem(45, createButton(Material.IRON_SWORD, "§f医师专属: ${if (editingRecipe.onlyDoctor) "§a是" else "§c否"}", listOf("§7点击切换")))

        // 46: 药毒时间
        inventory.setItem(46, createButton(Material.ROTTEN_FLESH, "§f药毒时间: §e${editingRecipe.sicknessTime}秒", listOf("§7左键+5s, 右键-5s")))

        // 47: 颜色
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val meta = chestplate.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta
        meta.setColor(hexToColor(editingRecipe.colorHex))
        meta.setDisplayName("§f当前颜色: ${editingRecipe.colorHex}")
        meta.lore = listOf("§7点击切换预设颜色")
        chestplate.itemMeta = meta
        inventory.setItem(47, chestplate)

        // 48: 等级要求
        inventory.setItem(48, createButton(Material.EXPERIENCE_BOTTLE, "§f基础等级: §e${editingRecipe.requiredLevel}", listOf("§7左键+1, 右键-1")))

        // 49: 保存
        inventory.setItem(49, createButton(Material.WRITABLE_BOOK, "§a[保存配方]", listOf("§7保存所有更改")))

        // 50：设置获得的经验
        val expItem = ItemStack(Material.EXPERIENCE_BOTTLE)
        val expMeta = expItem.itemMeta
        expMeta.setDisplayName("§e设置冶药经验")
        expMeta.lore = listOf(
            "§7当前基础经验: §f${editingRecipe.baseExp}",
            "§7(初级炼制获得的经验)",
            "",
            "§7中级炼制: §f${editingRecipe.baseExp + 10}",
            "§7高级炼制: §f${editingRecipe.baseExp + 20}",
            "",
            "§a左键: +5  §c右键: -5"
        )
        expItem.itemMeta = expMeta
        inventory.setItem(50, expItem) // Slot 51

        // 53: 关闭
        inventory.setItem(53, createButton(Material.BARRIER, "§c关闭", listOf("§7放弃更改")))
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
        // 扫描前5格
        for (i in 0 until 5) {
            val item = inventory.getItem(startSlot + i)
            if (item != null && item.type != Material.AIR && !item.type.name.contains("STAINED_GLASS")) {
                ingredients.add(item.clone())
            }
        }
        // 扫描成品格 (第9格)
        val result = inventory.getItem(startSlot + 8)

        if (result != null && result.type != Material.AIR) {
            editingRecipe.tierData[tier] = TierConfig(ingredients, result.clone())
        } else {
            editingRecipe.tierData.remove(tier)
        }
    }
}