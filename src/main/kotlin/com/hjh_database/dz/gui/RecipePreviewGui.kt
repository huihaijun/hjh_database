package com.hjh_database.dz.gui

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
import java.util.Arrays

class RecipePreviewGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    recipeId: String
) : InventoryHolder, Listener {

    private val recipe: DzRecipe?
    private val inv: Inventory

    private val INPUT_SLOTS = intArrayOf(11, 12, 13, 14, 15)
    private val RESULT_SLOT = 24
    private val START_BUTTON_SLOT = 49
    private val BACK_BUTTON_SLOT = 45

    init {
        // 获取配方
        this.recipe = plugin.recipeManager.getRecipe(category, recipeId)

        if (this.recipe == null) {
            // 如果 ID 传过来了但配方找不到 (比如文件被删了)
            player.sendMessage("${ChatColor.RED}无法加载配方预览: $recipeId (数据可能已丢失)")
            this.inv = Bukkit.createInventory(this, 9, "错误")
            // Kotlin init 块不能 return，但通过 if-else 跳过后续初始化逻辑
        } else {
            this.inv = Bukkit.createInventory(this, 54, "${plainItemName(this.recipe.result)}配方预览")
            setupGui()
            plugin.server.pluginManager.registerEvents(this, plugin)
        }
    }

    private fun setupGui() {
        // 安全检查：虽然进入此方法 recipe 理论上不为 null，但 inv 是非空的
        val bg = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = bg.itemMeta
        if (meta != null) {
            meta.setDisplayName(" ")
            bg.itemMeta = meta
        }
        for (i in 0 until 54) inv.setItem(i, bg)

        if (recipe != null) {
            // 1. 展示材料
            val ingredients = recipe.ingredients
            // Kotlin 集合默认非空，但为了保持原逻辑判断 check
            if (ingredients != null) {
                for (i in ingredients.indices) {
                    if (i < INPUT_SLOTS.size) {
                        inv.setItem(INPUT_SLOTS[i], ingredients[i])
                    }
                }
            }
            // 2. 展示成品
            inv.setItem(RESULT_SLOT, recipe.result)

            // 3. 展示详情
            val info = ItemStack(Material.PAPER)
            val im = info.itemMeta
            if (im != null) {
                im.setDisplayName("§e§l配方要求")
                im.lore = Arrays.asList(
                    "§7职业: " + DzUtil.getJobName(recipe.reqJob),
                    "§7锻造等级: " + recipe.reqForgeLevel,
                    "§7锻造资质: " + recipe.reqLicense,
                    "§7锻造成功奖励经验: " + recipe.expReward
                )
                info.itemMeta = im
            }
            inv.setItem(23, info)
        }

        // 返回按钮
        val back = ItemStack(Material.RED_BED)
        val bm = back.itemMeta
        if (bm != null) {
            bm.setDisplayName("§c返回列表")
            back.itemMeta = bm
        }
        inv.setItem(BACK_BUTTON_SLOT, back)

        // 开始锻造按钮
        val start = ItemStack(Material.ANVIL)
        val sm = start.itemMeta
        if (sm != null) {
            sm.setDisplayName("§a§l[开始锻造]")
            sm.lore = Arrays.asList("§7点击进入材料投放界面", "§7开始制作物品")
            start.itemMeta = sm
        }
        inv.setItem(START_BUTTON_SLOT, start)
    }

    fun open() {
        if (recipe == null) return // 如果配方为空，不打开界面
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

        val slot = event.slot
        if (slot == BACK_BUTTON_SLOT) {
            player.closeInventory()
            PlayerRecipeListGui(plugin, player, category).open()
        } else if (slot == START_BUTTON_SLOT) {
            if (recipe != null) {
                player.closeInventory()
                // 跳转到锻造台
                RecipeCraftingGui(plugin, player, category, recipe).open()
            }
        }
    }

    private fun plainItemName(item: ItemStack): String {
        val meta = item.itemMeta
        val name = if (meta != null && meta.hasDisplayName()) meta.displayName else item.type.name
        return ChatColor.stripColor(name) ?: name
    }
}
