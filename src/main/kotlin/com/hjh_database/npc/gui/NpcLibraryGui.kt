package com.hjh_database.npc.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.math.ceil

/** 只展示管理员用石锄明确保存的 NPC 模板；最后一行用于分页。 */
class NpcLibraryGui(
    private val plugin: Hjh_database,
    private val player: Player,
    targetLocation: Location
) : InventoryHolder, Listener {

    companion object {
        private const val PAGE_SIZE = 45
        private const val INVENTORY_SIZE = 54
        private const val PREVIOUS_SLOT = 45
        private const val PAGE_INFO_SLOT = 49
        private const val NEXT_SLOT = 53
    }

    private val targetLocation = targetLocation.clone()
    private val inventory: Inventory = Bukkit.createInventory(this, INVENTORY_SIZE, "NPC 模板库 (左键生成/右键移除)")
    private val slotMap = HashMap<Int, String>()
    private var currentPage = 0

    init {
        loadContent()
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun getInventory(): Inventory = inventory

    fun open() {
        if (!player.isOp) {
            player.sendMessage("§c只有管理员能打开 NPC 模板库。")
            HandlerList.unregisterAll(this)
            return
        }
        player.openInventory(inventory)
    }

    private fun loadContent() {
        inventory.clear()
        slotMap.clear()

        val templates = plugin.npcModule.manager.getLibraryTemplates()
        val pageCount = maxOf(1, ceil(templates.size / PAGE_SIZE.toDouble()).toInt())
        currentPage = currentPage.coerceIn(0, pageCount - 1)
        val firstIndex = currentPage * PAGE_SIZE

        templates.drop(firstIndex).take(PAGE_SIZE).forEachIndexed { slot, template ->
            val icon = ItemStack(Material.PLAYER_HEAD)
            val meta = icon.itemMeta as SkullMeta
            meta.setDisplayName("§e${template.name}")
            meta.lore = listOf(
                "§7ID: §f${template.id}",
                "§7职业: ${template.profession.key.key}",
                "§7类型: ${template.type.key.key}",
                "§7交易项: ${template.trades.size} 个",
                " ",
                "§a[左键] §f在此处生成",
                "§c[右键] §f从模板库移除"
            )
            icon.itemMeta = meta
            inventory.setItem(slot, icon)
            slotMap[slot] = template.id
        }

        if (templates.isEmpty()) {
            inventory.setItem(22, createItem(
                Material.BARRIER,
                "§e模板库为空",
                listOf("§7用石锄右键一个 NPC 即可保存模板")
            ))
        }

        for (slot in PAGE_SIZE until INVENTORY_SIZE) {
            inventory.setItem(slot, createItem(Material.GRAY_STAINED_GLASS_PANE, " "))
        }
        if (currentPage > 0) {
            inventory.setItem(PREVIOUS_SLOT, createItem(Material.ARROW, "§a⬅ 上一页"))
        }
        inventory.setItem(PAGE_INFO_SLOT, createItem(
            Material.BOOK,
            "§e第 ${currentPage + 1} / $pageCount 页",
            listOf("§7共 ${templates.size} 个石锄模板")
        ))
        if (currentPage + 1 < pageCount) {
            inventory.setItem(NEXT_SLOT, createItem(Material.ARROW, "§a下一页 ➡"))
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !== this) return
        event.isCancelled = true
        if (event.whoClicked.uniqueId != player.uniqueId || !player.isOp) return

        when (event.rawSlot) {
            PREVIOUS_SLOT -> if (currentPage > 0) {
                currentPage--
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1f)
                loadContent()
            }

            NEXT_SLOT -> {
                val templateCount = plugin.npcModule.manager.getLibraryTemplates().size
                if ((currentPage + 1) * PAGE_SIZE < templateCount) {
                    currentPage++
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1f)
                    loadContent()
                }
            }

            else -> {
                val templateId = slotMap[event.rawSlot] ?: return
                when {
                    event.isLeftClick -> {
                        val spawned = plugin.npcModule.manager.spawnNpc(targetLocation, templateId)
                        if (spawned == null) {
                            player.sendMessage("§c[NPC] 生成失败：模板或目标世界不可用。")
                            return
                        }
                        player.sendMessage("§a[NPC] 已成功生成: ${plugin.npcModule.manager.getTemplate(templateId)?.name}")
                        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                        player.closeInventory()
                    }

                    event.isRightClick -> {
                        if (plugin.npcModule.manager.removeTemplateFromLibrary(templateId)) {
                            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1f)
                            player.sendMessage("§c[NPC] 已从石锄模板库移除: $templateId")
                            loadContent()
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder !== this) return
        HandlerList.unregisterAll(this)
    }

    private fun createItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName(name)
            this.lore = lore
        }
        return item
    }
}
