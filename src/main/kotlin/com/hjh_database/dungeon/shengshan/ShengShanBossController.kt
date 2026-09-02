package com.hjh_database.dungeon.shengshan

import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID

private data class SkillWarning(
    val name: String,
    val hintMessage: String,
    val durationTicks: Int,
    val elderHint: Boolean
)

internal abstract class ShengShanBossController(
    protected val manager: ShengShanDungeonManager,
    protected val session: ShengShanSession,
    val trigram: Trigram,
    val boss: LivingEntity
) {
    private enum class TaskScope { BASE, APPEARANCE, NORMAL, ULTIMATE }
    private val tasks = LinkedHashMap<BukkitTask, TaskScope>()
    private val phaseBars = LinkedHashMap<BossBar, TaskScope>()
    private var schedulingScope = TaskScope.BASE
    private val ownedEntities = HashSet<UUID>()
    private val ownedEffectKeys = HashSet<String>()
    protected val terrainGroups = LinkedHashSet<String>()
    private lateinit var bossBar: BossBar
    private var warningBar: BossBar? = null
    private var restoreAiAfterCast = true
    private var restoreInvulnerabilityAfterCast = false
    protected var elapsedTicks = 0
    protected var normalSkillRunning = false
    protected var ultimateActive = false
    private var nextUltimateThresholdIndex = 0
    private var ultimatePending = false
    private var nextSkillAt = Int.MAX_VALUE
    private var nextSkillIndex = 0
    private var echoController: ShengShanEchoController? = null
    var invulnerable: Boolean = true
        protected set
    var incomingDamageMultiplier: Double = 1.0
        protected set

    /** Allows mechanics such as the empowered thunder eye to decide mitigation from the actual attacker. */
    internal open fun damageTakenMultiplier(event: EntityDamageByEntityEvent): Double =
        incomingDamageMultiplier.coerceIn(0.0, 1.0)

    /** Multiples every scripted hit dealt through [dealDamage]. */
    protected open val outgoingDamageMultiplier: Double
        get() = 1.0

    open fun start() {
        // 实体会先于客户端出场演出生成；统一封住原版 AI、碰撞和伤害，直到 reveal()。
        boss.getAttribute(Attribute.FLYING_SPEED)?.baseValue =
            boss.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue ?: 0.3
        boss.setAI(false)
        boss.isInvulnerable = true
        boss.isCollidable = false
        boss.isCustomNameVisible = false
        boss.velocity = Vector()
        (boss as? Mob)?.target = null
        bossBar = manager.createBar(
            session,
            "${trigram.color}§l${trigram.displayName}",
            BarColor.RED,
            BarStyle.SEGMENTED_10
        )
        bossBar.removeAll()
        every(1L, 1L) { tickBase() }
        withScope(TaskScope.APPEARANCE) { onAppearance() }
    }

    protected open fun barColor(): BarColor = when (trigram) {
        Trigram.THUNDER -> BarColor.YELLOW
        Trigram.SKY -> BarColor.WHITE
        Trigram.WATER -> BarColor.BLUE
        Trigram.MOUNTAIN -> BarColor.YELLOW
        Trigram.FIRE -> BarColor.RED
        Trigram.WIND -> BarColor.WHITE
        Trigram.SWAMP -> BarColor.PURPLE
        Trigram.EARTH -> BarColor.PURPLE
    }

    protected abstract fun onAppearance()
    protected abstract fun castNormalSkill(index: Int)
    protected abstract fun startUltimate()

    protected open fun onTick() = Unit
    protected open val forceNearestTarget: Boolean = true
    protected open val normalSkillCooldownTicks: Int = 10 * 20
    protected open val ultimateHealthThresholds: DoubleArray
        get() = doubleArrayOf(0.80, 0.60, 0.40, 0.20)
    protected open fun canCastNormalDuringUltimate(index: Int): Boolean = true
    protected open fun selectNormalSkillIndex(scheduledIndex: Int): Int = scheduledIndex
    protected open fun showGenericNormalParticle(index: Int): Boolean = true
    protected open fun onNormalWarningStarted(index: Int) = Unit
    protected open fun onUltimateWarningStarted() = Unit

    private fun tickBase() {
        if (!boss.isValid || boss.isDead || session.current !== this) return
        elapsedTicks++
        val maximum = boss.getAttribute(Attribute.MAX_HEALTH)?.value?.coerceAtLeast(1.0) ?: 1.0
        val ratio = (boss.health / maximum).coerceIn(0.0, 1.0)
        bossBar.progress = ratio
        bossBar.setTitle("${trigram.color}§l${trigram.displayName}")

        if (forceNearestTarget && elapsedTicks % 10 == 0 && !invulnerable) {
            val target = players().minByOrNull { it.location.distanceSquared(boss.location) }
            (boss as? Mob)?.target = target
        }
        val thresholds = ultimateHealthThresholds
        if (nextUltimateThresholdIndex < thresholds.size && ratio <= thresholds[nextUltimateThresholdIndex]) {
            ultimatePending = true
            // 一次高额伤害跨过多个阈值时只排入一次大技能，多余阈值直接越过。
            while (nextUltimateThresholdIndex < thresholds.size && ratio <= thresholds[nextUltimateThresholdIndex]) {
                nextUltimateThresholdIndex++
            }
        }
        if (ultimatePending && !ultimateActive && !normalSkillRunning) {
                ultimatePending = false
                normalSkillRunning = false
                ultimateActive = true
                invulnerable = true
                cancelWarning()
                boss.velocity = Vector()
                boss.removePotionEffect(PotionEffectType.SLOWNESS)
                boss.removePotionEffect(PotionEffectType.WEAKNESS)
                withScope(TaskScope.ULTIMATE) {
                    message(ULTIMATE_SYSTEM_MESSAGES.getValue(trigram))
                    incomingDamageMultiplier = 1.0
                    beginSkillWarning(ultimateWarning(), { onUltimateWarningStarted() }, invulnerableDuringWarning = true) {
                        emitParticle(ParticleMoment.ULTIMATE)
                        startUltimate()
                    }
                }
        }
        if (!normalSkillRunning && !invulnerable && elapsedTicks >= nextSkillAt) {
            val scheduledIndex = nextSkillIndex
            val index = selectNormalSkillIndex(scheduledIndex)
            if (!ultimateActive || canCastNormalDuringUltimate(index)) {
                normalSkillRunning = true
                incomingDamageMultiplier = 1.0
                nextSkillIndex = 1 - scheduledIndex
                withScope(TaskScope.NORMAL) {
                    beginSkillWarning(normalWarning(index), { onNormalWarningStarted(index) }) {
                        if (showGenericNormalParticle(index)) emitParticle(ParticleMoment.SKILL)
                        castNormalSkill(index)
                    }
                }
            }
        }
        onTick()
    }

    protected fun reveal(initialSkillDelayTicks: Int = 200) {
        boss.isInvisible = false
        boss.isInvulnerable = false
        boss.isCollidable = true
        boss.isCustomNameVisible = true
        boss.setAI(true)
        players().forEach(bossBar::addPlayer)
        invulnerable = false
        nextSkillAt = elapsedTicks + initialSkillDelayTicks
        session.activeEcho?.let { echo ->
            echoController = ShengShanEchoController(manager, session, boss, echo).also { it.start() }
        }
        emitParticle(ParticleMoment.APPEARANCE)
        sound(Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.2f)
    }

    protected fun finishNormalSkill() {
        if (boss.isValid && !boss.isDead) boss.setAI(true)
        invulnerable = false
        incomingDamageMultiplier = 1.0
        normalSkillRunning = false
        nextSkillAt = elapsedTicks + normalSkillCooldownTicks
    }

    /** Completes a one-way transformation without success/failure particles or a break stun. */
    protected fun finishUltimateTransformation() {
        if (!ultimateActive) return
        ultimateActive = false
        cancelWarning()
        cancelScope(TaskScope.ULTIMATE)
        invulnerable = false
        incomingDamageMultiplier = 1.0
        boss.isInvisible = false
        boss.setAI(true)
        boss.velocity = Vector()
        nextSkillAt = elapsedTicks + normalSkillCooldownTicks
    }

    open fun onDamageByEntity(event: EntityDamageByEntityEvent) = Unit
    open fun onEntityDeath(entity: LivingEntity) = Unit
    open fun onProjectileHit(event: ProjectileHitEvent) = Unit
    open fun onProjectileLaunch(event: ProjectileLaunchEvent) = Unit
    open fun onPlayerInteract(event: PlayerInteractEvent): Boolean = false
    open fun onBossDealtDamage(player: Player) = Unit

    internal fun onEchoBossDamaged(event: EntityDamageByEntityEvent) {
        if (!ultimateActive) echoController?.onBossDamaged(event)
    }
    internal fun handleEchoDamageEvent(event: EntityDamageByEntityEvent): Boolean =
        echoController?.handleDamageEvent(event) == true
    internal fun onEchoProjectileHit(event: ProjectileHitEvent) = echoController?.onProjectileHit(event)
    internal fun onEchoBossDealtDamage(player: Player?) {
        echoController?.onBossDealtDamage(player)
        if (player != null) onBossDealtDamage(player)
    }
    internal fun onEchoParticipantDeath() {
        if (!ultimateActive) echoController?.onParticipantDeath()
    }

    open fun removePlayer(player: Player) {
        if (::bossBar.isInitialized) bossBar.removePlayer(player)
    }

    open fun shutdown(restoreTerrain: Boolean) {
        echoController?.shutdown()
        echoController = null
        cancelWarning()
        tasks.keys.forEach(BukkitTask::cancel)
        tasks.clear()
        phaseBars.keys.toList().forEach(::removeBar)
        ownedEffectKeys.toList().forEach(::stopEffect)
        ownedEffectKeys.clear()
        if (::bossBar.isInitialized) {
            bossBar.removeAll()
            session.bars.remove(bossBar)
        }
        ownedEntities.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        ownedEntities.clear()
        if (restoreTerrain) terrainGroups.forEach(manager::restoreTerrain)
    }

    protected fun players(): List<Player> = manager.activePlayers(session)
    protected fun message(text: String) = manager.broadcast(session, text)

    protected fun elder(key: String, text: String, firstOnly: Boolean = true) =
        manager.sendElderHint(session, "${trigram.name}:$key", text, firstOnly)

    protected fun sound(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, location: Location = boss.location) =
        manager.playDungeonSound(session, location, sound, volume, pitch)

    protected fun sound(location: Location, sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f) =
        manager.playDungeonSound(session, location, sound, volume, pitch)

    protected fun soundNearby(location: Location, sound: Sound, radius: Double = 28.0, volume: Float = 1.0f, pitch: Float = 1.0f) =
        manager.playNearbyDungeonSound(session, location, sound, radius, volume, pitch)

    protected fun playEffect(
        effect: ShengShanEffect,
        origin: Location,
        end: Location = origin,
        options: ShengShanEffectOptions = ShengShanEffectOptions()
    ) {
        if (options.key.isNotBlank()) ownedEffectKeys += options.key
        manager.playDungeonEffect(session, effect, origin, end, options)
    }

    protected fun stopEffect(key: String) {
        ownedEffectKeys.remove(key)
        manager.stopDungeonEffect(session, key)
    }

    protected fun spawnVisualItem(
        location: Location,
        material: Material,
        scale: Float = 1.0f,
        kind: String = "visual_item"
    ): ItemDisplay = boss.world.spawn(location, ItemDisplay::class.java) { display ->
        display.setItemStack(ItemStack(material))
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(15, 15)
        display.transformation = Transformation(
            Vector3f(), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f()
        )
        display.isInvulnerable = true
        display.isPersistent = false
        display.setGravity(false)
    }.also {
        own(it)
        manager.trackEntity(session, it, kind)
    }

    protected fun interpolateVisualItem(display: ItemDisplay, destination: Location, durationTicks: Int) {
        if (!display.isValid) return
        display.teleportDuration = durationTicks.coerceIn(0, 59)
        display.teleport(destination)
    }

    protected fun buildAnimated(
        group: String,
        changes: Map<Block, BlockData>,
        batchSize: Int = 12,
        periodTicks: Long = 3L,
        order: Comparator<Map.Entry<Block, BlockData>> = compareBy({ it.key.y }, { it.key.x }, { it.key.z }),
        evictionCenter: Location? = null,
        evictionRadius: Double = 0.0,
        evictionHeight: Double = 0.0,
        onComplete: () -> Unit = {}
    ) {
        if (changes.isEmpty()) return onComplete()
        terrainGroups += group
        manager.prepareTerrain(group, changes.keys)
        val entries = changes.entries.sortedWith(order)
        var cursor = 0
        lateinit var task: BukkitTask
        task = every(0L, periodTicks) {
            if (evictionCenter != null && evictionRadius > 0.0) {
                evictPlayersFromBuild(evictionCenter, evictionRadius, evictionHeight)
            }
            val end = (cursor + batchSize.coerceAtLeast(1)).coerceAtMost(entries.size)
            for (index in cursor until end) {
                val entry = entries[index]
                entry.key.blockData = entry.value
            }
            val focus = entries[(end - 1).coerceAtLeast(0)].key.location.add(.5, .5, .5)
            playEffect(ShengShanEffect.BUILD_GLOW, focus,
                options = ShengShanEffectOptions(radius = 1.8, height = 1.2))
            cursor = end
            if (cursor >= entries.size) {
                task.cancel()
                onComplete()
            }
        }
    }

    private fun evictPlayersFromBuild(center: Location, radius: Double, height: Double) {
        players().filter { player ->
            horizontalDistanceSquared(player.location, center) <= radius * radius &&
                player.location.y + player.height >= center.y - .25 && player.location.y <= center.y + height + .5
        }.forEach { player ->
            val destination = shengShanPlayerStart(player.world)
            player.teleport(destination)
            player.velocity = Vector()
            player.sendMessage("§7艮山的力量把你冲击开了……")
        }
    }

    protected fun collapseAnimated(
        group: String,
        blocks: Collection<Block>,
        batchSize: Int = 16,
        periodTicks: Long = 2L,
        onComplete: () -> Unit = {}
    ) {
        if (blocks.isEmpty()) {
            manager.restoreTerrain(group)
            return onComplete()
        }
        val ordered = blocks.sortedWith(compareByDescending<Block> { it.y }.thenBy { it.x }.thenBy { it.z })
        var cursor = 0
        lateinit var task: BukkitTask
        task = every(0L, periodTicks) {
            val end = (cursor + batchSize.coerceAtLeast(1)).coerceAtMost(ordered.size)
            val batch = ordered.subList(cursor, end)
            val focus = batch.first().location.add(.5, .5, .5)
            manager.restoreTerrainBlocks(group, batch)
            playEffect(ShengShanEffect.COLLAPSE, focus,
                options = ShengShanEffectOptions(radius = 2.3, height = 1.8))
            cursor = end
            if (cursor >= ordered.size) {
                task.cancel()
                manager.restoreTerrain(group)
                onComplete()
            }
        }
    }

    private fun beginSkillWarning(
        warning: SkillWarning,
        onStarted: () -> Unit,
        invulnerableDuringWarning: Boolean = false,
        release: () -> Unit
    ) {
        cancelWarning()
        if (warning.hintMessage.isNotBlank()) {
            if (warning.elderHint) elder("cast:${warning.name}", warning.hintMessage)
            else message("§6${warning.hintMessage}")
        }
        onStarted()
        if (warning.durationTicks <= 0) {
            playCastSound(start = true)
            release()
            return
        }
        restoreAiAfterCast = boss.hasAI()
        restoreInvulnerabilityAfterCast = invulnerable
        invulnerable = invulnerableDuringWarning
        boss.setAI(false)
        boss.velocity = Vector()
        players().minByOrNull { it.location.distanceSquared(boss.location) }?.let { target ->
            val direction = target.location.toVector().subtract(boss.location.toVector())
            boss.teleport(boss.location.clone().apply { this.direction = direction })
        }
        playCastSound(start = true)
        emitParticle(ParticleMoment.SKILL)
        val total = warning.durationTicks.coerceAtLeast(1)
        var remaining = total
        val bar = temporaryBar("${trigram.color}${warning.name}准备中……", barColor(), BarStyle.SOLID)
        warningBar = bar
        lateinit var task: BukkitTask
        task = every(0L, 1L) {
            if (warningBar !== bar) {
                task.cancel()
                return@every
            }
            bar.progress = (remaining / total.toDouble()).coerceIn(0.0, 1.0)
            if (remaining == 20 || remaining == 10 || remaining == 5) playCastSound(start = false)
            remaining--
            if (remaining <= 0) {
                task.cancel()
                warningBar = null
                removeBar(bar)
                if (restoreAiAfterCast) boss.setAI(true)
                invulnerable = restoreInvulnerabilityAfterCast
                playReleaseSound()
                release()
            }
        }
    }

    private fun cancelWarning() {
        warningBar?.let(::removeBar)
        warningBar = null
        if (boss.isValid && !boss.isDead && restoreAiAfterCast) boss.setAI(true)
        invulnerable = restoreInvulnerabilityAfterCast
    }

    protected fun phaseBar(
        title: String,
        durationTicks: Int,
        color: BarColor = barColor(),
        onTick: (remainingTicks: Int) -> Unit = {},
        onComplete: () -> Unit = {}
    ): BossBar {
        val total = durationTicks.coerceAtLeast(1)
        var remaining = total
        val bar = temporaryBar(title, color, BarStyle.SOLID)
        phaseBars[bar] = schedulingScope
        lateinit var task: BukkitTask
        task = every(0L, 1L) {
            if (!session.bars.contains(bar)) {
                task.cancel()
                return@every
            }
            bar.progress = (remaining / total.toDouble()).coerceIn(0.0, 1.0)
            onTick(remaining)
            remaining--
            if (remaining <= 0) {
                task.cancel()
                phaseBars.remove(bar)
                removeBar(bar)
                onComplete()
            }
        }
        return bar
    }

    private fun playCastSound(start: Boolean) {
        val selected = when (trigram) {
            Trigram.THUNDER -> Sound.BLOCK_BEACON_ACTIVATE
            Trigram.SKY -> Sound.ENTITY_GHAST_WARN
            Trigram.WATER -> Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE
            Trigram.MOUNTAIN -> Sound.BLOCK_BASALT_HIT
            Trigram.FIRE -> Sound.ITEM_FIRECHARGE_USE
            Trigram.WIND -> Sound.ENTITY_BREEZE_WHIRL
            Trigram.SWAMP -> Sound.ENTITY_SLIME_SQUISH
            Trigram.EARTH -> Sound.PARTICLE_SOUL_ESCAPE
        }
        sound(selected, if (start) .9f else .55f, if (start) .75f else 1.35f)
    }

    private fun playReleaseSound() {
        val selected = when (trigram) {
            Trigram.THUNDER -> Sound.ENTITY_LIGHTNING_BOLT_THUNDER
            Trigram.SKY -> Sound.ENTITY_GHAST_SHOOT
            Trigram.WATER -> Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED
            Trigram.MOUNTAIN -> Sound.ENTITY_IRON_GOLEM_ATTACK
            Trigram.FIRE -> Sound.ENTITY_BLAZE_SHOOT
            Trigram.WIND -> Sound.ENTITY_BREEZE_WIND_BURST
            Trigram.SWAMP -> Sound.ENTITY_SLIME_JUMP
            Trigram.EARTH -> Sound.BLOCK_SCULK_SHRIEKER_SHRIEK
        }
        sound(selected, 1.1f, .9f)
    }

    private fun normalWarning(index: Int): SkillWarning = NORMAL_SKILL_WARNINGS.getValue(trigram)[index]

    private fun ultimateWarning(): SkillWarning = ULTIMATE_SKILL_WARNINGS.getValue(trigram)

    private enum class ParticleMoment { APPEARANCE, SKILL, ULTIMATE, STUN }

    private fun emitParticle(moment: ParticleMoment) {
        val effect = when (trigram) {
            Trigram.THUNDER -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.THUNDER_BURST else ShengShanEffect.THUNDER_CHARGE
            Trigram.SKY -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.SKY_LIGHT else ShengShanEffect.SKY_CLOUD
            Trigram.WATER -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.WATER_BURST else ShengShanEffect.WATER_MIST
            Trigram.MOUNTAIN -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.GROUND_RUPTURE else ShengShanEffect.MOUNTAIN_DUST
            Trigram.FIRE -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.FIRE_BURST else ShengShanEffect.FIRE_CRACK
            Trigram.WIND -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.WIND_FIELD else ShengShanEffect.WIND_TRAIL
            Trigram.SWAMP -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.POISON_TIDE else ShengShanEffect.SWAMP_BUBBLE
            Trigram.EARTH -> if (moment == ParticleMoment.ULTIMATE) ShengShanEffect.SOUL_FIELD else ShengShanEffect.SOUL_LINK
        }
        val radius = when (moment) {
            ParticleMoment.APPEARANCE -> 2.4
            ParticleMoment.SKILL -> 1.8
            ParticleMoment.ULTIMATE -> 4.5
            ParticleMoment.STUN -> 2.2
        }
        playEffect(effect, boss.location.clone().add(0.0, boss.height * .5, 0.0),
            options = ShengShanEffectOptions(radius = radius, height = radius * .7))
    }

    protected fun later(delay: Long, action: () -> Unit): BukkitTask {
        val scope = schedulingScope
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskLater(manager.plugin, Runnable {
            tasks.remove(task)
            if (session.current === this && boss.isValid && !boss.isDead) withScope(scope, action)
        }, delay.coerceAtLeast(0L))
        tasks[task] = scope
        return task
    }

    protected fun every(delay: Long, period: Long, action: () -> Unit): BukkitTask {
        val scope = schedulingScope
        val task = Bukkit.getScheduler().runTaskTimer(manager.plugin, Runnable {
            if (session.current === this && boss.isValid && !boss.isDead) withScope(scope, action)
        }, delay.coerceAtLeast(0L), period.coerceAtLeast(1L))
        tasks[task] = scope
        return task
    }

    private fun cancelScope(scope: TaskScope) {
        tasks.filterValues { it == scope }.keys.toList().forEach { task ->
            task.cancel()
            tasks.remove(task)
        }
        phaseBars.filterValues { it == scope }.keys.toList().forEach { bar ->
            phaseBars.remove(bar)
            removeBar(bar)
        }
    }

    private inline fun <T> withScope(scope: TaskScope, action: () -> T): T {
        val previous = schedulingScope
        schedulingScope = scope
        return try {
            action()
        } finally {
            schedulingScope = previous
        }
    }

    protected fun own(entity: Entity): Entity {
        ownedEntities += entity.uniqueId
        return entity
    }

    protected fun spawn(mobId: String, location: Location, kind: String = mobId): LivingEntity? =
        manager.spawnMob(session, mobId, location, kind)?.also { ownedEntities += it.uniqueId }

    protected fun temporaryBar(title: String, color: BarColor, style: BarStyle = BarStyle.SEGMENTED_10): BossBar =
        manager.createBar(session, title, color, style).also { phaseBars[it] = schedulingScope }

    protected fun removeBar(bar: BossBar) {
        phaseBars.remove(bar)
        bar.removeAll()
        session.bars.remove(bar)
    }

    protected fun baseArmor(): Double =
        boss.persistentDataContainer.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE) ?: 0.0

    protected fun horizontalDirection(from: Location, to: Location): Vector {
        val vector = to.toVector().subtract(from.toVector()).setY(0.0)
        return if (vector.lengthSquared() < 0.0001) Vector(1.0, 0.0, 0.0) else vector.normalize()
    }

    protected fun horizontalDistanceSquared(a: Location, b: Location): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    protected fun dealDamage(target: LivingEntity, amount: Double, armorPenetration: Double = 0.0) {
        manager.dealPhysicalDamage(target, amount * outgoingDamageMultiplier, boss,
            maxOf(armorPenetration, echoController?.armorPenetration ?: 0.0),
            normalAttack = schedulingScope == TaskScope.NORMAL)
    }

    /**
     * NORMAL 技能产生的实体碰撞/常驻状态可能在 Bukkit 事件或基础 tick 中回调，
     * 此时 schedulingScope 已恢复为 BASE，需要显式保留“卦象普攻”语义。
     */
    protected fun dealNormalAttackDamage(
        target: LivingEntity,
        amount: Double,
        armorPenetration: Double = 0.0,
        source: LivingEntity = boss
    ) {
        manager.dealPhysicalDamage(target, amount * outgoingDamageMultiplier, source,
            maxOf(armorPenetration, echoController?.armorPenetration ?: 0.0), normalAttack = true)
    }
}

