package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class LuoShuiDan : AlchemyEffect {
    override val id = "luoshuidan"

    override fun getDefaultRecipe() = AlchemyRecipe(id, "洛水丹")

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        player.addPotionEffect(
            PotionEffect(PotionEffectType.WATER_BREATHING, EFFECT_DURATION_TICKS, 0, false, true, true),
            true
        )
        player.addPotionEffect(
            PotionEffect(PotionEffectType.DOLPHINS_GRACE, EFFECT_DURATION_TICKS, 0, false, true, true),
            true
        )
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private companion object {
        const val EFFECT_DURATION_TICKS = 60 * 20
    }
}
