package com.hjh_database.npc.gui

import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.CustomTrade
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * NPC 管理员编辑器 GUI
 */
@Suppress("DEPRECATION")
class NpcAdminGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val templateId: String,
    private val entityUuid: UUID? // 传入具体的实体UUID，用于删除操作
) : InventoryHolder, Listener {

    private val inventory: Inventory

    init {
        inventory = Bukkit.createInventory(this, 54, "编辑NPC: $templateId")
        loadContent()
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun getInventory(): Inventory = inventory

    fun open() {
        player.openInventory(inventory)
    }

    private fun loadContent() {
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return

        // === 1. 加载交易项 ===
        // 先清空交易区，防止刷新时残留
        for (i in 0 until 45) {
            inventory.setItem(i, null)
        }

        for (index in template.trades.indices) {
            if (index >= 5) break
            val trade = template.trades[index]
            val rowStart = index * 9

            inventory.setItem(rowStart + 0, trade.ingredient1)
            inventory.setItem(rowStart + 1, trade.ingredient2)
            // 箭头在下面统一设置
            inventory.setItem(rowStart + 3, trade.result)
        }

        // 统一设置箭头
        for (row in 0 until 5) {
            val arrowSlot = row * 9 + 2
            inventory.setItem(arrowSlot, createItem(Material.ARROW, "§7-->"))
        }

        // === 2. 加载底部按钮 ===
        inventory.setItem(45, createItem(Material.NAME_TAG, "§e修改名字", listOf("§7当前: ${template.name}", "§a点击输入新名字")))
        inventory.setItem(46, createItem(Material.LEATHER_CHESTPLATE, "§e切换职业", listOf("§7当前: ${template.profession.key.key}", "§a点击切换下一个")))
        inventory.setItem(47, createItem(Material.MAP, "§e切换类型", listOf("§7当前: ${template.type.key.key}", "§a点击切换下一个")))
        inventory.setItem(48, createItem(Material.EXPERIENCE_BOTTLE, "§b刷新所有实体", listOf("§7修改后点击此项", "§7让全服该ID的NPC变身")))

        // 装饰用的玻璃板 (只填 49 和 50)
        val pane = createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        inventory.setItem(49, pane)
        inventory.setItem(50, pane)

        // [新增] 种族打折开关 (Slot 51)
        val discountStatus = if (template.allowRaceDiscount) "§a已开启" else "§c已关闭"
        val switchIcon = if (template.allowRaceDiscount) Material.EMERALD else Material.REDSTONE_BLOCK

        inventory.setItem(51, createItem(switchIcon, "§e种族优惠开关", listOf(
            "§7当前状态: $discountStatus",
            "§7",
            "§e点击切换",
            "§7开启后，符合条件的人族",
            "§7玩家将获得价格优惠。"
        )))

        // [新增] 删除按钮 (Slot 52)
        inventory.setItem(52, createItem(Material.BARRIER, "§c§l删除此NPC", listOf("§7点击永久删除这个NPC实例", "§7(不会删除模板数据)")))

        // 保存按钮 (Slot 53)
        inventory.setItem(53, createItem(Material.EMERALD_BLOCK, "§a§l保存配置", listOf("§7点击保存当前交易项")))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory.holder != this) return

        // 允许上方点击与拖拽(编辑交易)，但在点击底部功能区时取消事件
        val clickedSlot = event.rawSlot
        if (clickedSlot in 45..53) {
            event.isCancelled = true

            val template = plugin.npcModule.manager.getTemplate(templateId) ?: return

            when (clickedSlot) {
                45 -> { // 修改名字
                    // 先保存当前的变更，避免数据丢失
                    saveTradesFromGui()
                    plugin.npcModule.manager.saveData()

                    plugin.npcModule.getInteractListener().editingNameMap[player.uniqueId] = templateId
                    player.closeInventory()
                    player.sendMessage("§a请在聊天栏输入新的 NPC 名字（支持颜色代码 &）：")
                }
                46 -> { // 切换职业
                    val allProfs = Registry.VILLAGER_PROFESSION.toList()
                    val currentIdx = allProfs.indexOf(template.profession)
                    val nextIdx = (currentIdx + 1) % allProfs.size
                    template.profession = allProfs[nextIdx]

                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                    loadContent() // 刷新图标
                }
                47 -> { // 切换类型
                    val allTypes = Registry.VILLAGER_TYPE.toList()
                    val currentIdx = allTypes.indexOf(template.type)
                    val nextIdx = (currentIdx + 1) % allTypes.size
                    template.type = allTypes[nextIdx]

                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                    loadContent() // 刷新图标
                }
                48 -> { // 刷新实体
                    saveTradesFromGui()
                    plugin.npcModule.manager.saveData()

                    var count = 0
                    for (world in Bukkit.getWorlds()) {
                        for (entity in world.entities) {
                            if (entity is Villager) {
                                val id = entity.persistentDataContainer.get(plugin.npcModule.manager.npcKey, PersistentDataType.STRING)
                                if (id == templateId) {
                                    entity.customName = template.name
                                    entity.profession = template.profession
                                    entity.villagerType = template.type
                                    // 刷新时再次应用属性，防止因为某些原因属性失效
                                    plugin.npcModule.manager.applyNpcAttributes(entity)
                                    count++
                                }
                            }
                        }
                    }
                    player.sendMessage("§a已刷新 $count 个 NPC 实例。")
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                }
                51 -> { // 【新增】修改打折
                    template.allowRaceDiscount = !template.allowRaceDiscount
                    // 这里不需要立即保存到文件，点击最右侧保存按钮时统一保存，或者你希望立即生效也可以：
                    // plugin.npcModule.manager.saveData()

                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                    loadContent() // 刷新界面以更新图标
                }
                52 -> { // 【新增】删除实体
                    if (entityUuid != null) {
                        plugin.npcModule.manager.removeNpc(entityUuid)
                        player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)
                        player.sendMessage("§c已删除该 NPC 实例。")
                        player.closeInventory()
                    } else {
                        player.sendMessage("§c无法获取实体信息（可能是刚改名后重新打开），请重新右键NPC。")
                        player.closeInventory()
                    }
                }
                53 -> { // 保存
                    saveTradesFromGui()
                    plugin.npcModule.manager.saveData()
                    player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)
                    player.sendMessage("§a配置已保存！")
                    player.closeInventory()
                }
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder != this) return

        // 关闭时自动保存交易项到内存（不一定保存到文件，取决于是否点击了保存按钮，但通常为了体验会存一下内存）
        saveTradesFromGui()

        InventoryClickEvent.getHandlerList().unregister(this)
        InventoryCloseEvent.getHandlerList().unregister(this)
    }

    private fun saveTradesFromGui() {
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return
        val newTrades = ArrayList<CustomTrade>()

        for (row in 0 until 5) {
            val start = row * 9
            val input1 = inventory.getItem(start + 0)
            val input2 = inventory.getItem(start + 1)
            val result = inventory.getItem(start + 3)

            if (result != null && result.type != Material.AIR &&
                input1 != null && input1.type != Material.AIR) {

                newTrades.add(CustomTrade(
                    result = result.clone(),
                    ingredient1 = input1.clone(),
                    ingredient2 = if (input2 != null && input2.type != Material.AIR) input2.clone() else null
                ))
            }
        }
        template.trades = newTrades
    }

    private fun createItem(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }
}