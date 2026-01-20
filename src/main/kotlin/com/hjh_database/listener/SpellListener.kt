package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.ui.MenuManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

class SpellListener(private val plugin: Hjh_database) : Listener {

    @EventHandler
    fun onCastSpell(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        val player = event.player
        val mainHandItem = player.inventory.itemInMainHand

        // 【优化】直接调用 Manager 判断副手是否激活
        // 这一步代替了之前的长串逻辑
        // 这里的 playerManager 和 weaponManager 均使用 Kotlin 属性访问
        if (plugin.playerManager.weaponManager.getActiveOffHandWeaponId(player) == null) {
            return // 副手没激活，直接撤
        }

        // 检查主手是否持有元素
        val elementType = getElementType(mainHandItem)
        if (elementType == null) {
            return
        }

        event.isCancelled = true

        // 假设 Hjh_database 中有 getElementZfManager() 方法
        plugin.elementZfManager.castSkill(
            player,
            elementType.name,
            plugin.playerManager.getData(player.uniqueId)!!
        )
    }

    private fun getElementType(item: ItemStack?): MenuManager.ElementType? {
        if (item == null || item.type == Material.AIR) return null

        // 遍历所有元素类型
        for (type in MenuManager.ElementType.values()) {
            if (type == MenuManager.ElementType.RELIVE) continue

            // 调用 MenuManager 判断是否为盘灵物品
            // (MenuManager 重构版已经适配了 1.21.3 的组件/PDC 判断)
            if (plugin.menuManager.isPanlingItem(item, type)) {
                return type
            }
        }
        return null
    }
}