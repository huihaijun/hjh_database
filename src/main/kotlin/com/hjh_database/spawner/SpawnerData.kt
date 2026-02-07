package com.hjh_database.spawner

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * 刷怪笼/怪物 的核心配置数据
 */
data class SpawnerData(
    // === 基础信息 ===
    var mobName: String = "&c未知怪物",
    var mobType: EntityType = EntityType.ZOMBIE,

    var internalId: String = "default_mob",

    // === 属性 (数值) ===
    var health: Double = 20.0,
    var damage: Double = 5.0,
    var armor: Double = 0.0,
    var speed: Double = 0.25,

    // === 刷怪配置 ===
    var spawnRange: Int = 4,
    var checkRange: Int = 18,
    var cooldown: Int = 400,
    var maxNearby: Int = 2,
    var targetLocationStr: String? = null,

    // === 废弃字段 (建议保留以防报错，但不再使用) ===
    var helmetMat: String? = null,
    var chestMat: String? = null,
    var leggingsMat: String? = null,
    var bootsMat: String? = null,
    var mainHandMat: String? = null,
    var offHandMat: String? = null,

    // === 掉落物 ===
    // MobDrop 使用 Material 枚举，这是安全的，不会报错
    var drops: MutableList<MobDrop> = ArrayList(),

    // === 词缀 ===
    // MobAffix 是枚举，安全的
    var affixes: MutableSet<MobAffix> = HashSet(),

    // === 开关 ===
    var isEnabled: Boolean = false,

    // === [修复核心] 装备数据 (存储 Base64 字符串) ===
    // GSON 可以完美序列化 String，不会报错
    var equipmentBase64: MutableMap<String, String> = HashMap()
) {
    /**
     * [GUI/逻辑调用] 获取反序列化后的装备 Map
     */
    fun getEquipmentMap(): Map<EquipmentSlot, ItemStack> {
        val result = HashMap<EquipmentSlot, ItemStack>()
        for ((slotName, base64) in equipmentBase64) {
            try {
                val slot = EquipmentSlot.valueOf(slotName)
                val item = ItemSerializer.fromBase64(base64)
                result[slot] = item
            } catch (e: Exception) {
                // 忽略无效数据
            }
        }
        return result
    }

    /**
     * [GUI/逻辑调用] 设置装备
     * 自动将 ItemStack 转为 Base64 存入 equipmentBase64
     */
    fun setEquipment(slot: EquipmentSlot, item: ItemStack?) {
        if (item == null || item.type == Material.AIR) {
            equipmentBase64.remove(slot.name)
        } else {
            equipmentBase64[slot.name] = ItemSerializer.toBase64(item)
        }
    }
}

// MobDrop 不需要改，因为它存的是 Material (枚举)，GSON 能处理
data class MobDrop(
    val material: Material,
    val amount: Int = 1,
    val chance: Double = 0.5,
    val modelData: Int = 0,
    // 默认为 null 以兼容旧数据
    val itemBase64: String? = null
)