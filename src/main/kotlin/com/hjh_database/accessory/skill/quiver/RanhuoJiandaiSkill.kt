package com.hjh_database.accessory.skill.quiver

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent

class RanhuoJiandaiSkill(plugin: Hjh_database) : BaseQuiverSkill(plugin) {

    // 接收 PlayerData
    override fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData) {

        // 直接从你的 PlayerData 里拿属性！不需要任何强制转换和查字典！
        val archerDamage = data.archerDamage

        // 比如：基础燃烧 100 tick (5秒)，每 1 点射手伤害额外增加 10 tick (0.5秒) 燃烧时间
        val fireDuration = 100 + (archerDamage * 10).toInt()

        event.projectile.fireTicks = fireDuration
    }
}