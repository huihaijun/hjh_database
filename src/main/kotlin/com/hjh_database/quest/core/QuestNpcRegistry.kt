package com.hjh_database.quest.core

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Villager

/**
 * 剧情 NPC 注册表
 * 这里记录了所有主线/支线涉及到的 NPC 的初始设定
 */
enum class StoryNpcs(
    val id: String,           // 唯一ID (NpcManager用的)
    val displayName: String,  // 默认名字 (支持颜色代码)
    val profession: Villager.Profession, // 职业 (影响外观)
    val type: Villager.Type,             // 种类 (影响外观)
    val locX: Double, val locY: Double, val locZ: Double, val yaw: Float // 默认坐标
) {

    // === 在这里定义你的 NPC ===

    // 格式: ID, 名字, 职业, 皮肤类型, X, Y, Z, 朝向
    REN_xiaoren(
        "ren_xiaoren",
        "§a§l新手引导员-小仁",
        Villager.Profession.NITWIT,
        Villager.Type.PLAINS,
        1677.5, 134.5, 141.5, 90f
    ),

    REN_CHIEF(
    "ren_chief",
    "§a§l村长",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    1686.5, 143.5, 105.5, 90f
    ),

    REN_xiaoli(
    "ren_xiaoli",
    "§a§l小礼",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    1786.5, 175.5, 109.5, 90f
    ),

    REN_SMITH(
    "ren_smith",
    "§a§l铁匠铺掌柜",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    1737.5, 160.5, 97.5, 90f
    ),

    REN_ALCHEMIST(
    "ren_alchemist",
    "§a§l炼丹房掌柜",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    1654.5, 153.5, 174.5, 90f
    ),

    REN_LIGONGGONG(
    "ren_ligonggong",
    "§a§l大内总管-李公公",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    173.5, 66.5, -158.5, 90f
    ),

    REN_FAHAI(
    "ren_fahai",
    "§a§l护国法师-法海",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    243.5, 64.5, -205.5, 90f
    ),

    REN_WANGMAO(
    "ren_wangmao",
    "§a§l镇长-王卯",
    Villager.Profession.NITWIT,
    Villager.Type.PLAINS,
    597.5, 45.5, 37.5, 0f
    ),

    QINGLONGFENHUN(
    "qinglongfenhun",
    "§a§l青龙分魂",
    Villager.Profession.NITWIT,
    Villager.Type.SNOW,
    1696.5, 103.5, 852.5, 0f
    ),

    LVZHOUXIAOZHENDEZHENZHANG(
    "lvzhouxiaozhendezhenzhang",
    "§a§l绿洲小镇的镇长",
    Villager.Profession.NONE,
    Villager.Type.SWAMP,
    -16.5, 48.5, 805.5, 0f
    ),

    WUZHEHONGLUAN(
    "wuzhehongluan",
    "§a§l巫者红鸾",
    Villager.Profession.NITWIT,
    Villager.Type.SAVANNA,
    11.5, 48.5, 813.5, 0f
    ),

    LIANXIN(
    "lianxin",
    "§a§l莲心",
    Villager.Profession.NITWIT,
    Villager.Type.SAVANNA,
    375.5, 50.5, 767.5, 0f
    ),

    JITAN_LIANXIN(
        "jitan_lianxin",
        "§a§l莲心",
        Villager.Profession.NITWIT,
        Villager.Type.SAVANNA,
        244.5, 17.5, 720.5, 0f
    ),

    SHAOBIN(
        "shaobin",
        "§a§l邵斌",
        Villager.Profession.FISHERMAN,
        Villager.Type.DESERT,
        -833.5, 83.5, 438.5, 0f
    ),
    TUIXIUDELAOLIEHU(
        "tuixiudelaoliehu",
        "§a§l退休的老猎户",
        Villager.Profession.NITWIT,
        Villager.Type.PLAINS,
        508.5, 51.5, 33.5, 0f
    ),
    SUYUANZHENREN(
        "suyuanzhenren",
        "§a§l溯源真人",
        Villager.Profession.NITWIT,
        Villager.Type.TAIGA,
        191.5, 48.5, -271.5, 0f
    ),
    DANYESHIFU(
        "danyeshifu",
        "§a§l旦野师傅",
        Villager.Profession.NITWIT,
        Villager.Type.TAIGA,
        255.5, 48.5, 45.5, 0f
    ),
        TIANJIGEZONGGUANSUNYUAN(
        "tianjigezongguansunyuan",
        "§a§l孙元",
        Villager.Profession.NITWIT,
        Villager.Type.PLAINS,
        120.5, 49.5, -5.5, 0f
    ),
        WULINGHE(
        "wulinghe",
        "§a§l武凌河",
        Villager.Profession.NITWIT,
        Villager.Type.TAIGA,
        118.5, 59.5, -23.5, 0f
    ),
        YAO_XIAOHUA(
        "yao_xiaohua",
        "§a§l新手引导员-小花",
        Villager.Profession.NONE,
        Villager.Type.JUNGLE,
        2849.5, 48.5, 885.5, 0f
    ),
    YAO_GUZHU(
        "yao_guzhu",
        "§a§l叶灵谷谷主",
        Villager.Profession.TOOLSMITH,
        Villager.Type.JUNGLE,
        2784.5, 53.5, 864.5, 0f
    ),
    YAO_XIAOMAN(
        "yao_xiaoman",
        "§a§l小蔓",
        Villager.Profession.NONE,
        Villager.Type.JUNGLE,
        2758.5, 51.5, 861.5, 0f
    ),
    YAO_TIEJIANGPUZHANGGUI(
        "yao_tiejiangpuzhanggui",
        "§a§l铁匠铺掌柜",
        Villager.Profession.NONE,
        Villager.Type.JUNGLE,
        2648.5, 96.5, 862.5, 0f
    ),
    YAO_DANYAOPUZHANGGUI(
        "yao_danyaopuzhanggui",
        "§a§l丹药铺掌柜",
        Villager.Profession.NONE,
        Villager.Type.JUNGLE,
        2656.5, 89.5, 895.5, 0f
    ),
    YAO_HUAYAO(
        "yao_huayao",
        "§a§l华夭",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        -196.5, 65.5, -172.5, 0f
    ),
    YAO_DAZHANGLAO(
        "yao_dazhanglao",
        "§a§l妖族大长老-蚩尤",
        Villager.Profession.LEATHERWORKER,
        Villager.Type.DESERT,
        -198.5, 148.5, -176.5, 0f
    ),
//    西方区域NPC
    SHANGXINDELAOYEYE(
        "shangxindelaoyeye",
        "§a§l伤心的老爷爷",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        -274.5, 100.5, 88.5, 0f
    ),
    GULINGLINGDEXIAONANHAI(
        "gulinglingdexiaonanhai",
        "§a§l孤伶伶的小男孩",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        -582.5, 109.5, 188.5, 0f
    ),
    SHOUJINGDELONGXUZHENJUMIN(
        "shoujingdelongxuzhenjumin",
        "§a§l受惊的龙须镇居民",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        390.5, 45.5, -13.5, 0f
    ),
    QIXIAZHENZHENZHANG(
        "qixaizhenzhenzhang",
        "§b§l栖霞镇镇长-安民",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        648.5, 57.5, -462.5, 0f
    ),
    QIXIAZHENFANGSHILAOBAN(
        "qixiazhenfangshilaoban",
        "§b§l坊市老板-金大仁",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        696.5, 58.5, -494.5, 0f
    ),
    BUTIANJUGUANLIYUAN(
        "butianjuguanliyuan",
        "§b§l卜天居管理员-采心",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        689.5, 58.5, -499.5, 0f
    ),
    LINGTIANZHANG(
        "lingtianzhang",
        "§b§l灵田田长-弦农",
        Villager.Profession.NONE,
        Villager.Type.PLAINS,
        656.5, 40.5, -529.5, 0f
    ),
    SHEN_SHULING(
    "shen_shuling",
    "§a§l新手引导员-舒灵",
    Villager.Profession.NONE,
    Villager.Type.SNOW,
    3195.5, 76.5, 374.5, 90f
    ),
    SHEN_ZHANGLAO(
        "shen_zhanglao",
        "§a§l长老",
        Villager.Profession.NONE,
        Villager.Type.SWAMP,
        3336.5, 158.5, 118.5, 0f
    ),
    SHEN_ZIYUANZHONGXINFUZEREN(
        "shen_ziyuanzhongxinfuzeren",
        "§a§l资源中心负责人",
        Villager.Profession.NONE,
        Villager.Type.SNOW,
        3284.5, 115.5, 362.5, 0f
    ),
    SHEN_DANTAFUZEREN(
        "shen_dantafuzeren",
        "§a§l丹塔负责人",
        Villager.Profession.NONE,
        Villager.Type.SNOW,
        3346.5, 120.5, 358.5, 0f
    ),
    RENHUANGXUANYUANSHI(
        "renhuangxuanyuanshi",
        "§a§l人皇-轩辕氏",
        Villager.Profession.MASON,
        Villager.Type.PLAINS,
        179.5, 68.5, -165.5, 0f
    );


    // 后续有新 NPC 直接往这里加...

    /**
     * 获取配置的 Location 对象 (默认在 world 世界)
     * 你可以根据需要修改世界获取逻辑
     */
    fun getLocation(): Location {
        val world = Bukkit.getWorld("world")
            ?: throw IllegalStateException("世界 'world' 不存在！无法获取NPC坐标")
        return Location(world, locX, locY, locZ, yaw, 0f)
    }
}
