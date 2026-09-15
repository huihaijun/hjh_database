package com.hjh_database.dungeon

import java.util.UUID

/**
 * 副本队伍 HUD 的通用入口。所有方法由 Bukkit 主线程调用。
 * 一个集合对应一个真实场次；同种副本多开时返回多个集合，禁止先合并场次名单。
 * 返回当前仍在参战的玩家 UUID；没有进行中的场次时返回空列表。
 */
interface DungeonPartyProvider {
    val dungeonId: String
    fun activePartyMembers(): List<Set<UUID>>
}
