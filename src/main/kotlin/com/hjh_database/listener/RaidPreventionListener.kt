package com.hjh_database.listener

import com.hjh_database.Hjh_database
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.raid.RaidTriggerEvent
import org.bukkit.event.world.WorldLoadEvent

/**
 * 全局关闭原版村庄袭击。
 *
 * 主要依靠原版 disableRaids 游戏规则，服务端无需周期扫描村庄、玩家或袭击实体；
 * RaidTriggerEvent 仅作为低开销的兜底，防止其他插件临时改回游戏规则后触发袭击。
 */
class RaidPreventionListener(private val plugin: Hjh_database) : Listener {

    init {
        plugin.server.worlds.forEach(::disableRaids)
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        disableRaids(event.world)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRaidTrigger(event: RaidTriggerEvent) {
        event.isCancelled = true
    }

    private fun disableRaids(world: World) {
        if (world.getGameRuleValue(GameRule.DISABLE_RAIDS) != true) {
            world.setGameRule(GameRule.DISABLE_RAIDS, true)
        }
    }
}
