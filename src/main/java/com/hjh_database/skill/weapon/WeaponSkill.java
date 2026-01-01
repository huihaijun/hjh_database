package com.hjh_database.skill.weapon;

import com.hjh_database.data.PlayerData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public interface WeaponSkill {
    /**
     * 释放主动技能
     * @param player 释放者
     * @param data   玩家数据
     * @param config 配置节点
     * @param projectile 触发技能的投射物 (弓箭手是箭矢，战士可能是 null)
     * @return 释放是否成功
     */
    boolean castActive(Player player, PlayerData data, ConfigurationSection config, Entity projectile);
}