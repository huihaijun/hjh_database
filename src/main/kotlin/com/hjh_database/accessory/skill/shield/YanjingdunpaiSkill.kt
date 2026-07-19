package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class YanjingdunpaiSkill(plugin: Hjh_database) : BaseShieldSkill(plugin) {

    private val activeUntil = HashMap<UUID, Long>()
    private val retaliationCooldownUntil = HashMap<UUID, Long>()
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
        val now = System.currentTimeMillis()
        if (now > until) {
            activeUntil.remove(player.uniqueId)
            return
        }

        // 炎火对最终进入 Bukkit 伤害事件的所有伤害统一生效，包括魔法、真实伤害和 /damage。
        if (event.damage <= 0.0) return
        event.damage *= DAMAGE_TAKEN_MULTIPLIER

        if ((retaliationCooldownUntil[player.uniqueId] ?: 0L) <= now) {
            retaliationCooldownUntil[player.uniqueId] = now + RETALIATION_COOLDOWN_MS
            retaliate(player)
        }
    }

    fun onPotionEffect(event: EntityPotionEffectEvent) {
        val player = event.entity as? Player ?: return
        val until = activeUntil[player.uniqueId] ?: return
        if (System.currentTimeMillis() > until) {
            activeUntil.remove(player.uniqueId)
            return
        }

        val type = event.newEffect?.type ?: return
        if (type == PotionEffectType.POISON || type == PotionEffectType.WITHER) {
            event.isCancelled = true
        }
    }

    private fun activateFlame(player: Player) {
        val uuid = player.uniqueId
        activeUntil[uuid] = System.currentTimeMillis() + FLAME_DURATION_MS
        player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, FLAME_DURATION_TICKS, 0, false, true, true), true)
        player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, FLAME_DURATION_TICKS, 1, false, true, true), true)
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
            ticks += 5
        }, 0L, 5L)

        particleTasks[uuid] = task
    }

    private fun retaliate(player: Player) {
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.maxHealth
        val damage = maxHealth * RETALIATION_MAX_HEALTH_RATIO
        if (damage <= 0.0) return

        val center = player.location
        player.world.getNearbyEntities(center, RETALIATION_RADIUS, RETALIATION_RADIUS, RETALIATION_RADIUS)
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster") }
            .filter { it.location.distanceSquared(center) <= RETALIATION_RADIUS * RETALIATION_RADIUS }
            .forEach { target ->
                target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                target.noDamageTicks = 0
                try {
                    target.damage(damage, player)
                } finally {
                    target.removeMetadata("hjh_physical_skill", plugin)
                    target.noDamageTicks = 0
                }
            }

        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.45f, 1.55f)
        player.world.spawnParticle(Particle.FLAME, center.clone().add(0.0, 0.9, 0.0), 28, 1.2, 0.45, 1.2, 0.035)
    }

    fun cleanup(player: Player) {
        val uuid = player.uniqueId
        activeUntil.remove(uuid)
        retaliationCooldownUntil.remove(uuid)
        particleTasks.remove(uuid)?.cancel()
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

    companion object {
        private const val FLAME_DURATION_MS = 15_000L
        private const val FLAME_DURATION_TICKS = 15 * 20
        private const val DAMAGE_TAKEN_MULTIPLIER = 0.75
        private const val RETALIATION_RADIUS = 5.0
        private const val RETALIATION_MAX_HEALTH_RATIO = 0.20
        private const val RETALIATION_COOLDOWN_MS = 2_000L
    }
}
