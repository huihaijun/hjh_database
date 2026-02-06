package com.hjh_database.npc.data

import org.bukkit.entity.Villager

/**
 * NPC 模板
 * 存储所有静态数据，不包含位置信息
 */
data class NpcTemplate(
    val id: String,
    var name: String,
    var profession: Villager.Profession = Villager.Profession.NONE, // 职业(皮肤)
    var type: Villager.Type = Villager.Type.PLAINS,               // 地区变种(皮肤)
    var dialogue: MutableList<String> = ArrayList(),              // 对话列表
    var trades: MutableList<CustomTrade> = ArrayList(),            // 交易列表
    var allowRaceDiscount: Boolean = false               // 【新增】是否允许种族打折（默认为 false）
)