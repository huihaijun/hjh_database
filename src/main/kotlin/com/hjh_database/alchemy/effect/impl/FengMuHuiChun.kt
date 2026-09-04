package com.hjh_database.alchemy.effect.impl

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

open class FengMuHuiChun(
    override val id: String = "fengmuhuichun",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§b逢木回春")
        if (fixedTier != null) return recipe

        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        addTier(recipe, plugin, AlchemyTier.LOW, "fengmuhuichun0")
        addTier(recipe, plugin, AlchemyTier.MID, "fengmuhuichun1")
        addTier(recipe, plugin, AlchemyTier.HIGH, "fengmuhuichun2")
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val effectiveTier = fixedTier ?: tier
        val config = config(effectiveTier)
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: data.maxHealth
        player.health = (player.health + config.heal).coerceAtMost(maxHealth)

        val current = player.getPotionEffect(PotionEffectType.REGENERATION)
        if (current == null || current.amplifier <= config.amplifier) {
            player.addPotionEffect(
                PotionEffect(PotionEffectType.REGENERATION, (config.duration * 20).toInt(), config.amplifier, false, true, true),
                true
            )
        }

        player.world.playSound(player.location, Sound.BLOCK_GRASS_PLACE, 0.9f, 1.6f)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location.clone().add(0.0, 1.0, 0.0), 18, 0.45, 0.45, 0.45, 0.02)
        player.sendMessage("§b[逢木回春] §f恢复 §b${config.heal.toInt()} §f点生命。")
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun addTier(recipe: AlchemyRecipe, plugin: Hjh_database, tier: AlchemyTier, resultId: String) {
        val result = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.POTION)
        recipe.tierData[tier] = TierConfig(arrayListOf(), result)
    }

    private fun config(tier: AlchemyTier): HuiChunConfig = when (tier) {
        AlchemyTier.LOW -> HuiChunConfig(8.0, 0, 10L)
        AlchemyTier.MID -> HuiChunConfig(16.0, 1, 10L)
        AlchemyTier.HIGH -> HuiChunConfig(24.0, 1, 15L)
    }

    private data class HuiChunConfig(val heal: Double, val amplifier: Int, val duration: Long)
}

class FengMuHuiChun0 : FengMuHuiChun("fengmuhuichun0", AlchemyTier.LOW)
class FengMuHuiChun1 : FengMuHuiChun("fengmuhuichun1", AlchemyTier.MID)
class FengMuHuiChun2 : FengMuHuiChun("fengmuhuichun2", AlchemyTier.HIGH)
