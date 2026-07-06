package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class YanjingdunpaiSkill(plugin: Hjh_database) : BaseShieldSkill(plugin) {

    private val activeUntil = HashMap<UUID, Long>()
    private val particleTasks = HashMap<UUID, BukkitTask>()
    private val emberDust = Particle.DustOptions(Color.fromRGB(255, 92, 18), 0.9f)

    override fun getBlockCooldownMillis(crystalData: CrystalData): Long {
        return 3000L
    }

    override fun onBlockSuccess(player: Player, event: EntityDamageByEntityEvent, crystalData: CrystalData) {
        event.isCancelled = true
        event.damage = 0.0
        activateFlame(player)
        sendSkillActionBar(player)
        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 0.9f, 0.75f)
        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.55f, 1.25f)
    }

    override fun handleShiftClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        return false
    }

    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val until = activeUntil[player.uniqueId] ?: return
        if (System.currentTimeMillis() > until) {
            activeUntil.remove(player.uniqueId)
            return
        }
        if (event.cause == EntityDamageEvent.DamageCause.VOID ||
            event.cause == EntityDamageEvent.DamageCause.SUICIDE ||
            event.cause == EntityDamageEvent.DamageCause.CUSTOM
        ) {
            return
        }

        event.damage *= 0.8
    }

    private fun activateFlame(player: Player) {
        val uuid = player.uniqueId
        activeUntil[uuid] = System.currentTimeMillis() + 8000L
        player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 8 * 20, 0, false, true, true), true)
        player.removePotionEffect(PotionEffectType.POISON)
        player.removePotionEffect(PotionEffectType.WITHER)

        if (particleTasks.containsKey(uuid)) return

        var ticks = 0
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val onlinePlayer = plugin.server.getPlayer(uuid)
            val until = activeUntil[uuid]
            if (onlinePlayer == null || !onlinePlayer.isOnline || onlinePlayer.isDead || until == null || System.currentTimeMillis() > until) {
                activeUntil.remove(uuid)
                particleTasks.remove(uuid)?.cancel()
                return@Runnable
            }

            drawFlames(onlinePlayer, ticks)
            if (ticks % 50 == 0) {
                onlinePlayer.removePotionEffect(PotionEffectType.POISON)
                onlinePlayer.removePotionEffect(PotionEffectType.WITHER)
            }
            ticks += 5
        }, 0L, 5L)

        particleTasks[uuid] = task
    }

    private fun sendSkillActionBar(player: Player) {
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§a§l饰品技【炎盾】已发动！")
        )
    }

    private fun drawFlames(player: Player, ticks: Int) {
        val world = player.world
        val base = player.location.clone()
        val phase = ticks * 0.24
        val points = 5

        for (i in 0 until points) {
            val angle = phase + (Math.PI * 2.0 * i / points)
            val radius = 0.72 + 0.08 * sin(phase + i)
            val y = 0.75 + 0.45 * ((ticks / 5 + i) % 8) / 8.0
            val loc = base.clone().add(cos(angle) * radius, y, sin(angle) * radius)
            world.spawnParticle(Particle.FLAME, loc, 1, 0.025, 0.035, 0.025, 0.01)
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, loc, 1, 0.015, 0.015, 0.015, 0.0, emberDust)
            }
        }

        if (ticks % 20 == 0) {
            world.spawnParticle(Particle.LAVA, base.clone().add(0.0, 0.55, 0.0), 1, 0.2, 0.1, 0.2, 0.0)
        }
    }
}
