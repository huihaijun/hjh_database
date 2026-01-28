package com.hjh_database.npc.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * NPC 模板库 GUI
 * 用于浏览所有保存的模板，进行粘贴或删除
 */
class NpcLibraryGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val targetLocation: Location // 粘贴的目标位置
) : InventoryHolder, Listener {

    private val inventory: Inventory
    // 映射 Slot -> TemplateID，用于处理点击
    private val slotMap = HashMap<Int, String>()

    init {
        inventory = Bukkit.createInventory(this, 54, "NPC 模板库 (左键粘贴/右键删除)")
        loadContent()
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun getInventory(): Inventory = inventory

    fun open() {
        player.openInventory(inventory)
    }

    private fun loadContent() {
        inventory.clear()
        slotMap.clear()

        val templates = plugin.npcModule.manager.templates.values.toList()

        for ((index, template) in templates.withIndex()) {
            if (index >= 54) break // 暂不处理翻页

            // 使用玩家头颅或村民蛋作为图标
            val icon = ItemStack(Material.PLAYER_HEAD)
            val meta = icon.itemMeta as SkullMeta
            meta.setDisplayName("§e${template.name}")

            val lore = ArrayList<String>()
            lore.add("§7ID: §f${template.id}")
            lore.add("§7职业: ${template.profession.key.key}")
            lore.add("§7类型: ${template.type.key.key}")
            lore.add("§7交易项: ${template.trades.size} 个")
            lore.add(" ")
            lore.add("§a[左键] §f在此处生成 (粘贴)")
            lore.add("§c[右键] §f永久删除模板")
            meta.lore = lore

            icon.itemMeta = meta

            inventory.setItem(index, icon)
            slotMap[index] = template.id
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory.holder != this) return
        event.isCancelled = true // 禁止拿取

        val slot = event.rawSlot
        val templateId = slotMap[slot] ?: return

        // === 左键：粘贴生成 ===
        if (event.click == ClickType.LEFT) {
            plugin.npcModule.manager.spawnNpc(targetLocation, templateId)
            player.sendMessage("§a[NPC] 已成功粘贴生成: ${plugin.npcModule.manager.getTemplate(templateId)?.name}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            player.closeInventory()
        }

        // === 右键：删除模板 ===
        if (event.click == ClickType.RIGHT) {
            // 双重确认逻辑可以加在这里，为了简便直接删除
            val success = plugin.npcModule.manager.deleteTemplate(templateId)
            if (success) {
                player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                player.sendMessage("§c[NPC] 已永久删除模板: $templateId")
                loadContent() // 刷新界面
            } else {
                player.sendMessage("§c[NPC] 删除失败，可能该模板不存在。")
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder != this) return
        InventoryClickEvent.getHandlerList().unregister(this)
        InventoryCloseEvent.getHandlerList().unregister(this)
    }
}