package com.hjh_database.dungeon.qixi

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.listener.CombatListener
import com.hjh_database.spawner.MobFactory
import com.hjh_database.spawner.MobRegistry
import io.papermc.paper.event.entity.EntityKnockbackEvent
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Display
import org.bukkit.entity.Evoker
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Spellcaster
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.net.URI
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 七夕副本第三阶段。所有战斗逻辑由一个 10Hz 驱动器推进，避免每个技能各自创建高频任务。
 */
internal class QixiThirdPhaseController(
    private val plugin: Hjh_database,
    private val world: World,
    private val playerIds: MutableSet<UUID>,
    private val niulang: Villager,
    private val zhinv: Villager,
    private val config: YamlConfiguration,
    private val difficulty: QixiDifficulty,
    private val onFinished: () -> Unit
) : Listener {
    private enum class Stage { INTRO, COMBAT, TIANHE, ENDING }
    private enum class MainState {
        BASIC, PRESSURE, BOUNDARY_FLIGHT, BOUNDARY_WARNING, BOUNDARY_ACTIVE, BOUNDARY_RETURN
    }
    private enum class BasicState { CHASE_NEAREST, CAST_LIGHTNING, CHASE_FARTHEST, CAST_LASER }
    private enum class Half(val key: String) { FRONT("front"), BACK("back") }
    private enum class StarColor { RED, BLUE }
    private enum class HeavenlyLaw { KEEP_APART, STAY_TOGETHER }

    private data class BoundaryZone(
        val targetId: UUID,
        var ageTicks: Int = 0,
        var lastSample: Location? = null,
        val markedCells: MutableSet<BoundaryCell> = LinkedHashSet(),
        val eruptionCenters: MutableSet<BoundaryCell> = LinkedHashSet(),
        val displays: MutableMap<BoundaryCell, UUID> = HashMap()
    )

    private data class BoundaryCell(val x: Int, val y: Int, val z: Int)

    private data class TimedDialogue(val deliverAtTick: Int, val message: String)

    private val entityTag = "qixi_queqiao_entity"
    private val playerTag = "qixi_queqiao_player"
    private val dungeonEntityKey = NamespacedKey(plugin, "qixi_entity")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val orderHalfKey = NamespacedKey(plugin, "qixi_order_half")
    private val tianheSpeedKey = NamespacedKey(plugin, "qixi_tianhe_speed")
    private val tianheKnockbackKey = NamespacedKey(plugin, "qixi_tianhe_knockback_resistance")

    private val difficultyPath = "phase-three.difficulty.${difficulty.configKey}"
    private val attributeMultiplier = config.getDouble(
        "difficulty.${difficulty.configKey}.attribute-multiplier",
        if (difficulty == QixiDifficulty.EASY) 0.75 else 1.0
    ).coerceIn(0.05, 2.0)
    private val bossHealth = (config.getDouble("phase-three.boss.health", 10000.0) * attributeMultiplier).coerceAtLeast(1.0)
    private val bossArmor = (config.getDouble("phase-three.boss.armor", 100.0) * attributeMultiplier).coerceAtLeast(0.0)
    private val bossSpeed = config.getDouble(
        "$difficultyPath.boss-speed",
        if (difficulty == QixiDifficulty.HARD) 0.45 else 0.40
    ).coerceIn(0.05, 1.0)
    private val bossScale = config.getDouble("phase-three.boss.scale", 1.20).coerceIn(1.0, 3.0)
    private val lightningDamage = config.getDouble("phase-three.normal-attacks.lightning-damage", 40.0).coerceAtLeast(0.0)
    private val laserDamage = config.getDouble("phase-three.normal-attacks.laser-damage", 30.0).coerceAtLeast(0.0)
    private val boundaryWarningTicks = secondsToTicks(config.getDouble("phase-three.golden-boundary.warning-seconds", 2.5))
    private val boundaryDurationTicks = secondsToTicks(config.getDouble("phase-three.golden-boundary.duration-seconds", 15.0))
    private val boundaryDamage = config.getDouble(
        "$difficultyPath.golden-boundary-damage",
        if (difficulty == QixiDifficulty.EASY) 60.0 else 80.0
    ).coerceAtLeast(0.0)
    private val pressureWarningTicks = secondsToTicks(config.getDouble("phase-three.phoenix-pressure.warning-seconds", 2.0))
    private val pressureCooldownTicks = secondsToTicks(config.getDouble("phase-three.phoenix-pressure.cooldown-seconds", 12.0))
    private val pressureRadius = config.getDouble("phase-three.phoenix-pressure.radius", 6.0).coerceAtLeast(1.0)
    private val armorBreakMillis = (config.getDouble(
        "$difficultyPath.armor-break-seconds",
        if (difficulty == QixiDifficulty.EASY) 6.0 else 10.0
    ) * 1000.0).toLong()
    private val pressureTakenDamageMultiplier = config.getDouble(
        "$difficultyPath.pressure-taken-damage-multiplier",
        if (difficulty == QixiDifficulty.EASY) 0.50 else 0.20
    ).coerceIn(0.0, 1.0)
    private val poolCooldownTicks = secondsToTicks(config.getDouble("phase-three.main-skill-pool.cooldown-seconds", 25.0))
    private val boundaryPoolWeight = config.getInt(
        "$difficultyPath.golden-boundary-weight",
        if (difficulty == QixiDifficulty.EASY) 3 else 1
    ).coerceAtLeast(1)
    private val tianhePoolWeight = config.getInt("$difficultyPath.inverted-river-weight", 1).coerceAtLeast(1)
    private val supportCooldownTicks = secondsToTicks(config.getDouble("phase-three.independent-pool.cooldown-seconds", 30.0))
    private val summonsPerCast = config.getInt(
        "$difficultyPath.summons-per-cast",
        if (difficulty == QixiDifficulty.EASY) 12 else 24
    ).coerceIn(0, 54)
    private val maxActiveSummons = config.getInt(
        "$difficultyPath.maximum-active-summons",
        24
    ).coerceIn(0, 80)
    private val bearerHealth = (config.getDouble("phase-three.bearer.health", 420.0) * attributeMultiplier).coerceAtLeast(1.0)
    private val bearerArmor = (config.getDouble("phase-three.bearer.armor", 70.0) * attributeMultiplier).coerceAtLeast(0.0)
    private val lawWarningTicks = secondsToTicks(config.getDouble("phase-three.heavenly-law.warning-seconds", 2.0))
    private val lawDurationTicks = secondsToTicks(config.getDouble("phase-three.heavenly-law.duration-seconds", 10.0))
    private val lawDistance = config.getDouble("phase-three.heavenly-law.distance", 7.0).coerceAtLeast(1.0)
    private val lawDamage = config.getDouble("phase-three.heavenly-law.violation-damage", 20.0).coerceAtLeast(0.0)
    private val lawDamageIntervalTicks = secondsToTicks(
        config.getDouble("$difficultyPath.law-damage-interval-seconds", if (difficulty == QixiDifficulty.EASY) 2.0 else 1.0)
    )
    private val tianheTriggerRatio = config.getDouble("phase-three.inverted-river.trigger-health-ratio", 0.40).coerceIn(0.01, 0.99)
    private val tianheWarningTicks = secondsToTicks(
        config.getDouble("$difficultyPath.inverted-river-warning-seconds", if (difficulty == QixiDifficulty.EASY) 15.0 else 10.0)
    )
    private val tianheFloatTicks = secondsToTicks(config.getDouble("phase-three.inverted-river.float-seconds", 15.0))
    private val tianheSpeedBonus = config.getDouble("phase-three.inverted-river.movement-speed-bonus", 0.50).coerceIn(0.0, 2.0)
    private val tianheNextWaveTicks = secondsToTicks(
        config.getDouble("$difficultyPath.inverted-river-next-wave-seconds", if (difficulty == QixiDifficulty.EASY) 15.0 else 10.0)
    )
    private val tianheMitigationRadius = config.getDouble("phase-three.inverted-river.mitigation-radius", 7.0).coerceAtLeast(1.0)
    private val tianhePunishmentDamage = config.getDouble(
        "$difficultyPath.inverted-river-punishment-damage",
        if (difficulty == QixiDifficulty.EASY) 60.0 else 100.0
    ).coerceAtLeast(0.0)
    private val tianheMaximumMitigationRatio = config.getDouble(
        "phase-three.inverted-river.maximum-mitigation-ratio",
        0.80
    ).coerceIn(0.0, 1.0)
    private val tianheSoloSuccessDamage = config.getDouble(
        "$difficultyPath.inverted-river-solo-success-damage",
        if (difficulty == QixiDifficulty.EASY) 15.0 else 25.0
    ).coerceAtLeast(0.0)
    private val tianheSoloFailureDamage = config.getDouble(
        "$difficultyPath.inverted-river-solo-failure-damage",
        if (difficulty == QixiDifficulty.EASY) 40.0 else 60.0
    ).coerceAtLeast(0.0)
    private val conserverHealth = (config.getDouble("phase-three.conserver.health", 160.0) * attributeMultiplier).coerceAtLeast(1.0)
    private val conserverArmor = (config.getDouble("phase-three.conserver.armor", 25.0) * attributeMultiplier).coerceAtLeast(0.0)

    private val trackedEntities = HashSet<UUID>()
    private val summonedIds = HashSet<UUID>()
    private val summonHalves = HashMap<UUID, Half>()
    private val bearerHalves = HashMap<UUID, Half>()
    private val bearersKilledByOrder = HashSet<UUID>()
    private val chargedSpiderFuseAt = HashMap<UUID, Long>()
    private val armorBrokenUntil = HashMap<UUID, Long>()
    private val boundaryZones = ArrayList<BoundaryZone>()
    private val playerColors = HashMap<UUID, StarColor>()
    private val floatingPlayers = HashSet<UUID>()
    private val conserverIds = HashSet<UUID>()
    private val tianheBars = HashMap<StarColor, BossBar>()
    private val remainingConserverLocations = ArrayList<Location>()
    private val timedDialogues = ArrayList<TimedDialogue>()

    private var stage = Stage.INTRO
    private var mainState = MainState.BASIC
    private var basicState = BasicState.CHASE_NEAREST
    private var boss: Evoker? = null
    private var driver: BukkitTask? = null
    private var elapsedTicks = 0
    private var stateTicks = 0
    private var basicCastTicks = 0
    private var laserShots = 0
    private var laserLockedTarget: Location? = null
    private var pressureCooldown = pressureCooldownTicks
    private var poolCooldown = poolCooldownTicks
    private var supportCooldown = supportCooldownTicks
    private var orderCastRemaining = 0
    private var currentLaw: HeavenlyLaw? = null
    private var lawWarningRemaining = 0
    private var lawActiveRemaining = 0
    private var lawDamageAccumulator = 0
    private var bossInvulnerable = true
    private var bossBar: BossBar? = null
    private var skillBar: BossBar? = null
    private var orderBar: BossBar? = null
    private var lawBar: BossBar? = null
    private var boundaryFlightStart: Location? = null
    private var boundaryReturnTarget: Location? = null
    private var tianheWarningRemaining = 0
    private var tianheCycleRemaining = 0
    private var tianheNextWaveRemaining = 0
    private var tianheFlightRemaining = 0
    private var tianheFlightStart: Location? = null
    private var pendingConserverLocation: Location? = null
    private var defeatedConservers = 0
    private var currentFloatingColor: StarColor? = null
    private var thresholdTianheTriggered = false
    private var phaseThreeBgmRemaining = 0
    private var completed = false

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        prepareEscorts()
        spawnBoss()
        if (boss == null) {
            plugin.logger.severe("七夕第三阶段无法生成王母娘娘，已结束本次测试流程。")
            HandlerList.unregisterAll(this)
            onFinished()
            return
        }
        driver = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, DRIVER_PERIOD_TICKS)
    }

    fun removePlayer(player: Player) {
        stopPhaseThreeBgm(player)
        listOf(bossBar, skillBar, orderBar, lawBar).forEach { it?.removePlayer(player) }
        tianheBars.values.forEach { it.removePlayer(player) }
        armorBrokenUntil.remove(player.uniqueId)
        floatingPlayers.remove(player.uniqueId)
        playerColors.remove(player.uniqueId)
        player.removePotionEffect(PotionEffectType.LEVITATION)
        removeTianhePlayerModifiers(player)
    }

    fun shutdown() {
        driver?.cancel()
        driver = null
        phaseThreeBgmRemaining = 0
        HandlerList.unregisterAll(this)
        clearAllBars()
        clearBoundaryZones()
        activePlayers().forEach {
            stopPhaseThreeBgm(it)
            it.removePotionEffect(PotionEffectType.LEVITATION)
            it.removePotionEffect(PotionEffectType.BLINDNESS)
            removeTianhePlayerModifiers(it)
        }
        trackedEntities.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        trackedEntities.clear()
        summonedIds.clear()
        summonHalves.clear()
        bearerHalves.clear()
        bearersKilledByOrder.clear()
        conserverIds.clear()
        remainingConserverLocations.clear()
        chargedSpiderFuseAt.clear()
        armorBrokenUntil.clear()
        floatingPlayers.clear()
        playerColors.clear()
        timedDialogues.clear()
    }

    private fun tick() {
        if (stage == Stage.ENDING) return
        elapsedTicks += DRIVER_PERIOD_TICKS.toInt()
        flushTimedDialogues()
        val players = activePlayers()
        if (players.isEmpty()) return
        val currentBoss = boss
        if (currentBoss == null || !currentBoss.isValid || currentBoss.isDead) return

        if (elapsedTicks % CLOUD_PERIOD_TICKS == 0) spawnCloud(currentBoss)
        if (elapsedTicks % 10 == 0) {
            updateBossBar(currentBoss)
            updateSummonTargets(players)
            autoSubmitJadeOrders(players)
            drawEscortPrisonAmbient(niulang)
            drawEscortPrisonAmbient(zhinv)
        }
        updateChargedSpiders(players)
        armorBrokenUntil.entries.removeIf { it.value <= System.currentTimeMillis() }

        val bgmWasActive = stage == Stage.COMBAT || stage == Stage.TIANHE
        when (stage) {
            Stage.INTRO -> tickIntro(currentBoss)
            Stage.COMBAT -> tickCombat(currentBoss, players)
            Stage.TIANHE -> tickTianhe(currentBoss, players)
            Stage.ENDING -> Unit
        }
        if (bgmWasActive && (stage == Stage.COMBAT || stage == Stage.TIANHE)) tickPhaseThreeBgm()
        if (stage == Stage.COMBAT || stage == Stage.TIANHE) {
            tickIndependentSkills(currentBoss, players)
        }
    }

    private fun tickIntro(currentBoss: Evoker) {
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        when (elapsedTicks) {
            2 -> {
                broadcast("§7刹那间，一道璀璨夺目的金光自鹊桥中心炸裂开来，将整座鹊桥连同星光一并吞没。待光芒稍敛，§7你们发现自己已被一股不可抗拒的力量裹挟至一方§e宽广的广场§7之上。")
                world.playSound(plazaCenter(), Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 0.65f)
            }
            62 -> {
                broadcast("§7广场两侧，牛郎与织女被§c金色的阵法枷锁§7囚禁于一左一右，动弹不得。")
                drawEscortPrison(niulang)
                drawEscortPrison(zhinv)
                world.playSound(plazaCenter(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.1f, 1.25f)
            }
            122 -> {
                activePlayers().forEach { it.removePotionEffect(PotionEffectType.BLINDNESS) }
                broadcast("§7广场正中的凉亭骤然爆发出刺眼的金光——一道威严的身影自光中凝形，手中金簪尚在微微颤动。")
                currentBoss.removePotionEffect(PotionEffectType.INVISIBILITY)
                revealBoss(currentBoss)
            }
            182 -> {
                broadcast("§b牛郎§f失声喊道：§c“不好……王母娘娘！她动了真怒！”")
                world.playSound(niulang.location, Sound.ENTITY_VILLAGER_NO, 1.2f, 0.8f)
            }
            242 -> broadcast("§7王母冷眼扫过全场，声音如寒冰坠地——")
            302 -> {
                broadcast("§4§n王母娘娘§f：擅闯天河，扰乱天规，还胆敢聚众抗命——尔等既执意成全这对苦命鸳鸯，那便一同§c伏诛§f于此，永世不得超生！")
                world.playSound(currentBoss.location, Sound.ENTITY_WITHER_SPAWN, 1.25f, 0.55f)
                currentBoss.spell = Spellcaster.Spell.WOLOLO
            }
            362 -> beginCombat(currentBoss)
        }
    }

    private fun beginCombat(currentBoss: Evoker) {
        currentBoss.spell = Spellcaster.Spell.NONE
        activePlayers().forEach { it.removePotionEffect(PotionEffectType.BLINDNESS) }
        stage = Stage.COMBAT
        bossInvulnerable = false
        currentBoss.isInvulnerable = false
        mainState = MainState.BASIC
        basicState = BasicState.CHASE_NEAREST
        laserLockedTarget = null
        pressureCooldown = pressureCooldownTicks
        poolCooldown = poolCooldownTicks
        supportCooldown = supportCooldownTicks
        bossBar = createBar("§4§n王母娘娘", BarColor.RED, BarStyle.SEGMENTED_10)
        broadcast("§6请击败王母娘娘！解救被困的牛郎和织女！")
        playPhaseThreeBgm()
        world.playSound(currentBoss.location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.2f, 0.65f)
    }

    private fun tickCombat(currentBoss: Evoker, players: List<Player>) {
        // 首次降至 40% 时立即进入天河倒悬，不等待 25 秒主技能池冷却，也不受当前主技能状态阻挡。
        if (!thresholdTianheTriggered && currentBoss.health / bossHealth <= tianheTriggerRatio) {
            thresholdTianheTriggered = true
            startTianhe(currentBoss, players)
            return
        }
        when (mainState) {
            MainState.BASIC -> {
                pressureCooldown -= DRIVER_PERIOD_TICKS.toInt()
                poolCooldown -= DRIVER_PERIOD_TICKS.toInt()
                when {
                    pressureCooldown <= 0 -> startPressure(currentBoss)
                    poolCooldown <= 0 -> {
                        val tianheEligible = currentBoss.health / bossHealth <= tianheTriggerRatio
                        val rollTianhe = tianheEligible &&
                            ThreadLocalRandom.current().nextInt(boundaryPoolWeight + tianhePoolWeight) >= boundaryPoolWeight
                        if (rollTianhe) {
                            startTianhe(currentBoss, players)
                        } else {
                            startBoundary(currentBoss)
                        }
                    }
                    else -> tickBasicAttack(currentBoss, players)
                }
            }
            MainState.PRESSURE -> tickPressure(currentBoss, players)
            MainState.BOUNDARY_FLIGHT -> tickBoundaryFlight(currentBoss)
            MainState.BOUNDARY_WARNING -> tickBoundaryWarning(currentBoss)
            MainState.BOUNDARY_ACTIVE -> tickBoundaryActive(currentBoss, players)
            MainState.BOUNDARY_RETURN -> tickBoundaryReturn(currentBoss, players)
        }
    }

    private fun tickBasicAttack(currentBoss: Evoker, players: List<Player>) {
        val candidates = if (stage == Stage.TIANHE) players.filter { it.uniqueId !in floatingPlayers } else players
        if (candidates.isEmpty()) {
            currentBoss.velocity = Vector(0.0, 0.0, 0.0)
            return
        }
        when (basicState) {
            BasicState.CHASE_NEAREST -> {
                val target = candidates.minByOrNull { it.location.distanceSquared(currentBoss.location) } ?: return
                if (horizontalDistanceSquared(target.location, currentBoss.location) <= BASIC_CAST_RANGE_SQUARED) {
                    freezeAndFace(currentBoss, target)
                    basicState = BasicState.CAST_LIGHTNING
                    basicCastTicks = BASIC_CHANNEL_TICKS
                    currentBoss.spell = Spellcaster.Spell.FANGS
                } else moveToward(currentBoss, target.location)
            }
            BasicState.CAST_LIGHTNING -> {
                currentBoss.velocity = Vector(0.0, 0.0, 0.0)
                basicCastTicks -= DRIVER_PERIOD_TICKS.toInt()
                if (basicCastTicks <= 0) {
                    castLightning(currentBoss, candidates)
                    currentBoss.spell = Spellcaster.Spell.NONE
                    basicState = BasicState.CHASE_FARTHEST
                }
            }
            BasicState.CHASE_FARTHEST -> {
                val target = candidates.maxByOrNull { it.location.distanceSquared(currentBoss.location) } ?: return
                if (horizontalDistanceSquared(target.location, currentBoss.location) <= BASIC_CAST_RANGE_SQUARED) {
                    freezeAndFace(currentBoss, target)
                    basicState = BasicState.CAST_LASER
                    basicCastTicks = BASIC_CHANNEL_TICKS
                    laserShots = 0
                    laserLockedTarget = target.eyeLocation.clone()
                    currentBoss.spell = Spellcaster.Spell.SUMMON_VEX
                } else moveToward(currentBoss, target.location)
            }
            BasicState.CAST_LASER -> {
                currentBoss.velocity = Vector(0.0, 0.0, 0.0)
                val lockedTarget = laserLockedTarget
                if (lockedTarget != null) {
                    face(currentBoss, lockedTarget)
                    if (basicCastTicks % LASER_WARNING_REFRESH_TICKS == 0) {
                        drawLaserWarning(currentBoss, lockedTarget)
                    }
                }
                basicCastTicks -= DRIVER_PERIOD_TICKS.toInt()
                if (basicCastTicks > 0) return
                if (lockedTarget != null) castGoldenLaser(currentBoss, lockedTarget, candidates)
                laserShots++
                if (laserShots >= 3) {
                    currentBoss.spell = Spellcaster.Spell.NONE
                    basicState = BasicState.CHASE_NEAREST
                    laserLockedTarget = null
                } else {
                    laserLockedTarget = candidates
                        .maxByOrNull { it.location.distanceSquared(currentBoss.location) }
                        ?.eyeLocation
                        ?.clone()
                    basicCastTicks = LASER_REPEAT_TICKS
                }
            }
        }
    }

    private fun castLightning(currentBoss: Evoker, players: List<Player>) {
        val center = currentBoss.location.clone().add(0.0, 0.2, 0.0)
        repeat(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            val point = center.clone().add(cos(angle) * LIGHTNING_AOE_RADIUS, 0.0, sin(angle) * LIGHTNING_AOE_RADIUS)
            world.spawnParticle(Particle.ELECTRIC_SPARK, point, 2, 0.18, 0.45, 0.18, 0.06)
        }
        val targets = players.filter { horizontalDistanceSquared(it.location, currentBoss.location) <= LIGHTNING_AOE_RADIUS_SQUARED }
        val random = ThreadLocalRandom.current()
        val ambientStrikes = List(LIGHTNING_AMBIENT_STRIKES) {
            val angle = random.nextDouble(0.0, 2.0 * PI)
            val radius = sqrt(random.nextDouble()) * LIGHTNING_AOE_RADIUS
            center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
        }
        val impactLocations = (targets.map { it.location.clone() } + center + ambientStrikes)
            .distinctBy { it.blockX to it.blockZ }
        impactLocations.forEach { impact ->
            // strikeLightningEffect 只负责原版闪电视觉，不产生实体伤害；静音后统一播放低音量雷声。
            world.strikeLightningEffect(impact).isSilent = true
            repeat(7) { step ->
                world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    impact.clone().add(0.0, 1.0 + step * 1.15, 0.0),
                    4,
                    0.16,
                    0.48,
                    0.16,
                    0.05
                )
            }
            world.spawnParticle(Particle.FLASH, impact.clone().add(0.0, 1.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
        }
        world.playSound(currentBoss.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.55f, 1.05f)
        targets.forEach { dealPhysicalDamage(it, lightningDamage, currentBoss) }
    }

    private fun castGoldenLaser(currentBoss: Evoker, targetLocation: Location, players: List<Player>) {
        face(currentBoss, targetLocation)
        val start = currentBoss.eyeLocation
        val direction = fixedDirection(start, targetLocation, currentBoss.location.direction)
        val end = start.clone().add(direction.clone().multiply(LASER_LENGTH))
        repeat(LASER_BEAM_POINTS) { index ->
            val point = start.clone().add(direction.clone().multiply(index * LASER_LENGTH / (LASER_BEAM_POINTS - 1).toDouble()))
            val dust = if (index % 2 == 0) LASER_GOLD_DUST else LASER_YELLOW_DUST
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
        world.playSound(start, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.65f)
        players.filter { distanceToSegmentSquared(it.location.clone().add(0.0, 1.0, 0.0), start, end) <= LASER_HIT_WIDTH_SQUARED }
            .forEach { dealPhysicalDamage(it, laserDamage, currentBoss) }
    }

    /** 低刷新率、低采样点的红线预警；每条线每 0.5 秒仅发送 8 个粒子点。 */
    private fun drawLaserWarning(currentBoss: Evoker, targetLocation: Location) {
        val start = currentBoss.eyeLocation
        val direction = fixedDirection(start, targetLocation, currentBoss.location.direction)
        repeat(LASER_WARNING_POINTS) { index ->
            val point = start.clone().add(direction.clone().multiply(index * LASER_LENGTH / (LASER_WARNING_POINTS - 1).toDouble()))
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, RED_WARNING_DUST)
        }
        if (basicCastTicks == BASIC_CHANNEL_TICKS || basicCastTicks == LASER_REPEAT_TICKS) {
            world.playSound(start, Sound.BLOCK_BEACON_POWER_SELECT, 0.65f, 0.75f)
        }
    }

    private fun tickIndependentSkills(currentBoss: Evoker, players: List<Player>) {
        tickJadeOrder(currentBoss)
        if (stage == Stage.TIANHE) {
            if (currentLaw != null) clearHeavenlyLaw()
        } else {
            tickHeavenlyLaw(players)
        }
        supportCooldown -= DRIVER_PERIOD_TICKS.toInt()
        if (supportCooldown > 0) return
        supportCooldown = supportCooldownTicks
        if (stage == Stage.TIANHE || ThreadLocalRandom.current().nextBoolean()) {
            startJadeOrder(currentBoss)
        } else {
            startHeavenlyLaw()
        }
    }

    private fun startPressure(currentBoss: Evoker) {
        pressureCooldown = pressureCooldownTicks
        mainState = MainState.PRESSURE
        stateTicks = pressureWarningTicks
        bossInvulnerable = false
        currentBoss.isInvulnerable = false
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        currentBoss.spell = Spellcaster.Spell.DISAPPEAR
        broadcast("§4§n王母娘娘§f: §f本座面前，岂容尔等放肆！")
        broadcast("§b牛郎：§f她在蓄力！快退开——凤仪威压一旦释放，近身者非死即残！")
        skillBar = createBar("§c§l凤仪威压蓄力", BarColor.RED, BarStyle.SOLID).also { it.progress = 0.0 }
        world.playSound(currentBoss.location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.2f, 0.55f)
    }

    private fun tickPressure(currentBoss: Evoker, players: List<Player>) {
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        stateTicks -= DRIVER_PERIOD_TICKS.toInt()
        skillBar?.progress = (1.0 - stateTicks.toDouble() / pressureWarningTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        world.spawnParticle(Particle.ENCHANT, currentBoss.location.clone().add(0.0, 1.0, 0.0), 5, 1.0, 0.8, 1.0, 0.05)
        drawPressureRing(currentBoss)
        if (stateTicks > 0) return

        val until = System.currentTimeMillis() + armorBreakMillis
        val radiusSquared = pressureRadius * pressureRadius
        players.filter { it.location.distanceSquared(currentBoss.location) <= radiusSquared }.forEach { player ->
            val knockback = player.location.toVector().subtract(currentBoss.location.toVector()).setY(0.0)
            player.velocity = if (knockback.lengthSquared() < 0.01) Vector(2.4, 0.6, 0.0)
            else knockback.normalize().multiply(2.4).setY(0.6)
            armorBrokenUntil[player.uniqueId] = until
            player.sendActionBar("§c§l凤仪威压使你的护甲暂时失效！")
        }
        world.spawnParticle(Particle.EXPLOSION, currentBoss.location.clone().add(0.0, 1.0, 0.0), 3, 1.2, 0.5, 1.2, 0.0)
        world.spawnParticle(Particle.CLOUD, currentBoss.location, 90, pressureRadius * 0.55, 0.3, pressureRadius * 0.55, 0.12)
        world.playSound(currentBoss.location, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.65f)
        finishMainSkill(currentBoss)
    }

    private fun drawPressureRing(currentBoss: Evoker) {
        if (elapsedTicks % 4 != 0) return
        val center = currentBoss.location.clone().add(0.0, 1.0, 0.0)
        val radius = 1.35 + 0.16 * sin(elapsedTicks * 0.16)
        repeat(16) { index ->
            val angle = 2.0 * PI * index / 16.0 + elapsedTicks * 0.055
            val point = center.clone().add(cos(angle) * radius, 0.18 * sin(angle * 2.0), sin(angle) * radius)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, LASER_GOLD_DUST)
        }
    }

    private fun startJadeOrder(currentBoss: Evoker) {
        orderCastRemaining = ORDER_CHANNEL_TICKS
        orderBar?.removeAll()
        orderBar = createBar("§e§l瑶池玉令·天兵即将降临", BarColor.YELLOW, BarStyle.SOLID).also { it.progress = 0.0 }
        if (stage != Stage.TIANHE) {
            broadcast("§4§n王母娘娘§f: §f天兵听令，速来护驾！")
            broadcastAfter(
                20,
                "§b牛郎：§f她在召唤援军！你们注意——广场上出现了两名§e持令者§f，击杀他们，夺下瑶池令！",
                "§d织女：§f携带§b瑶池令§f靠近我或牛郎君，我们便可炼化令牌诛灭半场的天兵！"
            )
            world.playSound(currentBoss.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.85f, 0.65f)
        }
        world.playSound(currentBoss.location, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 0.65f)
    }

    private fun tickJadeOrder(currentBoss: Evoker) {
        if (orderCastRemaining <= 0) return
        orderCastRemaining -= DRIVER_PERIOD_TICKS.toInt()
        orderBar?.progress = (1.0 - orderCastRemaining.toDouble() / ORDER_CHANNEL_TICKS).coerceIn(0.0, 1.0)
        val base = currentBoss.location.clone().add(0.0, 1.0, 0.0)
        repeat(4) { index ->
            world.spawnParticle(Particle.END_ROD, base.clone().add(0.0, index * 2.5, 0.0), 5, 0.25, 1.0, 0.25, 0.03)
        }
        if (orderCastRemaining > 0) return
        summonHeavenlySoldiers()
        spawnBearer(Half.FRONT)
        spawnBearer(Half.BACK)
        orderBar?.removeAll(); orderBar = null
    }

    private fun startBoundary(currentBoss: Evoker) {
        poolCooldown = poolCooldownTicks
        mainState = MainState.BOUNDARY_FLIGHT
        stateTicks = max(boundaryWarningTicks, BOUNDARY_FLIGHT_TICKS)
        boundaryFlightStart = currentBoss.location.clone()
        bossInvulnerable = true
        currentBoss.isInvulnerable = true
        currentBoss.setGravity(false)
        currentBoss.spell = Spellcaster.Spell.WOLOLO
        broadcast("§4§n王母娘娘§f: §f天河有界，岂容尔等擅越。")
        broadcastAfter(
            20,
            "§b牛郎：§f那是王母的金簪！快看地上——金光会追随目标走过的路径，速速离开铺开的金阵！",
            "§d织女：§f离她最远之人与最近之人，皆是她出手的首选目标。莫要心存侥幸！"
        )
        skillBar = createBar("§6§l金簪立界即将发动", BarColor.YELLOW, BarStyle.SOLID).also { it.progress = 0.0 }
        world.playSound(currentBoss.location, Sound.ITEM_TRIDENT_RETURN, 1.1f, 1.45f)
        world.playSound(currentBoss.location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.80f, 0.58f)
    }

    private fun tickBoundaryFlight(currentBoss: Evoker) {
        val start = boundaryFlightStart ?: currentBoss.location
        val target = boundaryPerch()
        stateTicks -= DRIVER_PERIOD_TICKS.toInt()
        val totalWarning = max(boundaryWarningTicks, BOUNDARY_FLIGHT_TICKS)
        val warningElapsed = totalWarning - stateTicks
        val progress = warningElapsed.toDouble() / BOUNDARY_FLIGHT_TICKS
        currentBoss.teleport(lerp(start, target, progress))
        skillBar?.progress = (warningElapsed.toDouble() / totalWarning).coerceIn(0.0, 1.0)
        world.spawnParticle(Particle.END_ROD, currentBoss.location, 6, 0.4, 0.3, 0.4, 0.03)
        if (warningElapsed < BOUNDARY_FLIGHT_TICKS) return
        currentBoss.teleport(target)
        mainState = MainState.BOUNDARY_WARNING
    }

    private fun tickBoundaryWarning(currentBoss: Evoker) {
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        stateTicks -= DRIVER_PERIOD_TICKS.toInt()
        val totalWarning = max(boundaryWarningTicks, BOUNDARY_FLIGHT_TICKS)
        skillBar?.progress = (1.0 - stateTicks.toDouble() / totalWarning).coerceIn(0.0, 1.0)
        world.spawnParticle(Particle.FIREWORK, currentBoss.location.clone().add(0.0, 1.2, 0.0), 8, 0.8, 0.8, 0.8, 0.05)
        if (stateTicks > 0) return
        skillBar?.removeAll()
        skillBar = createBar("§6§l金簪立界", BarColor.YELLOW, BarStyle.SOLID)
        mainState = MainState.BOUNDARY_ACTIVE
        stateTicks = boundaryDurationTicks
    }

    private fun tickBoundaryActive(currentBoss: Evoker, players: List<Player>) {
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        val elapsed = boundaryDurationTicks - stateTicks
        if (elapsed % BOUNDARY_ZONE_INTERVAL_TICKS == 0) startBoundaryZones(players)
        updateBoundaryZones(players, currentBoss)
        stateTicks -= DRIVER_PERIOD_TICKS.toInt()
        skillBar?.progress = (stateTicks.toDouble() / boundaryDurationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        if (stateTicks > 0) return
        clearBoundaryZones()
        skillBar?.removeAll(); skillBar = null
        mainState = MainState.BOUNDARY_RETURN
        stateTicks = BOUNDARY_FLIGHT_TICKS
        boundaryFlightStart = currentBoss.location.clone()
        boundaryReturnTarget = players.minByOrNull { it.location.distanceSquared(currentBoss.location) }
            ?.location?.clone()?.apply { y = 4.0 }
            ?: plazaCenter()
    }

    private fun tickBoundaryReturn(currentBoss: Evoker, players: List<Player>) {
        val start = boundaryFlightStart ?: currentBoss.location
        val target = boundaryReturnTarget ?: players.minByOrNull { it.location.distanceSquared(currentBoss.location) }?.location ?: plazaCenter()
        stateTicks -= DRIVER_PERIOD_TICKS.toInt()
        currentBoss.teleport(lerp(start, target, 1.0 - stateTicks.toDouble() / BOUNDARY_FLIGHT_TICKS))
        world.spawnParticle(Particle.END_ROD, currentBoss.location, 5, 0.35, 0.3, 0.35, 0.03)
        if (stateTicks <= 0) finishMainSkill(currentBoss)
    }

    private fun startBoundaryZones(players: List<Player>) {
        if (players.isEmpty()) return
        val nearest = players.minByOrNull { it.location.distanceSquared(boundaryPerch()) }
        val farthest = players.maxByOrNull { it.location.distanceSquared(boundaryPerch()) }
        listOfNotNull(nearest, farthest).distinctBy(Player::getUniqueId).forEach { player ->
            val zone = BoundaryZone(player.uniqueId)
            sampleBoundaryPath(zone, player.location)
            boundaryZones += zone
        }
    }

    private fun updateBoundaryZones(players: List<Player>, currentBoss: Evoker) {
        val iterator = boundaryZones.iterator()
        while (iterator.hasNext()) {
            val zone = iterator.next()
            zone.ageTicks += DRIVER_PERIOD_TICKS.toInt()
            if (zone.ageTicks < BOUNDARY_PATH_BUILD_TICKS && zone.ageTicks % BOUNDARY_PATH_SAMPLE_TICKS == 0) {
                Bukkit.getPlayer(zone.targetId)?.takeIf { it.isOnline && !it.isDead && it.world == world }
                    ?.let { sampleBoundaryPath(zone, it.location) }
            }
            if (zone.ageTicks < BOUNDARY_PATH_BUILD_TICKS) continue
            strikeBoundaryZone(zone, players, currentBoss)
            zone.displays.values.forEach { id ->
                Bukkit.getEntity(id)?.remove()
                trackedEntities.remove(id)
            }
            iterator.remove()
        }
    }

    private fun sampleBoundaryPath(zone: BoundaryZone, targetLocation: Location) {
        val previous = zone.lastSample
        val horizontalDistance = if (previous == null) 0.0 else sqrt(horizontalDistanceSquared(previous, targetLocation))
        val steps = ceil(horizontalDistance).toInt().coerceIn(1, BOUNDARY_MAX_INTERPOLATION_STEPS)
        repeat(steps) { step ->
            val progress = (step + 1).toDouble() / steps
            val sample = if (previous == null) targetLocation else lerp(previous, targetLocation, progress)
            placeBoundarySquare(zone, sample)
        }
        zone.lastSample = targetLocation.clone()
    }

    private fun placeBoundarySquare(zone: BoundaryZone, center: Location) {
        val centerX = floor(center.x).toInt()
        val centerZ = floor(center.z).toInt()
        val centerGround = groundAt(Location(world, centerX + 0.5, center.y, centerZ + 0.5, 0.0f, 0.0f))
        zone.eruptionCenters += BoundaryCell(centerX, centerGround.blockY, centerZ)
        for (dx in -1..1) for (dz in -1..1) {
            val ground = groundAt(Location(world, centerX + dx + 0.5, center.y, centerZ + dz + 0.5, 0.0f, 0.0f))
            val cell = BoundaryCell(centerX + dx, ground.blockY, centerZ + dz)
            if (!zone.markedCells.add(cell)) continue
            placeBoundaryDisplay(zone, cell)
        }
        val effectCenter = groundAt(Location(world, center.x, center.y, center.z, 0.0f, 0.0f)).apply {
            yaw = 0.0f
            pitch = 0.0f
        }
        world.spawnParticle(
            Particle.DUST,
            effectCenter.clone().add(0.0, 0.12, 0.0),
            18,
            1.35,
            0.04,
            1.35,
            0.0,
            GOLD_DUST
        )
        world.playSound(effectCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.42f, 1.75f)
    }

    private fun placeBoundaryDisplay(zone: BoundaryZone, cell: BoundaryCell) {
        val location = Location(world, cell.x + 0.5, cell.y + 0.08, cell.z + 0.5, 0.0f, 0.0f)
        val display = world.spawn(location, ItemDisplay::class.java) { entity ->
            entity.setRotation(0.0f, 0.0f)
            entity.setItemStack(ItemStack(Material.LIGHT_WEIGHTED_PRESSURE_PLATE))
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND)
            entity.brightness = Display.Brightness(15, 15)
            entity.isInvulnerable = true
            entity.isPersistent = false
            entity.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                AxisAngle4f(0f, 0f, 1f, 0f),
                Vector3f(2.2f, 2.2f, 2.2f),
                AxisAngle4f(0f, 0f, 1f, 0f)
            )
        }
        track(display)
        zone.displays[cell] = display.uniqueId
    }

    private fun strikeBoundaryZone(zone: BoundaryZone, players: List<Player>, currentBoss: Evoker) {
        // 粒子按路径中心发送并用大扩散覆盖 3x3，伤害仍按全部方格判定，避免逐格发包。
        zone.eruptionCenters.forEach { cell ->
            val base = Location(world, cell.x + 0.5, cell.y + 0.15, cell.z + 0.5)
            world.spawnParticle(Particle.FLASH, base.clone().add(0.0, 1.4, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(Particle.END_ROD, base.clone().add(0.0, 3.2, 0.0), 52, 1.45, 3.2, 1.45, 0.075)
            world.spawnParticle(Particle.DUST, base.clone().add(0.0, 1.8, 0.0), 44, 1.35, 1.8, 1.35, 0.03, LASER_YELLOW_DUST)
        }
        val soundCenter = zone.markedCells.firstOrNull()?.let { Location(world, it.x + 0.5, it.y + 0.5, it.z + 0.5) }
            ?: boundaryPerch()
        world.playSound(soundCenter, Sound.ITEM_TRIDENT_THUNDER, 1.45f, 1.15f)
        world.playSound(soundCenter, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.65f)
        players.filter { player ->
            zone.markedCells.any { cell ->
                abs(player.location.x - (cell.x + 0.5)) <= 0.75 &&
                    abs(player.location.z - (cell.z + 0.5)) <= 0.75
            }
        }.forEach { dealPhysicalDamage(it, boundaryDamage, currentBoss) }
    }

    private fun finishMainSkill(currentBoss: Evoker) {
        skillBar?.removeAll(); skillBar = null
        currentBoss.spell = Spellcaster.Spell.NONE
        currentBoss.setGravity(true)
        bossInvulnerable = false
        currentBoss.isInvulnerable = false
        mainState = MainState.BASIC
        basicState = BasicState.CHASE_NEAREST
        basicCastTicks = 0
        laserLockedTarget = null
    }

    private fun tickHeavenlyLaw(players: List<Player>) {
        if (currentLaw == null) return
        val law = currentLaw ?: return
        if (lawWarningRemaining > 0) {
            lawWarningRemaining -= DRIVER_PERIOD_TICKS.toInt()
            lawBar?.progress = (1.0 - lawWarningRemaining.toDouble() / lawWarningTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            if (lawWarningRemaining <= 0) {
                lawActiveRemaining = lawDurationTicks
                lawDamageAccumulator = 0
                lawBar?.setTitle(if (law == HeavenlyLaw.KEEP_APART) "§e§l天规·不得相近" else "§e§l天规·不得相离")
            }
            return
        }

        lawActiveRemaining -= DRIVER_PERIOD_TICKS.toInt()
        lawDamageAccumulator += DRIVER_PERIOD_TICKS.toInt()
        lawBar?.progress = (lawActiveRemaining.toDouble() / lawDurationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        if (lawDamageAccumulator >= lawDamageIntervalTicks) {
            lawDamageAccumulator -= lawDamageIntervalTicks
            punishLawViolations(players, law)
        }
        if (lawActiveRemaining <= 0) clearHeavenlyLaw()
    }

    private fun startHeavenlyLaw() {
        currentLaw = if (ThreadLocalRandom.current().nextBoolean()) HeavenlyLaw.KEEP_APART else HeavenlyLaw.STAY_TOGETHER
        lawWarningRemaining = lawWarningTicks
        val keepApart = currentLaw == HeavenlyLaw.KEEP_APART
        val solo = activePlayers().size == 1
        if (keepApart) {
            broadcast("§4§n王母娘娘§f: §f天规在此——不得相近。")
            broadcastAfter(
                20,
                if (solo) "§b牛郎：§f场中只余你一人——也不要靠近我和织女，七格之内同样会触发天罚！"
                else "§b牛郎：§f彼此散开！靠得太近会触发天罚！每二人之间不可相距在7格以内！"
            )
        } else {
            broadcast("§4§n王母娘娘§f: §f天规在此——不得相离。")
            broadcastAfter(
                20,
                if (solo) "§d织女：§f快靠近我或牛郎君！独自远离我们七格之外，同样会触发天罚！"
                else "§d织女：§f别落单！快找同伴会合，独行便是自寻死路！必须与至少一人相距7格以内！"
            )
        }
        lawBar = createBar(
            if (keepApart) "§e§l天规·不得相近 即将生效" else "§e§l天规·不得相离 即将生效",
            BarColor.YELLOW,
            BarStyle.SOLID
        ).also { it.progress = 0.0 }
        world.playSound(plazaCenter(), Sound.BLOCK_BELL_RESONATE, 1.35f, 0.55f)
    }

    private fun punishLawViolations(players: List<Player>, law: HeavenlyLaw) {
        val limitSquared = lawDistance * lawDistance
        val escortLocations = if (players.size == 1) listOf(niulang.location, zhinv.location) else emptyList()
        players.filter { player ->
            val hasNearbyPlayer = players.any { other ->
                other.uniqueId != player.uniqueId && other.location.distanceSquared(player.location) <= limitSquared
            }
            val hasNearbyEscort = escortLocations.any { it.distanceSquared(player.location) <= limitSquared }
            val hasNearby = hasNearbyPlayer || hasNearbyEscort
            if (law == HeavenlyLaw.KEEP_APART) hasNearby else !hasNearby
        }.forEach { player ->
            world.spawnParticle(Particle.ELECTRIC_SPARK, player.location.clone().add(0.0, 1.0, 0.0), 12, 0.5, 0.8, 0.5, 0.08)
            dealPhysicalDamage(player, lawDamage, boss)
        }
    }

    private fun clearHeavenlyLaw() {
        currentLaw = null
        lawWarningRemaining = 0
        lawActiveRemaining = 0
        lawDamageAccumulator = 0
        lawBar?.removeAll(); lawBar = null
    }

    private fun startTianhe(currentBoss: Evoker, players: List<Player>) {
        poolCooldown = poolCooldownTicks
        stage = Stage.TIANHE
        clearHeavenlyLaw()
        clearBoundaryZones()
        skillBar?.removeAll(); skillBar = null
        currentBoss.spell = Spellcaster.Spell.WOLOLO
        currentBoss.setGravity(false)
        currentBoss.isInvulnerable = true
        bossInvulnerable = true
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        mainState = MainState.BASIC
        basicState = BasicState.CHASE_NEAREST
        laserLockedTarget = null
        tianheFlightStart = currentBoss.location.clone()
        tianheFlightRemaining = BOUNDARY_FLIGHT_TICKS
        defeatedConservers = 0
        remainingConserverLocations.clear()
        remainingConserverLocations += conserverLocations().shuffled()
        pendingConserverLocation = takeNextConserverLocation()
        tianheCycleRemaining = 0
        tianheNextWaveRemaining = 0
        assignPlayerColors(players)
        tianheWarningRemaining = tianheWarningTicks
        broadcast("§4§n王母娘娘：§f天河倒悬，星轨逆行——尔等既敢擅闯，便尝尝这§e天穹颠倒§f的滋味。")
        broadcast("§6红蓝双色的彩带缠绕在你们身上，其中一方将随倒悬的天河§e浮空而起§f")
        broadcastAfter(
            20,
            "§b牛郎：§f你们身上被系上了红蓝双色的彩带。浮空之人，速去击杀城门上的§e守恒者§f，莫要耽搁！",
            "§d织女：§f未浮空者——红彩带靠近§c我§f，蓝彩带靠近§b牛郎§f。来缓解同伴落地时承受的制裁。"
        )
        world.playSound(plazaCenter(), Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 0.5f)
        world.playSound(currentBoss.location, Sound.ENTITY_WITHER_AMBIENT, 0.85f, 0.52f)
        world.playSound(currentBoss.location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.85f, 0.48f)
        updateTianheBars()
    }

    private fun tickTianhe(currentBoss: Evoker, players: List<Player>) {
        maintainTianheChannel(currentBoss)
        drawPlayerColors(players)
        drawInvertedRiverSky()

        if (tianheWarningRemaining > 0) {
            pendingConserverLocation?.let(::drawConserverSpawnWarning)
            tianheWarningRemaining -= DRIVER_PERIOD_TICKS.toInt()
            updateTianheBars()
            if (tianheWarningRemaining <= 0) {
                if (!spawnPendingConserver()) {
                    plugin.logger.warning("天河倒悬未能生成天河守恒者，已安全结束该机制。")
                    endTianhe(currentBoss)
                    return
                }
                beginTianheCycle(players, reassignColors = false)
            }
            return
        }

        if (tianheNextWaveRemaining > 0) {
            pendingConserverLocation?.let(::drawConserverSpawnWarning)
            tianheNextWaveRemaining -= DRIVER_PERIOD_TICKS.toInt()
            if (tianheNextWaveRemaining <= 0) {
                if (currentConserver() == null && !spawnPendingConserver()) {
                    plugin.logger.warning("天河倒悬下一轮守恒者生成失败，已安全结束该机制。")
                    endTianhe(currentBoss)
                    return
                }
                beginTianheCycle(players, reassignColors = true)
            }
            updateTianheBars()
            return
        }
        if (tianheCycleRemaining <= 0) return

        tianheCycleRemaining -= DRIVER_PERIOD_TICKS.toInt()
        maintainLevitation()
        if (tianheCycleRemaining <= 0) finishTianheCycle(conserverDefeated = false, scheduleNext = true)
        updateTianheBars()
    }

    private fun assignPlayerColors(players: List<Player>) {
        playerColors.clear()
        players.shuffled().forEachIndexed { index, player ->
            playerColors[player.uniqueId] = if (index % 2 == 0) StarColor.RED else StarColor.BLUE
        }
        syncTianheBarViewers(players)
    }

    private fun beginTianheCycle(players: List<Player>, reassignColors: Boolean) {
        if (currentConserver() == null) return
        if (reassignColors) assignPlayerColors(players)
        val availableColors = players.mapNotNull { playerColors[it.uniqueId] }.distinct()
        if (availableColors.isEmpty()) return
        currentFloatingColor = availableColors.random()
        floatingPlayers.clear()
        players.filter { playerColors[it.uniqueId] == currentFloatingColor }.forEach { player ->
            floatingPlayers += player.uniqueId
            player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, tianheFloatTicks + 40, 1, false, false, true))
            applyTianhePlayerModifiers(player)
        }
        tianheCycleRemaining = tianheFloatTicks
        tianheNextWaveRemaining = 0
        val colorName = if (currentFloatingColor == StarColor.RED) "§c红色" else "§b蓝色"
        broadcast("§d天河倒悬：佩戴$colorName§d彩带的玩家已经浮空！地面玩家立刻靠近自己彩带对应的牛郎或织女！")
        updateTianheBars()
    }

    private fun finishTianheCycle(conserverDefeated: Boolean, scheduleNext: Boolean) {
        val currentBoss = boss
        val players = activePlayers()
        val supporters = if (players.size > 1) countTianheSupporters(players) else 0
        val punishment = if (players.size <= 1) {
            if (conserverDefeated) tianheSoloSuccessDamage else tianheSoloFailureDamage
        } else {
            val groundedPlayers = players.count { it.uniqueId !in floatingPlayers }.coerceAtLeast(1)
            val mechanicCompletion = (supporters.toDouble() / groundedPlayers).coerceIn(0.0, 1.0)
            val mitigation = mechanicCompletion * tianheMaximumMitigationRatio
            tianhePunishmentDamage * (1.0 - mitigation)
        }
        floatingPlayers.mapNotNull(Bukkit::getPlayer).forEach { player ->
            player.removePotionEffect(PotionEffectType.LEVITATION)
            removeTianhePlayerModifiers(player)
            world.spawnParticle(Particle.FLASH, player.location, 1, 0.0, 0.0, 0.0, 0.0)
            dealPhysicalDamage(player, punishment, currentBoss, armorPenetration = 1.0)
        }
        if (!conserverDefeated) {
            broadcast("§c未能及时击杀天河守恒者！浮空者受到银河制裁！")
        }
        floatingPlayers.clear()
        currentFloatingColor = null
        tianheCycleRemaining = 0
        if (scheduleNext) tianheNextWaveRemaining = tianheNextWaveTicks
    }

    private fun endTianhe(currentBoss: Evoker) {
        floatingPlayers.mapNotNull(Bukkit::getPlayer).forEach {
            it.removePotionEffect(PotionEffectType.LEVITATION)
            removeTianhePlayerModifiers(it)
        }
        floatingPlayers.clear()
        currentFloatingColor = null
        tianheCycleRemaining = 0
        tianheNextWaveRemaining = 0
        tianheWarningRemaining = 0
        pendingConserverLocation = null
        remainingConserverLocations.clear()
        clearTianheBars()
        currentBoss.spell = Spellcaster.Spell.NONE
        stage = Stage.COMBAT
        mainState = MainState.BOUNDARY_RETURN
        stateTicks = BOUNDARY_FLIGHT_TICKS
        boundaryFlightStart = currentBoss.location.clone()
        boundaryReturnTarget = activePlayers().minByOrNull { it.location.distanceSquared(currentBoss.location) }?.location
            ?: plazaCenter()
        basicState = BasicState.CHASE_NEAREST
        laserLockedTarget = null
        poolCooldown = poolCooldownTicks
        broadcast("§a三名天河守恒者尽数倒下，倒悬的星轨终于恢复正常！")
        world.playSound(plazaCenter(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.25f, 1.1f)
    }

    private fun maintainLevitation() {
        floatingPlayers.mapNotNull(Bukkit::getPlayer).filter { it.isOnline && !it.isDead }.forEach { player ->
            if (!player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 30, 1, false, false, true))
            }
            if (player.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(tianheSpeedKey) == null ||
                player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.getModifier(tianheKnockbackKey) == null
            ) applyTianhePlayerModifiers(player)
        }
    }

    private fun applyTianhePlayerModifiers(player: Player) {
        val speedAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED)
        speedAttribute?.getModifier(tianheSpeedKey)?.let(speedAttribute::removeModifier)
        if (tianheSpeedBonus > 0.0) {
            speedAttribute?.addTransientModifier(AttributeModifier(tianheSpeedKey, tianheSpeedBonus, AttributeModifier.Operation.ADD_SCALAR))
        }
        val knockbackAttribute = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)
        knockbackAttribute?.getModifier(tianheKnockbackKey)?.let(knockbackAttribute::removeModifier)
        knockbackAttribute?.addTransientModifier(
            AttributeModifier(tianheKnockbackKey, 1.0, AttributeModifier.Operation.ADD_NUMBER)
        )
    }

    private fun removeTianhePlayerModifiers(player: Player) {
        val speedAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED)
        speedAttribute?.getModifier(tianheSpeedKey)?.let(speedAttribute::removeModifier)
        val knockbackAttribute = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)
        knockbackAttribute?.getModifier(tianheKnockbackKey)?.let(knockbackAttribute::removeModifier)
    }

    private fun updateTianheBars() {
        val conserver = currentConserver()
        val maximumHealth = conserver?.getAttribute(Attribute.MAX_HEALTH)?.value ?: conserverHealth
        val health = conserver?.health?.coerceAtLeast(0.0) ?: 0.0
        val seconds = ceil(tianheCycleRemaining.coerceAtLeast(0) / 20.0).toInt()
        val stateText = when {
            tianheWarningRemaining > 0 -> "守恒者将在${ceil(tianheWarningRemaining / 20.0).toInt()}秒后现身"
            tianheNextWaveRemaining > 0 -> "再次倒悬：${ceil(tianheNextWaveRemaining / 20.0).toInt()}秒"
            else -> "浮空剩余：${seconds}秒"
        }
        tianheBars.forEach { (color, bar) ->
            val colorText = if (color == StarColor.RED) "§c红色彩带" else "§b蓝色彩带"
            val role = if (color == currentFloatingColor) "§d浮空" else "§a地面"
            val conserverText = if (conserver == null) "§f守恒者等待现身" else "§f天河守恒者"
            bar.setTitle("§d§l天河倒悬 §7| $colorText §7| $role §7| $conserverText §7| $stateText")
            bar.progress = if (conserver != null) (health / maximumHealth.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            else when {
                tianheWarningRemaining > 0 -> (1.0 - tianheWarningRemaining.toDouble() / tianheWarningTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                else -> (tianheNextWaveRemaining.toDouble() / tianheNextWaveTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun drawPlayerColors(players: List<Player>) {
        if (elapsedTicks % 6 != 0) return
        players.forEach { player ->
            val dust = if (playerColors[player.uniqueId] == StarColor.RED) RED_DUST else BLUE_DUST
            repeat(5) { index ->
                val angle = elapsedTicks * 0.10 + index * 0.90
                val point = player.location.clone().add(cos(angle) * 0.58, 0.40 + index * 0.42, sin(angle) * 0.58)
                world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
            }
        }
    }

    private fun drawInvertedRiverSky() {
        if (elapsedTicks % 6 != 0) return
        repeat(3) {
            val x = ThreadLocalRandom.current().nextDouble(-596.0, -504.0)
            val z = ThreadLocalRandom.current().nextDouble(2370.0, 2497.0)
            val baseY = ThreadLocalRandom.current().nextDouble(7.0, 15.0)
            val dust = when (ThreadLocalRandom.current().nextInt(3)) {
                0 -> BLUE_DUST
                1 -> INVERTED_PURPLE_DUST
                else -> LASER_YELLOW_DUST
            }
            repeat(5) { step ->
                val point = Location(world, x, baseY + step * 0.9, z)
                world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
                if (step % 2 == 0) world.spawnParticle(Particle.END_ROD, point, 1, 0.08, 0.22, 0.08, 0.02)
            }
        }
    }

    private fun conserverLocations() = listOf(
            Location(world, -550.55, 20.0, 2366.80, 0.31f, 7.80f),
            Location(world, -501.30, 20.0, 2436.29, 90.14f, 5.70f),
            Location(world, -599.70, 20.0, 2436.86, 270.44f, 3.15f)
        )

    private fun takeNextConserverLocation(): Location? =
        if (remainingConserverLocations.isEmpty()) null else remainingConserverLocations.removeAt(0)

    private fun spawnPendingConserver(): Boolean {
        val location = pendingConserverLocation ?: return currentConserver() != null
        val entity = MobFactory.spawnMob(plugin, location, CONSERVER_ID, false) ?: return false
        configureFixedMob(entity, conserverHealth, conserverArmor)
        entity.isGlowing = true
        if (entity is Mob) entity.setAI(false)
        entity.setGravity(false)
        entity.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
        track(entity)
        conserverIds += entity.uniqueId
        pendingConserverLocation = null
        world.spawnParticle(Particle.FLASH, location.clone().add(0.0, 1.0, 0.0), 2, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.END_ROD, location.clone().add(0.0, 1.0, 0.0), 55, 0.8, 1.4, 0.8, 0.09)
        world.playSound(location, Sound.BLOCK_BEACON_ACTIVATE, 1.3f, 0.7f)
        return true
    }

    private fun currentConserver(): LivingEntity? = conserverIds.asSequence()
        .mapNotNull(Bukkit::getEntity)
        .filterIsInstance<LivingEntity>()
        .firstOrNull { it.isValid && !it.isDead }

    private fun maintainTianheChannel(currentBoss: Evoker) {
        currentBoss.velocity = Vector(0.0, 0.0, 0.0)
        currentBoss.setGravity(false)
        currentBoss.isInvulnerable = true
        bossInvulnerable = true
        currentBoss.spell = Spellcaster.Spell.WOLOLO
        if (tianheFlightRemaining > 0) {
            tianheFlightRemaining -= DRIVER_PERIOD_TICKS.toInt()
            val progress = 1.0 - tianheFlightRemaining.toDouble() / BOUNDARY_FLIGHT_TICKS
            currentBoss.teleport(lerp(tianheFlightStart ?: currentBoss.location, boundaryPerch(), progress))
        } else if (currentBoss.location.distanceSquared(boundaryPerch()) > 0.04) {
            currentBoss.teleport(boundaryPerch())
        }
    }

    private fun drawConserverSpawnWarning(location: Location) {
        if (elapsedTicks % 4 != 0) return
        val center = location.clone().add(0.0, 1.0, 0.0)
        val radius = 1.45 + 0.20 * sin(elapsedTicks * 0.12)
        repeat(12) { index ->
            val angle = 2.0 * PI * index / 12.0 + elapsedTicks * 0.04
            val point = center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, RED_WARNING_DUST, true)
        }
        world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, 3.0, 0.0), 24, 0.24, 3.2, 0.24, 0.025, null, true)
        world.spawnParticle(Particle.FIREWORK, center, 5, 0.45, 0.2, 0.45, 0.02, null, true)
    }

    private fun countTianheSupporters(players: List<Player>): Int {
        val radiusSquared = tianheMitigationRadius * tianheMitigationRadius
        return players.count { player ->
            if (player.uniqueId in floatingPlayers) return@count false
            val destination = when (playerColors[player.uniqueId]) {
                StarColor.BLUE -> niulang.location
                StarColor.RED -> zhinv.location
                null -> return@count false
            }
            player.location.distanceSquared(destination) <= radiusSquared
        }
    }

    private fun syncTianheBarViewers(players: List<Player>) {
        val redBar = tianheBars.getOrPut(StarColor.RED) {
            Bukkit.createBossBar("§d§l天河倒悬", BarColor.RED, BarStyle.SEGMENTED_10)
        }
        val blueBar = tianheBars.getOrPut(StarColor.BLUE) {
            Bukkit.createBossBar("§d§l天河倒悬", BarColor.BLUE, BarStyle.SEGMENTED_10)
        }
        redBar.removeAll()
        blueBar.removeAll()
        players.forEach { player ->
            if (playerColors[player.uniqueId] == StarColor.RED) redBar.addPlayer(player) else blueBar.addPlayer(player)
        }
    }

    private fun clearTianheBars() {
        tianheBars.values.forEach(BossBar::removeAll)
        tianheBars.clear()
    }

    private fun handleConserverDefeated(currentBoss: Evoker) {
        defeatedConservers++
        finishTianheCycle(conserverDefeated = true, scheduleNext = false)
        broadcast("§a天河守恒者已被击杀，倒悬暂时平息！§f进度：§e$defeatedConservers/3")
        if (defeatedConservers >= CONSERVER_COUNT) {
            endTianhe(currentBoss)
            return
        }
        pendingConserverLocation = takeNextConserverLocation()
        tianheNextWaveRemaining = tianheNextWaveTicks
        updateTianheBars()
    }

    private fun summonHeavenlySoldiers() {
        summonedIds.removeIf { Bukkit.getEntity(it)?.isValid != true }
        val capacity = (maxActiveSummons - summonedIds.size).coerceAtLeast(0)
        val amount = min(summonsPerCast, capacity)
        if (amount <= 0) return
        val frontAnchors = summonAnchors(Half.FRONT).shuffled().iterator()
        val backAnchors = summonAnchors(Half.BACK).shuffled().iterator()
        repeat(amount) { index ->
            val half = if (index % 2 == 0) Half.FRONT else Half.BACK
            val anchor = if (half == Half.FRONT && frontAnchors.hasNext()) frontAnchors.next()
            else if (half == Half.BACK && backAnchors.hasNext()) backAnchors.next()
            else plazaCenter()
            val mobId = SUMMON_POOL[index % SUMMON_POOL.size]
            val entity = MobFactory.spawnMob(plugin, anchor, mobId, false) ?: return@repeat
            applyDifficultyAttributes(entity)
            entity.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
            track(entity)
            summonedIds += entity.uniqueId
            summonHalves[entity.uniqueId] = half
            if (entity is Mob) activePlayers().minByOrNull { it.location.distanceSquared(entity.location) }?.let { entity.target = it }
            world.spawnParticle(Particle.END_ROD, anchor.clone().add(0.0, 1.0, 0.0), 18, 0.45, 0.8, 0.45, 0.08)
        }
    }

    private fun spawnBearer(half: Half) {
        val existing = bearerHalves.entries.firstOrNull { it.value == half && Bukkit.getEntity(it.key)?.isValid == true }
        if (existing != null) return
        val location = if (half == Half.FRONT) Location(world, -550.5, 4.0, 2402.5)
        else Location(world, -550.5, 4.0, 2470.5)
        val entity = MobFactory.spawnMob(plugin, location, BEARER_ID, false) ?: return
        configureFixedMob(entity, bearerHealth, bearerArmor)
        if (entity is Mob) entity.setAI(false)
        entity.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
        entity.persistentDataContainer.set(orderHalfKey, PersistentDataType.STRING, half.key)
        track(entity)
        bearerHalves[entity.uniqueId] = half
        world.spawnParticle(Particle.FIREWORK, location.clone().add(0.0, 1.0, 0.0), 28, 0.6, 1.0, 0.6, 0.08)
    }

    private fun dropJadeOrder(entity: LivingEntity, half: Half) {
        val stack = plugin.resourceManager.getItem(JADE_ORDER_ID) ?: ItemStack(Material.BOOK)
        val meta = stack.itemMeta
        meta.persistentDataContainer.set(resourceIdKey, PersistentDataType.STRING, JADE_ORDER_ID)
        meta.persistentDataContainer.set(orderHalfKey, PersistentDataType.STRING, half.key)
        stack.itemMeta = meta
        val item = world.dropItemNaturally(entity.location.clone().add(0.0, 0.7, 0.0), stack)
        track(item)
        world.spawnParticle(Particle.END_ROD, item.location, 24, 0.4, 0.6, 0.4, 0.06)
        world.playSound(item.location, Sound.ENTITY_ITEM_PICKUP, 1.2f, 0.65f)
    }

    private fun activateJadeOrder(half: Half) {
        val targetIds = (summonHalves.filterValues { it == half }.keys +
            bearerHalves.filterValues { it == half }.keys).toList()
        var hit = 0
        targetIds.mapNotNull(Bukkit::getEntity).filterIsInstance<LivingEntity>().filter { it.isValid && !it.isDead }.forEach { entity ->
            if (entity.uniqueId in bearerHalves) bearersKilledByOrder += entity.uniqueId
            world.spawnParticle(Particle.FLASH, entity.location.clone().add(0.0, 1.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
            entity.health = 0.0
            hit++
        }
        val halfName = if (half == Half.FRONT) "前半场" else "后半场"
        broadcast("§b瑶池令化作诛邪金光，直接消灭了$halfName§b的§f$hit§b名天兵！")
        world.playSound(if (half == Half.FRONT) niulang.location else zhinv.location, Sound.ITEM_TOTEM_USE, 1.1f, 1.25f)
    }

    private fun autoSubmitJadeOrders(players: List<Player>) {
        players.forEach { player ->
            val escort = listOf(niulang, zhinv)
                .filter { it.location.distanceSquared(player.location) <= JADE_ORDER_SUBMIT_RADIUS_SQUARED }
                .minByOrNull { it.location.distanceSquared(player.location) }
                ?: return@forEach
            val slot = player.inventory.contents.indexOfFirst { stack ->
                stack != null && resourceId(stack) == JADE_ORDER_ID
            }
            if (slot < 0) return@forEach
            val stack = player.inventory.getItem(slot) ?: return@forEach
            val halfKey = stack.itemMeta.persistentDataContainer.get(orderHalfKey, PersistentDataType.STRING)
            val half = Half.entries.firstOrNull { it.key == halfKey } ?: return@forEach
            if (stack.amount <= 1) player.inventory.setItem(slot, null) else stack.amount--
            player.sendActionBar("§b你靠近了${escort.customName ?: "牛郎或织女"}§b，瑶池令已自动提交！")
            activateJadeOrder(half)
        }
    }

    private fun updateChargedSpiders(players: List<Player>) {
        summonedIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>()
            .filter { mobId(it) == CHARGED_SPIDER_ID && it.isValid && !it.isDead }
            .forEach { spider ->
                val fuseAt = chargedSpiderFuseAt[spider.uniqueId]
                if (fuseAt != null) {
                    spider.setAI(false)
                    spider.velocity = Vector(0.0, 0.0, 0.0)
                    if (elapsedTicks % 4 == 0) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, spider.location.clone().add(0.0, 0.6, 0.0), 8, 0.5, 0.5, 0.5, 0.08)
                    }
                    if (System.currentTimeMillis() >= fuseAt) explodeChargedSpider(spider, players)
                    return@forEach
                }
                val target = players.minByOrNull { it.location.distanceSquared(spider.location) } ?: return@forEach
                spider.target = target
                if (target.location.distanceSquared(spider.location) <= 9.0) {
                    chargedSpiderFuseAt[spider.uniqueId] = System.currentTimeMillis() + CHARGED_SPIDER_FUSE_MILLIS
                    spider.setAI(false)
                    spider.velocity = Vector(0.0, 0.0, 0.0)
                }
            }
    }

    private fun explodeChargedSpider(spider: Mob, players: List<Player>) {
        val location = spider.location.clone().add(0.0, 0.5, 0.0)
        world.spawnParticle(Particle.EXPLOSION, location, 3, 0.4, 0.4, 0.4, 0.0)
        world.spawnParticle(Particle.END_ROD, location, 55, 2.2, 1.0, 2.2, 0.16)
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.1f, 1.25f)
        players.filter { it.location.distanceSquared(spider.location) <= CHARGED_SPIDER_RADIUS_SQUARED }
            .forEach { dealPhysicalDamage(it, CHARGED_SPIDER_DAMAGE * attributeMultiplier, spider) }
        summonedIds.remove(spider.uniqueId)
        summonHalves.remove(spider.uniqueId)
        chargedSpiderFuseAt.remove(spider.uniqueId)
        trackedEntities.remove(spider.uniqueId)
        spider.remove()
    }

    private fun updateSummonTargets(players: List<Player>) {
        if (players.isEmpty()) return
        summonedIds.removeIf { Bukkit.getEntity(it)?.isValid != true }
        summonedIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>()
            .filter { mobId(it) != CHARGED_SPIDER_ID }
            .forEach { mob ->
                val target = players.minByOrNull { it.location.distanceSquared(mob.location) }
                if (target != null && mob.target != target) mob.target = target
            }
    }

    private fun prepareEscorts() {
        niulang.teleport(Location(world, -523.30, 4.00, 2384.30, 357.26f, 2.10f))
        zhinv.teleport(Location(world, -577.44, 4.00, 2384.70, 321.71f, -3.00f))
        listOf(niulang, zhinv).forEach { escort ->
            escort.setAI(false)
            escort.isInvulnerable = true
            escort.isCollidable = false
            escort.velocity = Vector(0.0, 0.0, 0.0)
        }
    }

    private fun spawnBoss() {
        val entity = MobFactory.spawnMob(
            plugin,
            Location(world, -550.58, 6.0, 2427.82, -179.70f, 0.0f),
            BOSS_ID,
            false
        ) as? Evoker
            ?: return
        boss = entity
        configureFixedMob(entity, bossHealth, bossArmor)
        entity.addScoreboardTag(INSTANCE_BOSS_TAG)
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = bossSpeed
        entity.getAttribute(Attribute.SCALE)?.baseValue = bossScale
        entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        entity.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
        entity.isInvulnerable = true
        entity.canPickupItems = false
        Bukkit.getMobGoals().removeAllGoals(entity)
        entity.setAI(true)
        entity.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, 160, 0, false, false, false))
        equipBossHead(entity)
        track(entity)
    }

    private fun equipBossHead(entity: Evoker) {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta ?: return
        runCatching {
            val profile = Bukkit.createPlayerProfile(UUID.randomUUID())
            val textures = profile.textures
            textures.skin = URI(WANGMU_SKIN_URL).toURL()
            profile.setTextures(textures)
            meta.ownerProfile = profile
            head.itemMeta = meta
            entity.equipment?.helmet = head
            entity.equipment?.helmetDropChance = 0.0f
        }.onFailure { plugin.logger.warning("王母娘娘头颅纹理设置失败：${it.message}") }
    }

    private fun revealBoss(currentBoss: Evoker) {
        val center = currentBoss.location.clone().add(0.0, 1.1, 0.0)
        world.spawnParticle(Particle.FLASH, center, 4, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.FIREWORK, center, 150, 1.8, 2.8, 1.8, 0.14)
        world.spawnParticle(Particle.END_ROD, center, 180, 2.1, 3.2, 2.1, 0.12)
        world.spawnParticle(Particle.ENCHANT, center, 140, 2.4, 2.8, 2.4, 0.18)
        repeat(4) { ring ->
            val radius = 0.9 + ring * 0.65
            val y = -0.85 + ring * 0.62
            repeat(40) { index ->
                val angle = 2.0 * PI * index / 40.0
                val point = center.clone().add(cos(angle) * radius, y, sin(angle) * radius)
                world.spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    if ((index + ring) % 2 == 0) LASER_GOLD_DUST else LASER_YELLOW_DUST
                )
            }
        }
        repeat(44) { index ->
            val point = center.clone().add(0.0, -0.8 + index * 0.14, 0.0)
            world.spawnParticle(Particle.DUST, point, 2, 0.12, 0.02, 0.12, 0.0, LASER_YELLOW_DUST)
        }
        world.playSound(center, Sound.ITEM_TOTEM_USE, 1.55f, 0.62f)
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.45f, 0.78f)
        world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.25f, 0.62f)
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.1f, 0.7f)
    }

    private fun drawEscortPrison(escort: Villager) {
        val center = escort.location.clone().add(0.0, 1.0, 0.0)
        repeat(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            val point = center.clone().add(cos(angle) * 1.35, (index % 4) * 0.45 - 0.65, sin(angle) * 1.35)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, GOLD_DUST)
        }
    }

    private fun drawEscortPrisonAmbient(escort: Villager) {
        val center = escort.location.clone().add(0.0, 1.0, 0.0)
        repeat(4) { index ->
            val angle = 2.0 * PI * index / 4.0 + elapsedTicks * 0.035
            val lower = center.clone().add(cos(angle) * 1.3, -0.55, sin(angle) * 1.3)
            val upper = center.clone().add(cos(-angle) * 1.3, 0.75, sin(-angle) * 1.3)
            world.spawnParticle(Particle.DUST, lower, 1, 0.0, 0.0, 0.0, 0.0, GOLD_DUST)
            world.spawnParticle(Particle.DUST, upper, 1, 0.0, 0.0, 0.0, 0.0, GOLD_DUST)
        }
    }

    private fun spawnCloud(currentBoss: Evoker) {
        world.spawnParticle(Particle.CLOUD, currentBoss.location.clone().add(0.0, 0.08, 0.0), 5, 0.65, 0.12, 0.65, 0.015)
    }

    private fun moveToward(entity: LivingEntity, target: Location) {
        val delta = target.toVector().subtract(entity.location.toVector()).setY(0.0)
        if (delta.lengthSquared() < 0.01) {
            entity.velocity = Vector(0.0, entity.velocity.y, 0.0)
            return
        }
        face(entity, target)
        entity.velocity = delta.normalize().multiply(bossSpeed.coerceIn(0.15, 0.45)).setY(entity.velocity.y.coerceIn(-0.15, 0.15))
    }

    private fun freezeAndFace(entity: LivingEntity, target: Player) {
        entity.velocity = Vector(0.0, 0.0, 0.0)
        face(entity, target.location)
    }

    private fun face(entity: LivingEntity, target: Location) {
        val dx = target.x - entity.location.x
        val dz = target.z - entity.location.z
        if (dx * dx + dz * dz < 0.001) return
        entity.setRotation(Math.toDegrees(atan2(-dx, dz)).toFloat(), 0.0f)
    }

    private fun fixedDirection(start: Location, target: Location, fallback: Vector): Vector {
        val direction = target.toVector().subtract(start.toVector())
        return if (direction.lengthSquared() < 0.0001) fallback.clone().normalize() else direction.normalize()
    }

    private fun configureFixedMob(entity: LivingEntity, health: Double, armor: Double) {
        entity.getAttribute(Attribute.MAX_HEALTH)?.apply {
            baseValue = health
            entity.health = health
        }
        entity.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, armor)
    }

    private fun applyDifficultyAttributes(entity: LivingEntity) {
        entity.getAttribute(Attribute.MAX_HEALTH)?.let { attribute ->
            val health = (attribute.baseValue * attributeMultiplier).coerceAtLeast(1.0)
            attribute.baseValue = health
            entity.health = health
        }
        entity.persistentDataContainer.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE)?.let {
            entity.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, it * attributeMultiplier)
        }
        entity.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE)?.let {
            entity.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, it * attributeMultiplier)
        }
    }

    private fun dealPhysicalDamage(
        target: LivingEntity,
        amount: Double,
        source: LivingEntity?,
        armorPenetration: Double = 0.0
    ) {
        if (!target.isValid || target.isDead || amount <= 0.0) return
        target.setMetadata(PHYSICAL_SKILL_METADATA, FixedMetadataValue(plugin, true))
        if (armorPenetration > 0.0) {
            target.setMetadata(
                CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA,
                FixedMetadataValue(plugin, armorPenetration.coerceIn(0.0, 1.0))
            )
        }
        target.noDamageTicks = 0
        if (source != null && source.isValid) target.damage(amount, source) else target.damage(amount)
    }

    private fun activePlayers(): List<Player> = playerIds.mapNotNull(Bukkit::getPlayer).filter { player ->
        player.isOnline && !player.isDead && player.world == world && player.scoreboardTags.contains(playerTag) &&
            player.gameMode != GameMode.SPECTATOR && player.gameMode != GameMode.CREATIVE
    }

    private fun broadcast(message: String) = activePlayers().forEach { it.sendMessage(message) }

    private fun broadcastDialogue(vararg messages: String) {
        messages.forEachIndexed { index, message ->
            if (index == 0) broadcast(message)
            else timedDialogues += TimedDialogue(elapsedTicks + index * DIALOGUE_INTERVAL_TICKS, message)
        }
    }

    private fun broadcastAfter(delayTicks: Int, vararg messages: String) {
        messages.forEach { message ->
            timedDialogues += TimedDialogue(elapsedTicks + delayTicks.coerceAtLeast(0), message)
        }
    }

    private fun flushTimedDialogues() {
        val iterator = timedDialogues.iterator()
        while (iterator.hasNext()) {
            val line = iterator.next()
            if (line.deliverAtTick <= elapsedTicks) {
                broadcast(line.message)
                iterator.remove()
            }
        }
    }

    private fun createBar(title: String, color: BarColor, style: BarStyle): BossBar =
        Bukkit.createBossBar(title, color, style).also { bar ->
            activePlayers().forEach(bar::addPlayer)
            bar.progress = 1.0
        }

    private fun updateBossBar(currentBoss: Evoker) {
        bossBar?.progress = (currentBoss.health / bossHealth).coerceIn(0.0, 1.0)
    }

    private fun clearAllBars() {
        listOf(bossBar, skillBar, orderBar, lawBar).forEach { it?.removeAll() }
        clearTianheBars()
        bossBar = null
        skillBar = null
        orderBar = null
        lawBar = null
    }

    private fun clearBoundaryZones() {
        boundaryZones.flatMap { it.displays.values }.forEach { id ->
            Bukkit.getEntity(id)?.remove()
            trackedEntities.remove(id)
        }
        boundaryZones.clear()
    }

    private fun track(entity: Entity) {
        entity.addScoreboardTag(entityTag)
        entity.persistentDataContainer.set(dungeonEntityKey, PersistentDataType.BYTE, 1)
        if (entity is LivingEntity && mobId(entity) != null) {
            QixiCollisionSupport.enableProjectileHits(entity)
        }
        trackedEntities += entity.uniqueId
    }

    private fun actualAttacker(entity: Entity): LivingEntity? = when (entity) {
        is Projectile -> entity.shooter as? LivingEntity
        is LivingEntity -> entity
        else -> null
    }

    private fun mobId(entity: LivingEntity): String? =
        entity.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING)

    private fun resourceId(stack: ItemStack): String? =
        stack.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)

    private fun summonAnchors(half: Half): List<Location> {
        val zRange = if (half == Half.FRONT) 2369..2421 step 4 else 2451..2499 step 4
        return listOf(-540.0, -562.0).flatMap { x -> zRange.map { z -> Location(world, x + 0.5, 4.0, z + 0.5) } }
    }

    private fun groundAt(location: Location): Location {
        val result = location.clone()
        for (y in min(8, location.blockY + 2) downTo 2) {
            if (world.getBlockAt(location.blockX, y, location.blockZ).type.isSolid) {
                result.y = y + 1.02
                return result
            }
        }
        result.y = 4.02
        return result
    }

    private fun plazaCenter() = Location(world, -551.0, 4.0, 2428.0)

    private fun boundaryPerch() = Location(world, -550.46, 15.63, 2421.38, 179.30f, 1.35f)

    private fun lerp(start: Location, end: Location, progress: Double): Location {
        val t = progress.coerceIn(0.0, 1.0)
        return Location(
            world,
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t,
            start.z + (end.z - start.z) * t,
            start.yaw + (end.yaw - start.yaw) * t.toFloat(),
            start.pitch + (end.pitch - start.pitch) * t.toFloat()
        )
    }

    private fun horizontalDistanceSquared(first: Location, second: Location): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun distanceToSegmentSquared(point: Location, start: Location, end: Location): Double {
        val segment = end.toVector().subtract(start.toVector())
        val lengthSquared = segment.lengthSquared()
        if (lengthSquared < 0.0001) return point.distanceSquared(start)
        val t = point.toVector().subtract(start.toVector()).dot(segment).div(lengthSquared).coerceIn(0.0, 1.0)
        val closest = start.toVector().add(segment.multiply(t))
        return point.toVector().distanceSquared(closest)
    }

    private fun secondsToTicks(seconds: Double): Int = ceil(seconds.coerceAtLeast(0.05) * 20.0).toInt()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        val currentBoss = boss
        if (event.entity == currentBoss) {
            if (bossInvulnerable) {
                event.isCancelled = true
                return
            }
            if (mainState == MainState.PRESSURE) event.damage *= pressureTakenDamageMultiplier
        }
        if (event.entity.uniqueId !in conserverIds) return
        val attacker = (event as? EntityDamageByEntityEvent)?.let { actualAttacker(it.damager) } as? Player
        if (attacker == null || attacker.uniqueId !in floatingPlayers) event.isCancelled = true
    }

    /** 王母娘娘被动：完全免疫通过标准击退事件施加的位移。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBossKnockback(event: EntityKnockbackEvent) {
        if (event.entity.uniqueId == boss?.uniqueId) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEscortInteract(event: PlayerInteractEntityEvent) {
        if (event.rightClicked.uniqueId != niulang.uniqueId && event.rightClicked.uniqueId != zhinv.uniqueId) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val player = event.victim as? Player ?: return
        if ((armorBrokenUntil[player.uniqueId] ?: 0L) > System.currentTimeMillis()) event.armor = 0.0
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJadeOrderPickup(event: EntityPickupItemEvent) {
        if (resourceId(event.item.itemStack) == JADE_ORDER_ID) trackedEntities.remove(event.item.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSummonTarget(event: EntityTargetLivingEntityEvent) {
        if (event.entity.uniqueId !in summonedIds) return
        val target = event.target as? Player
        if (target == null || target.uniqueId !in playerIds || !target.scoreboardTags.contains(playerTag)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val id = mobId(entity)
        if (entity.uniqueId !in trackedEntities && entity != boss) return
        event.drops.clear()
        event.droppedExp = 0
        if (id != null && id != BOSS_ID) addConfiguredTongxinDrop(event, id)
        trackedEntities.remove(entity.uniqueId)
        summonedIds.remove(entity.uniqueId)
        summonHalves.remove(entity.uniqueId)
        chargedSpiderFuseAt.remove(entity.uniqueId)

        bearerHalves.remove(entity.uniqueId)?.let { half ->
            if (!bearersKilledByOrder.remove(entity.uniqueId)) dropJadeOrder(entity, half)
        }
        if (entity.uniqueId in conserverIds) {
            conserverIds.remove(entity.uniqueId)
            if (stage == Stage.TIANHE) boss?.let(::handleConserverDefeated)
        }
        if (id == BOSS_ID && !completed) completeBattle(entity)
    }

    private fun addConfiguredTongxinDrop(event: EntityDeathEvent, mobId: String) {
        val drop = MobRegistry.get(mobId)?.drops?.firstOrNull { it.resourceId == TONGXIN_LOCK_ID } ?: return
        if (ThreadLocalRandom.current().nextDouble() > drop.chance) return
        val item = plugin.resourceManager.getItem(drop.resourceId) ?: run {
            plugin.logger.warning("七夕副本怪物 $mobId 的同心锁掉落资源不存在：${drop.resourceId}")
            return
        }
        item.amount = ThreadLocalRandom.current().nextInt(drop.min, drop.max + 1)
        if (item.amount > 0) event.drops += item
    }

    private fun completeBattle(entity: LivingEntity) {
        completed = true
        stage = Stage.ENDING
        phaseThreeBgmRemaining = 0
        activePlayers().forEach(::stopPhaseThreeBgm)
        timedDialogues.clear()
        clearAllBars()
        clearBoundaryZones()
        floatingPlayers.mapNotNull(Bukkit::getPlayer).forEach {
            it.removePotionEffect(PotionEffectType.LEVITATION)
            removeTianhePlayerModifiers(it)
        }
        world.spawnParticle(Particle.FIREWORK, entity.location.clone().add(0.0, 1.0, 0.0), 120, 2.0, 2.5, 2.0, 0.15)
        world.spawnParticle(Particle.END_ROD, entity.location.clone().add(0.0, 1.0, 0.0), 100, 1.6, 2.2, 1.6, 0.12)
        world.playSound(entity.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 0.85f)
        onFinished()
    }

    private fun tickPhaseThreeBgm() {
        phaseThreeBgmRemaining -= DRIVER_PERIOD_TICKS.toInt()
        if (phaseThreeBgmRemaining <= 0) playPhaseThreeBgm()
    }

    private fun playPhaseThreeBgm() {
        activePlayers().forEach { player ->
            stopPhaseThreeBgm(player)
            player.playSound(player.location, QIXI_PHASE_THREE_BGM, SoundCategory.RECORDS, 1.0f, 1.0f)
        }
        phaseThreeBgmRemaining = QIXI_PHASE_THREE_BGM_LOOP_TICKS
    }

    private fun stopPhaseThreeBgm(player: Player) {
        player.stopSound(QIXI_PHASE_THREE_BGM, SoundCategory.RECORDS)
        player.stopSound(QIXI_PHASE_THREE_BGM)
    }

    private companion object {
        const val DRIVER_PERIOD_TICKS = 2L
        // OGG 实际时长约 196.162 秒，向上取整到完整 tick，避免循环重叠。
        const val QIXI_PHASE_THREE_BGM_LOOP_TICKS = 3_924
        const val DIALOGUE_INTERVAL_TICKS = 40
        const val CLOUD_PERIOD_TICKS = 4
        const val BASIC_CHANNEL_TICKS = 20
        const val LASER_REPEAT_TICKS = 40
        const val LASER_WARNING_REFRESH_TICKS = 10
        const val LASER_WARNING_POINTS = 8
        const val LASER_BEAM_POINTS = 24
        const val LASER_LENGTH = 15.0
        const val LASER_HIT_WIDTH_SQUARED = 1.25 * 1.25
        const val BASIC_CAST_RANGE_SQUARED = 5.0 * 5.0
        const val LIGHTNING_AOE_RADIUS = 6.0
        const val LIGHTNING_AOE_RADIUS_SQUARED = LIGHTNING_AOE_RADIUS * LIGHTNING_AOE_RADIUS
        const val LIGHTNING_AMBIENT_STRIKES = 3
        const val ORDER_CHANNEL_TICKS = 40
        const val CONSERVER_COUNT = 3
        const val BOUNDARY_FLIGHT_TICKS = 30
        const val BOUNDARY_ZONE_INTERVAL_TICKS = 60
        const val BOUNDARY_PATH_BUILD_TICKS = 40
        const val BOUNDARY_PATH_SAMPLE_TICKS = 8
        const val BOUNDARY_MAX_INTERPOLATION_STEPS = 6
        const val CHARGED_SPIDER_FUSE_MILLIS = 3_000L
        const val CHARGED_SPIDER_RADIUS_SQUARED = 6.0 * 6.0
        const val CHARGED_SPIDER_DAMAGE = 25.0
        const val JADE_ORDER_SUBMIT_RADIUS_SQUARED = 5.0 * 5.0
        const val PHYSICAL_SKILL_METADATA = "hjh_physical_skill"
        const val INSTANCE_BOSS_TAG = "instance_boss"
        const val BOSS_ID = "wangmuniangniang"
        const val BEARER_ID = "qixi_chilingzhe"
        const val CONSERVER_ID = "qixi_tianheshouhengzhe"
        const val CHARGED_SPIDER_ID = "qixi_xishoujingheyinlingzhu"
        const val TONGXIN_LOCK_ID = "tongxinsuo"
        const val JADE_ORDER_ID = "yaochiling"
        const val WANGMU_SKIN_URL = "https://textures.minecraft.net/texture/431e13457fa7399ee896bbdd9cf02a8953c11267f58c986f4dbe5ee96615"
        val SUMMON_POOL = listOf("xinghetongling", "qixi_qianghuayinhebuwei", "qixi_qianghuayinyugongwei", CHARGED_SPIDER_ID)
        val GOLD_DUST = Particle.DustOptions(Color.fromRGB(255, 205, 45), 1.2f)
        val LASER_GOLD_DUST = Particle.DustOptions(Color.fromRGB(255, 176, 24), 1.35f)
        val LASER_YELLOW_DUST = Particle.DustOptions(Color.fromRGB(255, 244, 132), 1.20f)
        val RED_DUST = Particle.DustOptions(Color.fromRGB(255, 55, 65), 1.15f)
        val RED_WARNING_DUST = Particle.DustOptions(Color.fromRGB(255, 35, 35), 1.0f)
        val BLUE_DUST = Particle.DustOptions(Color.fromRGB(65, 145, 255), 1.15f)
        val INVERTED_PURPLE_DUST = Particle.DustOptions(Color.fromRGB(180, 105, 255), 1.15f)
    }
}
