package com.hjh_database.data

/** 临时属性池中的最终倍率按来源相乘，作用于计算完成的属性；不改写装备或永久属性。 */
internal object TemporaryStatMultipliers {
    fun value(bonuses: Map<String, Double>, stat: String): Double = bonuses.entries
        .filter { it.key.substringAfterLast("::") == "${stat}_multiplier" }
        .fold(1.0) { result, entry -> result * entry.value.coerceAtLeast(0.0) }
}
