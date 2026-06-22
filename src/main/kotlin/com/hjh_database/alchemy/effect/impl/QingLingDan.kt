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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionEffectTypeCategory

class QingLingDan : AlchemyEffect {
    override val id = "qinglingdan"

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§b清灵丹")
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        val result = plugin.resourceManager.getItem(id)?.clone() ?: ItemStack(Material.POTION)
        recipe.tierData[AlchemyTier.LOW] = TierConfig(
            arrayListOf(ItemStack(Material.MILK_BUCKET), ItemStack(Material.BLAZE_POWDER, 2)),
            result
        )
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        player.activePotionEffects
            .filter { it.type.category == PotionEffectTypeCategory.HARMFUL }
            .forEach { player.removePotionEffect(it.type) }
        player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 30 * 20, 0))
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }
}
