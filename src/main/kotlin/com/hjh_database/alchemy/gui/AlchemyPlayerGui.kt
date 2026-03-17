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
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class AlchemyPlayerGui(
    private val plugin: Hjh_database,
    val player: Player,
    val cauldronLoc: Location
) : InventoryHolder {

    private val inventory: Inventory
    // 缓存当前界面显示的配方列表 (用于点击事件查找)
    val displayRecipes = HashMap<Int, AlchemyRecipe>()

    init {
        inventory = Bukkit.createInventory(this, 54, Component.text("§1丹药配方预览"))
        loadRecipes()
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun open() {
        player.openInventory(inventory)
    }

    private fun loadRecipes() {
        val playerData = plugin.playerManager.getPlayerData(player) ?: return
        val allRecipes = plugin.alchemyManager.recipes.values
        var slot = 0
        for (recipe in allRecipes) {
            // 确保配方不为空
            if (recipe.tierData.isEmpty()) continue

            // 获取初级成品（或者第一个存在的成品），以此来判断职业限制
            val firstTier = recipe.tierData.values.firstOrNull() ?: continue
            val resultItem = firstTier.result

            // 【修改点】从成品物品中提取资源ID，并从 ResourceManager 获取配置
            val resourceId = resultItem.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "resource_id"), PersistentDataType.STRING)
            val resourceData = if (resourceId != null) plugin.resourceManager.getLocalResource(resourceId) else null

            val onlyDoctor = resourceData?.onlyDoctor ?: false

            // 1. 检查职业限制 (如果仅限医师，且玩家不是医师，则跳过)
            if (onlyDoctor && playerData.job != 3) continue

            // 2. 检查是否有至少一个等级的配置
            if (recipe.tierData.isEmpty()) continue

            // 3. 构建图标 (使用初级成品作为图标，如果没有初级就用第一个有的)
            val iconItem = firstTier.result.clone()
            val meta = iconItem.itemMeta

            // 确保隐藏原版属性
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
            try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP) } catch (e:Error){}

            val lore = ArrayList<String>()
            lore.add(" ")
//            lore.add("§e基础等级要求: ${recipe.requiredLevel}")
            lore.add("§a点击选择炼制等级")
            meta.lore = lore
            iconItem.itemMeta = meta

            inventory.setItem(slot, iconItem)
            displayRecipes[slot] = recipe
            slot++
        }
    }

    // 打开二级菜单：选择品质
    fun openTierSelect(recipe: AlchemyRecipe) {
        // 【修改点】这里把 cauldronLoc 传进去，方便监听器获取
        val tierInv = Bukkit.createInventory(TierSelectHolder(recipe, cauldronLoc), 27, Component.text("§8选择炼制品质"))

        val playerData = plugin.playerManager.getPlayerData(player) ?: return
        val currentLevel = playerData.alchemyLevel

        // 放置三个品质的按钮
        setTierButton(tierInv, recipe, AlchemyTier.LOW, 11, currentLevel)
        setTierButton(tierInv, recipe, AlchemyTier.MID, 13, currentLevel)
        setTierButton(tierInv, recipe, AlchemyTier.HIGH, 15, currentLevel)

        player.openInventory(tierInv)
    }

    private fun setTierButton(inv: Inventory, recipe: AlchemyRecipe, tier: AlchemyTier, slot: Int, playerLevel: Int) {
        val config = recipe.tierData[tier]
        if (config == null) {
            inv.setItem(slot, createItem(Material.BARRIER, "§c${tier.displayName} (未配置)"))
            return
        }

        // 【修改点】动态读取当前品阶对应丹药的需求等级
        val resourceId = config.result.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "resource_id"), PersistentDataType.STRING)
        val resourceData = if (resourceId != null) plugin.resourceManager.getLocalResource(resourceId) else null

        val reqLevel = resourceData?.reqLevel ?: 1
        val canCraft = playerLevel >= reqLevel

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

        if (canCraft) lore.add("§a点击开始炼制") else lore.add("§c等级不足")

        val icon = if (canCraft) tier.icon else Material.GRAY_DYE
        inv.setItem(slot, createItem(icon, tier.displayName, lore))
    }

    private fun createItem(mat: Material, name: String, lore: List<String> = listOf()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    // 【修改点】内部类增加 cauldronLoc 属性
    class TierSelectHolder(val recipe: AlchemyRecipe, val cauldronLoc: Location) : InventoryHolder {
        override fun getInventory(): Inventory = Bukkit.createInventory(null, 9) // 哑实现
    }
}