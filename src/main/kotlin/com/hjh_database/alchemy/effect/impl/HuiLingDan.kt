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

open class HuiLingDan(
    override val id: String = "huilingdan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id)
        if (fixedTier != null) return recipe

        recipe.displayName = "§b回灵丹"
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database

        addTier(recipe, plugin, AlchemyTier.LOW, "huilingdan0", Material.GLOWSTONE_DUST, 2, Material.WHEAT_SEEDS, 2)
        addTier(recipe, plugin, AlchemyTier.MID, "huilingdan1", Material.GLOWSTONE_DUST, 4, Material.AMETHYST_SHARD, 1)
        addTier(recipe, plugin, AlchemyTier.HIGH, "huilingdan2", Material.GLOWSTONE_DUST, 6, Material.ECHO_SHARD, 1)

        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val effectiveTier = fixedTier ?: tier
        val restoreAmount = when (effectiveTier) {
            AlchemyTier.LOW -> 12.0
            AlchemyTier.MID -> 24.0
            AlchemyTier.HIGH -> 36.0
        }

        data.addLingli(restoreAmount)
        player.sendMessage("§b[回灵丹] §f恢复了 §b${restoreAmount.toInt()} §f点灵力。")
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

class HuiLingDan0 : HuiLingDan("huilingdan0", AlchemyTier.LOW)
class HuiLingDan1 : HuiLingDan("huilingdan1", AlchemyTier.MID)
class HuiLingDan2 : HuiLingDan("huilingdan2", AlchemyTier.HIGH)
