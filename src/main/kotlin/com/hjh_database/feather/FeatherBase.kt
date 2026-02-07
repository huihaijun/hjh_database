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
     * 冷却时间 (秒)
     */
    val cooldownSeconds: Int

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
     * 效果结束时触发 (时间到或受伤打断)
     */
    fun onEnd(player: Player)
}