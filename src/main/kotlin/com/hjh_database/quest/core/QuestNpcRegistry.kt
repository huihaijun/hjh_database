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