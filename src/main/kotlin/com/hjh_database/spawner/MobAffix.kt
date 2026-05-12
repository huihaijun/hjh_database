package com.hjh_database.spawner

enum class MobAffix(val id: String, val displayName: String, val description: String) {
    SPEED("speed", "神速的", "移动速度大幅提升"),
    THORNS("thorns", "反抗的", "受到攻击时反弹伤害"),
    BURNING("burning", "燃烧的", "免疫火焰，攻击附带燃烧效果"),
    HEAVY("heavy", "千斤的", "免疫所有击退效果"),
    PIERCING("piercing", "破军的", "攻击无视目标护甲"),
    // === 【新增】 ===
    DESERT_SOUTH("desert_south", "恶土的", "攻击有60%概率附带恶土之炎");

    companion object {
        private val map = values().associateBy(MobAffix::id)
        fun fromId(id: String): MobAffix? = map[id]
    }
}