package com.hjh_database.skill.weapon

import com.hjh_database.Hjh_database
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class WeaponSkillListener(private val plugin: Hjh_database) : Listener {
    private val weaponKey: NamespacedKey = NamespacedKey(plugin, "weapon_id")
    private val resourceKey: NamespacedKey = NamespacedKey(plugin, "resource_id")
    private val baihuWeaponKey: NamespacedKey = NamespacedKey(plugin, "baihu_weapon_id")

    // 战士触发
    @EventHandler
    fun onWarriorTrigger(event: PlayerInteractEvent) {
        val player = event.player
        if (!player.isSneaking) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item
        if (item == null || !item.hasItemMeta()) return
        if (isBaihuWeapon(item)) return

        val weaponId = getWeaponId(item)
        if (weaponId == null) return
        if (plugin.baihuDzManager.isBaihuWeaponSkillId(weaponId)) return

        val typeName = item.type.toString()
        if (typeName.contains("SWORD") || typeName.contains("AXE")) {
            // 战士没有投射物，传 null
            // 【关键】使用 !! 断言 manager 非空，解决平台类型可能的空指针报错
            plugin.weaponSkillManager!!.tryCastSkill(player, weaponId, item, null)
        }
    }

    // 弓箭手触发
    @EventHandler(ignoreCancelled = true)
    fun onArcherTrigger(event: EntityShootBowEvent) {
        // 模拟 Java 的 instanceof 模式匹配
        val entity = event.entity
        if (entity !is Player) return
        val player = entity

        if (!player.isSneaking) return

        val bow = event.bow
        if (bow == null || !bow.hasItemMeta()) return
        if (isBaihuWeapon(bow)) return

        val weaponId = getWeaponId(bow)
        if (weaponId == null) return
        if (plugin.baihuDzManager.isBaihuWeaponSkillId(weaponId)) return

        // 【关键】传入射出的箭矢 (event.getProjectile())
        // 使用 !! 断言 manager 非空
        plugin.weaponSkillManager!!.tryCastSkill(player, weaponId, bow, event.projectile)
    }

    // 【新增】弓箭手触发 (下蹲近战命中)
    @EventHandler
    fun onBowMeleeTrigger(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!player.isSneaking) return

        // 获取受击实体
        val victim = event.entity as? LivingEntity ?: return

        val item = player.inventory.itemInMainHand
        if (!item.hasItemMeta()) return
        if (isBaihuWeapon(item)) return

        val weaponId = getWeaponId(item) ?: return
        if (plugin.baihuDzManager.isBaihuWeaponSkillId(weaponId)) return

        // ==========================================
        // 【核心安全过滤】近战特化弓白名单
        // 只有在这个列表里的武器，下蹲近战才会被发送给 Manager 处理主动技能
        // ==========================================
        val allowedMeleeBows = listOf("zhongchuigong", "未来你可能加的其他近战弓id")
        if (weaponId !in allowedMeleeBows) {
            return // 如果不是重锤弓等特殊弓，直接无视，不触发任何技能判定
        }

        val typeName = item.type.toString()
        if (typeName.contains("BOW") || typeName.contains("CROSSBOW")) {
            // 将受击的怪物(victim)作为 projectile 参数传给 Manager
            plugin.weaponSkillManager!!.tryCastSkill(player, weaponId, item, victim)
        }
    }

    private fun getWeaponId(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        // 与 WeaponManager 的激活识别保持一致，兼容职业体验区发放的旧式 resource_id 武器。
        return pdc.get(weaponKey, PersistentDataType.STRING)
            ?: pdc.get(resourceKey, PersistentDataType.STRING)
    }

    private fun isBaihuWeapon(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(baihuWeaponKey, PersistentDataType.STRING)
    }
}
