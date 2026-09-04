package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.inventory.ItemStack

class BaihuEquipmentDamageMarkerListener(private val plugin: Hjh_database) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        if (!isUsableWarriorOrArcherWeapon(player, bow)) return
        BaihuEquipmentDamageTag.markProjectile(plugin, event.projectile)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        if (!isBaihuEquipmentDamage(event)) return
        BaihuEquipmentDamageTag.markTarget(plugin, target)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun cleanup(event: EntityDamageEvent) {
        val target = event.entity as? LivingEntity ?: return
        BaihuEquipmentDamageTag.clearTarget(plugin, target)
    }

    private fun isBaihuEquipmentDamage(event: EntityDamageByEntityEvent): Boolean {
        return when (val damager = event.damager) {
            is Player -> isUsableWarriorOrArcherWeapon(damager, damager.inventory.itemInMainHand)
            is AbstractArrow -> BaihuEquipmentDamageTag.isMarkedProjectile(plugin, damager)
            is Projectile -> BaihuEquipmentDamageTag.isMarkedProjectile(plugin, damager)
            else -> false
        }
    }

    private fun isUsableWarriorOrArcherWeapon(player: Player, item: ItemStack): Boolean {
        val weapon = plugin.baihuDzManager.getWeaponDataFromItem(item) ?: return false
        val data = plugin.playerManager.getData(player.uniqueId) ?: return false
        if (data.job != 0 && data.job != 1) return false
        return plugin.baihuDzManager.isWeaponActive(
            player,
            item,
            weapon,
            data,
            player.inventory.heldItemSlot
        )
    }
}
