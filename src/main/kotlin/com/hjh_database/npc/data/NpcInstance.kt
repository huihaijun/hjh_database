package com.hjh_database.npc.data

import org.bukkit.Location
import java.util.UUID

/**
 * NPC 实例
 * 存储实体UUID和位置，用于重启后恢复
 */
data class NpcInstance(
    val uuid: UUID,        // 实体的 UUID
    val templateId: String,// 关联的模板 ID
    val location: Location // 生成位置
)