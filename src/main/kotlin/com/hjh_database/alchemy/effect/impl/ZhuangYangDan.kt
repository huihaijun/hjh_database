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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

open class ZhuangYangDan(
    override val id: String = "zhuangyangdan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§6壮阳丹")
        if (fixedTier != null) return recipe

        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        addTier(recipe, plugin, AlchemyTier.LOW, "zhuangyangdan0", 2, 1)
        addTier(recipe, plugin, AlchemyTier.MID, "zhuangyangdan1", 4, 2)
        addTier(recipe, plugin, AlchemyTier.HIGH, "zhuangyangdan2", 6, 3)
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val effectiveTier = fixedTier ?: tier
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: data.maxHealth
        val (percent, cap, duration) = when (effectiveTier) {
            AlchemyTier.LOW -> Triple(0.20, 40.0, 15L)
            AlchemyTier.MID -> Triple(0.25, 50.0, 20L)
            AlchemyTier.HIGH -> Triple(0.30, 60.0, 30L)
        }
        val shield = min(maxHealth * percent, cap)
        val amplifier = (ceil(shield / 4.0).toInt() - 1).coerceAtLeast(0)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, (duration * 20).toInt(), amplifier))
        player.absorptionAmount = max(player.absorptionAmount, shield)
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
        blazePowder: Int,
        honeyBottles: Int
    ) {
        val result = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.POTION)
        recipe.tierData[tier] = TierConfig(
            arrayListOf(ItemStack(Material.BLAZE_POWDER, blazePowder), ItemStack(Material.HONEY_BOTTLE, honeyBottles)),
            result
        )
    }
}

class ZhuangYangDan0 : ZhuangYangDan("zhuangyangdan0", AlchemyTier.LOW)
class ZhuangYangDan1 : ZhuangYangDan("zhuangyangdan1", AlchemyTier.MID)
class ZhuangYangDan2 : ZhuangYangDan("zhuangyangdan2", AlchemyTier.HIGH)
