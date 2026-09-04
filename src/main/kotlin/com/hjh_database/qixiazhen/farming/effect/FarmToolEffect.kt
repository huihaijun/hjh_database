package com.hjh_database.qixiazhen.farming.effect

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import org.bukkit.entity.Player

interface FarmToolEffect {
    val resourceId: String
    fun apply(player: Player, state: PlayerFarmState, manager: FarmingManager): Boolean
}
