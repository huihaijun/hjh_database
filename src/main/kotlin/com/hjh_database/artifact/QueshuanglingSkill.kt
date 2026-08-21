package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import com.hjh_database.listener.CombatListener
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
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
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

class QueshuanglingSkill(private val plugin: Hjh_database) : Listener {
    private val instanceKey = NamespacedKey(plugin, "queshuangling_instance")
    private val cooldownEndKey = NamespacedKey(plugin, "queshuangling_cooldown_end")
    private val arrowSessionKey = NamespacedKey(plugin, "queshuangling_arrow_session")
    private val arrowIndexKey = NamespacedKey(plugin, "queshuangling_arrow_index")
    private val arrowProcessedKey = NamespacedKey(plugin, "queshuangling_arrow_processed")

    private val sessions = hashMapOf<UUID, WeaveSession>()
    private val finishingPlayers = hashSetOf<UUID>()
    private val activeFeatherDisplays = hashSetOf<ItemDisplay>()
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
        if (sessions.containsKey(player.uniqueId) || player.uniqueId in finishingPlayers) {
            player.sendMessage("§7法器技【星羽织弦】正在等待两支箭矢落定。")
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val manaCost = artifact.manaCost
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，鹊双翎需要 ${format(manaCost)} 点灵力。")
            return
        }

