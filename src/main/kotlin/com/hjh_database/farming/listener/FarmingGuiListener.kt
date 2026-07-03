package com.hjh_database.farming.listener

import com.hjh_database.farming.gui.FarmingGui
import com.hjh_database.farming.gui.FarmingMenuHolder
import com.hjh_database.farming.gui.FarmingMenuType
import com.hjh_database.farming.manager.FarmingManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent

class FarmingGuiListener(private val manager: FarmingManager) : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder as? FarmingMenuHolder ?: return
        event.isCancelled = true

        val data = manager.cached(player) ?: run {
            player.closeInventory()
            player.sendMessage("§c灵田数据尚未加载完成，请稍后重试。")
            return
        }

        when (holder.type) {
            FarmingMenuType.MAIN -> handleMain(player, data, event.slot, event.click)
            FarmingMenuType.PLANT_SELECT -> handlePlantSelect(player, data, holder, event.slot)
            FarmingMenuType.FIELD_ACTION -> handleFieldAction(player, data, holder.fieldIndex, event.slot)
            FarmingMenuType.PROTECTION_SHOP -> handleProtectionShop(player, data, holder, event.slot)
        }
    }

    private fun handleMain(player: Player, data: com.hjh_database.farming.data.FarmingPlayerData, slot: Int, click: ClickType) {
        if (slot == FarmingGui.CLOSE_SLOT) {
            player.closeInventory()
            return
        }
        if (slot == FarmingGui.PROTECTION_SHOP_SLOT) {
            val field = data.firstUnlocked()
            if (field == null) {
                player.sendMessage("§c请先开辟一方灵田。")
            } else {
                manager.gui.openProtectionShop(player, field.index)
            }
            return
        }

        val fieldIndex = manager.config.fieldSlots.indexOf(slot)
        if (fieldIndex < 0) return
        val field = data.field(fieldIndex)

        if (!field.unlocked) {
            if (manager.unlockField(player, data, field)) {
                manager.gui.openMain(player, data)
            }
            return
        }
        if (!field.planted) {
            manager.gui.openPlantSelect(player, data, fieldIndex)
            return
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (manager.harvest(player, data, field)) {
                manager.gui.openMain(player, data)
            }
        } else {
            manager.gui.openFieldAction(player, field)
        }
    }

    private fun handlePlantSelect(player: Player, data: com.hjh_database.farming.data.FarmingPlayerData, holder: FarmingMenuHolder, slot: Int) {
        if (slot == 49) {
            manager.gui.openMain(player, data)
            return
        }
        if (slot == 53) {
            player.closeInventory()
            return
        }
        val plantId = holder.slotIds[slot] ?: return
        val plant = manager.config.plant(plantId) ?: return
        val field = data.field(holder.fieldIndex)
        if (manager.plant(player, data, field, plant)) {
            manager.gui.openMain(player, data)
        }
    }

    private fun handleFieldAction(player: Player, data: com.hjh_database.farming.data.FarmingPlayerData, fieldIndex: Int, slot: Int) {
        val field = data.field(fieldIndex)
        when (slot) {
            FarmingGui.HARVEST_SLOT -> {
                if (manager.harvest(player, data, field)) manager.gui.openMain(player, data)
            }
            FarmingGui.ACCELERATOR_SLOT -> {
                manager.applyFirstAccelerator(player, data, field)
                manager.gui.openFieldAction(player, field)
            }
            FarmingGui.BOOSTER_SLOT -> {
                manager.applyFirstBooster(player, data, field)
                manager.gui.openFieldAction(player, field)
            }
            FarmingGui.PROTECTION_SLOT -> manager.gui.openProtectionShop(player, field.index)
            FarmingGui.BACK_SLOT -> manager.gui.openMain(player, data)
            FarmingGui.DESTROY_SLOT -> {
                manager.destroyPlanting(data, field)
                player.sendMessage("§a已铲除此方灵田中的灵植。")
                manager.gui.openMain(player, data)
            }
            FarmingGui.ACTION_CLOSE_SLOT -> player.closeInventory()
        }
    }

    private fun handleProtectionShop(player: Player, data: com.hjh_database.farming.data.FarmingPlayerData, holder: FarmingMenuHolder, slot: Int) {
        if (slot == 49) {
            manager.gui.openMain(player, data)
            return
        }
        if (slot == 53) {
            player.closeInventory()
            return
        }
        val protectionId = holder.slotIds[slot] ?: return
        val protection = manager.config.protection(protectionId) ?: return
        val field = data.field(holder.fieldIndex)
        if (!field.unlocked) {
            player.sendMessage("§c这块灵田尚未开辟。")
            return
        }
        if (manager.buyAndApplyProtection(player, data, field, protection.id)) {
            manager.gui.openMain(player, data)
        }
    }
}
