package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestStatus
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

class Shen_07TriggerListener(private val plugin: Hjh_database) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPressurePlate(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return

        val block = event.clickedBlock ?: return
        if (block.type != Material.STONE_PRESSURE_PLATE ||
            block.world.name != "world" ||
            block.x != 253 || block.y != 63 || block.z != -210
        ) return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses["main_shen_7"] != QuestStatus.IN_PROGRESS ||
            data.questProgress["main_shen_7"] != 2
        ) return

        plugin.questManager.updateProgress(player, "main_shen_7", 3)
        player.sendMessage("§7你被一股莫名的力量挡了回来，有一个声音告诉你：「没有圣兽祝福，是无法进入圣山的」，请将这个情况回报给法海吧")
    }
}