        val instanceId = readInstanceId(item) ?: return
        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        sessions[player.uniqueId] = WeaveSession(
            id = UUID.randomUUID(),
            ownerId = player.uniqueId,
            item = item,
            instanceId = instanceId,
            archerDamage = data.archerDamage,
            expiresAt = now + TOTAL_TIMEOUT_MILLIS
        )
        sendActivationActionBar(player, manaCost)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.65f)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_MARK, player.location.clone().add(0.0, 1.15, 0.0), data = 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val arrow = event.projectile as? AbstractArrow ?: return
        val session = sessions[player.uniqueId] ?: return
        val now = System.currentTimeMillis()
        val shotTick = Bukkit.getCurrentTick().toLong()
        val isMultishot = event.bow?.let { bow ->
            bow.type == Material.CROSSBOW && bow.containsEnchantment(Enchantment.MULTISHOT)
        } == true

        if (now > session.expiresAt) {
            failSession(player.uniqueId, "§c法器技【星羽织弦】已超时。")
            return
        }
        // 同一游戏刻发射的散射主副箭属于同一轮，共享一根翎羽的序号。
        if (isMultishot && session.multishotVolleyTick == shotTick && session.multishotVolleyIndex > 0) {
            markArrow(session, arrow, session.multishotVolleyIndex)
            return
        }
        if (session.launchedArrows >= REQUIRED_ARROWS) return
        if (session.firstShotAt > 0L && now - session.firstShotAt > SHOT_INTERVAL_MILLIS) {
            failSession(player.uniqueId, "§c两支箭矢的射出间隔超过5秒，星羽织弦消散。")
            return
        }

        if (session.firstShotAt == 0L) session.firstShotAt = now
        val arrowIndex = ++session.launchedArrows
        if (isMultishot) {
            session.multishotVolleyTick = shotTick
            session.multishotVolleyIndex = arrowIndex
        }
        markArrow(session, arrow, arrowIndex)
        spawnArrowMarkedEffect(player, arrowIndex)
    }

    /**
     * 部分弩型武器只为散射主箭触发 EntityShootBowEvent；三支箭仍都会触发投射物生成事件。
     * 在同一游戏刻内把漏掉的副箭补进当前散射轮，使任意一支最先命中时都能落下翎羽。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val player = arrow.shooter as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (session.multishotVolleyTick != Bukkit.getCurrentTick().toLong()) return
        val arrowIndex = session.multishotVolleyIndex
        if (arrowIndex !in 1..REQUIRED_ARROWS) return
        markArrow(session, arrow, arrowIndex)
    }

    private fun markArrow(session: WeaveSession, arrow: AbstractArrow, arrowIndex: Int) {
        val pdc = arrow.persistentDataContainer
        if (pdc.get(arrowSessionKey, PersistentDataType.STRING) == session.id.toString()) return
        session.arrowIds.add(arrow.uniqueId)
        arrow.persistentDataContainer.set(arrowSessionKey, PersistentDataType.STRING, session.id.toString())
        arrow.persistentDataContainer.set(arrowIndexKey, PersistentDataType.INTEGER, arrowIndex)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val pdc = arrow.persistentDataContainer
        if (pdc.has(arrowProcessedKey, PersistentDataType.BYTE)) return
        val sessionId = pdc.get(arrowSessionKey, PersistentDataType.STRING)?.let(::parseUuid) ?: return
        val arrowIndex = pdc.get(arrowIndexKey, PersistentDataType.INTEGER) ?: return
        val player = arrow.shooter as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (session.id != sessionId || arrowIndex !in 1..REQUIRED_ARROWS) return
        if (System.currentTimeMillis() > session.expiresAt) {
            failSession(player.uniqueId, "§c法器技【星羽织弦】已超时。")
            return
        }
        if (session.feathers.containsKey(arrowIndex)) return

        pdc.set(arrowProcessedKey, PersistentDataType.BYTE, 1.toByte())
        val target = (event.hitEntity as? LivingEntity)?.takeIf(::isValidTarget)
        val location = if (target != null) {
            target.location.clone().add(0.0, target.height * FEATHER_ENTITY_HEIGHT_FACTOR, 0.0)
        } else {
            arrow.location.clone().add(0.0, FEATHER_GROUND_HEIGHT, 0.0)
        }
        val feather = spawnFeather(location, target, session.item)
        if (feather == null) {
            failSession(player.uniqueId, "§c翎羽生成失败，法器技已取消。")
            return
        }
        session.feathers[arrowIndex] = feather
        spawnFeatherAttachEffect(location, arrowIndex)

        if (session.feathers.size >= REQUIRED_ARROWS) triggerWeave(player, session)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        failSession(event.player.uniqueId, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        failSession(event.entity.uniqueId, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        failSession(event.player.uniqueId, null)
    }

    fun shutdown() {
        ticker.cancel()
        sessions.keys.toList().forEach { failSession(it, null) }
        finishingPlayers.clear()
        activeFeatherDisplays.toList().forEach(::removeFeatherDisplay)
    }

    private fun tick() {
        tickCounter++
        val now = System.currentTimeMillis()
        for (session in sessions.values.toList()) {
            if (now > session.expiresAt ||
                (session.firstShotAt > 0L && session.launchedArrows < REQUIRED_ARROWS && now - session.firstShotAt > SHOT_INTERVAL_MILLIS)
            ) {
                failSession(session.ownerId, "§c法器技【星羽织弦】未能及时完成，翎羽已经消散。")
                continue
            }
            updateFeathers(session)
        }
    }

    private fun updateFeathers(session: WeaveSession) {
        for ((index, feather) in session.feathers) {
            val attached = feather.attachedEntityId?.let(Bukkit::getEntity) as? LivingEntity
            if (attached != null && attached.isValid && !attached.isDead) {
                feather.location = attached.location.clone().add(0.0, attached.height * FEATHER_ENTITY_HEIGHT_FACTOR, 0.0)
                feather.display.teleport(feather.location)
            } else if (feather.attachedEntityId != null) {
                feather.attachedEntityId = null
            }
            if (tickCounter % FEATHER_PARTICLE_INTERVAL_TICKS == 0L) spawnFeatherAmbient(feather.location, index)
        }
    }

    private fun spawnFeather(location: Location, target: LivingEntity?, artifactItem: ItemStack): Feather? = try {
        val displayItem = ItemStack(artifactItem.type).also { item ->
            val customModelData = artifactItem.itemMeta?.takeIf { it.hasCustomModelData() }?.customModelData
            if (customModelData != null) {
                val meta = item.itemMeta
                meta?.setCustomModelData(customModelData)
                item.itemMeta = meta
            }
        }
        val display = location.world.spawn(location, ItemDisplay::class.java) { entity ->
            entity.setItemStack(displayItem)
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED)
            entity.billboard = Display.Billboard.CENTER
            entity.setTransformation(
                Transformation(
                    Vector3f(0f, 0f, 0f),
                    AxisAngle4f(0f, 0f, 1f, 0f),
                    Vector3f(FEATHER_SCALE, FEATHER_SCALE, FEATHER_SCALE),
                    AxisAngle4f(0f, 0f, 1f, 0f)
                )
            )
            entity.setGravity(false)
            entity.isInvulnerable = true
            entity.isPersistent = false
            entity.isGlowing = true
            entity.teleportDuration = 1
        }
        activeFeatherDisplays.add(display)
        Feather(display, location.clone(), target?.uniqueId)
    } catch (error: Throwable) {
        plugin.logger.warning("鹊双翎翎羽实体生成失败：${error.message}")
        null
    }

    private fun triggerWeave(player: Player, session: WeaveSession) {
        val first = session.feathers[1]?.currentLocation() ?: return
        val second = session.feathers[2]?.currentLocation() ?: return
        if (first.world != second.world) {
            failSession(player.uniqueId, "§c两根翎羽不在同一世界，星羽织弦失败。")
            return
        }

        updateFeathers(session)
        val start = session.feathers[1]!!.currentLocation()
        val end = session.feathers[2]!!.currentLocation()
        val targets = findWeaveTargets(start, end)
        var restoredMana = 0.0
        for (target in targets) {
            val wasAlive = target.isValid && !target.isDead
            dealPhysicalSkillDamage(player, target, session.archerDamage * ARROW_DAMAGE_MULTIPLIER)
            if (target.isValid && !target.isDead) {
                val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 0.0
                val piercingDamage = min(maxHealth * MAX_HEALTH_DAMAGE_PERCENT, MAX_HEALTH_DAMAGE_CAP)
                dealArmorPiercingDamage(player, target, piercingDamage)
            }
            if (wasAlive && (target.isDead || !target.isValid || target.health <= 0.0) && restoredMana < MAX_MANA_RESTORE) {
                restoredMana = min(MAX_MANA_RESTORE, restoredMana + MANA_PER_KILL)
            }
        }

        if (restoredMana > 0.0) {
            val data = plugin.playerManager.getData(player.uniqueId)
            if (data != null) {
                data.addLingli(restoredMana)
                plugin.databaseManager.queuePlayerSave(data)
            }
        }

        playWeaveEffect(start, end)
        sessions.remove(player.uniqueId)
        finishingPlayers.add(player.uniqueId)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            startCooldown(player, session)
            finishingPlayers.remove(player.uniqueId)
        }, WEAVE_EFFECT_FRAMES.toLong())
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            session.feathers.values.forEach { removeFeatherDisplay(it.display) }
            session.arrowIds.forEach { (Bukkit.getEntity(it) as? AbstractArrow)?.remove() }
        }, FEATHER_REMOVE_DELAY_TICKS)
    }

    private fun findWeaveTargets(start: Location, end: Location): List<LivingEntity> {
        val world = start.world
        val midpoint = start.clone().add(end).multiply(0.5)
        val xRadius = abs(end.x - start.x) * 0.5 + WEAVE_HIT_RADIUS + 1.0
        val yRadius = abs(end.y - start.y) * 0.5 + WEAVE_HIT_RADIUS + 1.0
        val zRadius = abs(end.z - start.z) * 0.5 + WEAVE_HIT_RADIUS + 1.0
        val startVector = start.toVector()
        val endVector = end.toVector()
        val radiusSquared = WEAVE_HIT_RADIUS * WEAVE_HIT_RADIUS
        return world.getNearbyEntities(midpoint, xRadius, yRadius, zRadius).asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter(::isValidTarget)
            .filter { target ->
                val center = target.location.clone().add(0.0, target.height * 0.5, 0.0).toVector()
                distanceToSegmentSquared(center, startVector, endVector) <= radiusSquared
            }
            .distinctBy { it.uniqueId }
            .toList()
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

    private fun dealArmorPiercingDamage(player: Player, target: LivingEntity, amount: Double) {
        if (amount <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata(MAGIC_DAMAGE_METADATA, FixedMetadataValue(plugin, amount))
        target.setMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, FixedMetadataValue(plugin, 1.0))
        target.noDamageTicks = 0
        try {
            target.damage(0.01, player)
        } finally {
            target.removeMetadata(MAGIC_DAMAGE_METADATA, plugin)
            target.removeMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, plugin)
            if (target.isValid && !target.isDead) target.noDamageTicks = 0
        }
    }

    private fun playWeaveEffect(start: Location, end: Location) {
        val world = start.world
        world.playSound(start, Sound.BLOCK_BEACON_ACTIVATE, 1.1f, 1.65f)
        world.playSound(end, Sound.ITEM_TRIDENT_THUNDER, 0.75f, 1.8f)
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_WEAVE, start, end)
    }

    private fun drawWeaveFrame(start: Location, end: Location, frame: Int) {
        if (frame == 0) plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_WEAVE, start, end)
    }

    private fun perpendicular(direction: Vector): Vector {
        val side = direction.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        return if (side.lengthSquared() < 1.0E-6) Vector(1.0, 0.0, 0.0) else side.normalize()
    }

    private fun spawnArrowMarkedEffect(player: Player, index: Int) {
        val location = player.eyeLocation.clone().add(player.eyeLocation.direction.normalize().multiply(0.75))
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_MARK, location, data = index)
        player.world.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 0.75f, if (index == 1) 1.35f else 1.7f)
    }

    private fun spawnFeatherAttachEffect(location: Location, index: Int) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_ATTACH, location, data = index)
        location.world.playSound(location, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.9f, if (index == 1) 1.25f else 1.65f)
    }

    private fun spawnFeatherAmbient(location: Location, index: Int) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_AMBIENT, location, data = index)
    }

    private fun startCooldown(player: Player, session: WeaveSession) {
        val cooldownDuration = ArtifactCooldownGroups.reducedDurationMillis(plugin, player, BASE_COOLDOWN_MILLIS)
        val cooldownEndsAt = System.currentTimeMillis() + cooldownDuration
        val matches = matchingInstanceItems(player, session.instanceId)
        if (matches.isEmpty()) {
            writeCooldownEnd(session.item, cooldownEndsAt)
        } else {
            matches.forEach { writeCooldownEnd(it, cooldownEndsAt) }
        }
        player.setCooldown(session.item, (cooldownDuration / 50L).toInt().coerceAtLeast(1))
    }

    private fun failSession(playerId: UUID, message: String?) {
        val session = sessions.remove(playerId) ?: return
        session.feathers.values.forEach { feather ->
            spawnFeatherFadeEffect(feather.currentLocation())
            removeFeatherDisplay(feather.display)
        }
        session.arrowIds.forEach { arrowId -> (Bukkit.getEntity(arrowId) as? AbstractArrow)?.remove() }
        if (message != null) Bukkit.getPlayer(playerId)?.sendMessage(message)
    }

    private fun spawnFeatherFadeEffect(location: Location) {
        plugin.clientBridge.emitParticle(com.hjh_database.client.ClientParticleEffect.QUESHUANG_FADE, location)
    }

    private fun removeFeatherDisplay(display: ItemDisplay) {
        activeFeatherDisplays.remove(display)
        if (display.isValid) display.remove()
    }

    private fun matchingInstanceItems(player: Player, instanceId: String): List<ItemStack> {
        val matches = mutableListOf<ItemStack>()
        player.inventory.contents.filterNotNull().filterTo(matches) { readInstanceId(it) == instanceId }
        player.itemOnCursor.takeUnless { it.type.isAir }?.let { if (readInstanceId(it) == instanceId) matches.add(it) }
        player.openInventory.topInventory.contents.filterNotNull().filterTo(matches) { readInstanceId(it) == instanceId }
        return matches
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

    private fun sendActivationActionBar(player: Player, manaCost: Double) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val message = "&a&l法器技【星羽织弦】发动！ &6☯当前灵力值：&b${String.format(Locale.US, "%.1f", data.lingli)} &c(-${format(manaCost)}) &6/ &b${String.format(Locale.US, "%.0f", data.maxLingli)} &6☯"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun sendCooldownActionBar(player: Player, remainingMillis: Long) {
        val seconds = String.format(Locale.US, "%.1f", remainingMillis.coerceAtLeast(0L) / 1000.0)
        val message = "&c&l法器技【星羽织弦】处于冷却中，剩余${seconds}秒"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun distanceToSegmentSquared(point: Vector, start: Vector, end: Vector): Double {
        val segment = end.clone().subtract(start)
        val lengthSquared = segment.lengthSquared()
        if (lengthSquared <= 1.0E-9) return point.distanceSquared(start)
        val t = point.clone().subtract(start).dot(segment) / lengthSquared
        val closest = start.clone().add(segment.multiply(t.coerceIn(0.0, 1.0)))
        return point.distanceSquared(closest)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity.isValid && !entity.isDead && tags.contains("panling") && tags.contains("monster")
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Feather.currentLocation(): Location {
        val attached = attachedEntityId?.let(Bukkit::getEntity) as? LivingEntity
        if (attached != null && attached.isValid && !attached.isDead) {
            location = attached.location.clone().add(0.0, attached.height * FEATHER_ENTITY_HEIGHT_FACTOR, 0.0)
        }
        return location.clone()
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private data class WeaveSession(
        val id: UUID,
        val ownerId: UUID,
        val item: ItemStack,
        val instanceId: String,
        val archerDamage: Double,
        val expiresAt: Long,
        var firstShotAt: Long = 0L,
        var launchedArrows: Int = 0,
        var multishotVolleyTick: Long = Long.MIN_VALUE,
        var multishotVolleyIndex: Int = 0,
        val arrowIds: MutableSet<UUID> = hashSetOf(),
        val feathers: MutableMap<Int, Feather> = hashMapOf()
    )

    private data class Feather(
        val display: ItemDisplay,
        var location: Location,
        var attachedEntityId: UUID?
    )

    companion object {
        const val ARTIFACT_ID = "queshuangling"
        private const val ACTIVATE_SLOT = 7
        private const val BASE_COOLDOWN_MILLIS = 12_000L
        private const val TOTAL_TIMEOUT_MILLIS = 10_000L
        private const val SHOT_INTERVAL_MILLIS = 5_000L
        private const val REQUIRED_ARROWS = 2
        private const val ARROW_DAMAGE_MULTIPLIER = 3.50
        private const val MAX_HEALTH_DAMAGE_PERCENT = 0.10
        private const val MAX_HEALTH_DAMAGE_CAP = 100.0
        private const val MANA_PER_KILL = 2.0
        private const val MAX_MANA_RESTORE = 14.0
        private const val WEAVE_HIT_RADIUS = 2.0
        private const val WEAVE_VISUAL_HALF_WIDTH = 2.0
        private const val WEAVE_PARTICLE_STEP = 0.35
        private const val WEAVE_EFFECT_FRAMES = 8
        private const val FEATHER_REMOVE_DELAY_TICKS = 10L
        private const val FEATHER_PARTICLE_INTERVAL_TICKS = 2L
        private const val FEATHER_ENTITY_HEIGHT_FACTOR = 0.65
        private const val FEATHER_GROUND_HEIGHT = 0.25
        private const val FEATHER_SCALE = 0.9f
        private const val PHYSICAL_SKILL_METADATA = "hjh_physical_skill"
        private const val MAGIC_DAMAGE_METADATA = "HJH_MAGIC_DAMAGE"
        private val STAR_CYAN_DUST = Particle.DustOptions(Color.fromRGB(94, 226, 255), 1.15f)
        private val STAR_MAGENTA_DUST = Particle.DustOptions(Color.fromRGB(229, 112, 255), 1.15f)
        private val STAR_GOLD_DUST = Particle.DustOptions(Color.fromRGB(255, 224, 126), 1.05f)

        fun initializeNewItem(plugin: Hjh_database, item: ItemStack) {
            ArtifactCooldownGroups.ensure(plugin, item, ARTIFACT_ID)
            val meta = item.itemMeta ?: return
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "queshuangling_instance"), PersistentDataType.STRING, UUID.randomUUID().toString())
            pdc.set(NamespacedKey(plugin, "queshuangling_cooldown_end"), PersistentDataType.LONG, 0L)
            item.itemMeta = meta
        }
    }
}
