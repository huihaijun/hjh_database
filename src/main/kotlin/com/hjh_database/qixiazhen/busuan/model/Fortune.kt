package com.hjh_database.qixiazhen.busuan.model

import org.bukkit.Color
import kotlin.random.Random

enum class Fortune(
    val resourceId: String,
    val displayName: String,
    val particleColor: Color,
    val weight: Int
) {
    SHANG_SHANG("shangshangqian", "§e上上签", Color.fromRGB(255, 185, 35), 8),
    SHANG("shangqian", "§d上签", Color.fromRGB(255, 105, 200), 22),
    ZHONG("zhongqian", "§9中签", Color.fromRGB(65, 105, 255), 40),
    XIA("xiaqian", "§f下签", Color.fromRGB(245, 245, 245), 22),
    XIA_XIA("xiaxiaqian", "§7下下签", Color.fromRGB(105, 105, 105), 8);

    companion object {
        fun byResourceId(id: String?): Fortune? = entries.firstOrNull { it.resourceId == id }

        fun weightedRandom(): Fortune {
            val roll = Random.nextInt(entries.sumOf(Fortune::weight))
            var accumulated = 0
            return entries.first { fortune ->
                accumulated += fortune.weight
                roll < accumulated
            }
        }
    }
}
