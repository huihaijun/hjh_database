package com.hjh_database.race.impl

import com.hjh_database.race.RaceBase
import com.hjh_database.race.RaceManager
import com.hjh_database.race.xian.XianTalentManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot

class XianRace(manager: RaceManager) : RaceBase(manager) {

    data class ForgeReward(val forgeExp: Int, val playerExp: Int)

    override val raceId: Int = RACE_ID
    override val requiredQuestId: String = PROOF_QUEST_ID

    @EventHandler(priority = EventPriority.LOWEST)
    fun onProofUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        val player = event.player
        if (!isRaceActive(player) || manager.getResourceId(player.inventory.itemInMainHand) != XianTalentManager.PROOF_ITEM_ID) return

        val clickedLocation = event.clickedBlock?.location
        if (clickedLocation != null) {
            val waypointId = manager.plugin.chonghuaManager.getPlacedWaypointId(clickedLocation)
            if (waypointId != null) {
                event.isCancelled = true
                manager.plugin.xianTalentManager.bind(player, waypointId)
                return
            }
            // 手持证明点击普通重华晶本体时仍使用普通重华晶，不抢占交互。
            if (manager.plugin.chonghuaManager.isPlacedCrystal(clickedLocation)) return
        }

        event.isCancelled = true
        manager.plugin.xianTalentManager.openMenu(player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        manager.plugin.server.scheduler.runTaskLater(manager.plugin, Runnable {
            if (event.player.isOnline) ensureInitialForgeLevel(event.player, notify = false)
        }, 40L)
    }

    fun ensureInitialForgeLevel(player: Player, notify: Boolean = true): Boolean {
        if (!isRaceActive(player)) return false
        val data = manager.plugin.playerManager.getDzData(player.uniqueId) ?: return false
        if (data.forgeLevel >= INITIAL_FORGE_LEVEL) return false

        data.forgeLevel = INITIAL_FORGE_LEVEL
        manager.plugin.server.scheduler.runTaskAsynchronously(manager.plugin, Runnable {
            manager.plugin.databaseManager.saveDzPlayerData(data)
        })
        if (notify) player.sendMessage("§e[百器之智] §f你的初始锻造等级已提升至 §b2级§f。")
        return true
    }

    fun grantForgeSuccessRewards(player: Player, baseForgeExp: Int): ForgeReward {
        ensureInitialForgeLevel(player, notify = false)
        val active = isRaceActive(player)
        val actualForgeExp = if (active) {
            ((baseForgeExp.coerceAtLeast(0).toLong() * FORGE_EXP_PERCENT) / 100L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            baseForgeExp.coerceAtLeast(0)
        }
        val forgeData = manager.plugin.playerManager.getDzData(player.uniqueId)
        if (actualForgeExp > 0 && forgeData != null) {
            forgeData.addExp(actualForgeExp, manager.plugin, player)
        }

        val playerExp = if (active) {
            (actualForgeExp.toLong() * PLAYER_EXP_MULTIPLIER)
                .coerceAtMost(MAX_PLAYER_EXP.toLong()).toInt()
        } else 0
        if (playerExp > 0) manager.plugin.playerManager.giveExp(player, playerExp)
        return ForgeReward(actualForgeExp, playerExp)
    }

    override fun castActiveSkill(player: Player) {
        manager.plugin.xianTalentManager.openMenu(player)
    }

    companion object {
        const val PROOF_QUEST_ID = "main_xian_5"
        private const val RACE_ID = 1
        private const val INITIAL_FORGE_LEVEL = 2
        private const val FORGE_EXP_PERCENT = 130L
        private const val PLAYER_EXP_MULTIPLIER = 2L
        private const val MAX_PLAYER_EXP = 500
    }
}