private val NORMAL_SKILL_WARNINGS = mapOf(
    Trigram.THUNDER to listOf(
        Triple("奔雷刃", "靐已§e锁定最近的目标§6，将连续挥出§c三次奔雷刃§6，避开它面前的冲刺路径！", 40),
        Triple("引雷三叉", "", 0)
    ),
    Trigram.SKY to listOf(
        Triple("天羽坠火", "晶正在聚拢§e灼热火羽§6，注意落地后逐渐变红的§c爆炸区域§6！", 30),
        Triple("曜日凌空", "晶正在锁定§e五处云中坐标§6，远离它即将留下的§c金光瀑布§6！", 20)
    ),
    Trigram.WATER to listOf(
        Triple("波涛汹涌", "淼正在身前汇聚§e一道巨浪§6，寻找§a掩体§6或离开水墙的推进路径！", 30),
        Triple("海魂锁链", "淼正在凝聚§b海魂锁链§6，§e最远处的玩家§6将成为它的目标！", 50)
    ),
    Trigram.MOUNTAIN to listOf(
        Triple("镇岳重击", "芔正在积蓄§e镇岳之力§6，避开它即将冲过的§c直线路径§6！", 30),
        Triple("飞沙走石", "芔正在身前凝聚§e巨石§6，注意滚石的§c前进与反弹方向§6！", 0)
    ),
    Trigram.FIRE to listOf(
        Triple("离焰斩", "焱正在身前凝聚§c离焰斩§6，留意它锁定的§e斩击方向§6！", 30),
        Triple("天火临", "焱即将升空引导§c天火§6，持续躲避不断出现的§e轰炸落点§6！", 20)
    ),
    Trigram.WIND to listOf(
        Triple("回旋风刃", "雾正遁入风云并§e锁定最近的目标§6，避开它接连不断的§c旋转追击§6！", 50),
        Triple("风之矢", "", 100)
    ),
    Trigram.SWAMP to listOf(
        Triple("菌潮", "恶正在引动§e五路菌潮§6，远离即将依次喷发的§c直线毒泉§6！", NORMAL_ATTACK_WARNING_TICKS),
        Triple("分沼", "恶正在汇聚§5分沼之力§6，接下来将持续向§e不同目标§6发射沼泽分身！", NORMAL_ATTACK_WARNING_TICKS)
    ),
    Trigram.EARTH to listOf(
        Triple("阴阳双斩", "垚正在凝聚§8黑§f白§6两色剑气，它们将§e追踪玩家§6并在彼此接近时§c爆裂§6！", 60),
        Triple("鬼魂出窍", "垚即将令§5鬼魂脱离本体§6，本体会受到魂体庇护，尽快击破§e追击你们的出窍魂体§6！", 60)
    )
).mapValues { (_, warnings) -> warnings.map { (name, message, ticks) ->
    SkillWarning(name, message, ticks, elderHint = false)
} }

