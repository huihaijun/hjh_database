package com.hjh_database.spawner

import org.bukkit.Material
import org.bukkit.entity.EntityType
import java.util.HashMap

// 掉落物配置
data class MobDrop(
    val resourceId: String, // 对应 ResourceManager 中的物品ID
    val min: Int,
    val max: Int,
    val chance: Double // 0.0 - 1.0
)

// 怪物定义
data class MobDefinition(
    val id: String,
    val name: String,
    val type: EntityType,
    val health: Double,
    val damage: Double,
    val armor: Double, // 自定义护甲值
    val speed: Double,
    val maxNearby: Int,
    val drops: List<MobDrop>,
    val affixes: List<MobAffix> = emptyList(), // 使用你定义的枚举
    // === 【新增】装饰性装备配置 (默认为空) ===
    val helmet: Material? = null,
    val chestplate: Material? = null,
    val leggings: Material? = null,
    val boots: Material? = null,
    val mainHand: Material? = null,
    val offHand: Material? = null
)

object MobRegistry {
    private val mobs = HashMap<String, MobDefinition>()

    fun register(mob: MobDefinition) {
        mobs[mob.id] = mob
    }

    fun get(id: String): MobDefinition? {
        return mobs[id]
    }

    // === 【新增】获取所有ID供 Tab补全使用 ===
    fun getAllIds(): List<String> {
        return mobs.keys.toList()
    }

    fun init() {
        mobs.clear()
        // === 示例：注册一个测试怪物 ===
        register(MobDefinition(
            id = "test_zombie",
            name = "&c测试僵尸",
            type = EntityType.ZOMBIE,
            health = 20.0,
            damage = 4.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.3,
            maxNearby = 1,
            drops = listOf(
                // 假设 ResourceManager 里有个叫 "coin_gold" 的物品
                MobDrop("hjh_xsyy", 1, 1, 0.5),
                // 假设有个叫 "mystic_fragment" 的材料
                MobDrop("hjh_rzcy", 1, 1, 1.0)
            ),
            affixes = listOf(), // 自带荆棘和防击退
            // 配置装饰装备 (钻石套 + 铁剑)
//            helmet = Material.DIAMOND_HELMET,
//            chestplate = Material.DIAMOND_CHESTPLATE,
//            leggings = Material.DIAMOND_LEGGINGS,
//            boots = Material.DIAMOND_BOOTS,
//            mainHand = Material.IRON_SWORD,
//            offHand = Material.SHIELD
        ))
    }
}