package com.hjh_database.qixiazhen.farming.effect.impl

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.effect.FarmToolEffect
import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import org.bukkit.Sound
import org.bukkit.entity.Player

class FarmInsectWardEffect : FarmToolEffect {
    override val resourceId: String = FarmingManager.INSECT_WARD_RESOURCE_ID

    override fun apply(player: Player, state: PlayerFarmState, manager: FarmingManager): Boolean {
        val now = System.currentTimeMillis()
        if (!state.planted || state.ready(now)) return false

        val oldUntil = state.insectProtectedUntil.coerceAtLeast(now)
        val newUntil = (oldUntil + PROTECTION_MILLIS).coerceAtMost(state.maturesAt)
        if (newUntil <= state.insectProtectedUntil) {
            player.sendMessage(manager.config.message("insect_protection_full"))
            return true
        }

        state.insectProtectedUntil = newUntil
        manager.consumeMainHand(player, 1)
        manager.saveState(player, state)
        val seconds = ((newUntil - now + 999L) / 1000L).coerceAtLeast(0L)
        player.sendMessage(manager.config.message("insect_protected").replace("{seconds}", seconds.toString()))
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.35f)
        return true
    }

    companion object {
        private const val PROTECTION_MILLIS = 30L * 60L * 1000L
    }
}
