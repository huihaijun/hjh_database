package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.listener.CombatDamageCalculationEvent
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobAffixSupport
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

object BaihuWestSkill : Listener {
    private const val TRIGGER_CHANCE = 0.30
    private const val PLAYER_COOLDOWN_MS = 10_000L
    private const val MIASMA_INCREASE = 20
    private const val NON_BAIHU_DAMAGE_MULTIPLIER = 0.70

    private lateinit var plugin: Hjh_database
    private val playerCooldowns = ConcurrentHashMap<UUID, Long>()

    fun init(plugin: Hjh_database) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAffixMobDamagePlayer(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val attacker = MobAffixSupport.realAttacker(event.damager) ?: return
        if (attacker is Player) return
        if (!MobAffixSupport.hasAffix(attacker, MobAffix.BAIHU_WEST)) return
        tryTrigger(player)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onAffixMobTakesDamage(event: CombatDamageCalculationEvent) {
        val target = event.victim
        if (!MobAffixSupport.hasAffix(target, MobAffix.BAIHU_WEST)) return
        if (BaihuEquipmentDamageTag.isMarked(plugin, target)) return
        event.damage *= NON_BAIHU_DAMAGE_MULTIPLIER
    }

    private fun tryTrigger(player: Player) {
        val now = System.currentTimeMillis()
        val nextAllowed = playerCooldowns[player.uniqueId] ?: 0L
        if (now < nextAllowed) return

        if (ThreadLocalRandom.current().nextDouble() > TRIGGER_CHANCE) return

        playerCooldowns[player.uniqueId] = now + PLAYER_COOLDOWN_MS
        plugin.baihuMiasmaManager.addMiasmaDirect(player, MIASMA_INCREASE)
    }
}
