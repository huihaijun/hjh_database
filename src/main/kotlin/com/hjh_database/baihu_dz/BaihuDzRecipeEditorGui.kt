package com.hjh_database.baihu_dz

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

class BaihuDzRecipeEditorGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val category: String,
    private val editId: String?
) : InventoryHolder, Listener {
    private val inputSlots = intArrayOf(11, 12, 13, 14, 15)
    private val resultSlot = 24
    private val saveButtonSlot = 49
    private val inv: Inventory = Bukkit.createInventory(this, 54, if (editId == null) "新建白虎配方" else "编辑白虎配方: $editId")

    private var awaitingChat = false
    private var cachedResult: ItemStack? = null
    private var cachedIngredients: List<ItemStack>? = null
    private var cachedId: String? = null
    private var originalRecipe: DzRecipe? = null

    init {
        setup()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setup() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { setDisplayName("§7") }
        for (i in 0 until inv.size) {
            if (!isEditableSlot(i)) inv.setItem(i, filler)
        }

        val save = ItemStack(Material.LIME_WOOL)
        save.itemMeta = save.itemMeta?.apply {
            setDisplayName("§a§l[保存并设置参数]")
            lore = listOf(
                "§71. 放入成品和材料",
                "§72. 点击此按钮",
                "§73. 在聊天栏输入锻造等级、职业等参数"
            )
        }
        inv.setItem(saveButtonSlot, save)

        val recipeId = editId ?: return
        val recipe = plugin.baihuDzManager.getRecipe(category, recipeId) ?: return
        originalRecipe = recipe
        inv.setItem(resultSlot, recipe.result)
        recipe.ingredients.take(inputSlots.size).forEachIndexed { index, item ->
            inv.setItem(inputSlots[index], item)
        }
    }

    fun open() = player.openInventory(inv)
    override fun getInventory(): Inventory = inv

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv && !awaitingChat) {
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        val slot = event.rawSlot
        if (!isEditableSlot(slot) && slot < inv.size) event.isCancelled = true
        if (slot == saveButtonSlot) {
            event.isCancelled = true
            initiateSaveProcess()
        }
    }

    private fun initiateSaveProcess() {
        val result = inv.getItem(resultSlot)
        if (result == null || result.type == Material.AIR) {
            player.sendMessage("§c错误：结果槽位为空！")
            return
        }

        val baseId = ItemUtil.getPublicId(result)
        if (baseId == "AIR") {
            player.sendMessage("§c错误：无法识别结果物品的ID。")
            return
        }
        val id = editId ?: nextAvailableRecipeId(baseId)

        cachedResult = result
        cachedIngredients = inputSlots.map { inv.getItem(it) ?: ItemStack(Material.AIR) }
        cachedId = id
        awaitingChat = true
        player.closeInventory()

        player.sendMessage("§a========================================")
        player.sendMessage("§a正在保存白虎配方，ID识别为: §e$id")
        if (editId == null && id != baseId) {
            player.sendMessage("§7检测到同名配方已存在，本次自动保存为: §e$id")
        }
        val prev = originalRecipe
        if (prev != null) {
            player.sendMessage("§6当前配方参数:")
            player.sendMessage("§7  职业=${prev.reqJob}(${DzUtil.getJobName(prev.reqJob)})  锻造等级=${prev.reqForgeLevel}  经验=${prev.expReward}  资质=${prev.reqLicense}")
        } else {
            player.sendMessage("§6默认参数: §7职业=-1(全职业)  锻造等级=1  经验=10  资质=0")
        }
        player.sendMessage("§a请在聊天栏输入 4 个参数 (空格分隔):")
        player.sendMessage("§7格式: <职业> <锻造等级> <经验奖励> <锻造资质要求>")
        player.sendMessage("§7示例: 1 10 50 0")
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

        if (msg.equals("y", ignoreCase = true)) {
            val prev = originalRecipe
            saveRecipe(
                prev?.reqJob ?: -1,
                prev?.reqForgeLevel ?: 1,
                prev?.expReward ?: 10,
                prev?.reqLicense ?: 0
            )
            return
        }

        val args = msg.split("\\s+".toRegex())
        try {
            saveRecipe(
                args.getOrNull(0)?.toInt() ?: -1,
                args.getOrNull(1)?.toInt() ?: 1,
                args.getOrNull(2)?.toInt() ?: 10,
                args.getOrNull(3)?.toInt() ?: 0
            )
        } catch (_: NumberFormatException) {
            player.sendMessage("§c格式错误！请输入数字。例如: -1 1 10 0")
        }
    }

    private fun saveRecipe(job: Int, level: Int, exp: Int, license: Int) {
        val id = cachedId
        val result = cachedResult
        val ingredients = cachedIngredients
        if (id == null || result == null || ingredients == null) {
            player.sendMessage("§c错误：缓存数据丢失，保存失败。")
            awaitingChat = false
            HandlerList.unregisterAll(this)
            return
        }

        plugin.baihuDzManager.saveRecipe(DzRecipe(id, category, result, ingredients, job, level, license, exp))
        player.sendMessage("§a✔ 白虎配方保存成功！")
        player.sendMessage("§7参数: 职业=${job}(${DzUtil.getJobName(job)}) 锻造等级=$level 经验=$exp 资质=$license")

        awaitingChat = false
        HandlerList.unregisterAll(this)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            BaihuDzRecipeListGui(plugin, player, category, true).open()
        })
    }

    private fun isEditableSlot(slot: Int): Boolean {
        return slot == resultSlot || slot in inputSlots
    }

    private fun nextAvailableRecipeId(baseId: String): String {
        if (plugin.baihuDzManager.getRecipe(category, baseId) == null) return baseId
        var index = 2
        while (true) {
            val candidate = "${baseId}_$index"
            if (plugin.baihuDzManager.getRecipe(category, candidate) == null) return candidate
            index++
        }
    }
}
