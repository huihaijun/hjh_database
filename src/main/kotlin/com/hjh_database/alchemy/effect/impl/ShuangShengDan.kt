package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

open class ShuangShengDan(
    override val id: String = "shuangshengdan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe() = AlchemyRecipe(id, "§b双生丹")

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        return config(fixedTier ?: tier).durationSeconds
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
        if (remainingSeconds <= 0L) return

        val config = config(fixedTier ?: tier)
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: data.maxHealth
        val maxLingli = data.maxLingli
        val healthRatio = if (maxHealth > 0.0) player.health / maxHealth else 1.0
        val lingliRatio = if (maxLingli > 0.0) data.lingli / maxLingli else 1.0

        if (healthRatio <= lingliRatio) {
            player.health = (player.health + config.restoreAmount).coerceAtMost(maxHealth)
        } else {
            data.addLingli(config.restoreAmount)
        }
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun config(tier: AlchemyTier): ShuangShengConfig = when (tier) {
        AlchemyTier.LOW -> ShuangShengConfig(8L, 5.0)
        AlchemyTier.MID -> ShuangShengConfig(8L, 7.0)
        AlchemyTier.HIGH -> ShuangShengConfig(10L, 8.0)
    }

    private data class ShuangShengConfig(
        val durationSeconds: Long,
        val restoreAmount: Double
    )
}

class ShuangShengDan0 : ShuangShengDan("shuangshengdan0", AlchemyTier.LOW)
class ShuangShengDan1 : ShuangShengDan("shuangshengdan1", AlchemyTier.MID)
class ShuangShengDan2 : ShuangShengDan("shuangshengdan2", AlchemyTier.HIGH)
