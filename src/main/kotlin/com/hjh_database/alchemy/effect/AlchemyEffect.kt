package com.hjh_database.alchemy.effect

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player

interface AlchemyEffect {
    val id: String

    /** 获取默认配方 (用于自动生成配置) */
    fun getDefaultRecipe(): AlchemyRecipe

    // === 生命周期 ===

    /**
     * 1. 瞬间服用时触发 (一次性逻辑写在这，比如回血)
     * @return 返回该药效的持续时间(秒)。如果返回 0，则视为瞬间药剂，不进入 buff 列表。
     */
    fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long

    /**
     * 2. 每秒触发 (用于持续性 Buff，比如持续回血、属性加成)
     * 注意：如果是瞬间药剂(持续时间0)，此方法不会被调用
     */
    fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long)

    /**
     * 3. 药效结束时触发 (用于移除属性加成、发送提示)
     */
    fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier)
}