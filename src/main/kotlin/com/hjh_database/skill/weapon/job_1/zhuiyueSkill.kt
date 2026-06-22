package com.hjh_database.skill.weapon.job_1

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class zhuiyueSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database
    private val weaponKey = NamespacedKey(plugin, "weapon_id")
    private val storedDamageKey = NamespacedKey(plugin, "stored_arrow_damage")
    private val zhuiyueArrowKey = NamespacedKey(plugin, "zhuiyue_arrow")
    private val zhuiyuePrimaryKey = NamespacedKey(plugin, "zhuiyue_primary")

    private data class ChargeState(
        var distance: Double,
        var lastLocation: org.bukkit.Location,
        var expireAt: Long,
        var fullPlayed: Boolean = false
    )

    private data class ActiveState(
        var expireAt: Long,
        var shotCount: Int
    )

    private val chargeStates = ConcurrentHashMap<UUID, ChargeState>()
    private val speedBuffs = ConcurrentHashMap<UUID, Long>()
    private val activeStates = ConcurrentHashMap<UUID, ActiveState>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        object : BukkitRunnable() {
            override fun run() {
                tickStates()
            }
        }.runTaskTimer(plugin, 4L, 4L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        val durationMillis = ((config?.getDouble("duration", 10.0) ?: 10.0) * 1000.0).toLong()
        activeStates[player.uniqueId] = ActiveState(System.currentTimeMillis() + durationMillis, 0)
        plugin.weaponSkillManager?.registerToggle(player, "zhuiyue")
        setZhuiyueQuickCharge(player, ACTIVE_QUICK_CHARGE_LEVEL)

        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.45f)
        player.world.playSound(player.location, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 0.8f, 1.25f)
        player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.2, 0.0), 24, 0.6, 0.45, 0.6, 0.03)
        player.world.spawnParticle(
            Particle.DUST,
            player.location.add(0.0, 1.1, 0.0),
            24,
            0.75,
            0.45,
            0.75,
            0.0,
            Particle.DustOptions(Color.fromRGB(172, 205, 255), 1.2f)
        )

        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        if (!isZhuiyueItem(bow)) return

        val arrow = event.projectile as? AbstractArrow ?: return
        val primary = isPrimaryProjectile(player, arrow)
        arrow.persistentDataContainer.set(zhuiyueArrowKey, PersistentDataType.BYTE, 1)
        if (primary) {
            arrow.persistentDataContainer.set(zhuiyuePrimaryKey, PersistentDataType.BYTE, 1)
        }

        if (!primary) return

        val uuid = player.uniqueId
        val active = activeStates[uuid]
        val now = System.currentTimeMillis()
        val isActive = active != null && active.expireAt > now

        if (isActive) {
            active!!.shotCount++
            playCrescentShotParticles(player)
            if (active.shotCount % 5 == 0) {
                event.isCancelled = true
                arrow.remove()
                fireMoonBeam(player)
                return
            }
        }

        val charge = chargeStates.remove(uuid)
        if (charge != null && charge.expireAt > now) {
            val unit = if (isActive) ACTIVE_DISTANCE_UNIT else PASSIVE_DISTANCE_UNIT
            val multiplier = (1.0 + floor(charge.distance / unit) * 0.5).coerceAtMost(MAX_PASSIVE_MULTIPLIER)
            if (multiplier > 1.0) {
                val pdc = arrow.persistentDataContainer
                val currentDamage = pdc.get(storedDamageKey, PersistentDataType.DOUBLE)
                if (currentDamage != null) {
                    pdc.set(storedDamageKey, PersistentDataType.DOUBLE, currentDamage * multiplier)
                }
                player.world.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 0.75f, 1.65f)
                playChargedShotEffect(player, multiplier)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        if (!arrow.persistentDataContainer.has(zhuiyueArrowKey, PersistentDataType.BYTE)) return

        val shooter = arrow.shooter as? Player ?: return
        val target = event.entity as? LivingEntity ?: return
        if (!isPanlingMonster(target)) return

        val now = System.currentTimeMillis()
        chargeStates[shooter.uniqueId] = ChargeState(0.0, shooter.location.clone(), now + CHARGE_EXPIRE_MILLIS)
        setSpeedBuff(shooter, now + SPEED_BUFF_MILLIS)
        playChargeStartEffect(shooter)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
    }

    override fun deactivate(player: Player) {
        clearPlayer(player)
        plugin.weaponSkillManager?.unregisterToggle(player)
    }

    private fun tickStates() {
        val now = System.currentTimeMillis()

        val chargeIter = chargeStates.iterator()
        while (chargeIter.hasNext()) {
            val entry = chargeIter.next()
            val player = Bukkit.getPlayer(entry.key)
            if (player == null || !player.isOnline || entry.value.expireAt <= now) {
                chargeIter.remove()
                continue
            }

            val current = player.location
            if (current.world == entry.value.lastLocation.world) {
                val dx = current.x - entry.value.lastLocation.x
                val dz = current.z - entry.value.lastLocation.z
                val moved = sqrt(dx * dx + dz * dz)
                if (moved in 0.02..2.5) {
                    entry.value.distance = min(40.0, entry.value.distance + moved)
                }
            }
            entry.value.lastLocation = current.clone()
            renderChargeProgress(player, entry.value)
        }

        val speedIter = speedBuffs.iterator()
        while (speedIter.hasNext()) {
            val entry = speedIter.next()
            val player = Bukkit.getPlayer(entry.key)
            if (player == null || !player.isOnline || entry.value <= now) {
                if (player != null) removeSpeedBuff(player)
                speedIter.remove()
            }
        }

        val activeIter = activeStates.iterator()
        while (activeIter.hasNext()) {
            val entry = activeIter.next()
            val player = Bukkit.getPlayer(entry.key)
            if (player == null || !player.isOnline || entry.value.expireAt <= now) {
                if (player != null) {
                    setZhuiyueQuickCharge(player, 3)
                    plugin.weaponSkillManager?.unregisterToggle(player)
                }
                activeIter.remove()
                continue
            }

            renderActiveTrail(player)
            setZhuiyueQuickCharge(player, ACTIVE_QUICK_CHARGE_LEVEL)
        }
    }

    private fun setSpeedBuff(player: Player, expireAt: Long) {
        speedBuffs[player.uniqueId] = expireAt
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses["zhuiyue_speed"] != 0.20) {
            data.tempBonuses["zhuiyue_speed"] = 0.20
            plugin.playerManager.updateStats(player)
        }
    }

    private fun removeSpeedBuff(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses.remove("zhuiyue_speed") != null) {
            plugin.playerManager.updateStats(player)
        }
    }

    private fun fireMoonBeam(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val start = player.eyeLocation.clone().subtract(0.0, 0.12, 0.0)
        val direction = start.direction.normalize()
        val blockHit = player.world.rayTraceBlocks(start, direction, MOON_BEAM_RANGE, FluidCollisionMode.NEVER, true)
        val maxDistance = blockHit?.hitPosition?.distance(start.toVector()) ?: MOON_BEAM_RANGE
        val end = start.clone().add(direction.clone().multiply(maxDistance))

        playMoonBeam(start, end)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.8f)
        player.world.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.75f, 1.35f)

        val targets = findBeamTargets(player, start, direction, maxDistance)
        val damage = data.archerDamage * 3.0
        for (target in targets) {
            dealPhysicalDamage(player, target, damage)
        }
    }

    private fun findBeamTargets(player: Player, start: org.bukkit.Location, direction: Vector, maxDistance: Double): List<LivingEntity> {
        val midpoint = start.clone().add(direction.clone().multiply(maxDistance * 0.5))
        val nearby = player.world.getNearbyEntities(midpoint, maxDistance * 0.5 + 1.5, 2.2, maxDistance * 0.5 + 1.5)
        return nearby.asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it != player && isPanlingMonster(it) && it.isValid && !it.isDead }
            .mapNotNull { mob ->
                val center = mob.location.add(0.0, mob.height * 0.55, 0.0).toVector()
                val relative = center.clone().subtract(start.toVector())
                val along = relative.dot(direction)
                if (along < 0.0 || along > maxDistance) return@mapNotNull null
                val closest = start.toVector().add(direction.clone().multiply(along))
                val perpendicular = center.distance(closest)
                if (perpendicular <= 1.05) mob to along else null
            }
            .sortedBy { it.second }
            .take(6)
            .map { it.first }
            .toList()
    }

    private fun playMoonBeam(start: org.bukkit.Location, end: org.bukkit.Location) {
        object : BukkitRunnable() {
            private var frame = 0

            override fun run() {
                if (frame >= 6) {
                    cancel()
                    return
                }
                drawMoonBeamFrame(start, end, frame)
                frame++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun drawMoonBeamFrame(start: org.bukkit.Location, end: org.bukkit.Location, frame: Int) {
        val distance = start.distance(end)
        if (distance <= 0.0) return

        val direction = end.toVector().subtract(start.toVector()).normalize()
        val steps = max(1, (distance / 0.25).toInt())
        for (i in 0..steps) {
            val loc = start.clone().add(direction.clone().multiply(i * 0.25))
            loc.world.spawnParticle(Particle.END_ROD, loc, 1, 0.01, 0.01, 0.01, 0.0)
            if (i % 2 == 0) {
                loc.world.spawnParticle(
                    Particle.DUST,
                    loc,
                    1,
                    0.04,
                    0.04,
                    0.04,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(132, 170, 255), 0.9f)
                )
            }
            if ((i + frame) % 7 == 0) {
                val angle = frame * 0.65 + i * 0.3
                val side = perpendicular(direction).multiply(cos(angle) * 0.18)
                val up = Vector(0, 1, 0).multiply(sin(angle) * 0.18)
                val sparkle = loc.clone().add(side).add(up)
                sparkle.world.spawnParticle(Particle.CRIT, sparkle, 1, 0.01, 0.01, 0.01, 0.0)
            }
        }
    }

    private fun dealPhysicalDamage(player: Player, target: LivingEntity, damage: Double) {
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(damage, player)
        } finally {
            if (target.hasMetadata("hjh_physical_skill")) {
                target.removeMetadata("hjh_physical_skill", plugin)
            }
            target.noDamageTicks = 0
        }
    }

    private fun setZhuiyueQuickCharge(player: Player, level: Int) {
        val item = player.inventory.getItem(0) ?: return
        if (!isZhuiyueItem(item) || item.type != Material.CROSSBOW) return
        val meta = item.itemMeta ?: return
        if (meta.getEnchantLevel(Enchantment.QUICK_CHARGE) == level && meta.hasEnchant(Enchantment.MULTISHOT)) return
        meta.removeEnchant(Enchantment.QUICK_CHARGE)
        meta.addEnchant(Enchantment.QUICK_CHARGE, level, true)
        meta.addEnchant(Enchantment.MULTISHOT, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
    }

    private fun playChargeStartEffect(player: Player) {
        val center = player.location.add(0.0, 1.05, 0.0)
        player.world.spawnParticle(Particle.END_ROD, center, 12, 0.35, 0.25, 0.35, 0.02)
        player.world.spawnParticle(
            Particle.DUST,
            center,
            12,
            0.45,
            0.18,
            0.45,
            0.0,
            Particle.DustOptions(Color.fromRGB(150, 190, 255), 1.0f)
        )
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.45f, 1.65f)
    }

    private fun playChargedShotEffect(player: Player, multiplier: Double) {
        val full = multiplier >= MAX_PASSIVE_MULTIPLIER
        val center = player.eyeLocation.clone().subtract(0.0, 0.35, 0.0)
        val count = if (full) 18 else 8
        player.world.spawnParticle(Particle.END_ROD, center, count, 0.25, 0.2, 0.25, 0.04)
        if (full) {
            player.world.spawnParticle(Particle.FLASH, center, 1)
            player.world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.8f)
        }
    }

    private fun renderChargeProgress(player: Player, state: ChargeState) {
        val active = activeStates[player.uniqueId]
        val isActive = active != null && active.expireAt > System.currentTimeMillis()
        val unit = if (isActive) ACTIVE_DISTANCE_UNIT else PASSIVE_DISTANCE_UNIT
        val required = unit * 4.0
        val progress = (state.distance / required).coerceIn(0.0, 1.0)
        val full = progress >= 1.0
        val center = player.location.add(0.0, 0.95, 0.0)
        val lit = max(1, (progress * 10.0).toInt())
        val color = if (full) Color.fromRGB(255, 238, 168) else Color.fromRGB(132, 170, 255)

        for (i in 0 until lit) {
            val angle = (PI * 2.0 / 10.0) * i + System.currentTimeMillis() / 260.0
            val loc = center.clone().add(cos(angle) * 0.62, 0.05 * sin(angle * 2.0), sin(angle) * 0.62)
            player.world.spawnParticle(
                Particle.DUST,
                loc,
                1,
                0.01,
                0.01,
                0.01,
                0.0,
                Particle.DustOptions(color, if (full) 1.2f else 0.85f)
            )
        }

        if (full) {
            player.world.spawnParticle(Particle.END_ROD, center, 2, 0.25, 0.18, 0.25, 0.01)
            if (!state.fullPlayed) {
                state.fullPlayed = true
                player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.9f)
                player.world.spawnParticle(Particle.FLASH, center, 1)
            }
        }
    }

    private fun playCrescentShotParticles(player: Player) {
        val base = player.eyeLocation.clone().add(player.eyeLocation.direction.normalize().multiply(0.9))
        val right = player.eyeLocation.direction.clone().crossProduct(Vector(0, 1, 0)).normalize()
        for (i in -3..3) {
            val t = i / 3.0
            val loc = base.clone().add(right.clone().multiply(t * 0.35)).add(0.0, (1.0 - t * t) * 0.18, 0.0)
            player.world.spawnParticle(Particle.END_ROD, loc, 1, 0.01, 0.01, 0.01, 0.0)
        }
    }

    private fun renderActiveTrail(player: Player) {
        val now = System.currentTimeMillis() / 250.0
        val center = player.location.add(0.0, 1.05, 0.0)
        for (i in 0 until 4) {
            val angle = now + i * (PI / 2.0)
            val loc = center.clone().add(cos(angle) * 0.72, sin(angle * 1.4) * 0.2, sin(angle) * 0.72)
            player.world.spawnParticle(Particle.END_ROD, loc, 1, 0.01, 0.01, 0.01, 0.0)
            if (i % 2 == 0) {
                player.world.spawnParticle(
                    Particle.DUST,
                    loc,
                    1,
                    0.01,
                    0.01,
                    0.01,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(172, 205, 255), 0.85f)
                )
            }
        }
    }

    private fun perpendicular(direction: Vector): Vector {
        val up = Vector(0, 1, 0)
        val side = direction.clone().crossProduct(up)
        if (side.lengthSquared() < 0.0001) return Vector(1, 0, 0)
        return side.normalize()
    }

    private fun isPrimaryProjectile(player: Player, arrow: AbstractArrow): Boolean {
        val velocity = arrow.velocity
        if (velocity.lengthSquared() <= 0.0) return true
        return player.eyeLocation.direction.normalize().dot(velocity.normalize()) >= 0.985
    }

    private fun isZhuiyueItem(item: ItemStack): Boolean {
        if (!item.hasItemMeta()) return false
        val weaponId = item.itemMeta?.persistentDataContainer?.get(weaponKey, PersistentDataType.STRING)
        return weaponId.equals("zhuiyue", ignoreCase = true)
    }

    private fun isPanlingMonster(entity: LivingEntity): Boolean {
        return entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")
    }

    private fun clearPlayer(player: Player) {
        val uuid = player.uniqueId
        chargeStates.remove(uuid)
        speedBuffs.remove(uuid)
        activeStates.remove(uuid)
        removeSpeedBuff(player)
        setZhuiyueQuickCharge(player, 3)
    }

    companion object {
        private const val SPEED_BUFF_MILLIS = 5000L
        private const val CHARGE_EXPIRE_MILLIS = 15000L
        private const val PASSIVE_DISTANCE_UNIT = 5.0
        private const val ACTIVE_DISTANCE_UNIT = 2.0
        private const val MAX_PASSIVE_MULTIPLIER = 3.0
        private const val MOON_BEAM_RANGE = 25.0
        private const val ACTIVE_QUICK_CHARGE_LEVEL = 4
    }
}
