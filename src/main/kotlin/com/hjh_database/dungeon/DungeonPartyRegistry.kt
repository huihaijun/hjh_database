package com.hjh_database.dungeon

import java.util.UUID

/** 主线程注册、采集与清理。只读取副本的权威名单，不额外维护玩家进出状态。 */
class DungeonPartyRegistry(private val onError: (String, Exception) -> Unit) {
    private val providers = linkedMapOf<String, DungeonPartyProvider>()
    private val failed = hashSetOf<String>()

    /** 返回是否新增；同一对象重复注册不会重复同步或重复注册监听器。 */
    fun register(provider: DungeonPartyProvider): Boolean {
        val id = provider.dungeonId
        require(id.isNotBlank()) { "Dungeon ID must not be blank" }
        val previous = providers[id]
        if (previous === provider) return false
        require(previous == null) { "Dungeon provider already registered: $id" }
        providers[id] = provider
        return true
    }

    fun unregister(dungeonId: String) {
        providers.remove(dungeonId)
        failed.remove(dungeonId)
    }

    fun snapshotParties(): List<Set<UUID>> = providers.flatMap { (id, provider) ->
        try {
            val parties = provider.activePartyMembers().map { it.toSet() }.filter { it.isNotEmpty() }
            failed.remove(id)
            parties
        } catch (error: Exception) {
            // 单个副本异常时清空该副本 HUD，其他副本继续同步；恢复后自动重新显示。
            if (failed.add(id)) onError(id, error)
            emptyList()
        }
    }

    fun clear() {
        providers.clear()
        failed.clear()
    }
}
