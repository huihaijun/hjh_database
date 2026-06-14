package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class baihuajianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database
    private val weaponKey = NamespacedKey(plugin, "weapon_id")
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")

    private data class FlowerMark(
        var stacks: Int,
        var stackExpireAt: Long,
        var bloomExpireAt: Long = 0L
    ) {
        fun isBlooming(now: Long): Boolean = bloomExpireAt > now
        fun isExpired(now: Long): Boolean = bloomExpireAt <= now && stackExpireAt <= now
    }

    private val flowerMarks = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, FlowerMark>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        object : BukkitRunnable() {
            override fun run() {
                cleanupAndRenderFlowers()
            }
        }.runTaskTimer(plugin, 10L, 10L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val delayTicks = config?.getLong("delay_ticks", 16L) ?: 16L
        val baseCooldown = config?.getDouble("cooldown", 15.0) ?: 15.0
        val cooldownReductionSeconds = baseCooldown * 0.4
        val cooldownMaterial = player.inventory.itemInMainHand.type
        val initialTargets = getBloomingTargetsInView(player, radius)

        player.world.playSound(player.location, Sound.BLOCK_SPORE_BLOSSOM_BREAK, 0.9f, 1.5f)
        startGatheringEffect(player, delayTicks, initialTargets)

        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) return

                val targets = getBloomingTargetsInView(player, radius)
                var killed = 0
                for (target in targets) {
                    val wasAlive = target.isValid && !target.isDead && target.health > 0.0
                    playPetalExecutionEffect(target)
                    dealBloomExplosionDamage(player, target, data.attack * 3.0)
                    refreshBloom(target.uniqueId, player.uniqueId, System.currentTimeMillis())
                    if (wasAlive && (!target.isValid || target.isDead || target.health <= 0.0)) {
                        killed++
                    }
                }

                if (targets.isNotEmpty()) {
                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.72f)
                    player.world.playSound(player.location, Sound.ITEM_TRIDENT_HIT, 0.85f, 1.35f)
                    player.world.playSound(player.location, Sound.BLOCK_CHERRY_LEAVES_BREAK, 1.0f, 0.9f)
                }

                if (killed > 0) {
                    grantBloomShield(player)
                    plugin.weaponSkillManager?.reduceCooldown(player, cooldownReductionSeconds, cooldownMaterial)
                }
            }
        }.runTaskLater(plugin, max(1L, delayTicks))

        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val victim = event.entity as? LivingEntity ?: return

        if (victim.hasMetadata(INTERNAL_DAMAGE_META)) return
        if (!isBaihuaActive(player)) return
        if (!isPanlingMonster(victim)) return

        val now = System.currentTimeMillis()
        val mark = getValidMark(victim.uniqueId, player.uniqueId, now)

        if (mark != null && mark.isBlooming(now)) {
            refreshBloom(victim.uniqueId, player.uniqueId, now)
            event.damage *= getArmorPierceMultiplier(victim, 0.35)
            spawnBloomHitEffect(victim, player.uniqueId)
            return
        }

        addFlowerStack(player, victim, now)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMonsterDeath(event: EntityDeathEvent) {
        flowerMarks.remove(event.entity.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flowerMarks.values.forEach { it.remove(uuid) }
    }

    private fun addFlowerStack(player: Player, victim: LivingEntity, now: Long) {
        val playerMap = flowerMarks.computeIfAbsent(victim.uniqueId) { ConcurrentHashMap() }
        val mark = playerMap.computeIfAbsent(player.uniqueId) { FlowerMark(0, 0L) }

        mark.stacks = (mark.stacks + 1).coerceAtMost(FLOWER_STACKS_TO_BLOOM)
        mark.stackExpireAt = now + FLOWER_DURATION_MILLIS

        if (mark.stacks >= FLOWER_STACKS_TO_BLOOM) {
            mark.bloomExpireAt = now + BLOOM_DURATION_MILLIS
            mark.stackExpireAt = mark.bloomExpireAt
            playBloomStartEffect(victim, player.uniqueId)
        } else {
            victim.world.spawnParticle(Particle.CHERRY_LEAVES, victim.location.add(0.0, 1.0, 0.0), 5, 0.25, 0.35, 0.25, 0.02)
            victim.world.playSound(victim.location, Sound.BLOCK_AZALEA_LEAVES_STEP, 0.35f, 1.6f)
        }
    }

    private fun refreshBloom(mobId: UUID, playerId: UUID, now: Long) {
        val mark = getValidMark(mobId, playerId, now) ?: return
        mark.stacks = FLOWER_STACKS_TO_BLOOM
        mark.bloomExpireAt = now + BLOOM_DURATION_MILLIS
        mark.stackExpireAt = mark.bloomExpireAt
    }

    private fun getValidMark(mobId: UUID, playerId: UUID, now: Long): FlowerMark? {
        val playerMap = flowerMarks[mobId] ?: return null
        val mark = playerMap[playerId] ?: return null
        if (mark.isExpired(now)) {
            playerMap.remove(playerId)
            if (playerMap.isEmpty()) flowerMarks.remove(mobId)
            return null
        }
        return mark
    }

    private fun getBloomingTargetsInView(player: Player, radius: Double): List<LivingEntity> {
        val now = System.currentTimeMillis()
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val radiusSquared = radius * radius
        val result = ArrayList<LivingEntity>()

        for (entity in player.world.getNearbyEntities(player.location, radius, radius, radius)) {
            val mob = entity as? LivingEntity ?: continue
            if (!isPanlingMonster(mob)) continue
            if (mob.location.distanceSquared(player.location) > radiusSquared) continue

            val mark = getValidMark(mob.uniqueId, player.uniqueId, now) ?: continue
            if (!mark.isBlooming(now)) continue

            val targetVector = mob.location.add(0.0, mob.height * 0.55, 0.0).toVector().subtract(eye.toVector())
            if (targetVector.lengthSquared() <= 0.01) continue
            if (direction.dot(targetVector.normalize()) < VIEW_DOT_THRESHOLD) continue
            if (!player.hasLineOfSight(mob)) continue

            result.add(mob)
        }

        return result
    }

    private fun getArmorPierceMultiplier(victim: LivingEntity, ignoredArmorRatio: Double): Double {
        val armor = victim.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
        if (armor <= 0.0) return 1.0

        val normalMultiplier = 50.0 / (50.0 + armor)
        val piercedMultiplier = 50.0 / (50.0 + armor * (1.0 - ignoredArmorRatio))
        return (piercedMultiplier / normalMultiplier).coerceAtLeast(1.0)
    }

    private fun dealBloomExplosionDamage(player: Player, victim: LivingEntity, baseDamage: Double) {
        if (baseDamage <= 0.0 || !victim.isValid || victim.isDead) return

        val maxHealth = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: victim.health
        val executeDamage = maxHealth * 0.08
        val damage = baseDamage + executeDamage

        victim.setMetadata(INTERNAL_DAMAGE_META, FixedMetadataValue(plugin, true))
        victim.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        victim.noDamageTicks = 0
        try {
            victim.damage(damage, player)
        } finally {
            if (victim.hasMetadata(INTERNAL_DAMAGE_META)) {
                victim.removeMetadata(INTERNAL_DAMAGE_META, plugin)
            }
            if (victim.hasMetadata("HJH_MAGIC_DAMAGE")) {
                victim.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            victim.noDamageTicks = 0
        }
    }

    private fun grantBloomShield(player: Player) {
        player.addPotionEffect(org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.ABSORPTION,
            300,
            4,
            false,
            false,
            true
        ))
        player.absorptionAmount = max(player.absorptionAmount, 20.0)
        player.world.spawnParticle(Particle.HEART, player.location.add(0.0, 1.6, 0.0), 8, 0.5, 0.35, 0.5, 0.0)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.3f)
    }

    private fun startGatheringEffect(player: Player, delayTicks: Long, targets: List<LivingEntity>) {
        val runs = max(1, (delayTicks / 4L).toInt())
        object : BukkitRunnable() {
            private var tick = 0

            override fun run() {
                if (!player.isOnline || tick >= runs) {
                    cancel()
                    return
                }

                if (targets.isEmpty()) {
                    val center = player.eyeLocation.add(player.eyeLocation.direction.normalize().multiply(1.8))
                    val radius = 0.75 - tick * 0.12
                    for (i in 0 until 10) {
                        val angle = (PI * 2.0 / 10.0) * i + tick * 0.55
                        val loc = center.clone().add(cos(angle) * radius, sin(angle * 1.7) * 0.25, sin(angle) * radius)
                        player.world.spawnParticle(Particle.CHERRY_LEAVES, loc, 1, 0.02, 0.02, 0.02, 0.0)
                    }
                    player.world.spawnParticle(
                        Particle.DUST,
                        center,
                        8,
                        0.25,
                        0.18,
                        0.25,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(255, 122, 170), 1.0f)
                    )
                } else {
                    val progress = (tick + 1).toDouble() / runs.toDouble()
                    for (target in targets.take(8)) {
                        if (!target.isValid || target.isDead) continue
                        val center = target.location.add(0.0, target.height * 0.58, 0.0)
                        val radius = 1.65 * (1.0 - progress) + 0.16
                        for (i in 0 until 8) {
                            val angle = (PI * 2.0 / 8.0) * i + tick * 0.7
                            val loc = center.clone().add(cos(angle) * radius, 0.25 + sin(angle * 1.4 + tick) * 0.22, sin(angle) * radius)
                            target.world.spawnParticle(Particle.CHERRY_LEAVES, loc, 1, 0.015, 0.015, 0.015, 0.0)
                        }
                        target.world.spawnParticle(
                            Particle.DUST,
                            center,
                            5,
                            0.12 + radius * 0.15,
                            0.18,
                            0.12 + radius * 0.15,
                            0.0,
                            Particle.DustOptions(Color.fromRGB(255, 118, 166), 0.95f)
                        )
                    }
                }
                player.world.playSound(player.location, Sound.BLOCK_SPORE_BLOSSOM_STEP, 0.35f, 1.6f + tick * 0.08f)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 4L)
    }

    private fun playBloomStartEffect(victim: LivingEntity, playerId: UUID) {
        val color = colorForPlayer(playerId)
        val center = victim.location.add(0.0, victim.height * 0.55, 0.0)
        victim.world.spawnParticle(Particle.FLASH, center, 1)
        victim.world.spawnParticle(Particle.CHERRY_LEAVES, center, 35, 0.45, 0.55, 0.45, 0.04)
        victim.world.spawnParticle(Particle.DUST, center, 18, 0.35, 0.45, 0.35, 0.0, Particle.DustOptions(color, 1.15f))
        victim.world.playSound(victim.location, Sound.BLOCK_SPORE_BLOSSOM_BREAK, 0.8f, 1.25f)
    }

    private fun spawnBloomHitEffect(victim: LivingEntity, playerId: UUID) {
        val center = victim.location.add(0.0, victim.height * 0.55, 0.0)
        victim.world.spawnParticle(Particle.CHERRY_LEAVES, center, 8, 0.3, 0.35, 0.3, 0.03)
        victim.world.spawnParticle(Particle.DUST, center, 5, 0.25, 0.25, 0.25, 0.0, Particle.DustOptions(colorForPlayer(playerId), 0.8f))
    }

    private fun playPetalExecutionEffect(victim: LivingEntity) {
        val world = victim.world
        val center = victim.location.add(0.0, victim.height * 0.55, 0.0)
        world.spawnParticle(Particle.FLASH, center, 1)
        world.spawnParticle(Particle.CHERRY_LEAVES, center, 95, 0.95, 0.85, 0.95, 0.16)
        world.spawnParticle(Particle.END_ROD, center, 20, 0.55, 0.45, 0.55, 0.08)
        world.spawnParticle(Particle.DUST, center, 32, 0.55, 0.45, 0.55, 0.0, Particle.DustOptions(Color.fromRGB(255, 88, 142), 1.25f))
        world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.55f)
        world.playSound(victim.location, Sound.ITEM_TRIDENT_HIT, 0.8f, 1.55f)
        world.playSound(victim.location, Sound.BLOCK_CHERRY_LEAVES_BREAK, 1.0f, 0.85f)
    }

    private fun cleanupAndRenderFlowers() {
        if (flowerMarks.isEmpty()) return

        val now = System.currentTimeMillis()
        val mobIter = flowerMarks.iterator()
        while (mobIter.hasNext()) {
            val entry = mobIter.next()
            val mob = Bukkit.getEntity(entry.key) as? LivingEntity
            if (mob == null || !mob.isValid || mob.isDead) {
                mobIter.remove()
                continue
            }

            val playerMap = entry.value
            val markIter = playerMap.iterator()
            while (markIter.hasNext()) {
                val playerEntry = markIter.next()
                val mark = playerEntry.value
                if (mark.isExpired(now)) {
                    markIter.remove()
                    continue
                }

                if (mark.isBlooming(now)) {
                    renderBloomParticle(mob, playerEntry.key, now)
                } else {
                    renderStackParticle(mob, playerEntry.key, mark.stacks, now)
                }
            }

            if (playerMap.isEmpty()) {
                mobIter.remove()
            }
        }
    }

    private fun renderStackParticle(mob: LivingEntity, playerId: UUID, stacks: Int, now: Long) {
        val phase = playerPhase(playerId) + now / 450.0
        val radius = 0.35 + stacks * 0.08
        val loc = mob.location.add(cos(phase) * radius, mob.height * 0.65, sin(phase) * radius)
        mob.world.spawnParticle(Particle.CHERRY_LEAVES, loc, 1, 0.02, 0.02, 0.02, 0.0)
    }

    private fun renderBloomParticle(mob: LivingEntity, playerId: UUID, now: Long) {
        val color = colorForPlayer(playerId)
        val basePhase = playerPhase(playerId) + now / 300.0
        val center = mob.location.add(0.0, mob.height * 0.55, 0.0)

        for (i in 0 until 3) {
            val angle = basePhase + i * (PI * 2.0 / 3.0)
            val loc = center.clone().add(cos(angle) * 0.55, sin(basePhase + i) * 0.12, sin(angle) * 0.55)
            mob.world.spawnParticle(Particle.CHERRY_LEAVES, loc, 1, 0.015, 0.015, 0.015, 0.0)
        }
        mob.world.spawnParticle(Particle.DUST, center, 1, 0.22, 0.25, 0.22, 0.0, Particle.DustOptions(color, 0.75f))
    }

    private fun colorForPlayer(playerId: UUID): Color {
        val hue = playerId.leastSignificantBits.toInt()
        val r = 210 + (hue and 0x2F)
        val g = 80 + ((hue shr 8) and 0x3F)
        val b = 135 + ((hue shr 16) and 0x5F)
        return Color.fromRGB(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun playerPhase(playerId: UUID): Double {
        return ((playerId.leastSignificantBits ushr 8) and 0xFFFF).toDouble() / 0xFFFF.toDouble() * PI * 2.0
    }

    private fun isBaihuaActive(player: Player): Boolean {
        val item: ItemStack = player.inventory.itemInMainHand
        if (!item.hasItemMeta()) return false
        val weaponId = item.itemMeta?.persistentDataContainer?.get(weaponKey, PersistentDataType.STRING)
        if (!weaponId.equals("baihuajian", ignoreCase = true)) return false

        val activeWeapon = plugin.playerManager.weaponManager.checkActiveWeapon(player, item, player.inventory.heldItemSlot)
        return activeWeapon?.id.equals("baihuajian", ignoreCase = true)
    }

    private fun isPanlingMonster(entity: LivingEntity): Boolean {
        return entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")
    }

    override fun deactivate(player: Player) {
        flowerMarks.values.forEach { it.remove(player.uniqueId) }
    }

    companion object {
        private const val FLOWER_DURATION_MILLIS = 5000L
        private const val BLOOM_DURATION_MILLIS = 5000L
        private const val FLOWER_STACKS_TO_BLOOM = 3
        private const val VIEW_DOT_THRESHOLD = 0.45
        private const val INTERNAL_DAMAGE_META = "HJH_BAIHUA_INTERNAL"
    }
}
