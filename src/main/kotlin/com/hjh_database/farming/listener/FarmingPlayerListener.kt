package com.hjh_database.farming.listener

import com.hjh_database.farming.manager.FarmingManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class FarmingPlayerListener(private val manager: FarmingManager) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        manager.loadAndCache(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        manager.saveAndRemove(event.player)
    }
}
