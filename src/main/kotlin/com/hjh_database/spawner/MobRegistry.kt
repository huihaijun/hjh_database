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
        // ================ 东方区域怪物 ===================
        register(MobDefinition(
            id = "senlinjiangshi",
            name = "&c森林僵尸",
            type = EntityType.ZOMBIE,
            health = 20.0,
            damage = 3.0,
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
                MobDrop("pojiupige", 1, 1, 0.1),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
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
                MobDrop("relive_stone", 1, 1, 0.1),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
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
            damage = 4.0,
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
                // 50%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 1, 0.5),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
            ),
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "jy_senlinjiangshi",
            name = "&c精英-森林僵尸",
            type = EntityType.ZOMBIE,
            health = 35.0,
            damage = 5.5,
            armor = 6.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
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
                MobDrop("pojiupige", 1, 1, 0.4),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
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
            damage = 6.0,
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
                MobDrop("relive_stone", 1, 1, 0.3),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
            ),
            affixes = listOf(),
            helmet = Material.LEATHER_HELMET,
            mainHand = Material.BOW,
        ))
        register(MobDefinition(
            id = "jy_senlinzhizhu",
            name = "&c精英-森林蜘蛛",
            type = EntityType.SPIDER,
            health = 22.0,
            damage = 7.0,
            armor = 6.5, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
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
                // 60%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 2, 0.6),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.05)
            ),
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "gongpingongjianshou",
            name = "&c携带贡品的弓箭手",
            type = EntityType.SKELETON,
            health = 14.0,
            damage = 4.0,
            armor = 2.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
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
            damage = 4.5,
            armor = 5.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
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
            damage = 5.5,
            armor = 4.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
                // 掉落木元素
                MobDrop("wood", 1, 2, 0.7),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 25%掉落贡品
                MobDrop("shanshengongpin", 1, 2, 0.25),
                // 40%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 2, 0.4)
            ),
            affixes = listOf()
        ))
        register(MobDefinition(
            id = "zhizhunvwang",
            name = "&6神速的 蜘蛛女王",
            type = EntityType.SPIDER,
            health = 250.0,
            damage = 11.5,
            exp = 50,       // 经验值
            armor = 11.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.4,
            maxNearby = 1,
            drops = listOf(
                MobDrop("wood", 1, 2, 1.0),
                MobDrop("hjh_tongqian", 3, 5, 0.9),
                MobDrop("relive_stone", 1, 2, 1.0),
                MobDrop("zhizhuyan", 1, 3, 0.9),
                //赤铜锭
                MobDrop("chitongding", 1, 1, 0.25),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.15)
            ),
            affixes = listOf()
        ))
        register(MobDefinition(
            id = "yssl_jiangshi",
            name = "&c试图抢劫贡品的僵尸",
            type = EntityType.ZOMBIE,
            health = 9.5,
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
            health = 7.0,
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
            health = 8.5,
            damage = 3.0,
            armor = 0.0,
            speed = 0.3,
            maxNearby = 3,
            drops = listOf(
            ),
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "shenmushouwei",
            name = "&6千斤的 神木守卫",
            type = EntityType.ZOMBIE,
            health = 360.0,
            damage = 15.0,
            armor = 15.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            exp = 100,       // 经验值
            speed = 0.2,
            maxNearby = 1,
            drops = listOf(
                MobDrop("wood", 2, 2, 1.0),
                MobDrop("metal", 2, 2, 0.9),
                MobDrop("hjh_tongqian", 5, 6, 0.7),
                MobDrop("relive_stone", 1, 2, 1.0),
                MobDrop("renshen", 1, 2, 0.5),
                //赤铜锭
                MobDrop("chitongding", 1, 1, 0.25),
                //龙鳞神木
                MobDrop("longlinshenmu", 1, 1, 0.25),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_longlinzhisen", 1, 1, 0.2)
            ),
            affixes = listOf(),
            helmet = Material.IRON_HELMET,
            chestplate = Material.IRON_CHESTPLATE,
            mainHand = Material.IRON_SWORD

        ))
        // === 副本 Boss 注册 ===
        register(MobDefinition(
            id = "qinglongshiwei",
            name = "&c&l青龙侍卫",
            type = EntityType.ZOMBIE,
            health = 500.0,
            damage = 10.0,   // 伤害你可以自己按需调整
            armor = 20.0,   // 20点自定义护甲
            speed = 0.2,   // 移速按需调整
            maxNearby = 1,
            exp = 50,       // 经验值
            drops = listOf(), // 副本Boss暂不需要普通掉落，通过副本结算给奖励
            affixes = listOf(),
            helmet = Material.IRON_HELMET, // 纯装饰铁头盔
            mainHand = Material.IRON_AXE   // 纯装饰铁斧
        ))
        // ================ 南方区域怪物 ===================
        // === 朱雀试炼 ===
        register(MobDefinition(
            id = "zhuqueshiwei",
            name = "&c&l朱雀侍卫",
            type = EntityType.PHANTOM,
            health = 30000.0,
            damage = 10.0,   // 伤害你可以自己按需调整
            armor = 100.0,   // 20点自定义护甲
            speed = 0.2,   // 移速按需调整
            maxNearby = 1,
            exp = 500,       // 经验值
            drops = listOf(), // 副本Boss暂不需要普通掉落，通过副本结算给奖励
            affixes = listOf(),
        ))
        register(MobDefinition(
            id = "zhuque_yanbing_zombie",
            name = "&6朱雀炎兵-僵尸",
            type = EntityType.ZOMBIE,
            health = 16.0,
            damage = 8.0,
            armor = 12.0,
            exp=10,
            speed = 0.2,
            maxNearby = 1,
            drops = listOf(),
            affixes = listOf(),
            helmet = Material.GOLDEN_HELMET, // 纯装饰
            mainHand = Material.DIAMOND_SWORD   // 纯装饰铁斧
        ))
        register(MobDefinition(
            id = "zhuque_yanbing_kulou",
            name = "&6朱雀炎兵-骷髅",
            type = EntityType.SKELETON,
            health = 12.0,
            damage = 5.0,
            armor = 4.0,
            exp=10,
            speed = 0.25,
            maxNearby = 1,
            drops = listOf(),
            affixes = listOf(),
            helmet = Material.GOLDEN_HELMET, // 纯装饰
            mainHand = Material.BOW   // 纯装饰
        ))
