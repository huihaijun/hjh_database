package com.hjh_database.skill.element_zf

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player

interface ElementSkill {
    /**
     * 释放元素阵法
     * @param player 施法玩家
     * @param level 当前阵法等级
     * @param config 该技能在 element_zf.yml 中的配置节点 (包含数值、距离等)
     * @return 是否成功释放 (如果失败则不进入冷却)
     */
    fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean
}

interface EnhancedElementSkill {
    fun cast(player: Player, data: com.hjh_database.data.PlayerData): Boolean
}
