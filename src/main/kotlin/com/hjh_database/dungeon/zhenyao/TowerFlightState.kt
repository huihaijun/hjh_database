package com.hjh_database.dungeon.zhenyao

import com.hjh_database.Hjh_database
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** 存在玩家PDC中，掉线、死亡和服务器重启后均能恢复原来的飞行/重力设置。 */
internal class TowerFlightState(plugin: Hjh_database) {
    private val key = NamespacedKey(plugin, "zhenyao_flight_backup")
    fun capture(player: Player) {
        if (player.persistentDataContainer.has(key, PersistentDataType.STRING)) return
        val slow = player.getPotionEffect(PotionEffectType.SLOW_FALLING)
        val slowEnd = if (slow == null) 0L else if (slow.isInfinite) Long.MAX_VALUE else System.currentTimeMillis() + slow.duration * 50L
        player.persistentDataContainer.set(key, PersistentDataType.STRING,
            "${player.allowFlight}|${player.isFlying}|${player.hasGravity()}|${player.flySpeed}|$slowEnd|${slow?.amplifier ?: 0}")
    }
    fun restore(player: Player) {
        val raw = player.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return
        val c = raw.split('|')
        player.isFlying = false
        player.allowFlight = c[0].toBoolean()
        if (player.allowFlight && !player.isDead) player.isFlying = c[1].toBoolean()
        player.setGravity(c[2].toBoolean())
        player.flySpeed = c[3].toFloat()
        player.fallDistance = 0f
        player.removePotionEffect(PotionEffectType.SLOW_FALLING)
        val end = c[4].toLong()
        val duration = if (end == Long.MAX_VALUE) -1 else ((end - System.currentTimeMillis()) / 50).coerceAtLeast(0).toInt()
        if (duration != 0) player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, duration, c[5].toInt()))
        player.persistentDataContainer.remove(key)
    }
}
