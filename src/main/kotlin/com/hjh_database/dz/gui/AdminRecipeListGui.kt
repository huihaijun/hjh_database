package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayList
import java.util.HashMap

class AdminRecipeListGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String
) : InventoryHolder, Listener {

    private val inv: Inventory = Bukkit.createInventory(this, 54, "配方管理: ${PlayerRecipeListGui.getCategoryTitle(category)}")
    private var page = 0
    private val allRecipes: MutableList<DzRecipe> = ArrayList()
    private val rarityPages: MutableList<Int> = ArrayList()

    // 槽位 -> 配方ID 映射（与玩家界面一致的方案）
    private val slotMap: MutableMap<Int, String> = HashMap()

    // 【核心修复】1.21.3 替代 localizedName 的方案
    private val recipeIdKey = NamespacedKey(plugin, "gui_recipe_id")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        loadRecipes()
        setupPage()
    }

    private fun loadRecipes() {
        allRecipes.clear()
        allRecipes.addAll(plugin.recipeManager.getRecipesByCategory(category))
        allRecipes.sortWith(recipeComparator())
        rarityPages.clear()
        rarityPages.addAll(allRecipes.map { getRecipeRarity(it) }.distinct().sorted())
    }

    private fun setupPage() {
        inv.clear()
        slotMap.clear()

        // 1. 底栏背景填充
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val fm = filler.itemMeta
        if (fm != null) {
            fm.setDisplayName(" ")
            filler.itemMeta = fm
        }
        for (i in 45 until 54) inv.setItem(i, filler)

        val currentRarity = rarityPages.getOrNull(page)

        // 2. 顶部彩色羊毛阶数筛选栏
        setupRarityWoolBar()

        // 3. 翻页按钮
        if (page > 0) {
            setBtn(45, Material.ARROW, "§a上一稀有度", "§7查看 ${formatRarityName(rarityPages[page - 1])}")
        }
        if (page < rarityPages.lastIndex) {
            setBtn(53, Material.ARROW, "§a下一稀有度", "§7查看 ${formatRarityName(rarityPages[page + 1])}")
        }

        // 4. 返回按钮
        setBtn(47, Material.BARRIER, "§c返回分类")

        // 5. 新建配方按钮
        setBtn(49, Material.ANVIL, "§a§l[+ 新建配方]", "§7点击打开编辑器", "§7放入物品后保存即可")

        if (currentRarity == null) {
            setBtn(22, Material.GRAY_DYE, "§7暂无配方")
            return
        }

        // 6. 渲染当前阶数的配方列表
        val pageRecipes = allRecipes.filter { getRecipeRarity(it) == currentRarity }
        val slots = layoutSlots(pageRecipes.size)

        for ((index, recipe) in pageRecipes.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]

            slotMap[slot] = recipe.id

            val icon = recipe.result.clone()
            val meta = icon.itemMeta

            if (meta != null) {
                val lore = meta.lore ?: ArrayList()

                lore.add("")
                lore.add("§8§m------------------")

                // 职业需求 (使用中文名)
                val jobName = DzUtil.getJobName(recipe.reqJob)
                if (recipe.reqJob != -1) {
                    lore.add("§7职业: §f$jobName")
                } else {
                    lore.add("§7职业: §f通用")
                }

                // 锻造等级需求
                lore.add("§7锻造等级: §fLv.${recipe.reqForgeLevel}")

                // 锻造资质需求
                if (recipe.reqLicense > 0) {
                    lore.add("§7锻造资质: §f${recipe.reqLicense}级")
                }

                // 锻造经验
                lore.add("§7锻造经验: §e+${recipe.expReward}")

                lore.add("")
                lore.add("§7ID: §8${recipe.id}")
                lore.add("§a[左键] 编辑  §c[右键] 删除")

                meta.lore = lore

                // 使用 PDC 存储配方 ID
                meta.persistentDataContainer.set(recipeIdKey, PersistentDataType.STRING, recipe.id)

                icon.itemMeta = meta
            }

            inv.setItem(slot, icon)
        }
    }

    /**
     * 在顶部第一行(slots 0-8)放置彩色羊毛阶数按钮，空余位置灰色玻璃板填充
     */
    private fun setupRarityWoolBar() {
        val glassFiller = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val gm = glassFiller.itemMeta
        if (gm != null) {
            gm.setDisplayName(" ")
            glassFiller.itemMeta = gm
        }
        for (i in 0 until 9) inv.setItem(i, glassFiller)

        if (rarityPages.isEmpty()) return

        val count = rarityPages.size
        val startSlot = (9 - count) / 2

        for ((idx, rarity) in rarityPages.withIndex()) {
            val slot = startSlot + idx
            if (slot < 0 || slot > 8) continue

            val woolMat = getRarityWool(rarity)
            val wool = ItemStack(woolMat)
            val meta = wool.itemMeta
            if (meta != null) {
                val isCurrentPage = (idx == page)
                val rarityName = formatRarityName(rarity)
                if (isCurrentPage) {
                    meta.setDisplayName("$rarityName §l◀ 当前")
                } else {
                    meta.setDisplayName("$rarityName §7(点击切换)")
                }
                meta.persistentDataContainer.set(
                    NamespacedKey(plugin, "rarity_page_idx"),
                    PersistentDataType.INTEGER,
                    idx
                )
                wool.itemMeta = meta
            }
            inv.setItem(slot, wool)
        }
    }

    private fun getRarityWool(rarity: Int): Material {
        return when (rarity) {
            1 -> Material.WHITE_WOOL
            2 -> Material.LIME_WOOL
            3 -> Material.BLUE_WOOL
            4 -> Material.PINK_WOOL
            5 -> Material.YELLOW_WOOL
            else -> Material.WHITE_WOOL
        }
    }

    private fun recipeComparator(): Comparator<DzRecipe> {
        return compareBy<DzRecipe> { getRecipeRarity(it) }
            .thenBy { if (category == "armor") armorPieceOrder(it.result.type) else 0 }
            .thenBy { stripColor(getItemDisplayName(it.result)) }
    }

    private fun getRecipeRarity(recipe: DzRecipe): Int {
        val meta = recipe.result.itemMeta ?: return 1
        val key = NamespacedKey(plugin, "rarity")
        meta.persistentDataContainer.get(key, PersistentDataType.INTEGER)?.let { return it }

        val weaponKey = NamespacedKey(plugin, "weapon_id")
        val armorKey = NamespacedKey(plugin, "armor_id")
        val idKey = NamespacedKey(plugin, "resource_id")
        val itemId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
            ?: meta.persistentDataContainer.get(armorKey, PersistentDataType.STRING)
            ?: meta.persistentDataContainer.get(idKey, PersistentDataType.STRING)

        if (itemId != null) {
            when (category) {
                "weapon" -> plugin.playerManager.weaponManager.loadedWeapons[itemId]?.rarity?.let { return it }
                "armor" -> plugin.playerManager.armorManager.loadedArmors[itemId]?.rarity?.let { return it }
            }
        }

        val lore = meta.lore ?: return 1
        val rarityLine = lore.firstOrNull { ChatColor.stripColor(it)?.contains("稀有度") == true } ?: return 1
        return rarityLine.count { it == '★' }.coerceAtLeast(1)
    }

    private fun armorPieceOrder(material: Material): Int {
        return when {
            material.name.endsWith("_HELMET") -> 0
            material.name.endsWith("_CHESTPLATE") -> 1
            material.name.endsWith("_LEGGINGS") -> 2
            material.name.endsWith("_BOOTS") -> 3
            else -> 4
        }
    }

    private fun layoutSlots(size: Int): List<Int> {
        if (size <= 0) return emptyList()
        val rows = listOf(
            listOf(19, 20, 21, 22, 23, 24, 25),
            listOf(28, 29, 30, 31, 32, 33, 34),
            listOf(10, 11, 12, 13, 14, 15, 16),
            listOf(37, 38, 39, 40, 41, 42, 43)
        )
        val result = mutableListOf<Int>()
        var remaining = size
        for (row in rows) {
            if (remaining <= 0) break
            val take = remaining.coerceAtMost(row.size)
            val start = (row.size - take) / 2
            result.addAll(row.subList(start, start + take))
            remaining -= take
        }
        return result
    }

    private fun formatRarityName(rarity: Int): String {
        val color = when (rarity) {
            1 -> "§f"
            2 -> "§a"
            3 -> "§9"
            4 -> "§d"
            5 -> "§e"
            6 -> "§c"
            else -> "§7"
        }
        return "${color}${rarity}阶"
    }

    private fun getItemDisplayName(item: ItemStack): String {
        val meta = item.itemMeta
        return if (meta != null && meta.hasDisplayName()) meta.displayName else item.type.name
    }

    private fun stripColor(text: String): String = ChatColor.stripColor(text) ?: text

    private fun setBtn(slot: Int, mat: Material, name: String, vararg lore: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = ArrayList(listOf(*lore))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
        }
        inv.setItem(slot, item)
    }

    fun open() {
        player.openInventory(inv)
    }

    override fun getInventory(): Inventory {
        return inv
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv) {
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true

        val currentItem = event.currentItem
        if (currentItem == null || currentItem.type == Material.AIR) return

        val slot = event.slot

        // 0. 顶部羊毛栏点击 (slots 0-8)
        if (slot in 0..8) {
            val meta = currentItem.itemMeta ?: return
            val pageKey = NamespacedKey(plugin, "rarity_page_idx")
            if (meta.persistentDataContainer.has(pageKey, PersistentDataType.INTEGER)) {
                val targetPage = meta.persistentDataContainer.get(pageKey, PersistentDataType.INTEGER) ?: return
                if (targetPage != page) {
                    page = targetPage
                    setupPage()
                }
            }
            return
        }

        // 1. 翻页
        if (slot == 45 && page > 0) {
            page--
            setupPage()
            return
        }
        if (slot == 53 && page < rarityPages.lastIndex) {
            page++
            setupPage()
            return
        }

        // 2. 返回分类
        if (slot == 47) {
            player.closeInventory()
            CategoryGui(plugin, player, null).open()
            return
        }

        // 3. 新建配方
        if (slot == 49) {
            player.closeInventory()
            RecipeEditorGui(plugin, player, category, null).open()
            return
        }

        // 4. 配方区
        if (slot < 45) {
            val recipeId = slotMap[slot] ?: return

            if (event.click == ClickType.RIGHT) {
                plugin.recipeManager.deleteRecipe(category, recipeId)
                player.sendMessage("§c已删除: $recipeId")
                loadRecipes()
                setupPage()
            } else {
                player.closeInventory()
                RecipeEditorGui(plugin, player, category, recipeId).open()
            }
        }
    }
}
