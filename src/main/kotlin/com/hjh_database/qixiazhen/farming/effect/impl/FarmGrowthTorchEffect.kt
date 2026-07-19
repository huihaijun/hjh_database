package com.hjh_database.qixiazhen.farming.effect.impl

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.effect.FarmToolEffect
import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import org.bukkit.entity.Player

class FarmGrowthTorchEffect : FarmToolEffect {
    override val resourceId: String = "farm_growth_torch"

    override fun apply(player: Player, state: PlayerFarmState, manager: FarmingManager): Boolean {
        if (!state.planted || state.ready()) return false
        state.maturesAt = (state.maturesAt - 30_000L).coerceAtLeast(System.currentTimeMillis())
        manager.consumeMainHand(player, 1)
        manager.saveState(player, state)
        player.sendMessage(manager.config.message("accelerated"))
        return true
    }
}
