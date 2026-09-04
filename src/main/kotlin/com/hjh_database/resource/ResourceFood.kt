package com.hjh_database.resource

import org.bukkit.configuration.ConfigurationSection

data class ResourceFoodPotionEffect(
    val type: String,
    val chancePercent: Double,
    val durationSeconds: Double,
    val level: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean
)

data class ResourceFood(
    /** Hunger-bar icons restored. One icon equals two vanilla food-level points. */
    val hunger: Double,
    val saturation: Float,
    val canAlwaysEat: Boolean,
    val eatSeconds: Float,
    val potionEffects: List<ResourceFoodPotionEffect>,
    val clearEffects: List<String>,
    val cooldownSeconds: Float?
) {
    companion object {
        const val DEFAULT_EAT_SECONDS = 1.6f

        fun from(section: ConfigurationSection): ResourceFood? {
            val food = section.getConfigurationSection("food") ?: section
            if (!food.contains("hunger")) return null

            val effects = food.getMapList("potion_effects").mapNotNull { raw ->
                val type = raw["type"]?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                ResourceFoodPotionEffect(
                    type = type,
                    chancePercent = number(raw["chance"], 100.0).coerceIn(0.0, 100.0),
                    durationSeconds = number(raw["duration_seconds"], 10.0).coerceAtLeast(0.05),
                    level = number(raw["level"], 1.0).toInt().coerceAtLeast(1),
                    ambient = boolean(raw["ambient"], false),
                    particles = boolean(raw["particles"], true),
                    icon = boolean(raw["icon"], true)
                )
            }

            return ResourceFood(
                hunger = food.getDouble("hunger").coerceAtLeast(0.0),
                saturation = food.getDouble("saturation", 0.0).toFloat().coerceAtLeast(0f),
                canAlwaysEat = food.getBoolean("can_always_eat", false),
                eatSeconds = food.getDouble("eat_seconds", DEFAULT_EAT_SECONDS.toDouble()).toFloat().coerceAtLeast(0.05f),
                potionEffects = effects,
                clearEffects = food.getStringList("clear_effects").map(String::trim).filter(String::isNotEmpty),
                cooldownSeconds = if (food.contains("cooldown_seconds")) {
                    food.getDouble("cooldown_seconds").toFloat().coerceAtLeast(0f).takeIf { it > 0f }
                } else null
            )
        }

        private fun number(value: Any?, fallback: Double): Double = when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull() ?: fallback
        }

        private fun boolean(value: Any?, fallback: Boolean): Boolean = when (value) {
            is Boolean -> value
            else -> value?.toString()?.toBooleanStrictOrNull() ?: fallback
        }
    }
}
