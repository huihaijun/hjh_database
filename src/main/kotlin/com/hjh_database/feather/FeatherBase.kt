package com.hjh_database.feather

import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player

/**
 * 所有功能性羽毛的基类接口
 */
interface FeatherBase {
    /**
     * 资源物品的 resource_id (例如 hjh_xyzy)
     */
    val id: String

    /**
     * 用于提示玩家的羽毛名称（不包含颜色代码）。
     */
    val displayName: String

    /**
     * 冷却时间 (秒)
     */
    val cooldownSeconds: Int

    /**
     * 效果持续时间（秒），不同品阶羽毛可以独立配置。
     */
    val durationSeconds: Int

    /**
     * 检查玩家是否有资格使用
     * 返回 true 表示可以使用的
     * 返回 false 时，请在方法内自行发送提示消息
     */
    fun canUse(player: Player, data: PlayerData): Boolean

    /**
     * 激活效果时触发
     */
    fun onStart(player: Player)

    /**
     * 玩家实际受到伤害时触发。
     * 返回 true 表示本次受伤会令羽毛效果结束；false 表示调整后继续生效。
     */
    fun onDamage(player: Player): Boolean

    /**
     * 效果结束时触发。
     */
    fun onEnd(player: Player, reason: FeatherEndReason)
}

enum class FeatherEndReason {
    EXPIRED,
    DAMAGED,
    REPLACED,
    QUIT,
    DISABLE
}
