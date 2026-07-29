package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import com.hjh_database.spawner.impl.NorthWetnessSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class QuShiDan : AlchemyEffect {
    override val id = "qushidan"

    override fun getDefaultRecipe() = AlchemyRecipe(id, "§b祛湿丹")

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        NorthWetnessSkill.reduceWetness(player, WETNESS_REDUCTION)
        player.world.spawnParticle(
            Particle.SPLASH,
            player.location.clone().add(0.0, 1.0, 0.0),
            24,
            0.45,
            0.6,
            0.45,
            0.08
        )
        player.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 0.7f, 1.5f)
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private companion object {
        private const val WETNESS_REDUCTION = 25
    }
}
