package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.data.PlayerData
import com.hjh_database.weapon.CrystalData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.sin

class YanlingSkill(plugin: Hjh_database) : BaseRefluxSkill(plugin) {
    companion object {
        private const val COOLDOWN_MS = 15000L
        private const val DURATION_TICKS = 200
        private const val TICK_STEP = 5
        private const val SLOW_RANGE = 6.0
        private const val ZF_BONUS_KEY = "yanling::zf_str_percent"
        private const val ZF_BONUS = 0.25
    }

    private val activeUntil = HashMap<UUID, Long>()
    private val activeTasks = HashMap<UUID, BukkitTask>()
    private val buffedPlayers = HashSet<UUID>()
    private val flameDust = Particle.DustOptions(Color.fromRGB(255, 95, 24), 0.85f)
    private val goldDust = Particle.DustOptions(Color.fromRGB(255, 190, 55), 0.7f)

    override val accessoryId: String = "yanling"

    override fun getThresholdPercent(crystalData: CrystalData): Double = 0.5

    override fun getCostPerLevel(crystalData: CrystalData): Double = 3.0

    override fun getTriggerProbability(crystalData: CrystalData): Double = 0.5

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState =
        super.getHudState(player, item, crystalData).copy(
            effectEndMillis = activeUntil[player.uniqueId] ?: 0L,
            effectDurationMillis = DURATION_TICKS * 50L
        )

    fun onElementFormationCast(player: Player, data: PlayerData) {
        val now = System.currentTimeMillis()
        val uuid = player.uniqueId
        if (getTrackedCooldownEnd(player) > now) return

        startTrackedCooldown(player, COOLDOWN_MS, now)
        startBlessing(player, data)
    }

    fun cleanup(player: Player) {
        activeTasks.remove(player.uniqueId)?.cancel()
        activeUntil.remove(player.uniqueId)
        removeZfBonus(player, plugin.playerManager.getPlayerData(player))
    }

    private fun startBlessing(player: Player, data: PlayerData) {
        val uuid = player.uniqueId
        activeTasks.remove(uuid)?.cancel()
        activeUntil[uuid] = System.currentTimeMillis() + DURATION_TICKS * 50L
        addZfBonus(player, data)

        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.65f, 1.35f)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.65f)
        if (!plugin.passiveSubtitleManager.showAccessoryTrigger(player, accessoryId, "blessing")) {
            player.sendActionBar("§a§l饰品技【炎灵祝礼】已发动！")
        }

        var lived = 0
        val task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || player.isDead || lived >= DURATION_TICKS) {
                    removeZfBonus(player, plugin.playerManager.getPlayerData(player))
                    activeTasks.remove(uuid)
                    activeUntil.remove(uuid)
                    cancel()
                    return
                }

                val spiritLoc = rightShoulderLocation(player, lived)
                renderSpirit(spiritLoc, lived)
                if (lived % 10 == 0) {
                    slowNearbyMonsters(player)
                }

                lived += TICK_STEP
            }
        }.runTaskTimer(plugin, 0L, TICK_STEP.toLong())

        activeTasks[uuid] = task
    }

    private fun addZfBonus(player: Player, data: PlayerData) {
        if (!buffedPlayers.add(player.uniqueId)) return
        data.tempBonuses[ZF_BONUS_KEY] = ZF_BONUS
        plugin.playerManager.updateStats(player)
    }

    private fun removeZfBonus(player: Player, data: PlayerData?) {
        if (data == null) return
        if (!buffedPlayers.remove(player.uniqueId)) return
        data.tempBonuses.remove(ZF_BONUS_KEY)
        plugin.playerManager.updateStats(player)
    }

    private fun slowNearbyMonsters(player: Player) {
        val center = player.location
        val nearby = center.world?.getNearbyEntities(center, SLOW_RANGE, SLOW_RANGE, SLOW_RANGE) ?: return
        for (entity in nearby) {
            val mob = entity as? LivingEntity ?: continue
            if (!isValidTarget(mob)) continue
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 200, 1, false, false, true))

            val loc = mob.location.clone().add(0.0, mob.height * 0.55, 0.0)
            mob.world.spawnParticle(Particle.SMALL_FLAME, loc, 1, 0.25, 0.25, 0.25, 0.01)
            if (mob.ticksLived % 20 == 0) {
                mob.world.spawnParticle(Particle.DUST, loc, 3, 0.22, 0.3, 0.22, 0.0, flameDust)
            }
        }
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
            entity !is ArmorStand &&
            entity.isValid &&
            !entity.isDead &&
            entity.scoreboardTags.contains("panling") &&
            entity.scoreboardTags.contains("monster")
    }

    private fun rightShoulderLocation(player: Player, ticks: Int): Location {
        val base = player.location.clone()
        val forward = base.direction.setY(0.0)
        if (forward.lengthSquared() < 0.0001) {
            forward.x = 0.0
            forward.y = 0.0
            forward.z = 1.0
        } else {
            forward.normalize()
        }

        val right = Vector(-forward.z, 0.0, forward.x).normalize().multiply(0.58)
        val bob = sin(ticks * 0.28) * 0.08
        return base.add(right).add(forward.multiply(0.12)).add(0.0, 1.42 + bob, 0.0)
    }

    private fun renderSpirit(loc: Location, ticks: Int) {
        val world = loc.world ?: return
        world.spawnParticle(Particle.FLAME, loc, 2, 0.035, 0.035, 0.035, 0.015)
        world.spawnParticle(Particle.SMALL_FLAME, loc, 1, 0.06, 0.06, 0.06, 0.01)
        world.spawnParticle(Particle.DUST, loc, 1, 0.025, 0.025, 0.025, 0.0, flameDust)

        val angle = ticks * 0.32
        val orbit = loc.clone().add(sin(angle) * 0.16, 0.04, kotlin.math.cos(angle) * 0.16)
        world.spawnParticle(Particle.DUST, orbit, 1, 0.0, 0.0, 0.0, 0.0, goldDust)
    }
}
