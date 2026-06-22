package com.hjh_database.alchemy.effect.impl

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

open class FengHou(
    override val id: String = "fenghou",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§c封喉")
        if (fixedTier != null) return recipe

        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        addTier(recipe, plugin, AlchemyTier.LOW, "fenghou0", 2, 1)
        addTier(recipe, plugin, AlchemyTier.MID, "fenghou1", 4, 2)
        addTier(recipe, plugin, AlchemyTier.HIGH, "fenghou2", 6, 3)
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long = 0

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun addTier(
        recipe: AlchemyRecipe,
        plugin: Hjh_database,
        tier: AlchemyTier,
        resultId: String,
        spiderEyes: Int,
        poisonousPotatoes: Int
    ) {
        val result = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.SPLASH_POTION)
        recipe.tierData[tier] = TierConfig(
            arrayListOf(ItemStack(Material.SPIDER_EYE, spiderEyes), ItemStack(Material.POISONOUS_POTATO, poisonousPotatoes)),
            result
        )
    }
}

class FengHou0 : FengHou("fenghou0", AlchemyTier.LOW)
class FengHou1 : FengHou("fenghou1", AlchemyTier.MID)
class FengHou2 : FengHou("fenghou2", AlchemyTier.HIGH)