private val ULTIMATE_SKILL_WARNINGS = mapOf(
    Trigram.THUNDER to SkillWarning("天雷引", "", 0, elderHint = true),
    Trigram.SKY to SkillWarning("金云锻体", "", 0, elderHint = true),
    Trigram.WATER to SkillWarning("逆水归渊", "", 0, elderHint = true),
    Trigram.MOUNTAIN to SkillWarning("力撼山岩", "", 0, elderHint = true),
    Trigram.FIRE to SkillWarning("八方离火", "", 0, elderHint = true),
    Trigram.WIND to SkillWarning("风蚀圣山", "", 0, elderHint = true),
    Trigram.SWAMP to SkillWarning("息壤封泽", "", 0, elderHint = true),
    Trigram.EARTH to SkillWarning("鬼门开", "", 0, elderHint = true)
)

private val ULTIMATE_SYSTEM_MESSAGES = mapOf(
    Trigram.THUNDER to "§6靐停止攻势，正缓缓飞回§e阵心上空§6，躁动的§b天雷§6开始向它汇聚……",
    Trigram.SKY to "§6晶停止攻势，正飞回§e云息汇聚之处§6，周围的§f天光§6开始向它收拢……",
    Trigram.WATER to "§6淼停止攻势，正向§e阵心§6回返，四周§b水势§6开始向它汇聚……",
    Trigram.MOUNTAIN to "§6芔停止攻势，正向§e阵心§6回返，四周§8山土§6开始向它汇聚……",
    Trigram.FIRE to "§6焱停止攻势，正向§e阵心§6回返，§c八方离火§6开始在祭台间汇聚……",
    Trigram.WIND to "§6雾停止攻势，正向§e阵心§6回返，四周§f云雾§6开始向它急速汇聚……",
    Trigram.SWAMP to "§6恶停止攻势，正向§e阵心§6回返，§5紫色息壤§6开始向它周身汇聚……",
    Trigram.EARTH to "§6垚停止攻势，正向§e阵心§6回返，§5三座鬼门§6中的阴气开始向它周身汇聚……"
)

internal fun createShengShanBossController(
    manager: ShengShanDungeonManager,
    session: ShengShanSession,
    trigram: Trigram,
    boss: LivingEntity
): ShengShanBossController = when (trigram) {
    Trigram.THUNDER -> ReworkedThunderBossController(manager, session, boss)
    Trigram.SKY -> ReworkedSkyBossController(manager, session, boss)
    Trigram.WATER -> ReworkedWaterBossController(manager, session, boss)
    Trigram.MOUNTAIN -> ReworkedMountainBossController(manager, session, boss)
    Trigram.FIRE -> ReworkedFireBossController(manager, session, boss)
    Trigram.WIND -> ReworkedWindBossController(manager, session, boss)
    Trigram.SWAMP -> ReworkedSwampBossController(manager, session, boss)
    Trigram.EARTH -> ReworkedEarthBossController(manager, session, boss)
}

private const val NORMAL_ATTACK_WARNING_TICKS = 30
