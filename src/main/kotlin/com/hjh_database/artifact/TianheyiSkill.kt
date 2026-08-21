package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import com.hjh_database.listener.FormationMagicDamage
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class TianheyiSkill(private val plugin: Hjh_database) : Listener {
    private val instanceKey = NamespacedKey(plugin, "tianheyi_instance")
    private val cooldownEndKey = NamespacedKey(plugin, "tianheyi_cooldown_end")
    private val legacyBlackCountKey = NamespacedKey(plugin, "tianheyi_black")
    private val legacyWhiteCountKey = NamespacedKey(plugin, "tianheyi_white")
    private val legacySelectedColorKey = NamespacedKey(plugin, "tianheyi_selected")

    private val states = mutableMapOf<UUID, PlayerState>()
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
        val state = activeState(player) ?: run {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', artifact.activeLoreLine))
            return
        }
        if (plugin.elementZfManager.getActiveFurnaceRarity(player) == null) {
            player.sendMessage("§c需要副手激活术法炉，才能催动天河弈。")
            return
        }

        val now = System.currentTimeMillis()
        val pendingCast = state.pendingCastId?.let(state.casts::get)
        if (pendingCast != null) {
            if (now > pendingCast.whiteWindowEndsAt) {
                expireWhiteWindow(player, state, pendingCast)
            } else {
                placeWhitePiece(player, state, pendingCast)
            }
            return
        }

        if (now < state.cooldownEndsAt) {
            sendCooldownActionBar(player, state)
            return
        }
        placeBlackPiece(player, state)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        detach(event.player.uniqueId, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        detach(event.entity.uniqueId, event.entity)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        detach(event.player.uniqueId, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val state = states[event.player.uniqueId] ?: return
        if (readInstanceId(event.itemDrop.itemStack) != state.instanceId) return
        state.item = event.itemDrop.itemStack
        detach(event.player.uniqueId, event.player)
    }

    fun reloadPreservingStock() {
        states.keys.toList().forEach { uuid -> detach(uuid, Bukkit.getPlayer(uuid)) }
    }

    fun shutdown() {
        ticker.cancel()
        reloadPreservingStock()
    }

    private fun tick() {
        tickCounter++
        val activePlayers = hashSetOf<UUID>()

        for (player in Bukkit.getOnlinePlayers()) {
            if (player.isDead) continue
            val item = player.inventory.getItem(ACTIVATE_SLOT) ?: continue
            val artifact = plugin.artifactManager.getDataFromItem(item) ?: continue
            if (artifact.id != ARTIFACT_ID || !plugin.artifactManager.isActive(player, item, ACTIVATE_SLOT)) continue

            activePlayers.add(player.uniqueId)
            val state = ensureState(player, item)
            val pending = state.pendingCastId?.let(state.casts::get)
            if (pending != null) {
                if (System.currentTimeMillis() > pending.whiteWindowEndsAt) {
                    expireWhiteWindow(player, state, pending)
                }
            }
            updatePieces(player, state)
        }

        for (uuid in states.keys.toList()) {
            if (uuid !in activePlayers) detach(uuid, Bukkit.getPlayer(uuid))
        }
    }

    private fun activeState(player: Player): PlayerState? {
        val item = player.inventory.getItem(ACTIVATE_SLOT) ?: return null
        val artifact = plugin.artifactManager.getDataFromItem(item) ?: return null
        if (artifact.id != ARTIFACT_ID || !plugin.artifactManager.isActive(player, item, ACTIVATE_SLOT)) return null
        return ensureState(player, item)
    }

    private fun ensureState(player: Player, item: ItemStack): PlayerState {
        ensurePersistentDefaults(item)
        val pdc = item.itemMeta!!.persistentDataContainer
        val instanceId = pdc.get(instanceKey, PersistentDataType.STRING)!!
        val current = states[player.uniqueId]
        if (current != null && current.instanceId == instanceId) {
            current.item = item
            return current
        }
        if (current != null) detach(player.uniqueId, player)

        return PlayerState(
            item = item,
            instanceId = instanceId,
            cooldownEndsAt = pdc.get(cooldownEndKey, PersistentDataType.LONG) ?: 0L
        ).also { state ->
            states[player.uniqueId] = state
            applyVisualCooldown(player, state)
        }
    }

    private fun placeBlackPiece(player: Player, state: PlayerState) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val manaCost = plugin.artifactManager.getDataFromItem(state.item)?.manaCost ?: DEFAULT_MANA_COST
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，天河弈需要 ${format(manaCost)} 点灵力。")
            sendManaActionBar(player)
            return
        }

        val piece = spawnPiece(findPlacement(player), PieceColor.BLACK, UUID.randomUUID()) ?: run {
            player.sendMessage("§c黑棋落点异常，未消耗灵力。")
            return
        }
        val now = System.currentTimeMillis()
        val cast = CastSession(
            id = piece.castId,
            manaCost = manaCost,
            blackId = piece.id,
            whiteWindowEndsAt = now + WHITE_WINDOW_MILLIS,
            cooldownDurationMillis = ArtifactCooldownGroups.reducedDurationMillis(plugin, player, BASE_COOLDOWN_MILLIS)
        )
        state.pieces[piece.id] = piece
        state.casts[cast.id] = cast
        state.pendingCastId = cast.id

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        applyLandingDamage(player, piece.location)
        spawnLandingEffect(piece)
        player.world.playSound(piece.location, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.95f, 0.62f)
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e${player.name}&f凭借&e天河弈&f，释放了阵法——&b弈定天河"))
        player.sendMessage("§7请在3秒内再次使用天河弈落下白棋。")
        sendManaActionBar(player, manaCost)
    }

    private fun placeWhitePiece(player: Player, state: PlayerState, cast: CastSession) {
        val piece = spawnPiece(findPlacement(player), PieceColor.WHITE, cast.id) ?: run {
            player.sendMessage("§c白棋落点异常，可在剩余时间内重试。")
            return
        }
        cast.whiteId = piece.id
        state.pieces[piece.id] = piece
        state.pendingCastId = null

        applyLandingDamage(player, piece.location)
        spawnLandingEffect(piece)
        player.world.playSound(piece.location, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.95f, 1.38f)
        startCooldown(player, state, cast.cooldownDurationMillis)
        pairCastIfInRange(player, state, cast)
        sendManaActionBar(player)
    }

    private fun spawnPiece(location: Location, color: PieceColor, castId: UUID): Piece? {
        val display = spawnPieceDisplay(location, color) ?: return null
        return Piece(UUID.randomUUID(), castId, color, display, location, tickCounter + PIECE_LIFETIME_TICKS)
    }

    private fun pairCastIfInRange(player: Player, state: PlayerState, cast: CastSession) {
        val black = state.pieces[cast.blackId] ?: return
        val white = cast.whiteId?.let(state.pieces::get) ?: return
        if (black.location.world != white.location.world) return
        if (black.location.distanceSquared(white.location) > PAIR_DISTANCE_SQUARED) return

        val pairId = UUID.randomUUID()
        black.pairId = pairId
        white.pairId = pairId
        val initialDistance = black.location.distance(white.location)
        val pair = CollisionPair(
            id = pairId,
            castId = cast.id,
            firstId = black.id,
            secondId = white.id,
            initialDistance = initialDistance
        )
        state.pairs[pairId] = pair
        if (isExplosionDistance(initialDistance)) {
            explodePair(player, state, pair, black, white)
        }
    }

    private fun expireWhiteWindow(player: Player, state: PlayerState, cast: CastSession) {
        if (state.pendingCastId != cast.id) return
        state.pendingCastId = null
        startCooldown(player, state, cast.cooldownDurationMillis)
        failCast(player, state, cast.id, "§e白棋落子超时")
    }

    private fun updatePieces(player: Player, state: PlayerState) {
        if (tickCounter % MOVE_INTERVAL_TICKS == 0L) {
            state.pairs.values.toList().forEach { movePair(player, state, it) }
        }

        val expiredCastIds = state.pieces.values.asSequence()
            .filter { tickCounter >= it.expiresAt }
            .map { it.castId }
            .toSet()
        expiredCastIds.forEach { castId ->
            if (state.casts[castId]?.resolved == false) {
                failCast(player, state, castId, "§e棋子未碰撞，返还50%冷却与灵力")
            } else {
                removeCastPieces(state, castId)
            }
        }

        if (tickCounter % PARTICLE_INTERVAL_TICKS == 0L) {
            state.pieces.values.forEach { spawnOrbitParticle(it) }
        }
    }

    private fun movePair(player: Player, state: PlayerState, pair: CollisionPair) {
        val first = state.pieces[pair.firstId] ?: return removePair(state, pair.id)
        val second = state.pieces[pair.secondId] ?: return removePair(state, pair.id)
        if (first.location.world != second.location.world) return removePair(state, pair.id)

        val distance = first.location.distance(second.location)
        if (isExplosionDistance(distance)) {
            explodePair(player, state, pair, first, second)
            return
        }

        val eachMovement = min(
            pair.speedPerTick * MOVE_INTERVAL_TICKS,
            max(0.0, (distance - EXPLODE_DISTANCE) / 2.0)
        )
        if (eachMovement <= 0.0) {
            explodePair(player, state, pair, first, second)
            return
        }

        val firstOld = first.location.clone()
        val secondOld = second.location.clone()
        first.location.add(second.location.toVector().subtract(first.location.toVector()).normalize().multiply(eachMovement))
        second.location.add(firstOld.toVector().subtract(second.location.toVector()).normalize().multiply(eachMovement))
        first.display.teleport(first.location)
        second.display.teleport(second.location)
        spawnTrail(first)
        spawnTrail(second)
        spawnCollisionLink(first, second)

        damagePath(player, pair, firstOld, first.location)
        damagePath(player, pair, secondOld, second.location)
        pair.speedPerTick = min(
            MAX_MOVE_SPEED_PER_TICK,
            pair.speedPerTick + MOVE_ACCELERATION_PER_TICK * MOVE_INTERVAL_TICKS
        )
        val newDistanceSquared = first.location.distanceSquared(second.location)
        if (newDistanceSquared <= EXPLODE_DISTANCE_WITH_TOLERANCE * EXPLODE_DISTANCE_WITH_TOLERANCE ||
            newDistanceSquared >= distance * distance - MIN_PROGRESS_SQUARED
        ) {
            explodePair(player, state, pair, first, second)
        }
    }

    private fun isExplosionDistance(distance: Double): Boolean = distance <= EXPLODE_DISTANCE_WITH_TOLERANCE

    private fun applyLandingDamage(player: Player, location: Location) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val damage = data.zfStr * LANDING_DAMAGE_MULTIPLIER
        forEachTarget(location, LANDING_DAMAGE_RADIUS) { target ->
            FormationMagicDamage.deal(plugin, player, target, damage)
        }
    }

    private fun damagePath(player: Player, pair: CollisionPair, start: Location, end: Location) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val midpoint = start.clone().add(end).multiply(0.5)
        val x = abs(end.x - start.x) / 2.0 + PATH_HIT_RADIUS
        val y = abs(end.y - start.y) / 2.0 + PATH_HIT_RADIUS
        val z = abs(end.z - start.z) / 2.0 + PATH_HIT_RADIUS
        val radiusSquared = PATH_HIT_RADIUS * PATH_HIT_RADIUS
        for (entity in start.world.getNearbyEntities(midpoint, x, y, z)) {
            val target = entity as? LivingEntity ?: continue
            if (!isValidTarget(target) || target.uniqueId in pair.hitTargets) continue
            val point = target.location.clone().add(0.0, target.height * 0.5, 0.0).toVector()
            if (distanceToSegmentSquared(point, start.toVector(), end.toVector()) > radiusSquared) continue
            pair.hitTargets.add(target.uniqueId)
            FormationMagicDamage.deal(plugin, player, target, data.zfStr * PATH_DAMAGE_MULTIPLIER)
        }
    }

    private fun explodePair(player: Player, state: PlayerState, pair: CollisionPair, first: Piece, second: Piece) {
        val center = first.location.clone().add(second.location).multiply(0.5)
        val data = plugin.playerManager.getData(player.uniqueId)
        val damageMultiplier = explosionDamageMultiplier(pair.initialDistance)
        val damage = data?.zfStr?.times(damageMultiplier) ?: 0.0
        val slow = PotionEffect(
            PotionEffectType.SLOWNESS,
            EXPLOSION_SLOW_DURATION_TICKS,
            EXPLOSION_SLOW_AMPLIFIER,
            false,
            true,
            true
        )
        forEachTarget(center, EXPLOSION_RADIUS) { target ->
            if (damage > 0.0) FormationMagicDamage.deal(plugin, player, target, damage)
            target.addPotionEffect(slow)
        }

        state.casts[pair.castId]?.resolved = true
        spawnExplosionEffect(center)
        center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.25f, 0.78f)
        center.world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.05f, 0.55f)
        center.world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 1.35f)
        removePair(state, pair.id)
        state.casts.remove(pair.castId)
    }

    private fun explosionDamageMultiplier(initialDistance: Double): Double = when {
        initialDistance <= NEAR_DAMAGE_MAX_DISTANCE -> NEAR_EXPLOSION_DAMAGE_MULTIPLIER
        initialDistance <= MEDIUM_DAMAGE_MAX_DISTANCE -> MEDIUM_EXPLOSION_DAMAGE_MULTIPLIER
        else -> FAR_EXPLOSION_DAMAGE_MULTIPLIER
    }

    private inline fun forEachTarget(center: Location, radius: Double, action: (LivingEntity) -> Unit) {
        val radiusSquared = radius * radius
        for (entity in center.world.getNearbyEntities(center, radius, radius, radius)) {
            val target = entity as? LivingEntity ?: continue
            if (!isValidTarget(target) || target.location.distanceSquared(center) > radiusSquared) continue
            action(target)
        }
    }

    private fun startCooldown(player: Player, state: PlayerState, durationMillis: Long) {
        state.cooldownEndsAt = System.currentTimeMillis() + durationMillis
        persistCooldown(state)
        applyVisualCooldown(player, state)
    }

    private fun refundCast(player: Player, state: PlayerState, cast: CastSession) {
        if (cast.refunded || cast.resolved) return
        cast.refunded = true

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            data.lingli = min(data.maxLingli, data.lingli + cast.manaCost * FAILURE_MANA_REFUND_PERCENT)
            plugin.databaseManager.queuePlayerSave(data)
        }

        val cooldownRefundMillis = (cast.cooldownDurationMillis * FAILURE_COOLDOWN_REFUND_PERCENT).toLong()
        state.cooldownEndsAt = max(System.currentTimeMillis(), state.cooldownEndsAt - cooldownRefundMillis)
        persistCooldown(state)
        applyVisualCooldown(player, state)
    }

    private fun failCast(player: Player, state: PlayerState, castId: UUID, message: String) {
        val cast = state.casts[castId] ?: return
        val failureLocation = cast.lastKnownLocation(state)?.clone()
        refundCast(player, state, cast)
        if (state.pendingCastId == castId) state.pendingCastId = null
        removeCastPieces(state, castId)
        state.casts.remove(castId)
        spawnFailureEffect(failureLocation)
        player.sendMessage(message)
        sendManaActionBar(player)
    }

    private fun applyVisualCooldown(player: Player, state: PlayerState) {
        val remainingMillis = (state.cooldownEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val ticks = ceil(remainingMillis / 50.0).toInt()
        player.setCooldown(state.item, ticks)
    }

    private fun spawnPieceDisplay(location: Location, color: PieceColor): ItemDisplay? = try {
        location.world.spawn(location, ItemDisplay::class.java) { display ->
            display.setItemStack(ItemStack(color.material))
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED)
            display.setTransformation(
                Transformation(
                    Vector3f(0f, 0f, 0f),
                    AxisAngle4f(0f, 0f, 1f, 0f),
                    Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                    AxisAngle4f(0f, 0f, 1f, 0f)
                )
            )
            display.setGravity(false)
            display.isPersistent = false
            display.isInvulnerable = true
            display.teleportDuration = MOVE_INTERVAL_TICKS.toInt()
        }
    } catch (error: Throwable) {
        plugin.logger.warning("天河弈棋子显示生成失败：${error.message}")
        null
    }

    private fun findPlacement(player: Player): Location {
        val world = player.world
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val trace = world.rayTrace(
            eye,
            direction,
            MAX_CAST_RANGE,
            FluidCollisionMode.NEVER,
            true,
            0.35
        ) { entity: Entity -> entity is LivingEntity && isValidTarget(entity) }

        val raw = when {
            trace?.hitEntity is LivingEntity -> trace.hitEntity!!.location
            trace?.hitPosition != null -> trace.hitPosition!!.toLocation(world)
            else -> eye.clone().add(direction.multiply(MAX_CAST_RANGE))
        }
        val downStart = raw.clone().add(0.0, 1.0, 0.0)
        val ground = world.rayTraceBlocks(downStart, Vector(0.0, -1.0, 0.0), 8.0, FluidCollisionMode.NEVER, true)
        val base = ground?.hitPosition?.toLocation(world) ?: raw
        return base.add(0.0, DISPLAY_HEIGHT, 0.0)
    }

    private fun spawnLandingEffect(piece: Piece) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_LANDING, piece.location, data = if (piece.color == PieceColor.BLACK) 0 else 1)
    }

    private fun spawnOrbitParticle(piece: Piece) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_ORBIT, piece.location, data = if (piece.color == PieceColor.BLACK) 0 else 1)
    }

    private fun spawnTrail(piece: Piece) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_TRAIL, piece.location, data = if (piece.color == PieceColor.BLACK) 0 else 1)
    }

    private fun spawnCollisionLink(first: Piece, second: Piece) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_LINK, first.location, second.location)
    }

    private fun spawnExplosionEffect(center: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_EXPLOSION, center)
    }

    private fun spawnFailureEffect(location: Location?) {
        location ?: return
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.TIANHE_FAILURE, location)
        location.world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.55f, 0.72f)
    }

    private fun removePair(state: PlayerState, pairId: UUID) {
        val pair = state.pairs.remove(pairId) ?: return
        removePiece(state, pair.firstId, removePair = false)
        removePiece(state, pair.secondId, removePair = false)
    }

    private fun removePiece(state: PlayerState, pieceId: UUID, removePair: Boolean = true) {
        val piece = state.pieces.remove(pieceId) ?: return
        if (removePair) piece.pairId?.let { removePair(state, it) }
        if (piece.display.isValid) piece.display.remove()
    }

    private fun removeCastPieces(state: PlayerState, castId: UUID) {
        state.pieces.values.filter { it.castId == castId }.map { it.id }.forEach { removePiece(state, it) }
    }

    private fun clearField(state: PlayerState) {
        state.pieces.values.forEach { if (it.display.isValid) it.display.remove() }
        state.pieces.clear()
        state.pairs.clear()
        state.casts.clear()
        state.pendingCastId = null
    }

    private fun detach(uuid: UUID, player: Player?) {
        val state = states.remove(uuid) ?: return
        if (player != null) {
            if (state.pendingCastId != null && state.cooldownEndsAt <= System.currentTimeMillis()) {
                val pendingDuration = state.pendingCastId?.let(state.casts::get)?.cooldownDurationMillis
                    ?: ArtifactCooldownGroups.reducedDurationMillis(plugin, player, BASE_COOLDOWN_MILLIS)
                state.cooldownEndsAt = System.currentTimeMillis() + pendingDuration
            }
            state.casts.values.filter { !it.resolved }.toList().forEach { cast -> refundCast(player, state, cast) }
        }
        persistCooldown(state)
        if (player != null) {
            matchingInstanceItems(player, state.instanceId).forEach { persistCooldownInto(it, state.cooldownEndsAt) }
        }
        clearField(state)
    }

    private fun persistCooldown(state: PlayerState) {
        persistCooldownInto(state.item, state.cooldownEndsAt)
    }

    private fun persistCooldownInto(item: ItemStack, cooldownEndsAt: Long) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(cooldownEndKey, PersistentDataType.LONG, cooldownEndsAt)
        item.itemMeta = meta
    }

    private fun readInstanceId(item: ItemStack): String? = item.itemMeta?.persistentDataContainer
        ?.get(instanceKey, PersistentDataType.STRING)

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
        for (legacyKey in listOf(legacyBlackCountKey, legacyWhiteCountKey, legacySelectedColorKey)) {
            if (pdc.has(legacyKey)) {
                pdc.remove(legacyKey)
                changed = true
            }
        }
        if (changed) item.itemMeta = meta
    }

    private fun sendManaActionBar(player: Player, manaCost: Double? = null) {
        val data = plugin.playerManager.getData(player.uniqueId)
        val mana = data?.lingli ?: 0.0
        val maxMana = data?.maxLingli ?: 0.0
        val costText = manaCost?.takeIf { it > 0.0 }?.let { " &c(-${format(it)})" } ?: ""
        val message = "&6☯当前灵力值：&b${String.format(Locale.US, "%.1f", mana)}$costText &6/ &b${String.format(Locale.US, "%.0f", maxMana)} &6☯"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun sendCooldownActionBar(player: Player, state: PlayerState) {
        val remaining = formatSeconds(state.cooldownEndsAt - System.currentTimeMillis())
        val message = "&c&l弈定天河 阵法冷却中，剩余 $remaining 秒"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun distanceToSegmentSquared(point: Vector, start: Vector, end: Vector): Double {
        val segment = end.clone().subtract(start)
        val lengthSquared = segment.lengthSquared()
        if (lengthSquared <= 1.0E-9) return point.distanceSquared(start)
        val t = point.clone().subtract(start).dot(segment) / lengthSquared
        val closest = start.clone().add(segment.multiply(t.coerceIn(0.0, 1.0)))
        return point.distanceSquared(closest)
    }

    private fun CastSession.lastKnownLocation(state: PlayerState): Location? =
        whiteId?.let(state.pieces::get)?.location ?: state.pieces[blackId]?.location

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun formatSeconds(millis: Long): String =
        String.format(Locale.US, "%.1f", millis.coerceAtLeast(0L) / 1000.0)

    private data class PlayerState(
        var item: ItemStack,
        val instanceId: String,
        var cooldownEndsAt: Long,
        var pendingCastId: UUID? = null,
        val casts: MutableMap<UUID, CastSession> = mutableMapOf(),
        val pieces: MutableMap<UUID, Piece> = mutableMapOf(),
        val pairs: MutableMap<UUID, CollisionPair> = mutableMapOf()
    )

    private data class CastSession(
        val id: UUID,
        val manaCost: Double,
        val blackId: UUID,
        val whiteWindowEndsAt: Long,
        val cooldownDurationMillis: Long,
        var whiteId: UUID? = null,
        var resolved: Boolean = false,
        var refunded: Boolean = false
    )

    private data class Piece(
        val id: UUID,
        val castId: UUID,
        val color: PieceColor,
        val display: ItemDisplay,
        val location: Location,
        val expiresAt: Long,
        var pairId: UUID? = null
    )

    private data class CollisionPair(
        val id: UUID,
        val castId: UUID,
        val firstId: UUID,
        val secondId: UUID,
        val initialDistance: Double,
        var speedPerTick: Double = INITIAL_MOVE_SPEED_PER_TICK,
        val hitTargets: MutableSet<UUID> = hashSetOf()
    )

    private enum class PieceColor(val material: Material) {
        BLACK(Material.BLACK_WOOL),
        WHITE(Material.WHITE_WOOL)
    }

    companion object {
        const val ARTIFACT_ID = "tianheyi"
        private const val ACTIVATE_SLOT = 7
        private const val DEFAULT_MANA_COST = 16.0
        private const val BASE_COOLDOWN_MILLIS = 7_000L
        private const val WHITE_WINDOW_MILLIS = 3_000L
        private const val FAILURE_MANA_REFUND_PERCENT = 0.50
        private const val FAILURE_COOLDOWN_REFUND_PERCENT = 0.50
        private const val MAX_CAST_RANGE = 20.0
        private const val PIECE_LIFETIME_TICKS = 140L
        private const val LANDING_DAMAGE_RADIUS = 3.0
        private const val LANDING_DAMAGE_MULTIPLIER = 1.20
        private const val PAIR_DISTANCE_SQUARED = 20.0 * 20.0
        private const val EXPLODE_DISTANCE = 2.0
        private const val EXPLODE_DISTANCE_WITH_TOLERANCE = 2.05
        private const val MIN_PROGRESS_SQUARED = 1.0E-6
        private const val MOVE_INTERVAL_TICKS = 2L
        private const val INITIAL_MOVE_SPEED_PER_TICK = 0.35
        private const val MOVE_ACCELERATION_PER_TICK = 0.04
        private const val MAX_MOVE_SPEED_PER_TICK = 0.85
        private const val PATH_HIT_RADIUS = 1.25
        private const val PATH_DAMAGE_MULTIPLIER = 1.0
        private const val EXPLOSION_RADIUS = 10.0
        private const val NEAR_DAMAGE_MAX_DISTANCE = 8.0
        private const val MEDIUM_DAMAGE_MAX_DISTANCE = 14.0
        private const val NEAR_EXPLOSION_DAMAGE_MULTIPLIER = 3.0
        private const val MEDIUM_EXPLOSION_DAMAGE_MULTIPLIER = 4.0
        private const val FAR_EXPLOSION_DAMAGE_MULTIPLIER = 5.0
        private const val EXPLOSION_SLOW_DURATION_TICKS = 100
        private const val EXPLOSION_SLOW_AMPLIFIER = 2
        private const val PARTICLE_INTERVAL_TICKS = 3L
        private const val DISPLAY_HEIGHT = 0.45
        private const val DISPLAY_SCALE = 0.82f
        private val BLACK_DUST = Particle.DustOptions(Color.fromRGB(24, 25, 31), 1.2f)
        private val WHITE_DUST = Particle.DustOptions(Color.fromRGB(235, 242, 255), 1.2f)

        fun initializeNewItem(plugin: Hjh_database, item: ItemStack) {
            ArtifactCooldownGroups.ensure(plugin, item, ARTIFACT_ID)
            val meta = item.itemMeta ?: return
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "tianheyi_instance"), PersistentDataType.STRING, UUID.randomUUID().toString())
            pdc.set(NamespacedKey(plugin, "tianheyi_cooldown_end"), PersistentDataType.LONG, 0L)
            item.itemMeta = meta
        }
    }
}
