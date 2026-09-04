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
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.ceil
import kotlin.math.max

class JueZhangDan : AlchemyEffect {
    override val id = "juezhangdan"

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§b绝瘴丹")
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as? Hjh_database
        val result = plugin?.resourceManager?.getItem(id)?.clone() ?: ItemStack(Material.POTION)
        recipe.tierData[AlchemyTier.LOW] = TierConfig(arrayListOf(), result)
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as? Hjh_database ?: return 0
        val before = plugin.baihuMiasmaManager.getMiasma(player)
        val after = plugin.baihuMiasmaManager.reduceMiasma(player, MIASMA_REMOVE)
        val removed = (before - after).coerceAtLeast(0)

        player.world.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.6f)
        player.world.spawnParticle(Particle.WITCH, player.location.clone().add(0.0, 1.0, 0.0), 16, 0.35, 0.45, 0.35, 0.02)
        player.sendMessage("§b[绝瘴丹] §f散去了 §b${String.format("%.1f", removed / 10.0)}% §7虎瘴§f。")

        if (after > SHIELD_THRESHOLD) {
            grantShield(player)
            player.sendMessage("§b[绝瘴丹] §7残余虎瘴仍重，药力凝成护盾护住心脉。")
        }
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun grantShield(player: Player) {
        val amplifier = (ceil(SHIELD_AMOUNT / 4.0).toInt() - 1).coerceAtLeast(0)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, SHIELD_DURATION_TICKS, amplifier, false, true, true))
        player.absorptionAmount = max(player.absorptionAmount, SHIELD_AMOUNT)
        player.world.spawnParticle(Particle.ENCHANT, player.location.clone().add(0.0, 1.1, 0.0), 24, 0.45, 0.5, 0.45, 0.0)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f)
    }

    companion object {
        private const val MIASMA_REMOVE = 80
        private const val SHIELD_THRESHOLD = 400
        private const val SHIELD_AMOUNT = 15.0
        private const val SHIELD_DURATION_TICKS = 30 * 20
    }
}
