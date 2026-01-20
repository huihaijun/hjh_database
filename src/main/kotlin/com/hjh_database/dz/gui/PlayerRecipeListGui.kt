package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import org.bukkit.Bukkit
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
import java.util.ArrayList
import java.util.HashMap
import kotlin.math.min

class PlayerRecipeListGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String
) : InventoryHolder, Listener {

    private val inv: Inventory = Bukkit.createInventory(this, 54, "锻造列表: $category")
    private var page = 1
    private val displayRecipes: MutableList<DzRecipe> = ArrayList()

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
            // 职业过滤
            if (r.reqJob != -1 && r.reqJob != myJob) {
                continue
            }
            displayRecipes.add(r)
        }
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

        // 2. 设置翻页按钮 (保持原逻辑)
        if (page > 1) {
            val prev = ItemStack(Material.ARROW)
            val pm = prev.itemMeta
            if (pm != null) {
                pm.setDisplayName("§a上一页")
                prev.itemMeta = pm
            }
            inv.setItem(45, prev)
        }
        if ((page * 45) < displayRecipes.size) {
            val next = ItemStack(Material.ARROW)
            val nm = next.itemMeta
            if (nm != null) {
                nm.setDisplayName("§a下一页")
                next.itemMeta = nm
            }
            inv.setItem(53, next)
        }

        // 3. 返回按钮 (保持原逻辑)
        val back = ItemStack(Material.BARRIER)
        val bm = back.itemMeta
        if (bm != null) {
            bm.setDisplayName("§c返回分类")
            back.itemMeta = bm
        }
        inv.setItem(49, back)

        // ====================================================
        // 【核心修改区域】 配方列表渲染
        // ====================================================

        val startIndex = (page - 1) * 45
        val endIndex = min(startIndex + 45, displayRecipes.size)

        // A. 预先获取玩家数据 (用于显示 ✔/✘ 状态，不用于拦截)
        // 获取锻造数据
        val dzData = plugin.playerManager.getDzData(player.uniqueId)
        val myForgeLv = dzData?.forgeLevel ?: 1
        val myLicense = dzData?.forgeLicense ?: 0

        // 获取RPG职业数据
        val rpgData = plugin.playerManager.getData(player.uniqueId)
        val myJob = rpgData?.job ?: 0

        for (i in startIndex until endIndex) {
            val recipe = displayRecipes[i]
            val slot = i - startIndex

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

                // 1. 职业需求
                val jobName = DzUtil.getJobName(recipe.reqJob)
                val jobOk = (recipe.reqJob == 0) || (myJob == recipe.reqJob)
                val jobStatus = if (jobOk) "§a✔" else "§c✘"

                if (recipe.reqJob > 0) {
                    lore.add("§7职业: §f$jobName $jobStatus")
                } else {
                    lore.add("§7职业: §f通用")
                }

                // 2. 锻造等级需求
                val lvOk = myForgeLv >= recipe.reqForgeLevel
                val lvStatus = if (lvOk) "§a✔" else "§c✘"
                lore.add("§7等级: §fLv.${recipe.reqForgeLevel} $lvStatus")

                // 3. 锻造资质/执照需求 (新增)
                if (recipe.reqLicense > 0) {
                    val licOk = myLicense >= recipe.reqLicense
                    val licStatus = if (licOk) "§a✔" else "§c✘"
                    lore.add("§7资质: §f${recipe.reqLicense}级执照 $licStatus")
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

    // 原代码中有 setBtn 定义但未使用（只在内部直接 new 实现了），为保持一致性保留
    private fun setBtn(slot: Int, mat: Material, name: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
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
        if (event.currentItem == null) return // 允许点空位，反正做了判断

        val slot = event.slot

        // 1. 功能按钮区
        if (slot == 45) {
            if (page > 1) {
                page--
                setupPage()
            }
            return
        }
        if (slot == 53) {
            if ((page * 45) < displayRecipes.size) {
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
}