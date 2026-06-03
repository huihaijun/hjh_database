package com.hjh_database.alchemy.effect.impl

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

open class YuHeDan(
    override val id: String = "yuhedan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id)
        if (fixedTier != null) return recipe

        recipe.displayName = "§b愈合丹"
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database

        addTier(recipe, plugin, AlchemyTier.LOW, "yuhedan0", Material.WHEAT_SEEDS, 2, Material.APPLE, 1)
        addTier(recipe, plugin, AlchemyTier.MID, "yuhedan1", Material.WHEAT_SEEDS, 4, Material.GOLDEN_APPLE, 1)
        addTier(recipe, plugin, AlchemyTier.HIGH, "yuhedan2", Material.WHEAT_SEEDS, 6, Material.ENCHANTED_GOLDEN_APPLE, 1)

        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val effectiveTier = fixedTier ?: tier
        val healAmount = when (effectiveTier) {
            AlchemyTier.LOW -> 8.0
            AlchemyTier.MID -> 16.0
            AlchemyTier.HIGH -> 24.0
        }

        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = (player.health + healAmount).coerceAtMost(maxHealth)
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun addTier(
        recipe: AlchemyRecipe,
        plugin: Hjh_database,
        tier: AlchemyTier,
        resultId: String,
        firstMaterial: Material,
        firstAmount: Int,
        secondMaterial: Material,
        secondAmount: Int
    ) {
        val resultItem = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.POTION)
        val ingredients = arrayListOf(
            ItemStack(firstMaterial, firstAmount),
            ItemStack(secondMaterial, secondAmount)
        )
        recipe.tierData[tier] = TierConfig(ingredients, resultItem)
    }
}

class YuHeDan0 : YuHeDan("yuhedan0", AlchemyTier.LOW)
class YuHeDan1 : YuHeDan("yuhedan1", AlchemyTier.MID)
class YuHeDan2 : YuHeDan("yuhedan2", AlchemyTier.HIGH)
