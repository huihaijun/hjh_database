package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.skill.medical.spell.MedicalBannerRegenEvent
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class QueqiaoyinSkill(private val plugin: Hjh_database) : Listener {
    private val instanceKey = NamespacedKey(plugin, "queqiaoyin_instance")
    private val cooldownEndKey = NamespacedKey(plugin, "queqiaoyin_cooldown_end")
    private val bridges = linkedMapOf<UUID, Bridge>()
    private val buffedPlayers = hashSetOf<UUID>()
    private var elapsedTicks = 0L
    private val ticker: BukkitTask = plugin.server.scheduler.runTaskTimer(
        plugin,
        Runnable { tick() },
        TASK_INTERVAL_TICKS,
        TASK_INTERVAL_TICKS
    )

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        if (player.inventory.heldItemSlot != ACTIVATE_SLOT) return
        val item = player.inventory.itemInMainHand
        val artifact = plugin.artifactManager.getDataFromItem(item) ?: return
        if (artifact.id != ARTIFACT_ID || artifact.skillId != ARTIFACT_ID) return

        event.isCancelled = true
        if (!plugin.artifactManager.isActive(player, item, ACTIVATE_SLOT)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', artifact.activeLoreLine))
            return
        }

        ensurePersistentDefaults(item)
        val now = System.currentTimeMillis()
        val cooldownEndsAt = readCooldownEnd(item)
        if (now < cooldownEndsAt) {
            sendCooldownActionBar(player, cooldownEndsAt - now)
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val manaCost = artifact.manaCost
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，鹊桥引需要 ${format(manaCost)} 点灵力。")
            sendManaActionBar(player)
            return
        }

        val direction = horizontalDirection(player)
        val bridge = createBridge(player, direction, data.zfStr * BRIDGE_DAMAGE_MULTIPLIER)
        bridges[bridge.id] = bridge

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        val cooldownDuration = ArtifactCooldownGroups.reducedDurationMillis(plugin, player, BASE_COOLDOWN_MILLIS)
        val newCooldownEnd = now + cooldownDuration
        writeCooldownEnd(item, newCooldownEnd)
        player.setCooldown(item, (cooldownDuration / 50L).toInt().coerceAtLeast(1))

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e${player.name}&f凭借&e鹊桥引&f，释放了阵法——&b渡星河"))
        sendManaActionBar(player, manaCost)
        player.world.playSound(player.location, Sound.ENTITY_PARROT_FLY, 0.85f, 1.35f)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.82f)
        spawnCastEffect(bridge.origin)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onMedicalBannerRegen(event: MedicalBannerRegenEvent) {
        if (isOnBridge(event.player)) event.amount *= BANNER_REGEN_MULTIPLIER
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        bridges.values.filter { it.ownerId == event.player.uniqueId }.map { it.id }.forEach(::removeBridge)
        refreshBridgeBuffs()
        removeBuff(event.player.uniqueId, event.player)
    }

    fun healingMultiplier(target: LivingEntity): Double =
        if (target is Player && isOnBridge(target)) MEDICAL_HEAL_MULTIPLIER else 1.0

    fun shutdown() {
        ticker.cancel()
        bridges.keys.toList().forEach(::removeBridge)
        buffedPlayers.toList().forEach { removeBuff(it, Bukkit.getPlayer(it)) }
    }

    private fun tick() {
        elapsedTicks += TASK_INTERVAL_TICKS
        for (bridge in bridges.values.toList()) {
            bridge.ageTicks += TASK_INTERVAL_TICKS
            if (bridge.ageTicks >= BRIDGE_DURATION_TICKS) {
                removeBridge(bridge.id)
                continue
            }

            if (bridge.currentLength < BRIDGE_LENGTH) {
                val oldLength = bridge.currentLength
                val speed = if (bridge.accelerated) ACCELERATED_SPEED_PER_TICK else INITIAL_SPEED_PER_TICK
                bridge.currentLength = min(BRIDGE_LENGTH, oldLength + speed * TASK_INTERVAL_TICKS)
                moveParrots(bridge)
                val hitMonster = damageNewSegment(bridge, oldLength, bridge.currentLength)
                if (hitMonster && !bridge.accelerated) {
                    bridge.accelerated = true
                    spawnAccelerationEffect(bridge)
                }
                spawnConstructionParticles(bridge, oldLength, bridge.currentLength)
                if (bridge.currentLength >= BRIDGE_LENGTH && !bridge.parrotsDismissed) {
                    dismissParrots(bridge)
                }
            }

            if (elapsedTicks % AMBIENT_INTERVAL_TICKS == 0L) spawnBridgeParticles(bridge)
        }

        if (elapsedTicks % BUFF_CHECK_INTERVAL_TICKS == 0L) refreshBridgeBuffs()
    }

    private fun createBridge(player: Player, direction: Vector, damage: Double): Bridge {
        val origin = player.location.clone().apply {
            yaw = 0.0f
            pitch = 0.0f
        }
        val side = Vector(-direction.z, 0.0, direction.x).normalize()
        val id = UUID.randomUUID()
        val leftParrot = spawnParrot(origin.clone().add(side.clone().multiply(BRIDGE_HALF_WIDTH)), Parrot.Variant.BLUE)
        val rightParrot = spawnParrot(origin.clone().subtract(side.clone().multiply(BRIDGE_HALF_WIDTH)), Parrot.Variant.CYAN)
        return Bridge(id, player.uniqueId, player, origin, direction, side, damage, leftParrot, rightParrot)
    }

    private fun spawnParrot(location: Location, variant: Parrot.Variant): Parrot? = try {
        location.world.spawn(location.clone().add(0.0, PARROT_HEIGHT, 0.0), Parrot::class.java) { parrot ->
            parrot.variant = variant
            parrot.setAI(false)
            parrot.setGravity(false)
            parrot.isInvulnerable = true
            parrot.isSilent = true
            parrot.isCollidable = false
            parrot.isPersistent = false
        }
    } catch (error: Throwable) {
        plugin.logger.warning("鹊桥引引桥鹦鹉生成失败：${error.message}")
        null
    }

    private fun moveParrots(bridge: Bridge) {
        val front = pointOnBridge(bridge, bridge.currentLength, 0.0, PARROT_HEIGHT)
        bridge.leftParrot?.takeIf { it.isValid }?.teleport(front.clone().add(bridge.side.clone().multiply(BRIDGE_HALF_WIDTH)))
        bridge.rightParrot?.takeIf { it.isValid }?.teleport(front.clone().subtract(bridge.side.clone().multiply(BRIDGE_HALF_WIDTH)))
    }

    private fun dismissParrots(bridge: Bridge) {
        bridge.parrotsDismissed = true
        val locations = listOfNotNull(
            bridge.leftParrot?.takeIf { it.isValid }?.location,
            bridge.rightParrot?.takeIf { it.isValid }?.location
        )
        locations.forEachIndexed { index, location ->
            val dust = if (index == 0) BRIDGE_CYAN_DUST else BRIDGE_MAGENTA_DUST
            location.world.spawnParticle(Particle.FIREWORK, location, 22, 0.45, 0.45, 0.45, 0.09)
            location.world.spawnParticle(Particle.END_ROD, location, 18, 0.4, 0.5, 0.4, 0.055)
            location.world.spawnParticle(Particle.DUST, location, 24, 0.5, 0.45, 0.5, 0.0, dust)
        }
        bridge.leftParrot?.takeIf { it.isValid }?.remove()
        bridge.rightParrot?.takeIf { it.isValid }?.remove()
        val front = pointOnBridge(bridge, BRIDGE_LENGTH, 0.0, PARROT_HEIGHT)
        front.world.playSound(front, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.85f, 1.55f)
        front.world.playSound(front, Sound.ENTITY_PARROT_FLY, 0.65f, 1.8f)
    }

    private fun damageNewSegment(bridge: Bridge, oldLength: Double, newLength: Double): Boolean {
        if (newLength <= oldLength) return false
        val middleDistance = (oldLength + newLength) * 0.5
        val center = pointOnBridge(bridge, middleDistance, 0.0, 1.0)
        val halfSegment = (newLength - oldLength) * 0.5
        val xRadius = abs(bridge.direction.x) * halfSegment + abs(bridge.side.x) * BRIDGE_HALF_WIDTH + 1.5
        val zRadius = abs(bridge.direction.z) * halfSegment + abs(bridge.side.z) * BRIDGE_HALF_WIDTH + 1.5
        var hit = false

        for (entity in bridge.origin.world.getNearbyEntities(center, xRadius, DAMAGE_VERTICAL_RANGE, zRadius)) {
            val target = entity as? LivingEntity ?: continue
            if (!isValidTarget(target) || target.uniqueId in bridge.hitTargets) continue
            val relative = target.location.toVector().subtract(bridge.origin.toVector())
            val forward = relative.dot(bridge.direction)
            val lateral = relative.dot(bridge.side)
            val vertical = target.location.y - bridge.origin.y
            if (forward < oldLength - TARGET_EDGE_TOLERANCE || forward > newLength + TARGET_EDGE_TOLERANCE) continue
            if (abs(lateral) > BRIDGE_HALF_WIDTH + TARGET_EDGE_TOLERANCE) continue
            if (vertical < -1.0 || vertical > DAMAGE_VERTICAL_RANGE) continue

            bridge.hitTargets.add(target.uniqueId)
            FormationMagicDamage.deal(plugin, bridge.owner, target, bridge.damage)
            target.world.spawnParticle(
                Particle.DUST,
                target.location.clone().add(0.0, target.height * 0.55, 0.0),
                14,
                0.35,
                0.45,
                0.35,
                0.0,
                STAR_GOLD_DUST
            )
            hit = true
        }
        return hit
    }

    private fun refreshBridgeBuffs() {
        val shouldBeBuffed = Bukkit.getOnlinePlayers().asSequence()
            .filter { !it.isDead && isOnBridge(it) }
            .mapTo(hashSetOf()) { it.uniqueId }

        shouldBeBuffed.filter { it !in buffedPlayers }.forEach { uuid ->
            Bukkit.getPlayer(uuid)?.let(::applyBuff)
        }
        buffedPlayers.filter { it !in shouldBeBuffed }.toList().forEach { uuid ->
            removeBuff(uuid, Bukkit.getPlayer(uuid))
        }
    }

    private fun applyBuff(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        data.tempBonuses[ATTACK_BONUS_KEY] = OFFENSE_BONUS_PERCENT
        data.tempBonuses[ARCHER_BONUS_KEY] = OFFENSE_BONUS_PERCENT
        data.tempBonuses[FORMATION_BONUS_KEY] = OFFENSE_BONUS_PERCENT
        data.tempBonuses[SPEED_BONUS_KEY] = SPEED_BONUS_PERCENT
        player.addScoreboardTag(BRIDGE_SCOREBOARD_TAG)
        buffedPlayers.add(player.uniqueId)
        plugin.playerManager.updateStats(player)
    }

    private fun removeBuff(uuid: UUID, player: Player?) {
        val data = plugin.playerManager.getData(uuid)
        if (data != null) {
            data.tempBonuses.remove(ATTACK_BONUS_KEY)
            data.tempBonuses.remove(ARCHER_BONUS_KEY)
            data.tempBonuses.remove(FORMATION_BONUS_KEY)
            data.tempBonuses.remove(SPEED_BONUS_KEY)
        }
        player?.removeScoreboardTag(BRIDGE_SCOREBOARD_TAG)
        buffedPlayers.remove(uuid)
        if (player?.isOnline == true && data != null) plugin.playerManager.updateStats(player)
    }

    private fun isOnBridge(player: Player): Boolean = bridges.values.any { bridge ->
        bridge.origin.world == player.world && bridge.contains(player.location)
    }

    private fun Bridge.contains(location: Location): Boolean {
        val relative = location.toVector().subtract(origin.toVector())
        val forward = relative.dot(direction)
        val lateral = relative.dot(side)
        val vertical = location.y - origin.y
        return forward >= -BRIDGE_END_TOLERANCE &&
            forward <= currentLength + BRIDGE_END_TOLERANCE &&
            abs(lateral) <= BRIDGE_HALF_WIDTH &&
            vertical >= BRIDGE_MIN_Y && vertical <= BRIDGE_MAX_Y
    }

    private fun spawnCastEffect(origin: Location) {
        origin.world.spawnParticle(Particle.END_ROD, origin.clone().add(0.0, 0.45, 0.0), 32, 1.8, 0.35, 1.8, 0.06)
        origin.world.spawnParticle(Particle.ENCHANT, origin.clone().add(0.0, 0.6, 0.0), 48, 2.2, 0.5, 2.2, 0.18)
        repeat(36) { index ->
            val angle = 2.0 * PI * index / 36.0
            val loc = origin.clone().add(cos(angle) * BRIDGE_HALF_WIDTH, 0.12, sin(angle) * BRIDGE_HALF_WIDTH)
            origin.world.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, BRIDGE_CYAN_DUST)
        }
    }

    private fun spawnConstructionParticles(bridge: Bridge, oldLength: Double, newLength: Double) {
        var distance = oldLength
        while (distance <= newLength + 0.001) {
            val left = pointOnBridge(bridge, distance, BRIDGE_HALF_WIDTH, BRIDGE_SURFACE_Y)
            val right = pointOnBridge(bridge, distance, -BRIDGE_HALF_WIDTH, BRIDGE_SURFACE_Y)
            val center = pointOnBridge(bridge, distance, 0.0, BRIDGE_SURFACE_Y + 0.12)
            bridge.origin.world.spawnParticle(Particle.DUST, left, 2, 0.04, 0.02, 0.04, 0.0, BRIDGE_CYAN_DUST)
            bridge.origin.world.spawnParticle(Particle.DUST, right, 2, 0.04, 0.02, 0.04, 0.0, BRIDGE_MAGENTA_DUST)
            bridge.origin.world.spawnParticle(Particle.END_ROD, center, 1, 0.05, 0.03, 0.05, 0.01)
            distance += CONSTRUCTION_PARTICLE_STEP
        }
    }

    private fun spawnBridgeParticles(bridge: Bridge) {
        var distance = 0.0
        var index = 0
        while (distance <= bridge.currentLength + 0.001) {
            val left = pointOnBridge(bridge, distance, BRIDGE_HALF_WIDTH, BRIDGE_SURFACE_Y)
            val right = pointOnBridge(bridge, distance, -BRIDGE_HALF_WIDTH, BRIDGE_SURFACE_Y)
            bridge.origin.world.spawnParticle(Particle.DUST, left, 1, 0.025, 0.015, 0.025, 0.0, BRIDGE_CYAN_DUST)
            bridge.origin.world.spawnParticle(Particle.DUST, right, 1, 0.025, 0.015, 0.025, 0.0, BRIDGE_MAGENTA_DUST)
            if (index % 2 == 0) {
                val wave = sin(elapsedTicks * 0.12 + distance * 0.7) * 0.65
                val star = pointOnBridge(bridge, distance, wave, BRIDGE_SURFACE_Y + 0.18 + abs(wave) * 0.08)
                bridge.origin.world.spawnParticle(Particle.END_ROD, star, 1, 0.035, 0.025, 0.035, 0.0)
                if (index % 4 == 0) bridge.origin.world.spawnParticle(Particle.ELECTRIC_SPARK, star, 1, 0.06, 0.025, 0.06, 0.01)
            }
            if (index % 6 == 0) spawnCrossBeam(bridge, distance)
            distance += EDGE_PARTICLE_STEP
            index++
        }
    }

    private fun spawnCrossBeam(bridge: Bridge, distance: Double) {
        for (step in -4..4) {
            val sideOffset = BRIDGE_HALF_WIDTH * step / 4.0
            val loc = pointOnBridge(bridge, distance, sideOffset, BRIDGE_SURFACE_Y + 0.03)
            val dust = if (step % 2 == 0) STAR_GOLD_DUST else BRIDGE_CYAN_DUST
            bridge.origin.world.spawnParticle(Particle.DUST, loc, 1, 0.015, 0.01, 0.015, 0.0, dust)
        }
    }

    private fun spawnAccelerationEffect(bridge: Bridge) {
        val front = pointOnBridge(bridge, bridge.currentLength, 0.0, 0.65)
        bridge.origin.world.spawnParticle(Particle.FLASH, front, 1)
        bridge.origin.world.spawnParticle(Particle.FIREWORK, front, 32, 1.1, 0.55, 1.1, 0.12)
        bridge.origin.world.spawnParticle(Particle.END_ROD, front, 24, 1.0, 0.45, 1.0, 0.08)
        bridge.origin.world.playSound(front, Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 1.65f)
        bridge.origin.world.playSound(front, Sound.ENTITY_PARROT_FLY, 0.9f, 1.7f)
    }

    private fun pointOnBridge(bridge: Bridge, forward: Double, sideways: Double, y: Double): Location =
        bridge.origin.clone()
            .add(bridge.direction.clone().multiply(forward))
            .add(bridge.side.clone().multiply(sideways))
            .add(0.0, y, 0.0)

    private fun removeBridge(id: UUID) {
        val bridge = bridges.remove(id) ?: return
        bridge.leftParrot?.takeIf { it.isValid }?.remove()
        bridge.rightParrot?.takeIf { it.isValid }?.remove()
        bridge.origin.world.spawnParticle(
            Particle.ENCHANT,
            pointOnBridge(bridge, bridge.currentLength * 0.5, 0.0, 0.45),
            40,
            max(1.0, bridge.currentLength * 0.25),
            0.35,
            BRIDGE_HALF_WIDTH * 0.55,
            0.08
        )
        bridge.origin.world.playSound(bridge.origin, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.7f, 1.2f)
    }

    private fun horizontalDirection(player: Player): Vector {
        val direction = player.eyeLocation.direction.clone().setY(0.0)
        if (direction.lengthSquared() < 1.0E-6) {
            val radians = Math.toRadians(player.location.yaw.toDouble())
            return Vector(-sin(radians), 0.0, cos(radians)).normalize()
        }
        return direction.normalize()
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun readCooldownEnd(item: ItemStack): Long = item.itemMeta?.persistentDataContainer
        ?.get(cooldownEndKey, PersistentDataType.LONG) ?: 0L

    private fun writeCooldownEnd(item: ItemStack, value: Long) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(cooldownEndKey, PersistentDataType.LONG, value)
        item.itemMeta = meta
    }

    private fun ensurePersistentDefaults(item: ItemStack) {
        ArtifactCooldownGroups.ensure(plugin, item, ARTIFACT_ID)
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        var changed = false
        if (!pdc.has(instanceKey, PersistentDataType.STRING)) {
            pdc.set(instanceKey, PersistentDataType.STRING, UUID.randomUUID().toString())
            changed = true
        }
        if (!pdc.has(cooldownEndKey, PersistentDataType.LONG)) {
            pdc.set(cooldownEndKey, PersistentDataType.LONG, 0L)
            changed = true
        }
        if (changed) item.itemMeta = meta
    }

    private fun sendCooldownActionBar(player: Player, remainingMillis: Long) {
        val seconds = String.format(Locale.US, "%.1f", remainingMillis.coerceAtLeast(0L) / 1000.0)
        val message = "&c&l渡星河 阵法冷却中，剩余 $seconds 秒"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun sendManaActionBar(player: Player, manaCost: Double? = null) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val costText = manaCost?.takeIf { it > 0.0 }?.let { " &c(-${format(it)})" } ?: ""
        val message = "&6☯当前灵力值：&b${String.format(Locale.US, "%.1f", data.lingli)}$costText &6/ &b${String.format(Locale.US, "%.0f", data.maxLingli)} &6☯"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private data class Bridge(
        val id: UUID,
        val ownerId: UUID,
        val owner: Player,
        val origin: Location,
        val direction: Vector,
        val side: Vector,
        val damage: Double,
        val leftParrot: Parrot?,
        val rightParrot: Parrot?,
        val hitTargets: MutableSet<UUID> = hashSetOf(),
        var currentLength: Double = 0.0,
        var accelerated: Boolean = false,
        var parrotsDismissed: Boolean = false,
        var ageTicks: Long = 0L
    )

    companion object {
        const val ARTIFACT_ID = "queqiaoyin"
        private const val ACTIVATE_SLOT = 7
        private const val BASE_COOLDOWN_MILLIS = 25_000L
        private const val BRIDGE_LENGTH = 25.0
        private const val BRIDGE_HALF_WIDTH = 2.5
        private const val BRIDGE_DURATION_TICKS = 240L
        private const val BRIDGE_DAMAGE_MULTIPLIER = 2.0
        private const val INITIAL_SPEED_PER_TICK = 0.28
        private const val ACCELERATED_SPEED_PER_TICK = 0.78
        private const val TASK_INTERVAL_TICKS = 2L
        private const val AMBIENT_INTERVAL_TICKS = 6L
        private const val BUFF_CHECK_INTERVAL_TICKS = 4L
        private const val DAMAGE_VERTICAL_RANGE = 3.0
        private const val TARGET_EDGE_TOLERANCE = 0.8
        private const val BRIDGE_END_TOLERANCE = 0.5
        private const val BRIDGE_MIN_Y = -1.0
        private const val BRIDGE_MAX_Y = 2.25
        private const val PARROT_HEIGHT = 0.85
        private const val BRIDGE_SURFACE_Y = 0.12
        private const val CONSTRUCTION_PARTICLE_STEP = 0.3
        private const val EDGE_PARTICLE_STEP = 0.75
        private const val OFFENSE_BONUS_PERCENT = 0.30
        private const val SPEED_BONUS_PERCENT = 0.20
        private const val MEDICAL_HEAL_MULTIPLIER = 1.20
        private const val BANNER_REGEN_MULTIPLIER = 1.50
        private const val BRIDGE_SCOREBOARD_TAG = "hjh_queqiaoyin_bridge"
        private const val BUFF_SOURCE = "queqiaoyin_bridge"
        private const val ATTACK_BONUS_KEY = "$BUFF_SOURCE::attack_percent"
        private const val ARCHER_BONUS_KEY = "$BUFF_SOURCE::archer_damage_percent"
        private const val FORMATION_BONUS_KEY = "$BUFF_SOURCE::zf_str_percent"
        private const val SPEED_BONUS_KEY = "$BUFF_SOURCE::speed_percent"
        private val BRIDGE_CYAN_DUST = Particle.DustOptions(Color.fromRGB(92, 224, 244), 1.15f)
        private val BRIDGE_MAGENTA_DUST = Particle.DustOptions(Color.fromRGB(223, 116, 255), 1.1f)
        private val STAR_GOLD_DUST = Particle.DustOptions(Color.fromRGB(255, 226, 128), 1.0f)

        fun initializeNewItem(plugin: Hjh_database, item: ItemStack) {
            ArtifactCooldownGroups.ensure(plugin, item, ARTIFACT_ID)
            val meta = item.itemMeta ?: return
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "queqiaoyin_instance"), PersistentDataType.STRING, UUID.randomUUID().toString())
            pdc.set(NamespacedKey(plugin, "queqiaoyin_cooldown_end"), PersistentDataType.LONG, 0L)
            item.itemMeta = meta
        }
    }
}
