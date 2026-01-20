package com.hjh_database.skill.medical.spell

import com.hjh_database.data.PlayerData
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player

interface MedicalSpell {
    /**
     * 释放医术
     *
     * @param player 施法玩家
     * @param data   玩家数据 (用于获取阵法强度等)
     * @param config 该医术在 medical_items.yml 中的配置节点 (读取数值用) - 可能为 null
     * @return true 表示释放成功 (扣灵力/进CD)，false 表示释放失败或被取消
     */
    fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean
}