//        南方区域大世界怪物
        register(MobDefinition(
            id = "etuzhihun",
            name = "&c燃烧的 恶土之魂",
            type = EntityType.MAGMA_CUBE,
            health = 24.0,
            damage = 4.0,
            armor = 12.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=35,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.6),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 20%掉落煤炭 卖钱的
                MobDrop("meitan", 1, 1, 0.2),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
        ))
        register(MobDefinition(
            id = "huoyanmo",
            name = "&c燃烧的 火焰魔",
            type = EntityType.BLAZE,
            health = 35.0,
            damage = 10.0,
            armor = 12.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=35,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.6),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 20%掉落煤炭 卖钱的
                MobDrop("meitan", 1, 1, 0.05),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
        ))
        register(MobDefinition(
            id = "huangshatubing",
            name = "&c黄沙土兵",
            type = EntityType.HUSK,
            health = 45.0,
            damage = 8.0,
            armor = 14.0,
            speed = 0.2,
            exp=35,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.6),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 20%掉落煤炭 卖钱的
                MobDrop("meitan", 1, 1, 0.05),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.STONE_SWORD   // 纯装饰
        ))
        register(MobDefinition(
            id = "heiguzhanshi",
            name = "&c燃烧的 黑骨战士",
            type = EntityType.WITHER_SKELETON,
            health = 30.0,
            damage = 11.0,
            armor = 8.0,
            speed = 0.23,
            exp=40,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落煤炭 卖钱的
                MobDrop("meitan", 1, 1, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.IRON_AXE   // 纯装饰
        ))
        register(MobDefinition(
            id = "mazeituanshibing",
            name = "&c燃烧的 马贼团士兵",
            type = EntityType.ZOMBIE,
            health = 40.0,
            damage = 8.0,
            armor = 12.0,
            speed = 0.2,
            exp=45,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落货物
                MobDrop("wangyuanwaibeiqiangzoudehuowu", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.NETHER_BRICK  // 纯装饰 这个是小刀
        ))
        register(MobDefinition(
            id = "shamogongshou",
            name = "&c燃烧的 沙漠弓手",
            type = EntityType.SKELETON,
            health = 28.0,
            damage = 8.0,
            armor = 8.0,
            speed = 0.2,
            exp=45,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 10%掉落人参
                MobDrop("renshen", 1, 1, 0.1),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.05)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.BOW   // 纯装饰
        ))
        register(MobDefinition(
            id = "mazeituantuanzhang",
            name = "&6燃烧千斤的 马贼团团长",
            type = EntityType.HUSK,
            health = 540.0,
            damage = 16.0,
            armor = 23.0,
            speed = 0.3,
            exp=80,
            maxNearby = 1,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 1.0),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.8),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.4),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 1.0),
                // 货物
                MobDrop("wangyuanwaibeiqiangzoudehuowu", 3, 4, 1.0),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 2, 0.6),
                // 掉落三阶武器材料
                MobDrop("yanjingshi", 1, 1, 0.3),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.1)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            chestplate = Material.IRON_CHESTPLATE,
            mainHand = Material.DIAMOND_SWORD   // 纯装饰
        ))
        register(MobDefinition(
            id = "wenxian_huoyanmo",
            name = "&c携带文献的 火焰魔",
            type = EntityType.BLAZE,
            health = 32.0,
            damage = 10.0,
            armor = 14.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=60,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落货物
                MobDrop("shangxianwenxian", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),

            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
        ))
        register(MobDefinition(
            id = "wenxian_etuhun",
            name = "&c携带文献的 恶土魂",
            type = EntityType.MAGMA_CUBE,
            health = 30.0,
            damage = 8.0,
            armor = 9.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=60,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.6),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 30%掉落货物
                MobDrop("shangxianwenxian", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
        ))
        register(MobDefinition(
            id = "wenxian_zombie",
            name = "&c携带文献的 僵尸",
            type = EntityType.ZOMBIE,
            health = 46.0,
            damage = 9.0,
            armor = 16.0,
            speed = 0.2,
            exp=60,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落货物
                MobDrop("shangxianwenxian", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
            helmet = Material.LEATHER_HELMET, // 纯装饰
            mainHand = Material.STONE_SHOVEL  // 纯装饰
        ))
        register(MobDefinition(
            id = "wenquan_huoyanmo",
            name = "&c骚扰客栈的 火焰魔",
            type = EntityType.BLAZE,
            health = 30.0,
            damage = 9.0,
            armor = 11.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=60,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.1),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 30%掉落货物
                MobDrop("wenquankezhanbujipin", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
        ))
        register(MobDefinition(
            id = "wenquan_etuhun",
            name = "&c骚扰客栈的 恶土魂",
            type = EntityType.MAGMA_CUBE,
            health = 30.0,
            damage = 6.0,
            armor = 9.0, // 这里的 20 会被写入 NBT 供 CombatListener 计算减伤
            speed = 0.2,
            exp=60,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.6),
                // 掉落土元素
                MobDrop("earth", 2, 2, 0.9),
                // 30%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.3),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.1),
                // 30%掉落货物
                MobDrop("wenquankezhanbujipin", 1, 2, 0.3),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
        ))
        register(MobDefinition(
            id = "wenquan_huangshatubing",
            name = "&c骚扰客栈的 黄沙土兵",
            type = EntityType.HUSK,
            health = 50.0,
            damage = 8.0,
            armor = 16.0,
            speed = 0.2,
            exp=50,
            maxNearby = 2,
            drops = listOf(
                // 掉落火元素
                MobDrop("fire", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.6),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.2),
                // 20%掉落货物
                MobDrop("wenquankezhanbujipin", 1, 2, 0.2),
                // 焱砂之心
                MobDrop("yanshazhixin", 1, 1, 0.2),
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH),
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.STONE_SWORD   // 纯装饰
        ))
        register(MobDefinition(
            id = "shamofengbao",
            name = "&6千斤的 沙漠风暴",
            type = EntityType.BREEZE,
            health = 560.0,
            damage = 19.0,
            armor = 26.0,
            speed = 0.15,
            exp=80,
            maxNearby = 1,
            drops = listOf(
                // 掉落土元素
                MobDrop("earth", 3, 4, 1.0),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.4),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 1.0),
                // 焱砂之心
                MobDrop("yanshazhixin", 2, 3, 0.6),
                // 掉落三阶材料 黄风眼
                MobDrop("huangfengyan", 1, 1, 0.35),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.1)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
        ))
        register(MobDefinition(
            id = "xiongshentaisui",
            name = "&6千斤的 凶神太岁",
            type = EntityType.MAGMA_CUBE,
            health = 650.0,
            damage = 20.0,
            armor = 35.0,
            speed = 0.22,
            exp=100,
            maxNearby = 1,
            drops = listOf(
                // 掉落土元素
                MobDrop("earth", 3, 4, 1.0),
                // 10%掉落金元宝
                MobDrop("jinyuanbao", 1, 1, 0.4),
                // 10%掉落重生石
                MobDrop("relive_stone", 1, 1, 1.0),
                // 焱砂之心
                MobDrop("yanshazhixin", 3, 4, 0.6),
                // 掉落恶魂丹
                MobDrop("ehundan", 1, 1, 0.4),
                // 极低概率掉落江湖心得
                MobDrop("jianghuxinde_yanshadamo", 1, 1, 0.1)
            ),
            affixes = listOf(MobAffix.DESERT_SOUTH), // 南方词条
        ))

        // ================ 西方区域怪物 ===================
        register(MobDefinition(
            id = "baihudong_ceshi_zombie",
            name = "&c附满虎瘴的 测试僵尸",
            type = EntityType.ZOMBIE,
            health = 100.0,
            damage = 18.0,
            armor = 0.0,
            speed = 0.1,
            exp= 0,
            maxNearby = 2,
            drops = listOf(
                ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.DIAMOND_PICKAXE  // 纯装饰
        ))
        register(MobDefinition(
            id = "baihudong_zombie",
            name = "&c附满虎瘴的 矿洞僵尸",
            type = EntityType.ZOMBIE,
            health = 100.0,
            damage = 18.0,
            armor = 30.0,
            speed = 0.24,
            exp= 120,
            maxNearby = 2,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 30%掉落瘴气玄铁
                MobDrop("fumanzhangqidexuantieding", 1, 1, 0.3),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.DIAMOND_PICKAXE  // 纯装饰
        ))
        register(MobDefinition(
            id = "baihudong_kulou",
            name = "&c附满虎瘴的 矿洞白骨",
            type = EntityType.SKELETON,
            health = 75.0,
            damage = 16.0,
            armor = 25.0,
            speed = 0.22,
            exp= 120,
            maxNearby = 2,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 30%掉落瘴气玄铁
                MobDrop("fumanzhangqidezhuangbeihexin", 1, 1, 0.3),
                ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.BOW  // 纯装饰
        ))
        register(MobDefinition(
            id = "baihudong_zhizhu",
            name = "&c附满虎瘴的 矿洞蜘蛛",
            type = EntityType.SPIDER,
            health = 85.0,
            damage = 22.0,
            armor = 21.0,
            speed = 0.25,
            exp= 120,
            maxNearby = 2,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落蜘蛛眼
                MobDrop("zhizhuyan", 1, 3, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 15%掉落瘴气核心
                MobDrop("fumanzhangqidezhuangbeihexin", 1, 1, 0.15),
                // 15掉落瘴气玄铁
                MobDrop("fumanzhangqidexuantieding", 1, 1, 0.15),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
        ))
        register(MobDefinition(
            id = "lveduoshouju_zombie",
            name = "&c掠夺商人的 矿洞僵尸",
            type = EntityType.ZOMBIE,
            health = 110.0,
            damage = 19.0,
            armor = 35.0,
            speed = 0.24,
            exp= 160,
            maxNearby = 2,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 30%掉落收据
                MobDrop("huzhenshangrenshouju", 1, 2, 0.3),
                // 15%掉落瘴气核心
                MobDrop("fumanzhangqidezhuangbeihexin", 1, 1, 0.15),
                ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.LEATHER_HELMET, // 纯装饰
            chestplate = Material.LEATHER_CHESTPLATE,
            mainHand = Material.NETHER_BRICK  // 纯装饰
        ))
        register(MobDefinition(
            id = "lveduoshouju_kulou",
            name = "&c掠夺商人的 白骨战士",
            type = EntityType.SKELETON,
            health = 90.0,
            damage = 25.0,
            armor = 25.0,
            speed = 0.25,
            exp= 160,
            maxNearby = 1,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 30%掉落收据
                MobDrop("huzhenshangrenshouju", 1, 2, 0.3),
                // 15掉落瘴气玄铁
                MobDrop("fumanzhangqidexuantieding", 1, 1, 0.15),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.GOLDEN_HELMET, // 纯装饰
            mainHand = Material.IRON_AXE  // 纯装饰
        ))
        register(MobDefinition(
            id = "lveduoshouju_nushou",
            name = "&c掠夺商人的 洞穴弩手",
            type = EntityType.BOGGED,
            health = 80.0,
            damage = 25.0,
            armor = 28.0,
            speed = 0.18,
            exp= 160,
            maxNearby = 2,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 1, 2, 0.8),
                // 掉落土元素
                MobDrop("earth", 1, 2, 0.6),
                // 50%掉落铜钱
                MobDrop("hjh_tongqian", 1, 2, 0.5),
                // 30%掉落重生石
                MobDrop("relive_stone", 1, 1, 0.3),
                // 30%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 1, 2, 0.3),
                // 30%掉落收据
                MobDrop("huzhenshangrenshouju", 1, 2, 0.3),
                // 15%掉落瘴气核心
                MobDrop("fumanzhangqidezhuangbeihexin", 1, 1, 0.15),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.IRON_HELMET, // 纯装饰
            mainHand = Material.CROSSBOW  // 纯装饰
        ))
        register(MobDefinition(
            id = "jiaoguzhanshi",
            name = "&6焦骨战士",
            type = EntityType.WITHER_SKELETON,
            health = 650.0,
            damage = 30.0,
            armor = 45.0,
            speed = 0.28,
            exp= 240,
            maxNearby = 1,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 5, 5, 0.95),
                // 掉落土元素
                MobDrop("earth", 4, 5, 0.95),
                // 80%掉落金元宝
                MobDrop("hjh_tongqian", 1, 2, 0.8),
                // 100%掉落重生石
                MobDrop("relive_stone", 2, 3, 1.0),
                // 50%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 2, 2, 0.5),
                // 35%掉落瘴气虎魄
                MobDrop("zhangqi_hupo", 1, 1, 0.35),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            helmet = Material.DIAMOND_HELMET, // 纯装饰
            mainHand = Material.GOLDEN_SWORD  // 纯装饰
        ))
        register(MobDefinition(
            id = "kuanggongwanghun",
            name = "&6矿工亡魂",
            type = EntityType.VINDICATOR,
            health = 750.0,
            damage = 25.0,
            armor = 60.0,
            speed = 0.25,
            exp= 240,
            maxNearby = 1,
            drops = listOf(
                // 掉落金元素
                MobDrop("metal", 5, 5, 0.95),
                // 掉落土元素
                MobDrop("earth", 4, 5, 0.95),
                // 80%掉落金元宝
                MobDrop("hjh_tongqian", 1, 2, 0.8),
                // 100%掉落重生石
                MobDrop("relive_stone", 2, 3, 1.0),
                // 50%掉落瘴气meitan
                MobDrop("fumanzhangqidemeitan", 2, 2, 0.5),
                // 35%掉落瘴气瘴气虎金
                MobDrop("zhangqi_hujin", 1, 1, 0.35),
                // 15%掉落zhichunzhixin
                MobDrop("zhichunzhixin", 1, 1, 0.15),
            ),
            affixes = listOf(MobAffix.BAIHU_WEST), // 西方词条
            mainHand = Material.IRON_PICKAXE  // 纯装饰
        ))
    }
}
