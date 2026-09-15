package com.hjh_database.client

import java.util.UUID

/** 每个输入名单代表一个实际副本场次；人数上限和自己置顶均在场次内应用。 */
internal fun <T> dungeonPartiesByViewer(
    parties: List<List<T>>,
    memberId: (T) -> UUID,
    order: Comparator<T>,
    maxMembers: Int
): Map<UUID, List<T>> {
    require(maxMembers > 0)
    // 如果异常数据让同一玩家属于多个场次，清空其 HUD，避免泄露或合并其他队伍。
    val memberships = parties.flatMap { party -> party.map(memberId).distinct() }
        .groupingBy { it }.eachCount()
    return buildMap {
        parties.forEach { party ->
            val members = party.distinctBy(memberId)
                .filter { memberships[memberId(it)] == 1 }.sortedWith(order)
            members.forEach { viewer ->
                val viewerId = memberId(viewer)
                put(viewerId, (listOf(viewer) + members.filter { memberId(it) != viewerId }).take(maxMembers))
            }
        }
    }
}
