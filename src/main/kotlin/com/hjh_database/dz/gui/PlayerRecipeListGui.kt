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
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayList
import java.util.HashMap

class PlayerRecipeListGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String
) : InventoryHolder, Listener {

    private val inv: Inventory = Bukkit.createInventory(this, 54, getCategoryTitle(category))
    private var page = 0
    private val displayRecipes: MutableList<DzRecipe> = ArrayList()
    private val rarityPages: MutableList<Int> = ArrayList()

    // 【终极方案】不再依赖物品NBT，而是直接记录 槽位 -> 配方ID 的映射
    // 这样无论物品是否被刷新、Lore是否被清洗，都不会影响点击判定
    private val slotMap: MutableMap<Int, String> = HashMap()

    init {
        loadRecipes()
        setupPage()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun loadRecipes() {
        displayRecipes.clear()
        val all = plugin.recipeManager.getRecipesByCategory(category)

        // 假设 PlayerManager.getData 返回 nullable
        val data = plugin.playerManager.getData(player.uniqueId)

        // 处理 null 安全，Java: (data != null && data.getJob() != null)
        val myJob = data?.job ?: -1

        for (r in all) {
            // 职业过滤: -1 表示全职业通用
            if (r.reqJob != -1 && r.reqJob != myJob) {
                continue
            }
            displayRecipes.add(r)
        }
        displayRecipes.sortWith(recipeComparator())
        rarityPages.clear()
        rarityPages.addAll(displayRecipes.map { getRecipeRarity(it) }.distinct().sorted())
    }

    private fun setupPage() {
        inv.clear() // 清空当前页
        slotMap.clear() // 清空点击映射

        // 1. 设置背景填充物 (保持原逻辑)
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

        // 3. 设置翻页按钮：按稀有度翻页（保留底栏箭头）
        if (page > 0) {
            setBtn(45, Material.ARROW, "§a上一稀有度", "§7查看 ${formatRarityName(rarityPages[page - 1])}")
        }
        if (page < rarityPages.lastIndex) {
            setBtn(53, Material.ARROW, "§a下一稀有度", "§7查看 ${formatRarityName(rarityPages[page + 1])}")
        }

        // 4. 返回按钮 (保持原逻辑)
        setBtn(49, Material.BARRIER, "§c返回分类")

        if (currentRarity == null) {
            setBtn(22, Material.GRAY_DYE, "§7暂无可锻造配方")
            return
        }

        // ====================================================
        // 【核心修改区域】 配方列表渲染
        // ====================================================

        val pageRecipes = displayRecipes.filter { getRecipeRarity(it) == currentRarity }
        val slots = layoutSlots(pageRecipes.size)

        // A. 预先获取玩家数据 (用于显示 ✔/✘ 状态，不用于拦截)
        // 获取锻造数据
        val dzData = plugin.playerManager.getDzData(player.uniqueId)
        val myForgeLv = dzData?.forgeLevel ?: 1
        val myLicense = dzData?.forgeLicense ?: 0

        // 获取RPG职业数据
        val rpgData = plugin.playerManager.getData(player.uniqueId)
        val myJob = rpgData?.job ?: 0

        for ((index, recipe) in pageRecipes.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]

            // 记录槽位 -> 配方ID 的映射
            slotMap[slot] = recipe.id

            // B. 克隆结果物品 (关键：使用 clone 保留 WeaponManager 生成的原始属性)
            val icon = recipe.result.clone()
            val meta = icon.itemMeta

            if (meta != null) {
                // C. 获取物品现有的 Lore (如果有的话，比如武器的攻击力)
                val lore = meta.lore ?: ArrayList()

                // --- 在原有属性下方追加锻造信息 ---
                lore.add("")
                lore.add("§8§m------------------")

                // 1. 职业需求 (修复: -1才是通用, 0是战士)
                val jobName = DzUtil.getJobName(recipe.reqJob)
                val jobOk = (recipe.reqJob == -1) || (myJob == recipe.reqJob)
                val jobStatus = if (jobOk) "§a✔" else "§c✘"

                if (recipe.reqJob != -1) {
                    lore.add("§7职业: §f$jobName $jobStatus")
                } else {
                    lore.add("§7职业: §f通用")
                }

                // 2. 锻造等级需求
                val lvOk = myForgeLv >= recipe.reqForgeLevel
                val lvStatus = if (lvOk) "§a✔" else "§c✘"
                lore.add("§7锻造等级: §fLv.${recipe.reqForgeLevel} $lvStatus")

                // 3. 锻造资质需求
                if (recipe.reqLicense > 0) {
                    val licOk = myLicense >= recipe.reqLicense
                    val licStatus = if (licOk) "§a✔" else "§c✘"
                    lore.add("§7锻造资质: §f${recipe.reqLicense}级 $licStatus")
                }

                // 4. 经验奖励
                if (recipe.expReward > 0) {
                    lore.add("§7经验: §e+${recipe.expReward}")
                }

                // 5. 底部提示 (无论条件是否满足，都显示可点击)
                lore.add("")
                lore.add("§e▶ 点击查看配方详情")

                meta.lore = lore
                icon.itemMeta = meta
            }

            inv.setItem(slot, icon)
        }
    }

    /**
     * 在顶部第一行(slots 0-8)放置彩色羊毛阶数按钮，
     * 空余位置用灰色染色玻璃板填充。
     */
    private fun setupRarityWoolBar() {
        // 先用灰色玻璃板填充整行
        val glassFiller = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val gm = glassFiller.itemMeta
        if (gm != null) {
            gm.setDisplayName(" ")
            glassFiller.itemMeta = gm
        }
        for (i in 0 until 9) inv.setItem(i, glassFiller)

        if (rarityPages.isEmpty()) return

        // 计算居中起始位置
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
                // 使用PDC记录阶数索引，方便点击时定位
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

    /**
     * 根据阶数返回对应颜色的羊毛Material
     */
    private fun getRarityWool(rarity: Int): Material {
        return when (rarity) {
            1 -> Material.WHITE_WOOL
            2 -> Material.LIME_WOOL
            3 -> Material.BLUE_WOOL
            4 -> Material.PINK_WOOL
            5 -> Material.YELLOW_WOOL
            else -> Material.WHITE_WOOL // 无阶数或其他用白色
        }
    }

    // 原代码中有 setBtn 定义但未使用（只在内部直接 new 实现了），为保持一致性保留
    private fun setBtn(slot: Int, mat: Material, name: String, vararg lore: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            if (lore.isNotEmpty()) meta.lore = lore.toList()
            item.itemMeta = meta
        }
        inv.setItem(slot, item)
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
        if (event.currentItem == null) return // 允许点空位，反正做了判断

        val slot = event.slot

        // 0. 顶部羊毛栏点击 (slots 0-8)
        if (slot in 0..8) {
            val item = event.currentItem ?: return
            val meta = item.itemMeta ?: return
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

        // 1. 功能按钮区
        if (slot == 45) {
            if (page > 0) {
                page--
                setupPage()
            }
            return
        }
        if (slot == 53) {
            if (page < rarityPages.lastIndex) {
                page++
                setupPage()
            }
            return
        }
        if (slot == 49) {
            player.closeInventory()
            CategoryGui(plugin, player, null).open()
            return
        }

        // 2. 配方区 (使用 Map 查找)
        if (slot < 45) {
            // 直接从 Map 里查 ID，不再读取物品 NBT
            val recipeId = slotMap[slot]

            if (recipeId != null) {
                // println("[GUI命中] Slot:$slot -> ID:$recipeId")
                player.closeInventory()
                RecipePreviewGui(plugin, player, category, recipeId).open()
            } else {
                // 如果点了有物品的格子但 Map 里没 ID，说明这是异常情况
                if (event.currentItem?.type != Material.AIR) {
                    // println("[GUI未命中] Slot:$slot 有物品但无映射!")
                }
            }
        }
    }

    companion object {
        fun getCategoryTitle(category: String): String {
            return when (category) {
                "weapon" -> "锻造武器"
                "armor" -> "锻造防具"
                "artifact" -> "锻造法宝饰品"
                "misc" -> "锻造杂项"
                else -> "锻造"
            }
        }
    }
}
