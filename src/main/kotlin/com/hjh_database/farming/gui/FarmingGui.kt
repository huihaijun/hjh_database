package com.hjh_database.farming.gui

import com.hjh_database.farming.data.FarmingField
import com.hjh_database.farming.data.FarmingPlayerData
import com.hjh_database.farming.data.PlantType
import com.hjh_database.farming.manager.FarmingManager
import com.hjh_database.farming.util.FarmingItems
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class FarmingGui(private val manager: FarmingManager) {
    companion object {
        const val PROTECTION_SHOP_SLOT = 37
        const val INFO_SLOT = 40
        const val CLOSE_SLOT = 43
        const val HARVEST_SLOT = 12
        const val ACCELERATOR_SLOT = 13
        const val BOOSTER_SLOT = 14
        const val PROTECTION_SLOT = 15
        const val BACK_SLOT = 18
        const val DESTROY_SLOT = 22
        const val ACTION_CLOSE_SLOT = 26
        val PLANT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
    }

    fun openMain(player: Player, data: FarmingPlayerData) {
        val holder = FarmingMenuHolder(FarmingMenuType.MAIN)
        val inv = holder.bind(Bukkit.createInventory(holder, manager.config.guiRows * 9, FarmingItems.color(manager.config.guiTitle)))
        fill(inv)

        val slots = manager.config.fieldSlots
        data.allFields(slots.size).forEachIndexed { index, field ->
            inv.setItem(slots[index], fieldIcon(field))
        }
        inv.setItem(PROTECTION_SHOP_SLOT, FarmingItems.simple(Material.PAPER, "&5&l符箓阁", listOf("&7购买防护符，贴附到第一块已开辟灵田。", "&e点击进入")))
        inv.setItem(INFO_SLOT, FarmingItems.simple(Material.BOOK, "&a&l灵田信息", listOf("&7已开辟：&f${data.fields.count { it.unlocked }}&7/&f${manager.config.maxTotalFields}", "&7总收获：&f${data.totalHarvests}")))
        inv.setItem(CLOSE_SLOT, FarmingItems.simple(Material.BARRIER, "&c&l关闭"))
        player.openInventory(inv)
    }

    fun openPlantSelect(player: Player, data: FarmingPlayerData, fieldIndex: Int) {
        val available = manager.matchSeed(player)
        val slotIds = available.mapIndexedNotNull { i, plant ->
            FarmingGui.PLANT_SLOTS.getOrNull(i)?.let { it to plant.id }
        }.toMap()
        val holder = FarmingMenuHolder(FarmingMenuType.PLANT_SELECT, fieldIndex, slotIds)
        val inv = holder.bind(Bukkit.createInventory(holder, 54, "§2选择药种 #${fieldIndex + 1}"))
        fill(inv)
        PLANT_SLOTS.forEachIndexed { i, slot ->
            val plant = available.getOrNull(i)
            inv.setItem(slot, if (plant != null) seedIcon(plant) else FarmingItems.simple(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " "))
        }
        inv.setItem(49, FarmingItems.simple(Material.OAK_DOOR, "&7返回"))
        inv.setItem(53, FarmingItems.simple(Material.BARRIER, "&c关闭"))
        player.openInventory(inv)
    }

    fun openFieldAction(player: Player, field: FarmingField) {
        val holder = FarmingMenuHolder(FarmingMenuType.FIELD_ACTION, field.index)
        val inv = holder.bind(Bukkit.createInventory(holder, 27, "§2药圃操作 #${field.index + 1}"))
        fill(inv)
        val plant = manager.config.plant(field.plantType)
        val remaining = FarmingItems.formatDuration(field.remainingMs())
        inv.setItem(HARVEST_SLOT, FarmingItems.simple(Material.WHEAT, "&a&l采收", listOf("&7灵植：&f${plant?.name ?: field.plantType}", "&7进度：&e${manager.progressPercent(field)}%", "&7剩余：&e$remaining", "&e成熟后点击采收")))
        inv.setItem(ACCELERATOR_SLOT, FarmingItems.simple(Material.GLOWSTONE_DUST, "&b&l使用催生灵液", listOf("&7自动使用背包中第一种催生灵液。")))
        inv.setItem(BOOSTER_SLOT, FarmingItems.simple(Material.PAPER, "&6&l使用丰饶宝箓", listOf("&7自动使用背包中第一种增产道具。")))
        inv.setItem(PROTECTION_SLOT, FarmingItems.simple(Material.SHIELD, "&5&l防护符咒", listOf("&7打开符箓阁并贴附到此田。")))
        inv.setItem(BACK_SLOT, FarmingItems.simple(Material.OAK_DOOR, "&7返回"))
        inv.setItem(DESTROY_SLOT, FarmingItems.simple(Material.IRON_SHOVEL, "&c&l铲除灵植", listOf("&7清空当前种植状态。")))
        inv.setItem(ACTION_CLOSE_SLOT, FarmingItems.simple(Material.BARRIER, "&c关闭"))
        player.openInventory(inv)
    }

    fun openProtectionShop(player: Player, fieldIndex: Int) {
        val protections = manager.config.allProtections().toList()
        val slotIds = protections.mapIndexedNotNull { i, protection ->
            FarmingGui.PLANT_SLOTS.getOrNull(i)?.let { it to protection.id }
        }.toMap()
        val holder = FarmingMenuHolder(FarmingMenuType.PROTECTION_SHOP, fieldIndex, slotIds)
        val inv = holder.bind(Bukkit.createInventory(holder, 54, "§5符箓阁"))
        fill(inv)
        protections.forEachIndexed { i, protection ->
            val slot = PLANT_SLOTS.getOrNull(i) ?: return@forEachIndexed
            val icon = FarmingItems.resourceItem(manager.plugin, protection.itemId) ?: FarmingItems.simple(Material.PAPER, protection.itemId)
            val meta = icon.itemMeta
            if (meta != null) {
                val lore = (meta.lore ?: emptyList()).toMutableList()
                lore.add("")
                lore.add("§7价格：§e${protection.price} 金元宝")
                lore.add("§e点击购买并贴附")
                meta.lore = lore
                icon.itemMeta = meta
            }
            inv.setItem(slot, icon)
                }
        inv.setItem(49, FarmingItems.simple(Material.OAK_DOOR, "&7返回"))
        inv.setItem(53, FarmingItems.simple(Material.BARRIER, "&c关闭"))
        player.openInventory(inv)
    }

    private fun fieldIcon(field: FarmingField): ItemStack {
        if (!field.unlocked) {
            val cost = manager.config.unlockCost(field.index)
            return FarmingItems.simple(Material.COARSE_DIRT, "&7第 ${field.index + 1} 方灵田", listOf("&c尚未开辟", "&7需要开拓令：&e$cost", "&e点击开辟"))
        }
        if (!field.planted) {
            return FarmingItems.simple(Material.FARMLAND, "&a第 ${field.index + 1} 方灵田", listOf("&7空置", "&e点击选择药种"))
        }
        val plant = manager.config.plant(field.plantType)
        val progress = manager.progressPercent(field)
        val readyLine = if (field.ready) "&a已成熟，Shift 点击可直接采收" else "&7剩余：&e${FarmingItems.formatDuration(field.remainingMs())}"
        return FarmingItems.simple(Material.WHEAT, plant?.name ?: "&e未知灵植", listOf("&7第 ${field.index + 1} 方灵田", "&7进度：&e$progress%", readyLine, "&e点击查看操作"))
    }

    private fun seedIcon(plant: PlantType): ItemStack {
        return (FarmingItems.resourceItem(manager.plugin, plant.seedItemId) ?: FarmingItems.simple(Material.WHEAT_SEEDS, plant.seedItemId)).apply {
            val meta = itemMeta
            if (meta != null) {
                val lore = (meta.lore ?: emptyList()).toMutableList()
                lore.add("")
                lore.add("§7采收：§f${plant.harvestMin}-${plant.harvestMax}")
                lore.add("§7成长：§f${FarmingItems.formatDuration(plant.totalGrowthMs)}")
                lore.add("§e点击播种")
                meta.lore = lore
                itemMeta = meta
            }
        }
    }

    private fun fill(inv: Inventory) {
        val pane = FarmingItems.simple(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (i in 0 until inv.size) inv.setItem(i, pane)
    }
}
