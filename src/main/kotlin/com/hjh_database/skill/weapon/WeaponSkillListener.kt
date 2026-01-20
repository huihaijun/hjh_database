package com.hjh_database.skill.weapon

import com.hjh_database.Hjh_database
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class WeaponSkillListener(private val plugin: Hjh_database) : Listener {
    private val weaponKey: NamespacedKey = NamespacedKey(plugin, "weapon_id")

    // 战士触发
    @EventHandler
    fun onWarriorTrigger(event: PlayerInteractEvent) {
        val player = event.player
        if (!player.isSneaking) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item
        if (item == null || !item.hasItemMeta()) return

        val weaponId = getWeaponId(item)
        if (weaponId == null) return

        val typeName = item.type.toString()
        if (typeName.contains("SWORD") || typeName.contains("AXE")) {
            // 战士没有投射物，传 null
            // 【关键】使用 !! 断言 manager 非空，解决平台类型可能的空指针报错
            plugin.weaponSkillManager!!.tryCastSkill(player, weaponId, item, null)
        }
    }

    // 弓箭手触发
    @EventHandler
    fun onArcherTrigger(event: EntityShootBowEvent) {
        // 模拟 Java 的 instanceof 模式匹配
        val entity = event.entity
        if (entity !is Player) return
        val player = entity

        if (!player.isSneaking) return

        val bow = event.bow
        if (bow == null || !bow.hasItemMeta()) return

        val weaponId = getWeaponId(bow)
        if (weaponId == null) return

        // 【关键】传入射出的箭矢 (event.getProjectile())
        // 使用 !! 断言 manager 非空
        plugin.weaponSkillManager!!.tryCastSkill(player, weaponId, bow, event.projectile)
    }

    private fun getWeaponId(item: ItemStack): String? {
        val meta = item.itemMeta
        if (meta == null) return null
        return meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
    }
}