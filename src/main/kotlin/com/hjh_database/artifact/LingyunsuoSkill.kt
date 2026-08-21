package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class LingyunsuoSkill(private val plugin: Hjh_database) : Listener {
    private val instanceKey = NamespacedKey(plugin, "lingyunsuo_instance")
    private val cooldownEndKey = NamespacedKey(plugin, "lingyunsuo_cooldown_end")
    private val sessions = hashMapOf<UUID, WeaveSession>()
    private val pulls = hashMapOf<UUID, PullSequence>()
    private val rootedTargets = hashMapOf<UUID, RootState>()
    private val recentMainHandSwingTicks = hashMapOf<UUID, Long>()
    private val pendingMeleeChecks = hashSetOf<MeleeCheckKey>()
    private val activeDisplays = hashSetOf<ItemDisplay>()
    private var tickCounter = 0L
    private val ticker: BukkitTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)

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
        if (sessions.containsKey(player.uniqueId) || pulls.containsKey(player.uniqueId)) {
            player.sendMessage("§7法器技【牵云织锦】仍在施展中。")
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val manaCost = artifact.manaCost
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，灵云梭需要 ${format(manaCost)} 点灵力。")
            return
        }

        val start = handLocation(player)
        val display = spawnShuttle(start, item) ?: run {
            player.sendMessage("§c云梭实体生成失败，本次未消耗灵力。")
            return
        }
        val hover = findInitialHover(player)
        sessions[player.uniqueId] = WeaveSession(
            ownerId = player.uniqueId,
            instanceId = readInstanceId(item) ?: UUID.randomUUID().toString(),
            item = item,
            attack = data.attack,
            display = display,
            shuttleLocation = start,
            phase = ShuttlePhase.INITIAL_OUTBOUND,
            destination = hover
        )

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        playCastEffect(player, start)
        sendActivationActionBar(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMainHandSwing(event: PlayerAnimationEvent) {
        if (event.animationType != PlayerAnimationType.ARM_SWING) return
        if (!sessions.containsKey(event.player.uniqueId)) return
        recentMainHandSwingTicks[event.player.uniqueId] = Bukkit.getCurrentTick().toLong()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNormalAttack(event: EntityDamageByEntityEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK || event.finalDamage <= 0.0) return
        val player = event.damager as? Player ?: return
        if (event.entity.hasMetadata(PHYSICAL_SKILL_METADATA) ||
            event.entity.hasMetadata(MAGIC_DAMAGE_METADATA) ||
            event.entity.hasMetadata(FORMATION_DAMAGE_METADATA)
        ) return
        val session = sessions[player.uniqueId] ?: return
        val hitTick = Bukkit.getCurrentTick().toLong()
        val checkKey = MeleeCheckKey(player.uniqueId, hitTick)
        if (!pendingMeleeChecks.add(checkKey)) return

        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingMeleeChecks.remove(checkKey)
            val currentPlayer = Bukkit.getPlayer(player.uniqueId) ?: return@Runnable
            val currentSession = sessions[player.uniqueId] ?: return@Runnable
            if (currentSession !== session) return@Runnable
            val swingTick = recentMainHandSwingTicks[player.uniqueId] ?: return@Runnable
            if (swingTick < hitTick - MELEE_SWING_TOLERANCE_TICKS ||
                swingTick > hitTick + MELEE_SWING_TOLERANCE_TICKS
            ) return@Runnable
            controlShuttleFromMelee(currentPlayer, currentSession)
        })
    }

    private fun controlShuttleFromMelee(player: Player, session: WeaveSession) {
        val now = System.currentTimeMillis()
        if (!session.isActive(now) || now < session.nextControlAt) return

        when (session.phase) {
            ShuttlePhase.ANCHORED -> {
                session.nextControlAt = now + SHUTTLE_CONTROL_COOLDOWN_MILLIS
                beginReturn(session)
            }
            ShuttlePhase.HELD -> {
                if (beginRethrow(player, session)) {
                    session.nextControlAt = now + SHUTTLE_CONTROL_COOLDOWN_MILLIS
                }
            }
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        rootedTargets.remove(entity.uniqueId)
        val deathLocation = entity.location.clone()
        sessions.values.forEach { session ->
            session.nodes.filter { it.entityId == entity.uniqueId }.forEach { node ->
                node.lastLocation = deathLocation.clone()
                node.entityId = null
            }
            if (session.anchorEntityId == entity.uniqueId) {
                session.anchorEntityId = null
                session.shuttleLocation = anchorDisplayLocation(deathLocation, entity.height)
            }
            if (session.destinationEntityId == entity.uniqueId) {
                session.destinationEntityId = null
                session.destination = anchorDisplayLocation(deathLocation, entity.height)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = clearPlayer(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = clearPlayer(event.entity.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = clearPlayer(event.player.uniqueId)

    fun shutdown() {
        ticker.cancel()
        sessions.keys.toList().forEach(::clearPlayer)
        pulls.clear()
        rootedTargets.clear()
        recentMainHandSwingTicks.clear()
        pendingMeleeChecks.clear()
        activeDisplays.toList().forEach(::removeDisplay)
    }

    private fun tick() {
        tickCounter++
        val now = System.currentTimeMillis()

        for (session in sessions.values.toList()) {
            val player = Bukkit.getPlayer(session.ownerId)
            if (player == null || !player.isOnline || player.isDead) {
                clearPlayer(session.ownerId)
                continue
            }
            updateNodeLocations(session)
            if (session.startedAt > 0L && now >= session.expiresAt && session.phase != ShuttlePhase.FINAL_RETURNING) {
                beginFinalReturn(session)
            }
            updateShuttle(player, session, now)
            if (sessions[session.ownerId] === session &&
                session.startedAt > 0L && tickCounter % SILK_PARTICLE_INTERVAL_TICKS == 0L
            ) {
                drawSilkNetwork(session)
            }
        }

        pulls.values.toList().forEach(::updatePull)
        updateRoots()
    }

    private fun updateShuttle(player: Player, session: WeaveSession, now: Long) {
        when (session.phase) {
            ShuttlePhase.INITIAL_OUTBOUND -> updateInitialOutbound(player, session, now)
            ShuttlePhase.RETURNING -> updateReturning(player, session)
            ShuttlePhase.FINAL_RETURNING -> updateFinalReturning(player, session)
            ShuttlePhase.OUTBOUND -> updateRethrowOutbound(session)
            ShuttlePhase.ANCHORED -> updateAnchored(session)
            ShuttlePhase.HELD -> {
                session.shuttleLocation = handLocation(player)
                session.display.teleport(session.shuttleLocation)
                spawnHeldEffect(session.shuttleLocation)
            }
        }
    }

    private fun updateInitialOutbound(player: Player, session: WeaveSession, now: Long) {
        val destination = session.destination ?: findInitialHover(player).also { session.destination = it }
        val stepEnd = nextStep(session.shuttleLocation, destination)
        val firstTarget = findTargetsOnSegment(session.shuttleLocation, stepEnd, emptySet()).firstOrNull()
        if (firstTarget != null) {
            val targetPoint = entityAnchorLocation(firstTarget)
            processPath(player, session, session.shuttleLocation, targetPoint)
            session.shuttleLocation = targetPoint
            session.display.teleport(targetPoint)
            session.anchorEntityId = firstTarget.uniqueId
            session.destination = null
            session.phase = ShuttlePhase.ANCHORED
            beginDuration(session, now)
            playAnchorEffect(targetPoint)
            return
        }

        moveDisplayAlong(session, stepEnd)
        if (reached(session.shuttleLocation, destination)) {
            session.shuttleLocation = destination.clone()
            session.display.teleport(destination)
            addStaticNode(session, destination)
            session.destination = null
            session.phase = ShuttlePhase.ANCHORED
            beginDuration(session, now)
            playAnchorEffect(destination)
        }
    }

    private fun updateReturning(player: Player, session: WeaveSession) {
        val destination = handLocation(player)
        val stepEnd = nextStep(session.shuttleLocation, destination)
        processPath(player, session, session.shuttleLocation, stepEnd)
        moveDisplayAlong(session, stepEnd, drawTrail = false)
        if (reached(session.shuttleLocation, destination)) {
            session.shuttleLocation = destination
            session.display.teleport(destination)
            session.phase = ShuttlePhase.HELD
            session.destination = null
            session.destinationEntityId = null
            player.world.playSound(destination, Sound.ITEM_TRIDENT_RETURN, 0.85f, 1.7f)
        }
    }

    private fun updateFinalReturning(player: Player, session: WeaveSession) {
        val destination = handLocation(player)
        val stepEnd = nextStep(session.shuttleLocation, destination)
        processPath(player, session, session.shuttleLocation, stepEnd)
        moveDisplayAlong(session, stepEnd, drawTrail = false)
        if (!reached(session.shuttleLocation, destination)) return

        session.shuttleLocation = destination
        session.display.teleport(destination)
        addStaticNode(session, player.location)
        player.world.playSound(destination, Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.55f)
        finishWeaving(player, session)
    }

    private fun updateRethrowOutbound(session: WeaveSession) {
        val target = session.destinationEntityId?.let(Bukkit::getEntity) as? LivingEntity
        val destination = if (target != null && isValidTarget(target)) {
            entityAnchorLocation(target).also { session.destination = it }
        } else {
            session.destinationEntityId = null
            session.destination
        } ?: run {
            session.phase = ShuttlePhase.ANCHORED
            return
        }

        val player = Bukkit.getPlayer(session.ownerId) ?: return
        val stepEnd = nextStep(session.shuttleLocation, destination)
        processPath(player, session, session.shuttleLocation, stepEnd)
        moveDisplayAlong(session, stepEnd, drawTrail = false)
        if (reached(session.shuttleLocation, destination)) {
            session.shuttleLocation = destination.clone()
            session.display.teleport(destination)
            if (target != null && isValidTarget(target)) {
                hitPathTarget(player, session, target)
                session.anchorEntityId = target.uniqueId
            } else {
                session.anchorEntityId = null
            }
            session.destination = null
            session.destinationEntityId = null
            session.phase = ShuttlePhase.ANCHORED
            playAnchorEffect(destination)
        }
    }

    private fun updateAnchored(session: WeaveSession) {
        val target = session.anchorEntityId?.let(Bukkit::getEntity) as? LivingEntity
        if (target != null && isValidTarget(target)) {
            session.shuttleLocation = entityAnchorLocation(target)
            session.display.teleport(session.shuttleLocation)
        } else if (session.anchorEntityId != null) {
            session.anchorEntityId = null
        }
        if (tickCounter % 3L == 0L) spawnAnchorAmbient(session.shuttleLocation)
    }

    private fun beginDuration(session: WeaveSession, now: Long) {
        if (session.startedAt > 0L) return
        session.startedAt = now
        session.expiresAt = now + WEAVE_DURATION_MILLIS
    }

    private fun beginReturn(session: WeaveSession) {
        session.currentTransitHits.clear()
        session.anchorEntityId = null
        session.destination = null
        session.destinationEntityId = null
        session.phase = ShuttlePhase.RETURNING
        session.shuttleLocation.world.playSound(session.shuttleLocation, Sound.ITEM_TRIDENT_RETURN, 0.9f, 1.45f)
    }

    private fun beginFinalReturn(session: WeaveSession) {
        session.currentTransitHits.clear()
        session.anchorEntityId = null
        session.destination = null
        session.destinationEntityId = null
        session.phase = ShuttlePhase.FINAL_RETURNING
        session.shuttleLocation.world.playSound(session.shuttleLocation, Sound.ITEM_TRIDENT_RETURN, 1.1f, 1.25f)
    }

    private fun beginRethrow(player: Player, session: WeaveSession): Boolean {
        val target = selectFarthestTarget(player, session) ?: run {
            player.world.playSound(handLocation(player), Sound.BLOCK_NOTE_BLOCK_HAT, 0.55f, 0.65f)
            return false
        }
        session.destinationEntityId = target.uniqueId
        session.destination = entityAnchorLocation(target)
        session.currentTransitHits.clear()
        session.phase = ShuttlePhase.OUTBOUND
        player.world.playSound(handLocation(player), Sound.ENTITY_BREEZE_SHOOT, 1.0f, 1.2f)
        return true
    }

    private fun selectFarthestTarget(player: Player, session: WeaveSession): LivingEntity? {
        val center = player.location
        val candidates = player.world.getNearbyEntities(center, RETARGET_RANGE, RETARGET_RANGE, RETARGET_RANGE).asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter(::isValidTarget)
            .filter { it.location.distanceSquared(center) <= RETARGET_RANGE * RETARGET_RANGE }
            .toList()
        return candidates.filter { it.uniqueId !in session.wovenTargets }
            .maxByOrNull { it.location.distanceSquared(center) }
            ?: candidates.maxByOrNull { it.location.distanceSquared(center) }
    }

    private fun processPath(player: Player, session: WeaveSession, start: Location, end: Location) {
        drawShuttleTrail(start, end)
        for (target in findTargetsOnSegment(start, end, emptySet())) {
            hitPathTarget(player, session, target)
        }
    }

    private fun hitPathTarget(player: Player, session: WeaveSession, target: LivingEntity) {
        weaveTarget(session, target)
        if (session.currentTransitHits.add(target.uniqueId)) dealPathDamage(player, session, target)
    }

    private fun findTargetsOnSegment(
        start: Location,
        end: Location,
        excluded: Set<UUID>,
        hitRadius: Double = PATH_HIT_RADIUS
    ): List<LivingEntity> {
        if (start.world != end.world) return emptyList()
        val delta = end.toVector().subtract(start.toVector())
        val midpoint = start.clone().add(delta.clone().multiply(0.5))
        val xRadius = abs(delta.x) * 0.5 + hitRadius
        val yRadius = abs(delta.y) * 0.5 + hitRadius
        val zRadius = abs(delta.z) * 0.5 + hitRadius
        val startVector = start.toVector()
        val endVector = end.toVector()
        val radiusSquared = hitRadius * hitRadius

        return start.world.getNearbyEntities(midpoint, xRadius, yRadius, zRadius).asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter(::isValidTarget)
            .filter { it.uniqueId !in excluded }
            .map { target ->
                val point = entityCenter(target).toVector()
                target to segmentProjection(point, startVector, endVector)
            }
            .filter { (target, projection) ->
                val point = entityCenter(target).toVector()
                point.distanceSquared(projection.closest) <= radiusSquared
            }
            .sortedBy { it.second.t }
            .map { it.first }
            .distinctBy { it.uniqueId }
            .toList()
    }

    private fun weaveTarget(session: WeaveSession, target: LivingEntity) {
        if (!session.wovenTargets.add(target.uniqueId)) return
        session.nodes += SilkNode(target.uniqueId, target.location.clone())
        val center = entityCenter(target)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_ENDPOINT, center, data = 1)
        center.world.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.65f, 1.7f)
    }

    private fun addStaticNode(session: WeaveSession, location: Location) {
        session.nodes += SilkNode(null, location.clone())
    }

    private fun dealPathDamage(player: Player, session: WeaveSession, target: LivingEntity) {
        val previousHits = session.pathHitCounts.getOrDefault(target.uniqueId, 0)
        val multiplier = if (previousHits == 0) PATH_DAMAGE_MULTIPLIER else REPEAT_PATH_DAMAGE_MULTIPLIER
        session.pathHitCounts[target.uniqueId] = previousHits + 1
        dealAndReward(player, session, target, session.attack * multiplier)
    }

    private fun dealAndReward(player: Player, session: WeaveSession, target: LivingEntity, amount: Double) {
        val wasAlive = target.isValid && !target.isDead
        dealPhysicalSkillDamage(player, target, amount)
        if (wasAlive && isKilled(target) && session.restoredMana < MAX_MANA_RESTORE) {
            val restored = min(MANA_PER_KILL, MAX_MANA_RESTORE - session.restoredMana)
            plugin.playerManager.getData(player.uniqueId)?.let { data ->
                data.addLingli(restored)
                plugin.databaseManager.queuePlayerSave(data)
            }
            session.restoredMana += restored
        }
    }

    private fun finishWeaving(player: Player, session: WeaveSession) {
        sessions.remove(session.ownerId)
        updateNodeLocations(session)
        removeDisplay(session.display)
        session.display.world.playSound(session.shuttleLocation, Sound.BLOCK_BEACON_DEACTIVATE, 0.85f, 1.6f)

        val first = session.nodes.firstOrNull()?.lastLocation?.clone()
        val last = session.nodes.lastOrNull()?.lastLocation?.clone()
        if (first == null || last == null || first.world != last.world) return

        val damageTargets = session.wovenTargets.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }
            .filter { isValidTarget(it) && it.world == first.world }
            .distinctBy { it.uniqueId }
        val pullTargets = damageTargets.filterNot { target ->
            target.scoreboardTags.contains(INSTANCE_BOSS_TAG)
        }.map { target ->
            PullTarget(target.uniqueId, calculatePullStep(target.location, first, last))
        }.toMutableList()

        playTightenEffect(session.nodes.map { it.lastLocation })
        pulls[player.uniqueId] = PullSequence(
            ownerId = player.uniqueId,
            session = session,
            lineStart = first,
            lineEnd = last,
            targets = pullTargets
        )
    }

    private fun updatePull(pull: PullSequence) {
        val player = Bukkit.getPlayer(pull.ownerId)
        if (player == null || !player.isOnline || player.isDead) {
            pulls.remove(pull.ownerId)
            return
        }

        if (pull.delayTicks > 0) {
            if (pull.delayTicks % 2 == 0) {
                drawTighteningLine(pull.lineStart, pull.lineEnd)
                drawEndpointEffect(pull.lineStart, strong = true)
                drawEndpointEffect(pull.lineEnd, strong = true)
            }
            pull.delayTicks--
            return
        }

        if (pull.step < PULL_STEPS) {
            pull.targets.forEach { targetState ->
                if (targetState.blocked) return@forEach
                val target = Bukkit.getEntity(targetState.entityId) as? LivingEntity ?: return@forEach
                if (!isValidTarget(target)) return@forEach
                if (target.scoreboardTags.contains(INSTANCE_BOSS_TAG)) return@forEach
                val next = target.location.clone().add(targetState.stepVector)
                if (isSafePullLocation(next)) {
                    target.teleport(next)
                    target.velocity = Vector(0.0, target.velocity.y.coerceAtMost(0.0), 0.0)
                    plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_HELD, next.clone().add(0.0, target.height * 0.45, 0.0))
                } else {
                    targetState.blocked = true
                }
            }
            drawTighteningLine(pull.lineStart, pull.lineEnd)
            pull.step++
            return
        }

        pulls.remove(pull.ownerId)
        val finalTargets = findTargetsOnSegment(
            pull.lineStart,
            pull.lineEnd,
            emptySet(),
            FINAL_LINE_HIT_RADIUS
        )
        finalTargets.forEach { target ->
            if (!target.scoreboardTags.contains(INSTANCE_BOSS_TAG)) {
                applyTightenedSilkEffects(target)
            }
            dealAndReward(player, pull.session, target, pull.session.attack * FINAL_DAMAGE_MULTIPLIER)
        }
        val midpoint = pull.lineStart.clone().add(pull.lineEnd).multiply(0.5)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_ENDPOINT, midpoint, data = 1)
        midpoint.world.playSound(midpoint, Sound.ITEM_TRIDENT_THUNDER, 1.0f, 1.8f)
        startCooldown(player, pull.session.item)
    }

    private fun applyTightenedSilkEffects(target: LivingEntity) {
        rootedTargets[target.uniqueId] = RootState(
            anchor = target.location.clone(),
            expiresAtTick = tickCounter + FINAL_ROOT_TICKS
        )
        target.addPotionEffect(
            PotionEffect(PotionEffectType.SLOWNESS, FINAL_SLOW_TICKS, FINAL_SLOW_AMPLIFIER, false, true, true),
            true
        )
        val center = entityCenter(target)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_ENDPOINT, center, data = 1)
    }

    private fun updateRoots() {
        val iterator = rootedTargets.iterator()
        while (iterator.hasNext()) {
            val (targetId, root) = iterator.next()
            if (tickCounter >= root.expiresAtTick) {
                iterator.remove()
                continue
            }
            val target = Bukkit.getEntity(targetId) as? LivingEntity
            if (target == null || !isValidTarget(target) || target.world != root.anchor.world) {
                iterator.remove()
                continue
            }
            val locked = root.anchor.clone().apply {
                yaw = target.location.yaw
                pitch = target.location.pitch
            }
            if (target.location.distanceSquared(locked) > 1.0E-6) target.teleport(locked)
            target.velocity = Vector()
        }
    }

    private fun calculatePullStep(location: Location, lineStart: Location, lineEnd: Location): Vector {
        val start = Vector(lineStart.x, 0.0, lineStart.z)
        val end = Vector(lineEnd.x, 0.0, lineEnd.z)
        val point = Vector(location.x, 0.0, location.z)
        val projection = segmentProjection(point, start, end).closest
        val towardLine = projection.subtract(point)
        val distance = towardLine.length()
        if (distance <= 1.0E-6) return Vector()
        return towardLine.normalize().multiply(min(MAX_PULL_DISTANCE, distance) / PULL_STEPS)
    }

    private fun isSafePullLocation(location: Location): Boolean =
        location.block.isPassable && location.clone().add(0.0, 1.0, 0.0).block.isPassable

    private fun updateNodeLocations(session: WeaveSession) {
        session.nodes.forEach { node ->
            val entity = node.entityId?.let(Bukkit::getEntity) as? LivingEntity
            if (entity != null && entity.isValid && !entity.isDead) node.lastLocation = entity.location.clone()
        }
    }

    private fun drawSilkNetwork(session: WeaveSession) {
        if (session.nodes.isEmpty()) return
        drawEndpointEffect(session.nodes.first().lastLocation, strong = false)
        if (session.nodes.size > 1) drawEndpointEffect(session.nodes.last().lastLocation, strong = false)
        if (session.nodes.size < 2) return
        val links = session.nodes.zipWithNext()
        val firstIndex = ((tickCounter / SILK_PARTICLE_INTERVAL_TICKS) % links.size).toInt()
        var budget = NETWORK_PARTICLE_BUDGET
        for (offset in links.indices) {
            if (budget <= 0) break
            val (from, to) = links[(firstIndex + offset) % links.size]
            budget -= drawSilkLine(from.lastLocation, to.lastLocation, dense = false, budget = budget)
        }
    }

    private fun playTightenEffect(nodes: List<Location>) {
        var budget = TIGHTEN_PARTICLE_BUDGET
        for ((from, to) in nodes.zipWithNext()) {
            if (budget <= 0) break
            budget -= drawSilkLine(from, to, dense = true, budget = budget)
        }
    }

    private fun drawSilkLine(start: Location, end: Location, dense: Boolean, budget: Int): Int {
        if (start.world != end.world || budget <= 0) return 0
        val delta = end.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= 1.0E-6) return 0
        val step = if (dense) 0.45 else 0.85
        val desiredPoints = min(MAX_SILK_SAMPLES + 1, max(2, ceil(distance / step).toInt() + 1))
        val pointCount = min(budget, desiredPoints)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_LINE, start, end, if (dense) 1 else 0)
        return pointCount
    }

    private fun drawEndpointEffect(location: Location, strong: Boolean) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_ENDPOINT, location, data = if (strong) 1 else 0)
    }

    private fun drawTighteningLine(start: Location, end: Location) {
        drawSilkLine(start, end, dense = true, budget = MAX_SILK_SAMPLES + 1)
    }

    private fun drawShuttleTrail(start: Location, end: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_TRAIL, start, end)
    }

    private fun moveDisplayAlong(session: WeaveSession, destination: Location, drawTrail: Boolean = true) {
        if (drawTrail) drawShuttleTrail(session.shuttleLocation, destination)
        session.shuttleLocation = destination.clone()
        session.display.teleport(destination)
    }

    private fun nextStep(start: Location, destination: Location): Location {
        val delta = destination.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= SHUTTLE_SPEED_PER_TICK) return destination.clone()
        return start.clone().add(delta.normalize().multiply(SHUTTLE_SPEED_PER_TICK))
    }

    private fun reached(current: Location, destination: Location): Boolean =
        current.world == destination.world && current.distanceSquared(destination) <= ARRIVAL_DISTANCE_SQUARED

    private fun findInitialHover(player: Player): Location {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val hit = player.world.rayTraceBlocks(eye, direction, INITIAL_THROW_RANGE, FluidCollisionMode.NEVER, true)
        val raw = hit?.hitPosition?.toLocation(player.world)
            ?: eye.clone().add(direction.clone().multiply(INITIAL_THROW_RANGE))
        return raw.subtract(direction.clone().multiply(BLOCK_HOVER_OFFSET))
    }

    private fun handLocation(player: Player): Location {
        val forward = player.eyeLocation.direction.apply { y = 0.0 }
        if (forward.lengthSquared() > 1.0E-6) forward.normalize() else forward.setX(0.0).setZ(1.0)
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize()
        return player.location.clone().add(0.0, 1.35, 0.0).add(right.multiply(0.55)).add(forward.multiply(0.1))
    }

    private fun entityCenter(entity: LivingEntity): Location =
        entity.location.clone().add(0.0, entity.height * 0.5, 0.0)

    private fun entityAnchorLocation(entity: LivingEntity): Location =
        anchorDisplayLocation(entity.location, entity.height)

    private fun anchorDisplayLocation(location: Location, height: Double): Location =
        location.clone().add(0.0, height * 0.68, 0.0)

    private fun spawnShuttle(location: Location, artifactItem: ItemStack): ItemDisplay? = try {
        val displayItem = ItemStack(artifactItem.type).also { item ->
            val customModelData = artifactItem.itemMeta?.takeIf { it.hasCustomModelData() }?.customModelData
            if (customModelData != null) {
                val meta = item.itemMeta
                meta?.setCustomModelData(customModelData)
                item.itemMeta = meta
            }
        }
        location.world.spawn(location, ItemDisplay::class.java) { display ->
            display.setItemStack(displayItem)
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED)
            display.billboard = Display.Billboard.CENTER
            display.setTransformation(
                Transformation(
                    Vector3f(),
                    AxisAngle4f(),
                    Vector3f(SHUTTLE_SCALE, SHUTTLE_SCALE, SHUTTLE_SCALE),
                    AxisAngle4f()
                )
            )
            display.setGravity(false)
            display.isInvulnerable = true
            display.isPersistent = false
            display.isGlowing = true
            display.teleportDuration = 1
        }.also(activeDisplays::add)
    } catch (error: Throwable) {
        plugin.logger.warning("灵云梭展示实体生成失败：${error.message}")
        null
    }

    private fun removeDisplay(display: ItemDisplay) {
        activeDisplays.remove(display)
        if (display.isValid) display.remove()
    }

    private fun dealPhysicalSkillDamage(player: Player, target: LivingEntity, amount: Double) {
        if (amount <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata(PHYSICAL_SKILL_METADATA, FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            target.removeMetadata(PHYSICAL_SKILL_METADATA, plugin)
            if (target.isValid && !target.isDead) target.noDamageTicks = 0
        }
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity.isValid && !entity.isDead && tags.contains("panling") && tags.contains("monster")
    }

    private fun isKilled(entity: LivingEntity): Boolean =
        entity.isDead || !entity.isValid || runCatching { entity.health <= 0.0 }.getOrDefault(true)

    private fun segmentProjection(point: Vector, start: Vector, end: Vector): Projection {
        val segment = end.clone().subtract(start)
        val lengthSquared = segment.lengthSquared()
        if (lengthSquared <= 1.0E-9) return Projection(0.0, start.clone())
        val t = (point.clone().subtract(start).dot(segment) / lengthSquared).coerceIn(0.0, 1.0)
        return Projection(t, start.clone().add(segment.multiply(t)))
    }

    private fun playCastEffect(player: Player, location: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_CAST, location)
        player.world.playSound(location, Sound.ENTITY_BREEZE_SHOOT, 1.05f, 1.25f)
    }

    private fun playAnchorEffect(location: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_ANCHOR, location)
        location.world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.8f, 1.5f)
    }

    private fun spawnAnchorAmbient(location: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_AMBIENT, location)
    }

    private fun spawnHeldEffect(location: Location) {
        if (tickCounter % 3L == 0L) plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.LINGYUN_HELD, location)
    }

    private fun startCooldown(player: Player, item: ItemStack) {
        val cooldownDuration = ArtifactCooldownGroups.reducedDurationMillis(plugin, player, BASE_COOLDOWN_MILLIS)
        val cooldownEndsAt = System.currentTimeMillis() + cooldownDuration
        val instanceId = readInstanceId(item)
        val matches = instanceId?.let { matchingInstanceItems(player, it) }.orEmpty()
        if (matches.isEmpty()) writeCooldownEnd(item, cooldownEndsAt) else matches.forEach { writeCooldownEnd(it, cooldownEndsAt) }
        player.setCooldown(item, (cooldownDuration / 50L).toInt().coerceAtLeast(1))
    }

    private fun clearPlayer(playerId: UUID) {
        sessions.remove(playerId)?.let { removeDisplay(it.display) }
        pulls.remove(playerId)
        recentMainHandSwingTicks.remove(playerId)
        pendingMeleeChecks.removeIf { it.playerId == playerId }
    }

    private fun matchingInstanceItems(player: Player, instanceId: String): List<ItemStack> {
        val matches = mutableListOf<ItemStack>()
        player.inventory.contents.filterNotNull().filterTo(matches) { readInstanceId(it) == instanceId }
        player.itemOnCursor.takeUnless { it.type.isAir }?.let { if (readInstanceId(it) == instanceId) matches.add(it) }
        player.openInventory.topInventory.contents.filterNotNull().filterTo(matches) { readInstanceId(it) == instanceId }
        return matches
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

    private fun readInstanceId(item: ItemStack): String? = item.itemMeta?.persistentDataContainer
        ?.get(instanceKey, PersistentDataType.STRING)

    private fun readCooldownEnd(item: ItemStack): Long = item.itemMeta?.persistentDataContainer
        ?.get(cooldownEndKey, PersistentDataType.LONG) ?: 0L

    private fun writeCooldownEnd(item: ItemStack, value: Long) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(cooldownEndKey, PersistentDataType.LONG, value)
        item.itemMeta = meta
    }

    private fun sendActivationActionBar(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val message = "&a&l法器技【牵云织锦】发动！ &6☯当前灵力值：&b${String.format(Locale.US, "%.1f", data.lingli)} &6/ &b${String.format(Locale.US, "%.0f", data.maxLingli)} &6☯"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
    }

    private fun sendCooldownActionBar(player: Player, remainingMillis: Long) {
        val seconds = String.format(Locale.US, "%.1f", remainingMillis.coerceAtLeast(0L) / 1000.0)
        val message = "&c&l法器技【牵云织锦】处于冷却中，剩余${seconds}秒"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private data class WeaveSession(
        val ownerId: UUID,
        val instanceId: String,
        val item: ItemStack,
        val attack: Double,
        val display: ItemDisplay,
        var shuttleLocation: Location,
        var phase: ShuttlePhase,
        var destination: Location? = null,
        var destinationEntityId: UUID? = null,
        var anchorEntityId: UUID? = null,
        var startedAt: Long = 0L,
        var expiresAt: Long = Long.MAX_VALUE,
        val nodes: MutableList<SilkNode> = arrayListOf(),
        val wovenTargets: MutableSet<UUID> = hashSetOf(),
        val pathHitCounts: MutableMap<UUID, Int> = hashMapOf(),
        val currentTransitHits: MutableSet<UUID> = hashSetOf(),
        var nextControlAt: Long = 0L,
        var restoredMana: Double = 0.0
    ) {
        fun isActive(now: Long): Boolean = startedAt > 0L && now < expiresAt
    }

    private data class SilkNode(
        var entityId: UUID?,
        var lastLocation: Location
    )

    private data class PullSequence(
        val ownerId: UUID,
        val session: WeaveSession,
        val lineStart: Location,
        val lineEnd: Location,
        val targets: MutableList<PullTarget>,
        var delayTicks: Int = PULL_DELAY_TICKS,
        var step: Int = 0
    )

    private data class RootState(
        val anchor: Location,
        val expiresAtTick: Long
    )

    private data class MeleeCheckKey(
        val playerId: UUID,
        val hitTick: Long
    )

    private data class PullTarget(
        val entityId: UUID,
        val stepVector: Vector,
        var blocked: Boolean = false
    )

    private data class Projection(val t: Double, val closest: Vector)

    private enum class ShuttlePhase {
        INITIAL_OUTBOUND,
        ANCHORED,
        RETURNING,
        FINAL_RETURNING,
        HELD,
        OUTBOUND
    }

    companion object {
        const val ARTIFACT_ID = "lingyunsuo"
        private const val ACTIVATE_SLOT = 7
        private const val BASE_COOLDOWN_MILLIS = 15_000L
        private const val WEAVE_DURATION_MILLIS = 7_000L
        private const val SHUTTLE_CONTROL_COOLDOWN_MILLIS = 400L
        private const val MELEE_SWING_TOLERANCE_TICKS = 1L
        private const val INITIAL_THROW_RANGE = 20.0
        private const val RETARGET_RANGE = 15.0
        private const val SHUTTLE_SPEED_PER_TICK = 1.7
        private const val ARRIVAL_DISTANCE_SQUARED = 0.04
        private const val BLOCK_HOVER_OFFSET = 0.22
        private const val PATH_HIT_RADIUS = 0.85
        private const val PATH_DAMAGE_MULTIPLIER = 1.50
        private const val REPEAT_PATH_DAMAGE_MULTIPLIER = 1.00
        private const val FINAL_DAMAGE_MULTIPLIER = 4.00
        private const val FINAL_LINE_HIT_RADIUS = 2.0
        private const val FINAL_ROOT_TICKS = 30L
        private const val FINAL_SLOW_TICKS = 200
        private const val FINAL_SLOW_AMPLIFIER = 1
        private const val MANA_PER_KILL = 2.0
        private const val MAX_MANA_RESTORE = 14.0
        private const val MAX_PULL_DISTANCE = 5.0
        private const val PULL_STEPS = 6
        private const val PULL_DELAY_TICKS = 12
        private const val SILK_PARTICLE_INTERVAL_TICKS = 4L
        private const val MAX_SILK_SAMPLES = 18
        private const val NETWORK_PARTICLE_BUDGET = 96
        private const val TIGHTEN_PARTICLE_BUDGET = 180
        private const val SHUTTLE_SCALE = 0.9f
        private const val PHYSICAL_SKILL_METADATA = "hjh_physical_skill"
        private const val MAGIC_DAMAGE_METADATA = "HJH_MAGIC_DAMAGE"
        private const val FORMATION_DAMAGE_METADATA = "HJH_FORMATION_DAMAGE"
        private const val INSTANCE_BOSS_TAG = "instance_boss"
        private val SILK_DUST = Particle.DustOptions(Color.fromRGB(183, 231, 255), 0.85f)
        private val ENDPOINT_DUST = Particle.DustOptions(Color.fromRGB(244, 252, 255), 1.35f)
        private val SHUTTLE_DUST = Particle.DustOptions(Color.fromRGB(104, 209, 255), 1.0f)

        fun initializeNewItem(plugin: Hjh_database, item: ItemStack) {
            ArtifactCooldownGroups.ensure(plugin, item, ARTIFACT_ID)
            val meta = item.itemMeta ?: return
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "lingyunsuo_instance"), PersistentDataType.STRING, UUID.randomUUID().toString())
            pdc.set(NamespacedKey(plugin, "lingyunsuo_cooldown_end"), PersistentDataType.LONG, 0L)
            item.itemMeta = meta
        }
    }
}
