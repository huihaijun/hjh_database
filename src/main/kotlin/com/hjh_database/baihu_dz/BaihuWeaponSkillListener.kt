package com.hjh_database.baihu_dz

import com.hjh_database.Hjh_database
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.Material
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent

class BaihuWeaponSkillListener(private val plugin: Hjh_database) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAnhuMobTarget(event: EntityTargetLivingEntityEvent) {
        plugin.baihuWeaponSkillManager.onMobTarget(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAnhuCrossbowLoad(event: EntityLoadCrossbowEvent) {
        plugin.baihuWeaponSkillManager.handleCrossbowLoad(event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onCiguDashDamage(event: EntityDamageEvent) {
        plugin.baihuWeaponSkillManager.onPlayerDamage(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCiguDashDamageFinal(event: EntityDamageEvent) {
        // LOWEST 先阻止本插件的受击副作用，HIGHEST 再兜底，避免其他监听器重新写入伤害。
        plugin.baihuWeaponSkillManager.onPlayerDamage(event)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.baihuWeaponSkillManager.deactivate(event.player)
    }

    @EventHandler
    fun onWarriorTrigger(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player

        val item = player.inventory.itemInMainHand
        val weaponData = plugin.baihuDzManager.getWeaponDataFromItem(item) ?: return
        if (item.type == Material.BOW || item.type == Material.CROSSBOW) return
        if (!player.isSneaking && weaponData.skillId !in RIGHT_CLICK_ONLY_SKILLS) return

        event.isCancelled = true
        plugin.baihuWeaponSkillManager.tryCastSkill(player, item, null)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onArcherTrigger(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        if (plugin.baihuDzManager.getWeaponDataFromItem(bow) == null) return

        if (plugin.baihuWeaponSkillManager.handleArcherShot(event)) return
        if (!player.isSneaking) return

        plugin.baihuWeaponSkillManager.tryCastSkill(player, bow, event.projectile)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAnhuEnhancedArrowHit(event: EntityDamageByEntityEvent) {
        plugin.baihuWeaponSkillManager.onProjectileDamage(event)
    }

    @EventHandler
    fun onBowMeleeTrigger(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!player.isSneaking) return
        val victim = event.entity as? LivingEntity ?: return
        if (victim.hasMetadata("hjh_physical_skill")) return

        val item = player.inventory.itemInMainHand
        if (plugin.baihuDzManager.getWeaponDataFromItem(item) == null) return
        val typeName = item.type.toString()
        if (!typeName.contains("BOW") && !typeName.contains("CROSSBOW")) return

        plugin.baihuWeaponSkillManager.tryCastSkill(player, item, event.entity)
    }

    companion object {
        private val RIGHT_CLICK_ONLY_SKILLS = setOf("duhuozhu")
    }
}
