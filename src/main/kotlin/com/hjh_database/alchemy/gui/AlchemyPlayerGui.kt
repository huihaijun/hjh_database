package com.hjh_database.alchemy.gui

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class AlchemyPlayerGui(
    private val plugin: Hjh_database,
    val player: Player,
    val cauldronLoc: Location
) : InventoryHolder {

    private val inventory: Inventory
    val displayRecipes = HashMap<Int, AlchemyRecipe>()
    private val categorySlots = HashMap<Int, String>()
    private var selectedCategory: String? = null

    init {
        inventory = Bukkit.createInventory(this, 54, Component.text("§1丹药配方预览"))
        renderRecipes()
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun open() {
        player.openInventory(inventory)
    }

    fun selectCategory(slot: Int): Boolean {
        val category = categorySlots[slot] ?: return false
        if (selectedCategory != category) {
            selectedCategory = category
            renderRecipes()
        }
        return true
    }

    private fun renderRecipes() {
        inventory.clear()
        displayRecipes.clear()
        categorySlots.clear()

        val beginnerOnly = plugin.playerManager.getPlayerData(player)?.status == STORY_IN_PROGRESS_STATUS
        val recipesByCategory = plugin.alchemyManager.recipes.values
            .filter { it.tierData.isNotEmpty() }
            .filter { !beginnerOnly || getRecipeCategory(it) == BEGINNER_CATEGORY }
            .groupBy { getRecipeCategory(it) }
            .toSortedMap(compareBy<String> { categoryOrder(it) }.thenBy { it })

        val currentCategory = selectedCategory
        if (currentCategory == null || !recipesByCategory.containsKey(currentCategory)) {
            selectedCategory = recipesByCategory.keys.firstOrNull()
        }

        renderCategoryBar(recipesByCategory)

        val recipes = recipesByCategory[selectedCategory].orEmpty()
            .sortedBy { it.displayName }
        for ((index, recipe) in recipes.take(RECIPE_SLOT_COUNT).withIndex()) {
            val firstTier = firstTier(recipe) ?: continue
            val slot = RECIPE_START_SLOT + index
            val iconItem = firstTier.result.clone()
            val meta = iconItem.itemMeta
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            val lore = ArrayList<String>()
            lore.addAll(meta.lore ?: emptyList())
            if (lore.isNotEmpty()) {
                lore.add(" ")
            }
            lore.add("§a点击选择炼制等级")
            meta.lore = lore
            iconItem.itemMeta = meta

            inventory.setItem(slot, iconItem)
            displayRecipes[slot] = recipe
        }
    }

    private fun renderCategoryBar(recipesByCategory: Map<String, List<AlchemyRecipe>>) {
        val filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in CATEGORY_SLOTS) inventory.setItem(slot, filler)

        val categories = recipesByCategory.keys.take(CATEGORY_SLOTS.count())
        val startSlot = (CATEGORY_SLOTS.count() - categories.size) / 2
        for ((index, category) in categories.withIndex()) {
            val slot = startSlot + index
            val icon = ItemStack(categoryMaterial(category))
            val meta = icon.itemMeta
            val isSelected = category == selectedCategory
            meta.setDisplayName(
                if (isSelected) "§a§l${categoryDisplayName(category)} §f[当前]"
                else "§e${categoryDisplayName(category)} §7[点击切换]"
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            icon.itemMeta = meta

            inventory.setItem(slot, icon)
            categorySlots[slot] = category
        }
    }

    private fun getRecipeCategory(recipe: AlchemyRecipe): String {
        val result = firstTier(recipe)?.result ?: return OTHER_CATEGORY
        val resourceId = result.itemMeta?.persistentDataContainer
            ?.get(NamespacedKey(plugin, "resource_id"), PersistentDataType.STRING)
            ?: return OTHER_CATEGORY
        return plugin.resourceManager.getLocalResource(resourceId)?.category ?: OTHER_CATEGORY
    }

    private fun firstTier(recipe: AlchemyRecipe) = sequenceOf(AlchemyTier.LOW, AlchemyTier.MID, AlchemyTier.HIGH)
        .mapNotNull { recipe.tierData[it] }
        .firstOrNull()

    private fun categoryDisplayName(category: String): String = when (category) {
        BEGINNER_CATEGORY -> "初窥"
        "GUIYUAN" -> "归元"
        "ZHUSHI" -> "助势"
        "FEIDAN" -> "飞丹"
        OTHER_CATEGORY -> "其他"
        else -> category
    }

    private fun categoryOrder(category: String): Int = when (category) {
        BEGINNER_CATEGORY -> 0
        "GUIYUAN" -> 1
        "ZHUSHI" -> 2
        "FEIDAN" -> 3
        OTHER_CATEGORY -> Int.MAX_VALUE
        else -> 100
    }

    private fun categoryMaterial(category: String): Material = when (category) {
        BEGINNER_CATEGORY -> Material.WHITE_WOOL
        "GUIYUAN" -> Material.LIGHT_BLUE_WOOL
        "ZHUSHI" -> Material.ORANGE_WOOL
        "FEIDAN" -> Material.PURPLE_WOOL
        else -> Material.GRAY_WOOL
    }

    // 打开二级菜单：选择品质
    fun openTierSelect(recipe: AlchemyRecipe) {
        // 【修改点】这里把 cauldronLoc 传进去，方便监听器获取
        val tierInv = Bukkit.createInventory(TierSelectHolder(recipe, cauldronLoc), 27, Component.text("§8选择炼制品质"))

        val playerData = plugin.playerManager.getPlayerData(player) ?: return
        val currentLevel = playerData.alchemyLevel

        // 放置三个品质的按钮
        setTierButton(tierInv, recipe, AlchemyTier.LOW, 11, currentLevel, playerData.job)
        setTierButton(tierInv, recipe, AlchemyTier.MID, 13, currentLevel, playerData.job)
        setTierButton(tierInv, recipe, AlchemyTier.HIGH, 15, currentLevel, playerData.job)

        player.openInventory(tierInv)
    }

    private fun setTierButton(inv: Inventory, recipe: AlchemyRecipe, tier: AlchemyTier, slot: Int, playerLevel: Int, playerJob: Int?) {
        val config = recipe.tierData[tier]
        if (config == null) {
            inv.setItem(slot, createItem(Material.BARRIER, "§c${tier.displayName} (当前丹药无此等级)"))
            return
        }

        // 【修改点】动态读取当前品阶对应丹药的需求等级
        val resourceId = config.result.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "resource_id"), PersistentDataType.STRING)
        val resourceData = if (resourceId != null) plugin.resourceManager.getLocalResource(resourceId) else null

        val reqLevel = resourceData?.reqLevel ?: 1
        val isDoctorOnlyTier = tier == AlchemyTier.HIGH
        val canCraft = playerLevel >= reqLevel && (!isDoctorOnlyTier || playerJob == 3)

        val lore = ArrayList<String>()
        lore.add("§7需要炼药等级: $reqLevel")
        lore.add(" ")
        lore.add("§7所需材料:")
        for (ing in config.ingredients) {
            lore.add(" §f- ${ing.itemMeta?.displayName ?: ing.type.name} x${ing.amount}")
        }

        // 【新增：产出显示逻辑】
        lore.add(" ")
        val resName = config.result.itemMeta?.displayName ?: "未知丹药"
        val resAmount = config.result.amount
        lore.add("§a产出: §b$resName §fx$resAmount")

        when {
            canCraft -> lore.add("§a点击开始炼制")
            playerLevel < reqLevel -> lore.add("§c等级不足")
            isDoctorOnlyTier -> lore.add("§c高级丹药仅医师可炼制")
        }

        val icon = if (canCraft) tier.icon else Material.GRAY_DYE
        inv.setItem(slot, createItem(icon, tier.displayName, lore))
    }

    private fun createItem(mat: Material, name: String, lore: List<String> = listOf()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        item.itemMeta = meta
        return item
    }

    // 【修改点】内部类增加 cauldronLoc 属性
    class TierSelectHolder(val recipe: AlchemyRecipe, val cauldronLoc: Location) : InventoryHolder {
        override fun getInventory(): Inventory = Bukkit.createInventory(null, 9) // 哑实现
    }

    companion object {
        private val CATEGORY_SLOTS = 0..8
        private const val RECIPE_START_SLOT = 9
        private const val RECIPE_SLOT_COUNT = 45
        private const val OTHER_CATEGORY = "OTHER"
        private const val BEGINNER_CATEGORY = "CHUKUI"
        private const val STORY_IN_PROGRESS_STATUS = 2
    }
}
