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
    // === 【新增】自定义经验值，默认为 20 ===
    val exp: Int = 20,
    // === 【新增】刷怪笼随机生成冷却 (单位：秒) ===
    val minSpawnDelay: Int = 20,
    val maxSpawnDelay: Int = 50,
    // === 装备配置 (默认为空) ===
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
            id = "ceshijiangshi",
            name = "&c测试僵尸",
            type = EntityType.ZOMBIE,
            health = 20.0,
            damage = 2.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.15,
            maxNearby = 1,
            exp = 0,    // 经验给0，默认不写给20
            drops = listOf(),
            affixes = listOf(),
//            helmet = Material.LEATHER_HELMET,
//            chestplate = Material.DIAMOND_CHESTPLATE,
//            leggings = Material.DIAMOND_LEGGINGS,
//            boots = Material.DIAMOND_BOOTS,
//            mainHand = Material.IRON_SWORD,
//            offHand = Material.SHIELD
        ))
        register(MobDefinition(
            id = "senlinjiangshi",
            name = "&c森林僵尸",
            type = EntityType.ZOMBIE,
            health = 20.0,
            damage = 2.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.8),
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.4),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 20%掉落皮革 卖钱的
                MobDrop("pojiupige", 1, 1, 0.1)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
        ))
        register(MobDefinition(
            id = "senlinkulou",
            name = "&c森林骷髅",
            type = EntityType.SKELETON,
            health = 12.0,
            damage = 3.5,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.25,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.9),
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.2),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.WOODEN_SWORD,
        ))
        register(MobDefinition(
            id = "senlinzhizhu",
            name = "&c森林蜘蛛",
            type = EntityType.SPIDER,
            health = 10.0,
            damage = 3.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.6),
                // 掉落水元素
                MobDrop("metal", 1, 2, 0.2),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 30%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 1, 0.3)
            ),
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "jy_senlinjiangshi",
            name = "&c精英-森林僵尸",
            type = EntityType.ZOMBIE,
            health = 35.0,
            damage = 5.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 2, 3, 0.8),
                // 掉落金元素
                MobDrop("metal", 2, 3, 0.4),
                // 70%掉落铜钱
                MobDrop("hjh_tongqian", 1, 3, 0.7),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 40%掉落皮革 卖钱的
                MobDrop("pojiupige", 1, 1, 0.4)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.STONE_SWORD
        ))
        register(MobDefinition(
            id = "jy_senlinkulou",
            name = "&c精英-森林骷髅",
            type = EntityType.SKELETON,
            health = 16.0,
            damage = 4.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 2, 3, 0.9),
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.2),
                // 70%掉落铜钱
                MobDrop("hjh_tongqian", 1, 3, 0.7),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.BOW,
        ))
        register(MobDefinition(
            id = "jy_senlinzhizhu",
            name = "&c精英-森林蜘蛛",
            type = EntityType.SPIDER,
            health = 16.0,
            damage = 5.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 3, 0.6),
                // 掉落水元素
                MobDrop("metal", 1, 3, 0.2),
                // 70%掉落铜钱
                MobDrop("hjh_tongqian", 1, 3, 0.7),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 2, 0.5)
            ),
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "gongpingongjianshou",
            name = "&c携带贡品的弓箭手",
            type = EntityType.SKELETON,
            health = 14.0,
            damage = 4.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.25,
            maxNearby = 2,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.7),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 25%掉落贡品
                MobDrop("shanshengongpin", 1, 2, 0.25)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.BOW
        ))
        register(MobDefinition(
            id = "gongpinjiangshi",
            name = "&c携带贡品的僵尸",
            type = EntityType.ZOMBIE,
            health = 24.0,
            damage = 3.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.7),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 25%掉落贡品
                MobDrop("shanshengongpin", 1, 2, 0.25)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
        ))
        register(MobDefinition(
            id = "gongpingongzhizhu",
            name = "&c携带贡品的蜘蛛",
            type = EntityType.SPIDER,
            health = 15.0,
            damage = 3.0,
            armor = 0.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.7),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 25%掉落贡品
                MobDrop("shanshengongpin", 1, 2, 0.25)
            ),
            affixes = listOf()
        ))
        register(MobDefinition(
            id = "zhizhunvwang",
            name = "&6神速的 蜘蛛女王",
            type = EntityType.SPIDER,
            health = 200.0,
            damage = 4.0,
            armor = 5.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.35,
            maxNearby = 1,
            drops = listOf(
                MobDrop("wood", 1, 2, 1.0),
                MobDrop("hjh_tongqian", 3, 5, 0.9),
                MobDrop("relive_stone", 1, 2, 1.0),
                MobDrop("zhizhuyan", 1, 3, 0.7),
                //赤铜锭
                MobDrop("chitongding", 1, 1, 0.25),
                //三阶核心
                MobDrop("armor_core_3", 1, 1, 0.25)
            ),
            affixes = listOf()
        ))
        register(MobDefinition(
            id = "yssl_jiangshi",
            name = "&c试图抢劫贡品的僵尸",
            type = EntityType.ZOMBIE,
            health = 7.5,
            damage = 2.0,
            armor = 0.0,
            speed = 0.2,
            maxNearby = 3,
            drops = listOf(
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
        ))
        register(MobDefinition(
            id = "yssl_kulou",
            name = "&c试图抢劫贡品的骷髅",
            type = EntityType.SKELETON,
            health = 6.0,
            damage = 3.5,
            armor = 0.0,
            speed = 0.25,
            maxNearby = 3,
            drops = listOf(
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.WOODEN_SWORD,
        ))
        register(MobDefinition(
            id = "yssl_zhizhu",
            name = "&c试图抢劫贡品的蜘蛛",
            type = EntityType.SPIDER,
            health = 6.5,
            damage = 3.0,
            armor = 0.0,
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
            ),
            affixes = listOf(),
        ))
        // === 副本 Boss 注册 ===
        register(MobDefinition(
            id = "qinglongshiwei",
            name = "&a&l青龙侍卫",
            type = EntityType.ZOMBIE,
            health = 500.0,
            damage = 5.0,   // 伤害你可以自己按需调整
            armor = 20.0,   // 20点自定义护甲
            speed = 0.2,   // 移速按需调整
            maxNearby = 1,
            exp = 50,       // 经验值
            drops = listOf(), // 副本Boss暂不需要普通掉落，通过副本结算给奖励
            affixes = listOf(),
            helmet = Material.IRON_HELMET, // 纯装饰铁头盔
            mainHand = Material.IRON_AXE   // 纯装饰铁斧
        ))
    }
}