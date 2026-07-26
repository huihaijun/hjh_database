package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.impl.Jiaoguzhanshi
import com.hjh_database.spawner.impl.Kuanggongwanghun
import com.hjh_database.spawner.impl.Shamofengbao
import com.hjh_database.spawner.impl.Xiongshentaisui
import com.hjh_database.spawner.impl.Xuanshuihun
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.math.min

object MobFactory {

    val KEY_MOB_ID = NamespacedKey.fromString("hjh_database:mob_id")!!
    val KEY_CUSTOM_ARMOR = NamespacedKey.fromString("hjh_database:hjh_mob_armor")!!
    val KEY_CUSTOM_DAMAGE = NamespacedKey.fromString("hjh_database:hjh_mob_damage")!!
    val KEY_NO_REWARD = NamespacedKey.fromString("hjh_database:no_reward")!!
    private val KEY_MOB_AFFIXES = NamespacedKey.fromString("hjh_database:mob_affixes")!!

    fun spawnMob(plugin: Hjh_database, location: Location, mobId: String, removeWhenFarAway: Boolean = false): LivingEntity? {
        val def = MobRegistry.get(mobId) ?: return null
        val world = location.world ?: return null

        val entity = world.spawnEntity(location, def.type) as? LivingEntity ?: return null

        // (如果你之前加了这行，保留它) 防止原版机制刷没怪物
        entity.removeWhenFarAway = removeWhenFarAway
        clearVanillaSpecialSpawnState(entity)

        // === 【新增修改】1. 强制设置为成年体，防止出现小僵尸、小牛、小猪灵等幼体 ===
        if (entity is org.bukkit.entity.Ageable) {
            entity.setAdult()
        }
        if (entity is org.bukkit.entity.Zombie) {
            entity.setBaby(false)
        }
        if (entity is org.bukkit.entity.Piglin) {
            entity.setBaby(false)
        }
        // ★★★ 【新增】强制史莱姆与岩浆怪默认生成最大尺寸(4) ★★★
        if (entity is org.bukkit.entity.Slime) {
            entity.size = 4
        }


        // === 【新增修改】2. 关闭拾取功能，彻底杜绝怪物捡起地上的防具/武器并自动穿上 ===
        entity.canPickupItems = false

        // === 【新增修改】3. 强制清理所有坐骑和乘客，杜绝蜘蛛骑士、小鸡骑士等“买一送一”的杂交怪 ===
        // 如果这个实体被生成时自带了坐骑(比如它骑着小鸡/蜘蛛)，把坐骑直接删掉
        val vehicle = entity.vehicle
        if (vehicle != null) {
            entity.leaveVehicle()
            vehicle.remove()
        }
        // 如果这个实体生成时背上骑了东西(比如蜘蛛背上刷了骷髅)，把背上的东西删掉
        val passengers = entity.passengers
        if (passengers.isNotEmpty()) {
            entity.eject()
            for (passenger in passengers) {
                passenger.remove()
            }
        }

        // 1. 基础显示与属性
        entity.customName = ChatColor.translateAlternateColorCodes('&', def.name)
        entity.isCustomNameVisible = true

        entity.getAttribute(Attribute.MAX_HEALTH)?.let { maxHealth ->
            maxHealth.modifiers.toList().forEach { modifier ->
                maxHealth.removeModifier(modifier)
            }
            maxHealth.baseValue = def.health
            entity.health = min(def.health, maxHealth.value)
        }
//        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = def.damage
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = def.speed

        // 2. 护甲系统适配
        entity.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
        entity.persistentDataContainer.set(KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, def.armor)
        entity.persistentDataContainer.set(KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, def.damage)

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
            // 【新增神木守卫】
            "shenmushouwei" -> {
                com.hjh_database.spawner.impl.Shenmushouwei(plugin, entity)
            }
            // 【新增神木守卫】
            "mazeituantuanzhang" -> {
                com.hjh_database.spawner.impl.Mazeituantuanzhang(plugin, entity)
            }
            "shamofengbao" -> Shamofengbao(plugin, entity)
            "xiongshentaisui" -> Xiongshentaisui(plugin, entity)
            "jiaoguzhanshi" -> Jiaoguzhanshi(plugin, entity)
            "kuanggongwanghun" -> Kuanggongwanghun(plugin, entity)
            "xuanshuihun" -> Xuanshuihun(plugin, entity)
            // 以后如果有新 boss，继续往下加就行：
//             "shiyanguai" -> Shiyanguai(plugin, entity)
            // "kulouwang" -> Kulouwang(plugin, entity)
        }


        // 4. 词缀
        if (def.affixes.isNotEmpty()) {
            val affixStr = def.affixes.joinToString(",") { it.id }
            entity.persistentDataContainer.set(KEY_MOB_AFFIXES, PersistentDataType.STRING, affixStr)

            def.affixes.forEach { affix ->
                when (affix) {
                    MobAffix.SPEED -> {
                        entity.addPotionEffect(
                            org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.SPEED,
                                Int.MAX_VALUE,
                                1
                            )
                        )
                    }
                    MobAffix.NORTH_WETNESS -> {
                        entity.addPotionEffect(
                            org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.WATER_BREATHING,
                                Int.MAX_VALUE,
                                0,
                                true,
                                false,
                                false
                            )
                        )
                        entity.addPotionEffect(
                            org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE,
                                Int.MAX_VALUE,
                                0,
                                true,
                                false,
                                false
                            )
                        )
                    }
                    else -> Unit
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

    private fun clearVanillaSpecialSpawnState(entity: LivingEntity) {
        entity.activePotionEffects.toList().forEach { entity.removePotionEffect(it.type) }
        entity.fireTicks = 0

        if (entity is org.bukkit.entity.Ageable) {
            entity.setAdult()
        }
        if (entity is org.bukkit.entity.Zombie) {
            entity.setBaby(false)
        }
        if (entity is org.bukkit.entity.Piglin) {
            entity.setBaby(false)
        }
        if (entity is org.bukkit.entity.Slime) {
            entity.size = 4
        }

        entity.vehicle?.let { vehicle ->
            entity.leaveVehicle()
            removeEntityTree(vehicle)
        }
        entity.passengers.toList().forEach { passenger ->
            entity.removePassenger(passenger)
            removeEntityTree(passenger)
        }
    }

    private fun removeEntityTree(entity: Entity) {
        entity.passengers.toList().forEach { passenger ->
            entity.removePassenger(passenger)
            removeEntityTree(passenger)
        }
        entity.remove()
    }
}
