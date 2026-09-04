package com.hjh_database.race

import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.event.Listener

/**
 * 所有种族的基类
 */
abstract class RaceBase(protected val manager: RaceManager) : Listener {

    /**
     * 种族 ID (如 人族=2)
     */
    abstract val raceId: Int

    /**
     * 激活种族被动的前置任务 ID
     */
    abstract val requiredQuestId: String

    /**
     * 判断玩家是否拥有该种族且完成了任务
     */
    fun isRaceActive(player: Player): Boolean {
        val data = manager.plugin.playerManager.getPlayerData(player) ?: return false

        // 1. 检查种族 ID
        if (data.race != raceId) return false

        // 2. 检查任务状态
        if (!manager.isQuestCompleted(player, requiredQuestId)) return false

        return true
    }

    /**
     * 主动技能接口 (预留)
     */
    open fun castActiveSkill(player: Player) {
        player.sendMessage("§7该种族暂无主动技能。")
    }
}