package com.hjh_database.accessory.skill

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent

// 注意这里：一定要加上 : BaseQuiver(plugin)
class JiandaiSkill(plugin: Hjh_database) : BaseQuiver(plugin) {

    // 基础箭袋没特效，直接留空即可
    override fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData) {
        // 无事发生
    }
}