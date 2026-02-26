package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object MobFactory {

    val KEY_MOB_ID = NamespacedKey.fromString("hjh_database:mob_id")!!
    private val KEY_CUSTOM_ARMOR = NamespacedKey.fromString("hjh_database:hjh_mob_armor")!!
    private val KEY_MOB_AFFIXES = NamespacedKey.fromString("hjh_database:mob_affixes")!!

    fun spawnMob(plugin: Hjh_database, location: Location, mobId: String): LivingEntity? {
        val def = MobRegistry.get(mobId) ?: return null
        val world = location.world ?: return null

        val entity = world.spawnEntity(location, def.type) as? LivingEntity ?: return null

        // 1. 基础显示与属性
        entity.customName = ChatColor.translateAlternateColorCodes('&', def.name)
        entity.isCustomNameVisible = true

        entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = def.health
        entity.health = def.health
        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = def.damage
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = def.speed

        // 2. 护甲系统适配
        entity.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
        entity.persistentDataContainer.set(KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, def.armor)

        // 3. Tags
        entity.addScoreboardTag("panling")
        entity.addScoreboardTag("monster")
        entity.persistentDataContainer.set(KEY_MOB_ID, PersistentDataType.STRING, def.id)

        // === 【新增】Boss 技能挂载分配器 ===
        // 根据注册的 mobId 动态分配技能类
        when (mobId) {
            "zhizhunvwang" -> {
                com.hjh_database.spawner.impl.Zhizhunvwang(plugin, entity)
            }
            // 以后如果有新 boss，继续往下加就行：
//             "shiyanguai" -> Shiyanguai(plugin, entity)
            // "kulouwang" -> Kulouwang(plugin, entity)
        }


        // 4. 词缀
        if (def.affixes.isNotEmpty()) {
            val affixStr = def.affixes.joinToString(",") { it.id }
            entity.persistentDataContainer.set(KEY_MOB_AFFIXES, PersistentDataType.STRING, affixStr)

            def.affixes.forEach { affix ->
                if (affix == MobAffix.SPEED) {
                    entity.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, Int.MAX_VALUE, 1))
                }
            }
        }

        // 5. ★★★ 装备系统 (装饰性) ★★★
        val equipment = entity.equipment
        if (equipment != null) {
            // A. 清空默认生成的装备 (防止普通僵尸自带杂乱装备)
            equipment.clear()
            // B. 辅助函数：创建纯装饰物品
            fun setCosmetic(slot: EquipmentSlot, mat: Material?) {
                if (mat == null || mat == Material.AIR) return
                val item = ItemStack(mat)
                val meta = item.itemMeta
                if (meta != null) {
                    // 无限耐久
                    meta.isUnbreakable = true
                    // 核心：清除所有属性修饰符 (Attack, Armor 等)
                    // 这样装备就仅仅是"看起来"穿在身上，不提供任何数值
                    meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()
                    item.itemMeta = meta
                }

                equipment.setItem(slot, item)
                // 绝对不掉落
                equipment.setDropChance(slot, 0f)
            }

            // C. 应用配置的装备
            setCosmetic(EquipmentSlot.HEAD, def.helmet)
            setCosmetic(EquipmentSlot.CHEST, def.chestplate)
            setCosmetic(EquipmentSlot.LEGS, def.leggings)
            setCosmetic(EquipmentSlot.FEET, def.boots)
            setCosmetic(EquipmentSlot.HAND, def.mainHand)
            setCosmetic(EquipmentSlot.OFF_HAND, def.offHand)
        }

        return entity
    }
}