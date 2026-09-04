package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType

open class JieDuWan(
    override val id: String = "jieduwan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe() = AlchemyRecipe(id, "§b解毒丸")

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val config = config(fixedTier ?: tier)
        data.reduceOtherPillSickness(config.sicknessReductionPercent)
        data.activePills.removeIf { it.effectId.startsWith(PREFIX, ignoreCase = true) }
        player.removePotionEffect(PotionEffectType.POISON)
        return config.poisonImmunitySeconds
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
        if (remainingSeconds <= 0L) return
        player.removePotionEffect(PotionEffectType.POISON)
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun config(tier: AlchemyTier): JieDuConfig = when (tier) {
        AlchemyTier.LOW -> JieDuConfig(50.0, 45L)
        AlchemyTier.MID -> JieDuConfig(80.0, 120L)
        AlchemyTier.HIGH -> JieDuConfig(100.0, 180L)
    }

    private data class JieDuConfig(
        val sicknessReductionPercent: Double,
        val poisonImmunitySeconds: Long
    )

    companion object {
        const val PREFIX = "jieduwan"
    }
}

class JieDuWan0 : JieDuWan("jieduwan0", AlchemyTier.LOW)
class JieDuWan1 : JieDuWan("jieduwan1", AlchemyTier.MID)
class JieDuWan2 : JieDuWan("jieduwan2", AlchemyTier.HIGH)
