package com.hjh_database.dungeon.qixi

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.scoreboard.Team

/**
 * 保留怪物的箭矢命中箱，同时通过队伍规则关闭实体之间的物理推挤。
 * LivingEntity#isCollidable=false 会让原版箭矢直接穿过实体，不能用于战斗怪物。
 */
internal object QixiCollisionSupport {
    private const val TEAM_NAME = "hjh_qixi_mobs"

    fun enableProjectileHits(entity: LivingEntity) {
        entity.isCollidable = true
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val team = scoreboard.getTeam(TEAM_NAME) ?: scoreboard.registerNewTeam(TEAM_NAME)
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)
        team.addEntry(entity.uniqueId.toString())
    }

    fun clear() {
        Bukkit.getScoreboardManager().mainScoreboard.getTeam(TEAM_NAME)?.unregister()
    }
}
