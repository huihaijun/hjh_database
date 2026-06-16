package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.DzUtil
import com.hjh_database.util.ItemUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.ArrayList
import java.util.Arrays

class RecipeEditorGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val editId: String? // 允许为 null，代表新建
) : InventoryHolder, Listener {

    private val inv: Inventory
    private var awaitingChat = false

    // 缓存变量，初始化为 null
    private var cachedResult: ItemStack? = null
    private var cachedIngredients: List<ItemStack>? = null
    private var cachedId: String? = null

    // 缓存原配方对象（编辑时用于 y/Y 快捷保存）
    private var originalRecipe: DzRecipe? = null

    private val INPUT_SLOTS = intArrayOf(11, 12, 13, 14, 15)
    private val RESULT_SLOT = 24
    private val SAVE_BUTTON_SLOT = 49

    init {
        val title = if (editId == null) "新建配方" else "编辑: $editId"
        this.inv = Bukkit.createInventory(this, 54, title)

        setupGui()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setupGui() {
        val bg = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val bgm = bg.itemMeta
        if (bgm != null) {
            bgm.setDisplayName("§7")
            bg.itemMeta = bgm
        }
        for (i in 0 until 54) {
            if (!isInputOrResult(i)) inv.setItem(i, bg)
        }

        val save = ItemStack(Material.LIME_WOOL)
        val sm = save.itemMeta
        if (sm != null) {
            sm.setDisplayName("§a§l[保存并设置参数]")
            sm.lore = Arrays.asList(
                "§71. 放入成品和材料",
                "§72. 点击此按钮",
                "§73. 在聊天栏输入锻造等级、职业等参数"
            )
            save.itemMeta = sm
        }
        inv.setItem(SAVE_BUTTON_SLOT, save)

        if (editId != null) {
            val r = plugin.recipeManager.getRecipe(category, editId)
            if (r != null) {
                originalRecipe = r
                inv.setItem(RESULT_SLOT, r.result)
                val ings = r.ingredients
                var i = 0
                while (i < ings.size && i < INPUT_SLOTS.size) {
                    inv.setItem(INPUT_SLOTS[i], ings[i])
                    i++
                }
            }
        }
    }

    private fun isInputOrResult(slot: Int): Boolean {
        if (slot == RESULT_SLOT) return true
        for (s in INPUT_SLOTS) if (s == slot) return true
        return false
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
            if (!awaitingChat) {
                HandlerList.unregisterAll(this)
            }
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        val slot = event.rawSlot
        if (!isInputOrResult(slot) && slot < 54) event.isCancelled = true

        if (slot == SAVE_BUTTON_SLOT) {
            event.isCancelled = true
            initiateSaveProcess()
        }
    }

    private fun initiateSaveProcess() {
        val result = inv.getItem(RESULT_SLOT)
        if (result == null || result.type == Material.AIR) {
            player.sendMessage("§c错误：结果槽位为空！")
            return
        }

        // 核心：识别ID
        val id = editId ?: ItemUtil.getPublicId(result)

        // 如果识别出来是 AIR，说明这既不是 RPG物品，也不是原版物品，这几乎不可能发生，除非 ItemUtil 逻辑有误
        if (id == "AIR") {
            player.sendMessage("§c错误：无法识别结果物品的ID。")
            return
        }

        val ings: MutableList<ItemStack> = ArrayList()
        for (s in INPUT_SLOTS) {
            val it = inv.getItem(s)
            ings.add(it ?: ItemStack(Material.AIR))
        }

        this.cachedResult = result
        this.cachedIngredients = ings
        this.cachedId = id
        this.awaitingChat = true

        player.closeInventory()

        player.sendMessage("§a========================================")
        player.sendMessage("§a正在保存配方，ID识别为: §e$id")
        if (id == result.type.name) {
            player.sendMessage("§7(提示: 这是一个原版物品配方)")
        } else {
            player.sendMessage("§7(提示: 这是一个自定义RPG物品配方)")
        }

        // 显示当前参数（编辑时显示原有值，新建时显示默认值）
        val prev = originalRecipe
        if (prev != null) {
            player.sendMessage("§6当前配方参数:")
            player.sendMessage("§7  职业=${prev.reqJob}(${DzUtil.getJobName(prev.reqJob)})  锻造等级=${prev.reqForgeLevel}  经验=${prev.expReward}  资质=${prev.reqLicense}")
        } else {
            player.sendMessage("§6默认参数: §7职业=-1(全职业)  锻造等级=1  经验=10  资质=0")
        }

        player.sendMessage("§a请在聊天栏输入 4 个参数 (空格分隔):")
        player.sendMessage("§7格式: <职业> <锻造等级> <经验奖励> <锻造资质要求>")
        player.sendMessage("§7示例: 1 10 50 0  (代表:弓手 锻造等级10 50经验 锻造资质0)")
        player.sendMessage("§e输入 'y' 使用上述参数直接保存")
        player.sendMessage("§e输入 'cancel' 取消保存")
        player.sendMessage("§a========================================")
    }

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        if (event.player != player || !awaitingChat) return

        event.isCancelled = true
        val msg = event.message.trim()

        if (msg.equals("cancel", ignoreCase = true)) {
            player.sendMessage("§c已取消保存。")
            awaitingChat = false
            HandlerList.unregisterAll(this)
            return
        }

        // y/Y 快捷保存: 使用之前配置好的数据
        if (msg.equals("y", ignoreCase = true)) {
            val prev = originalRecipe
            val job: Int
            val lv: Int
            val exp: Int
            val lic: Int

            if (prev != null) {
                // 编辑已有配方：复用原配方参数
                job = prev.reqJob
                lv = prev.reqForgeLevel
                exp = prev.expReward
                lic = prev.reqLicense
            } else {
                // 新建配方：使用默认值
                job = -1
                lv = 1
                exp = 10
                lic = 0
            }

            saveRecipe(job, lv, exp, lic)
            return
        }

        val args = msg.split("\\s+".toRegex()).toTypedArray()
        try {
            val job = if (args.isNotEmpty()) args[0].toInt() else -1
            val lv = if (args.size > 1) args[1].toInt() else 1
            val exp = if (args.size > 2) args[2].toInt() else 10
            val lic = if (args.size > 3) args[3].toInt() else 0

            saveRecipe(job, lv, exp, lic)

        } catch (e: NumberFormatException) {
            player.sendMessage("§c格式错误！请输入数字。例如: -1 1 10 0")
        }
    }

    /**
     * 执行配方保存的公共逻辑
     */
    private fun saveRecipe(job: Int, lv: Int, exp: Int, lic: Int) {
        if (cachedId != null && cachedResult != null && cachedIngredients != null) {
            val recipe = DzRecipe(
                cachedId!!, category, cachedResult!!, cachedIngredients!!,
                job, lv, lic, exp
            )
            plugin.recipeManager.saveRecipe(recipe)
            player.sendMessage("§a✔ 配方保存成功！")
            player.sendMessage("§7参数: 职业=${job}(${DzUtil.getJobName(job)}) 锻造等级=$lv 经验=$exp 资质=$lic")
        } else {
            player.sendMessage("§c错误：缓存数据丢失，保存失败。")
        }

        awaitingChat = false
        HandlerList.unregisterAll(this)

        // 回到主线程打开 GUI
        Bukkit.getScheduler().runTask(plugin, Runnable {
            AdminRecipeListGui(plugin, player, category).open()
        })
    }
}
