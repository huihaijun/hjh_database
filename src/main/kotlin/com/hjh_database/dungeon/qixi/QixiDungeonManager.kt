package com.hjh_database.dungeon.qixi

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.impl.side.Side_Qixi_StarWish
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.spawner.MobFactory
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.data.type.Lantern
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.io.File
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val QIXI_PHASE_ONE_TWO_BGM = "hjh:bgm_instance_queqiaoxinyuan1"
internal const val QIXI_PHASE_THREE_BGM = "hjh:bgm_instance_queqiaoxinyuan2"

internal enum class QixiDifficulty(val displayName: String, val color: String, val configKey: String) {
    EASY("简单难度", "§a", "easy"),
    HARD("困难难度", "§c", "hard")
}

/** 鹊桥星愿副本（当前完成鹊桥唤醒、护送和七夕广场测试落点）。 */
class QixiDungeonManager(private val plugin: Hjh_database) : Listener {
    init {
        // 清理服务器异常停止后可能遗留的无推挤队伍；新副本会按需重新创建。
        QixiCollisionSupport.clear()
    }

    private enum class Phase { INTRO, AWAKENING, ESCORT_DIALOGUE, ESCORT, TRANSITION, THIRD_PHASE }
    private enum class AwakeningMechanic { SILVER_SPIDERS, STAR_COMMANDERS, CHAOS_WALKERS }
    private data class DifficultySettings(
        val attributeMultiplier: Double,
        val crystalProgressMin: Int,
        val crystalProgressMax: Int,
        val escortMaxHealth: Int,
        val featherProgressRatio: Double,
        val otherEscortInvulnerableMillis: Long,
        val otherEscortInvulnerabilityCooldownMillis: Long
    )

    private data class PendingDifficultySelection(
        val token: String,
        val playerIds: MutableSet<UUID>,
        val selectorId: UUID,
        var remainingSeconds: Int = DIFFICULTY_SELECTION_SECONDS,
        var task: BukkitTask? = null
    )

    private data class Escort(
        val displayName: String,
        val entityId: UUID,
        val start: Location,
        val target: Location,
        var maxHealth: Int,
        var health: Int = maxHealth,
        var healingActions: Int = 0
    )

    private data class FeatherShield(
        var amount: Double,
        var expiresAt: Long,
        var observedAbsorption: Double
    )

    private data class ExitReward(
        val experience: Int,
        val starSand: Int,
        val exchangeTickets: Int,
        val conversionTickets: Int
    )

    private data class Session(
        val worldName: String,
        val playerIds: MutableSet<UUID>,
        val difficulty: QixiDifficulty,
        val entityIds: MutableSet<UUID> = HashSet(),
        val tasks: MutableSet<BukkitTask> = HashSet(),
        val chunkTickets: MutableSet<Pair<Int, Int>> = HashSet(),
        val starLockIds: MutableSet<UUID> = HashSet(),
        val retaliationReadyAt: MutableMap<UUID, Long> = HashMap(),
        val escortDamageReadyAt: MutableMap<UUID, Long> = HashMap(),
        val escortInvulnerableUntil: MutableMap<UUID, Long> = HashMap(),
        var escortProtectionReadyAt: Long = 0L,
        var featherReadyAt: Long = 0L,
        val fallTicks: MutableMap<UUID, Int> = HashMap(),
        val featherShields: MutableMap<UUID, FeatherShield> = HashMap(),
        val announcedAwakeningMechanics: MutableSet<AwakeningMechanic> = HashSet(),
        val spiderCrystals: MutableMap<UUID, Int> = HashMap(),
        val spiderFuseEndsAt: MutableMap<UUID, Long> = HashMap(),
        val chaosArrivedAt: MutableMap<UUID, Long> = HashMap(),
        val spiritGroundCrystals: MutableMap<UUID, Int> = HashMap(),
        val spiritCrystalTargets: MutableMap<UUID, UUID> = HashMap(),
        val spiritBlessingCharge: MutableMap<UUID, Double> = HashMap(),
        val mobOutOfBoundsSince: MutableMap<UUID, Long> = HashMap(),
        var phase: Phase = Phase.INTRO,
        var niulang: Escort? = null,
        var zhinv: Escort? = null,
        var awakeningProgress: Int = 0,
        var escortProgress: Double = 0.0,
        var awakeningBar: BossBar? = null,
        var niulangSpiritBar: BossBar? = null,
        var zhinvSpiritBar: BossBar? = null,
        var meetingBar: BossBar? = null,
        var niulangBar: BossBar? = null,
        var zhinvBar: BossBar? = null,
        var starLockBar: BossBar? = null,
        var starLockBarLabel: String? = null,
        var starLockLastDisplayedHealth: Int = -1,
        var starLockCenter: Location? = null,
        var starLockDirection: Vector? = null,
        var starLockScheduleVersion: Int = 0,
        var starLocked: Boolean = false,
        var phaseOneTwoBgmTask: BukkitTask? = null,
        var thirdPhase: QixiThirdPhaseController? = null,
        var ending: Boolean = false
    )

    private val config: YamlConfiguration
    private val worldName: String
    private val minLevel: Int
    private val minRarity: Int
    private val maxPlayers: Int
    private val initialGuards: Int
    private val guardsPerPlayer: Int
    private val phaseOneReplenishTicks: Long
    private val phaseOneTimeoutTicks: Long
    private val escortDurationTicks: Double
    private val elitePerPlayer: Int
    private val phaseTwoReplenishTicks: Long
    private val phaseTwoStarCommanderChance: Double
    private val phaseTwoStarLockTicks: Long
    private val difficultySettings: Map<QixiDifficulty, DifficultySettings>
    private val escortUpdateTicks: Long
    private val spiritUpdateTicks: Long

    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val dungeonEntityKey = NamespacedKey(plugin, "qixi_entity")
    private val dungeonDropItemKey = NamespacedKey(plugin, "qixi_drop_item")
    private val bridgeRecoveryKey = NamespacedKey(plugin, "qixi_bridge_recovery")
    private val bridgeRecoveryXKey = NamespacedKey(plugin, "qixi_bridge_recovery_x")
    private val bridgeRecoveryYKey = NamespacedKey(plugin, "qixi_bridge_recovery_y")
    private val bridgeRecoveryZKey = NamespacedKey(plugin, "qixi_bridge_recovery_z")
    private val featherShieldKey = NamespacedKey(plugin, "qixi_feather_shield")
    private var session: Session? = null
    private var pendingDifficultySelection: PendingDifficultySelection? = null

    init {
        val file = File(plugin.dataFolder, "dungeon/qixi.yml")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            plugin.saveResource("dungeon/qixi.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file)
        QixiAccessPolicy.reload(plugin)
        worldName = config.getString("world", "world") ?: "world"
        minLevel = config.getInt("entry.min-level", 40)
        minRarity = config.getInt("entry.min-total-rarity", 40)
        maxPlayers = config.getInt("entry.max-players", 5).coerceIn(1, 5)
        initialGuards = config.getInt("phase-one.initial-guards", 60).coerceAtLeast(0)
        guardsPerPlayer = config.getInt("phase-one.guards-per-player", 12).coerceAtLeast(0)
        phaseOneReplenishTicks = secondsToTicks(config.getInt("phase-one.replenish-seconds", 5))
        phaseOneTimeoutTicks = secondsToTicks(config.getInt("phase-one.timeout-seconds", 600))
        escortDurationTicks = secondsToTicks(config.getInt("phase-two.escort-seconds", 120)).toDouble()
        elitePerPlayer = config.getInt("phase-two.elite-guards-per-player", 12).coerceAtLeast(0)
        phaseTwoReplenishTicks = secondsToTicks(config.getInt("phase-two.replenish-seconds", 5))
        phaseTwoStarCommanderChance = config.getDouble("phase-two.star-commander-chance", 0.20).coerceIn(0.0, 1.0)
        phaseTwoStarLockTicks = secondsToTicks(config.getInt("phase-two.star-lock-seconds", 18))
        difficultySettings = QixiDifficulty.entries.associateWith { difficulty ->
            val path = "difficulty.${difficulty.configKey}"
            val easy = difficulty == QixiDifficulty.EASY
            val defaultCrystalMin = if (easy) 2 else 1
            val defaultCrystalMax = if (easy) 3 else 2
            val defaultEscortHealth = if (easy) 120 else 80
            val crystalProgressMin = config.getInt("$path.crystal-progress-min", defaultCrystalMin).coerceAtLeast(0)
            DifficultySettings(
                attributeMultiplier = config.getDouble("$path.attribute-multiplier", if (easy) 0.75 else 1.0)
                    .coerceIn(0.05, 2.0),
                crystalProgressMin = crystalProgressMin,
                crystalProgressMax = config.getInt("$path.crystal-progress-max", defaultCrystalMax).coerceAtLeast(crystalProgressMin),
                escortMaxHealth = config.getInt("$path.escort-max-health", defaultEscortHealth).coerceAtLeast(1),
                featherProgressRatio = (config.getDouble(
                    "$path.feather-progress-percent",
                    if (easy) 10.0 else config.getDouble("phase-two.feather-progress-percent", 5.0)
                ) / 100.0).coerceIn(0.0, 1.0),
                otherEscortInvulnerableMillis = if (easy) {
                    (config.getDouble("$path.other-escort-invulnerable-seconds", 3.0) * 1000.0).toLong().coerceAtLeast(0L)
                } else 0L,
                otherEscortInvulnerabilityCooldownMillis = if (easy) {
                    (config.getDouble("$path.other-escort-invulnerability-cooldown-seconds", 10.0) * 1000.0)
                        .toLong().coerceAtLeast(0L)
                } else 0L
            )
        }
        escortUpdateTicks = config.getLong("performance.escort-update-ticks", 4L).coerceIn(1L, 20L)
        spiritUpdateTicks = config.getLong("performance.spirit-update-ticks", 4L).coerceIn(2L, 20L)

        // 热重载或崩服重启后，在线玩家身上残留的旧实例标签不能进入新实例的选择范围。
        Bukkit.getOnlinePlayers().forEach {
            it.removeScoreboardTag(PLAYER_TAG)
            it.getAttribute(Attribute.MAX_ABSORPTION)?.let { attribute ->
                attribute.getModifier(featherShieldKey)?.let(attribute::removeModifier)
                it.absorptionAmount = it.absorptionAmount.coerceAtMost(attribute.value.coerceAtLeast(0.0))
            }
        }
        Bukkit.getWorld(worldName)?.let(::ensureExitBells)
    }

    private fun ensureExitBells(world: org.bukkit.World) {
        world.getBlockAt(EASY_EXIT_BELL_X, EXIT_BELL_Y, EASY_EXIT_BELL_Z).setBlockData(
            Bukkit.createBlockData("minecraft:bell[attachment=single_wall,facing=west,powered=false]"),
            false
        )
        world.getBlockAt(HARD_EXIT_BELL_X, EXIT_BELL_Y, HARD_EXIT_BELL_Z).setBlockData(
            Bukkit.createBlockData("minecraft:bell[attachment=single_wall,facing=east,powered=false]"),
            false
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onExitBellInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.world.name != worldName || block.type != Material.BELL || block.y != EXIT_BELL_Y) return
        val reward = when {
            block.x == EASY_EXIT_BELL_X && block.z == EASY_EXIT_BELL_Z -> ExitReward(
                experience = 1200,
                starSand = 2,
                exchangeTickets = 8,
                conversionTickets = 16
            )
            block.x == HARD_EXIT_BELL_X && block.z == HARD_EXIT_BELL_Z -> ExitReward(
                experience = 2500,
                starSand = 4,
                exchangeTickets = 32,
                conversionTickets = 32
            )
            else -> return
        }
        event.isCancelled = true
        grantExitReward(event.player, reward, block.location)
    }

    private fun grantExitReward(player: Player, reward: ExitReward, bellLocation: Location) {
        bellLocation.world.playSound(bellLocation.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_BELL_USE, 1.0f, 1.0f)
        val returnLocation = Location(
            bellLocation.world,
            QIXI_BRIDGE_RETURN_X,
            QIXI_BRIDGE_RETURN_Y,
            QIXI_BRIDGE_RETURN_Z,
            QIXI_BRIDGE_RETURN_YAW,
            QIXI_BRIDGE_RETURN_PITCH
        )
        player.teleport(returnLocation)
        plugin.playerManager.giveExp(player, reward.experience)
        giveExitResource(player, "xingsha", reward.starSand, "星砂")
        giveExitResource(player, "yuansuduihuanquan", reward.exchangeTickets, "元素兑换券")
        giveExitResource(player, "yuansuzhuanhuaquan", reward.conversionTickets, "元素转化券")
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)
        player.sendMessage(
            "§f你返回了鹊影桥，获得了" +
                "§b星砂 §f× §e${reward.starSand}§f、" +
                "§e经验 §f× §e${reward.experience}§f、" +
                "§b元素兑换券 §f× §e${reward.exchangeTickets}§f、" +
                "§b元素转化券 §f× §e${reward.conversionTickets}"
        )
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.15f)
    }

    private fun giveExitResource(player: Player, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("七夕副本离场奖励缺少资源：$resourceId")
            player.sendMessage("§c[错误] 未找到离场奖励：$displayName，请联系管理员。")
            return
        }
        item.amount = amount
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
            player.sendMessage("§e背包已满，$displayName 已掉落在鹊影桥脚下。")
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onEntranceInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.world.name != worldName || block.x != 1380 || block.y != 44 || block.z != 3021) return
        if (block.type != Material.LANTERN) return
        val lantern = block.blockData as? Lantern ?: return
        if (lantern.isHanging || lantern.isWaterlogged) return
        event.isCancelled = true

        if (session != null || pendingDifficultySelection != null) {
            event.player.sendMessage("§c鹊桥星愿秘境正在进行中，请稍后再试！")
            return
        }
        val platformPlayers = block.world.players.filter(::isOnEntryPlatform)
        val deniedPlayers = platformPlayers.filterNot { QixiAccessPolicy.isAllowed(plugin, it) }
        deniedPlayers.forEach(::removeInternalTestPlayer)
        if (event.player in deniedPlayers) return

        val candidates = platformPlayers.filter { it !in deniedPlayers }
        if (candidates.isEmpty()) {
            event.player.sendMessage("§c传送阵上没有可进入秘境的玩家！")
            return
        }
        if (candidates.size > maxPlayers) {
            candidates.forEach { it.sendMessage("§c鹊桥星愿至多允许§e${maxPlayers}§c名玩家进入！") }
            return
        }

        for (candidate in candidates) {
            plugin.playerManager.updateStats(candidate)
            val data = plugin.playerManager.getData(candidate.uniqueId)
            if (data == null) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c的数据尚未加载，无法进入秘境！") }
                return
            }
            if (data.lv < minLevel) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c等级不满${minLevel}级，无法进入秘境！") }
                return
            }
            if (data.totalRarity < minRarity) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c装备稀有度总和不满${minRarity}点，无法进入秘境！") }
                return
            }
        }
        startDifficultySelection(candidates)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDifficultySelectionCommand(event: PlayerCommandPreprocessEvent) {
        val parts = event.message.trim().split(Regex("\\s+"))
        if (parts.firstOrNull()?.lowercase() != DIFFICULTY_SELECTION_COMMAND) return
        event.isCancelled = true
        if (parts.size != 3) return
        val pending = pendingDifficultySelection ?: return
        if (event.player.uniqueId != pending.selectorId || parts[1] != pending.token) {
            event.player.sendMessage("§c这次难度选择并不属于你，或已经失效。")
            return
        }
        val difficulty = when (parts[2].lowercase()) {
            "easy" -> QixiDifficulty.EASY
            "hard" -> QixiDifficulty.HARD
            else -> return
        }
        completeDifficultySelection(pending, difficulty)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDifficultySelectionMove(event: PlayerMoveEvent) {
        val pending = pendingDifficultySelection ?: return
        if (event.player.uniqueId !in pending.playerIds) return
        val destination = event.to ?: return
        val origin = event.from
        if (origin.world == destination.world &&
            origin.x == destination.x && origin.y == destination.y && origin.z == destination.z
        ) return

        // 只锁定坐标，保留视角转动，避免玩家在选择期间走出 3x3 入场范围。
        event.to = origin.clone().apply {
            yaw = destination.yaw
            pitch = destination.pitch
        }
    }

    private fun startDifficultySelection(players: List<Player>) {
        if (players.isEmpty() || session != null || pendingDifficultySelection != null) return
        val selector = players.random()
        val pending = PendingDifficultySelection(
            token = UUID.randomUUID().toString().replace("-", "").take(12),
            playerIds = players.mapTo(LinkedHashSet()) { it.uniqueId },
            selectorId = selector.uniqueId
        )
        pendingDifficultySelection = pending

        val serializer = LegacyComponentSerializer.legacySection()
        selector.sendMessage("§d§l────────【鹊桥星愿】────────")
        selector.sendMessage("§f你被选为本次秘境的§e难度决定者§f。")
        selector.sendMessage("§7难度一旦选定，进入秘境后将无法更改。")
        selector.sendMessage("")
        selector.sendMessage(
            serializer.deserialize("§a§l[点击选择：简单难度]")
                .clickEvent(ClickEvent.runCommand("$DIFFICULTY_SELECTION_COMMAND ${pending.token} easy"))
                .hoverEvent(HoverEvent.showText(serializer.deserialize("§a推荐1～2人；敌人属性较低，奖励较为基础。")))
        )
        selector.sendMessage("§7推荐人数：§f1～2人")
        selector.sendMessage("§7敌人属性较低，多人强制合作机制较少，奖励较为基础。")
        selector.sendMessage("")
        selector.sendMessage(
            serializer.deserialize("§c§l[点击选择：困难难度]")
                .clickEvent(ClickEvent.runCommand("$DIFFICULTY_SELECTION_COMMAND ${pending.token} hard"))
                .hoverEvent(HoverEvent.showText(serializer.deserialize("§c推荐4～5人；可能出现多人合作机制，奖励远高于简单。")))
        )
        selector.sendMessage("§7推荐人数：§f4～5人")
        selector.sendMessage("§7敌人更加强大，可能出现多人合作机制，通关奖励远高于简单难度。")
        selector.sendMessage("")
        selector.sendMessage("§e请在§c10秒§e内点击上方文本作出选择。")

        players.filter { it.uniqueId != selector.uniqueId }.forEach {
            it.sendMessage("§d§l【鹊桥星愿】§e${selector.name}§f已被选为难度决定者，请等待其在§c10秒§f内作出选择。")
        }
        playDifficultyCountdown(selector, pending.remainingSeconds)
        pending.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (pendingDifficultySelection !== pending) return@Runnable
            pending.remainingSeconds--
            if (pending.remainingSeconds <= 0) {
                timeoutDifficultySelection(pending)
                return@Runnable
            }
            Bukkit.getPlayer(pending.selectorId)?.takeIf(Player::isOnline)?.let {
                playDifficultyCountdown(it, pending.remainingSeconds)
            } ?: timeoutDifficultySelection(pending)
        }, 20L, 20L)
    }

    private fun playDifficultyCountdown(player: Player, seconds: Int) {
        sendActionBar(player, "§e请在§c${seconds}§e秒内选择秘境难度")
        val pitch = if (seconds <= 3) 1.75f else (1.0f + (10 - seconds) * 0.06f)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.85f, pitch)
    }

    private fun completeDifficultySelection(pending: PendingDifficultySelection, difficulty: QixiDifficulty) {
        if (pendingDifficultySelection !== pending || session != null) return
        pending.task?.cancel()
        pending.task = null
        pendingDifficultySelection = null
        val onlinePlayers = pending.playerIds.mapNotNull(Bukkit::getPlayer).filter { it.isOnline }
        val deniedPlayers = onlinePlayers.filterNot { QixiAccessPolicy.isAllowed(plugin, it) }
        deniedPlayers.forEach(::removeInternalTestPlayer)
        val players = onlinePlayers.filter { it !in deniedPlayers }
        if (players.isEmpty()) return
        players.forEach {
            it.sendMessage(
                "§d§l【鹊桥星愿】§e${Bukkit.getOfflinePlayer(pending.selectorId).name ?: "队伍成员"}§f选择了" +
                    "${difficulty.color}${difficulty.displayName}§f，秘境即将开启！"
            )
        }
        startDungeon(players, difficulty)
    }

    private fun timeoutDifficultySelection(pending: PendingDifficultySelection) {
        if (pendingDifficultySelection !== pending) return
        pending.task?.cancel()
        pending.task = null
        pendingDifficultySelection = null
        pending.playerIds.mapNotNull(Bukkit::getPlayer).filter { it.isOnline }.forEach { player ->
            player.teleport(entryRemovalDestination() ?: return@forEach)
            player.sendMessage("§c§l【鹊桥星愿】§c长时间未作出难度选择，你们已被移出秘境进入选择区。")
        }
    }

    private fun removeInternalTestPlayer(player: Player) {
        entryRemovalDestination()?.let(player::teleport)
        player.sendMessage(QixiAccessPolicy.deniedMessage(plugin))
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f)
    }

    private fun entryRemovalDestination(): Location? {
        val world = Bukkit.getWorld(worldName) ?: return null
        return Location(world, 1333.90, 37.0, 3018.48, 270.11f, -1.65f)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onPhaseThreeTestItemUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK)
        ) return
        if (resourceId(event.item ?: return) != PHASE_THREE_TEST_ITEM_ID) return
        event.isCancelled = true

        val player = event.player
        if (!player.isOp && !player.hasPermission("hjh.admin")) {
            player.sendMessage("§c该测试道具仅限管理员使用。")
            return
        }
        val current = session
        if (current == null || current.ending || !isSessionPlayer(current, player)) {
            player.sendMessage("§c你必须处于正在进行的七夕副本中才能使用该测试道具。")
            return
        }
        if (current.phase == Phase.TRANSITION || current.phase == Phase.THIRD_PHASE) {
            player.sendMessage("§e副本已经进入第二阶段结束过场或第三阶段，无需再次快进。")
            return
        }

        fastForwardToThirdPhaseTransition(current)
        player.sendMessage("§a已快进至第二阶段结束，正在播放第三阶段转场剧情。")
        plugin.logger.info("管理员 ${player.name} 使用测试道具将七夕副本快进至第三阶段转场")
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onFeatherUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK)
        ) return
        if (resourceId(event.item ?: return) != GALAXY_ORDER_ID) return
        event.isCancelled = true

        val s = session
        if (s == null || !isSessionPlayer(s, event.player) || s.phase != Phase.ESCORT || s.ending) {
            event.player.sendMessage("§c星河令只能在护送牛郎与织女时使用！")
            return
        }
        val now = System.currentTimeMillis()
        if (now < s.featherReadyAt) {
            val remaining = ceil((s.featherReadyAt - now) / 1000.0).toInt().coerceAtLeast(1)
            sendActionBar(event.player, "§c星河令正在冷却，剩余§e${remaining}§c秒")
            return
        }

        val solo = activePlayers(s).size == 1
        val cooldownMillis = if (solo) SOLO_GALAXY_ORDER_COOLDOWN_MILLIS else FEATHER_COOLDOWN_MILLIS
        s.featherReadyAt = now + cooldownMillis
        consumeMainHandItem(event.player)
        if (s.starLocked && detonateStarLockWithGalaxyOrder(s)) {
            if (solo) {
                damageMobsNearEscorts(s, listOfNotNull(s.niulang, s.zhinv), event.player)
            }
            val cooldownSeconds = cooldownMillis / 1000L
            activePlayers(s).forEach {
                sendActionBar(it, "§b星河令引爆了星锁，全队共用冷却：§f${cooldownSeconds}秒")
            }
            return
        }
        val gained = min(settings(s).featherProgressRatio, 1.0 - s.escortProgress)
        s.escortProgress = min(1.0, s.escortProgress + gained)
        broadcast(s, "§b星河令化作流光，牛郎与织女的会面进度加快了§e${(gained * 100).roundToInt()}%§b！")

        val escorts = listOfNotNull(s.niulang, s.zhinv)
        if (solo) {
            escorts.forEach(::healEscortWithGalaxyOrder)
            damageMobsNearEscorts(s, escorts, event.player)
        } else {
            escorts.filter { it.health < it.maxHealth }
                .minByOrNull { it.health }
                ?.let(::healEscortWithGalaxyOrder)
        }

        grantFeatherShield(s, event.player, FEATHER_SHIELD_AMOUNT)
        event.player.world.spawnParticle(Particle.END_ROD, event.player.location.clone().add(0.0, 1.0, 0.0), 45, 1.0, 1.0, 1.0, 0.12)
        event.player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, event.player.location.clone().add(0.0, 1.0, 0.0), 35, 1.1, 1.2, 1.1, 0.07)
        updateEscortBars(s)
        val cooldownSeconds = cooldownMillis / 1000L
        activePlayers(s).forEach { sendActionBar(it, "§b星河令已生效，全队共用冷却：§f${cooldownSeconds}秒") }
        event.player.world.playSound(event.player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.35f)
    }

    private fun healEscortWithGalaxyOrder(escort: Escort) {
        val healed = min(FEATHER_HEAL_AMOUNT, escort.maxHealth - escort.health)
        if (healed <= 0) return
        escort.health += healed
        escortEntity(escort)?.let { npc ->
            npc.health = escort.health.toDouble()
            npc.world.spawnParticle(Particle.HEART, npc.location.clone().add(0.0, 1.2, 0.0), 18, 0.7, 0.9, 0.7, 0.08)
        }
    }

    private fun damageMobsNearEscorts(s: Session, escorts: List<Escort>, source: Player) {
        val centers = escorts.mapNotNull(::escortEntity).map { it.location }
        if (centers.isEmpty()) return
        val radiusSquared = SOLO_GALAXY_ORDER_DAMAGE_RADIUS * SOLO_GALAXY_ORDER_DAMAGE_RADIUS
        val targets = s.entityIds.asSequence()
            .mapNotNull(Bukkit::getEntity)
            .filterIsInstance<LivingEntity>()
            .filter { it.isValid && !it.isDead && mobId(it) != null && mobId(it) != STAR_LOCK_GUARD_ID }
            .filter { mob -> centers.any { center -> center.world == mob.world && center.distanceSquared(mob.location) <= radiusSquared } }
            .distinctBy(Entity::getUniqueId)
            .toList()
        targets.forEach { mob ->
            val maximumHealth = mob.getAttribute(Attribute.MAX_HEALTH)?.value ?: return@forEach
            mob.world.spawnParticle(Particle.SWEEP_ATTACK, mob.location.clone().add(0.0, 1.0, 0.0), 4, 0.45, 0.55, 0.45, 0.0)
            mob.world.spawnParticle(Particle.END_ROD, mob.location.clone().add(0.0, 1.0, 0.0), 12, 0.45, 0.65, 0.45, 0.07)
            mob.damage(maximumHealth * SOLO_GALAXY_ORDER_MAX_HEALTH_DAMAGE_RATIO, source)
        }
        if (targets.isNotEmpty()) {
            source.world.playSound(source.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.75f)
        }
    }

    private fun detonateStarLockWithGalaxyOrder(s: Session): Boolean {
        val guard = s.starLockIds.asSequence()
            .mapNotNull(Bukkit::getEntity)
            .filterIsInstance<LivingEntity>()
            .firstOrNull { it.isValid && !it.isDead }
            ?: return false
        val center = guard.location.clone().add(0.0, 1.0, 0.0)
        val radiusSquared = GALAXY_ORDER_STAR_LOCK_RADIUS * GALAXY_ORDER_STAR_LOCK_RADIUS
        val targets = s.entityIds.asSequence()
            .mapNotNull(Bukkit::getEntity)
            .filterIsInstance<LivingEntity>()
            .filter { it.isValid && !it.isDead && mobId(it) != null }
            .filter { it.world == guard.world && it.location.distanceSquared(guard.location) <= radiusSquared }
            .distinctBy(Entity::getUniqueId)
            .toList()

        val armorSnapshots = targets.associate { mob ->
            mob.uniqueId to Pair(
                mob.getAttribute(Attribute.ARMOR)?.baseValue,
                mob.persistentDataContainer.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE)
            )
        }
        targets.forEach { mob ->
            mob.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
            mob.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, 0.0)
            mob.velocity = Vector(0.0, 0.0, 0.0)
            mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, GALAXY_ORDER_STAR_LOCK_DISABLE_TICKS, 10, false, true, true))
            mob.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, GALAXY_ORDER_STAR_LOCK_DISABLE_TICKS, 10, false, true, true))
            mob.world.spawnParticle(Particle.ELECTRIC_SPARK, mob.location.clone().add(0.0, 1.0, 0.0), 18, 0.55, 0.8, 0.55, 0.08)
        }
        guard.world.spawnParticle(Particle.EXPLOSION, center, 3, 0.45, 0.45, 0.45, 0.0)
        guard.world.spawnParticle(Particle.END_ROD, center, 70, 2.2, 1.2, 2.2, 0.12)
        guard.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.15f, 1.35f)
        guard.world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.7f)
        broadcast(s, "§b星河令引爆了星锁！星锁守卫与周围怪物被星辉禁锢§f3秒§b，并失去护甲§f5秒§b！")
        later(s, GALAXY_ORDER_STAR_LOCK_ARMOR_BREAK_TICKS.toLong()) {
            armorSnapshots.forEach { (entityId, armor) ->
                val mob = Bukkit.getEntity(entityId) as? LivingEntity ?: return@forEach
                if (!mob.isValid || mob.isDead) return@forEach
                armor.first?.let { mob.getAttribute(Attribute.ARMOR)?.baseValue = it }
                if (armor.second == null) {
                    mob.persistentDataContainer.remove(MobFactory.KEY_CUSTOM_ARMOR)
                } else {
                    mob.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, armor.second!!)
                }
            }
        }
        return true
    }

    private fun consumeMainHandItem(player: Player) {
        val stack = player.inventory.itemInMainHand
        if (stack.amount <= 1) player.inventory.setItemInMainHand(null) else stack.amount--
    }

    private fun grantFeatherShield(s: Session, player: Player, amount: Double) {
        syncFeatherShield(s, player)
        val existing = s.featherShields[player.uniqueId]
        if (existing != null) {
            existing.expiresAt = System.currentTimeMillis() + FEATHER_SHIELD_DURATION_MILLIS
            existing.observedAbsorption = player.absorptionAmount
            player.sendMessage("§b星河令护盾的持续时间已刷新为§f30§b秒，剩余护盾§f${existing.amount.roundToInt()}§b点。")
            return
        }
        val before = player.absorptionAmount
        val state = FeatherShield(amount, 0L, before)
        s.featherShields[player.uniqueId] = state
        state.expiresAt = System.currentTimeMillis() + FEATHER_SHIELD_DURATION_MILLIS
        replaceFeatherShieldModifier(player, state.amount, before + amount)
        state.observedAbsorption = player.absorptionAmount
        player.sendMessage("§b星河令带来§f${amount.roundToInt()}§b点护盾，持续§f30§b秒；再次获得时只会刷新时间。")
    }

    private fun maintainFeatherShields(s: Session) {
        val now = System.currentTimeMillis()
        s.featherShields.keys.toList().forEach { playerId ->
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                s.featherShields.remove(playerId)
                return@forEach
            }
            syncFeatherShield(s, player)
            val state = s.featherShields[playerId] ?: return@forEach
            if (now >= state.expiresAt) clearFeatherShield(s, player, true)
        }
    }

    private fun syncFeatherShield(s: Session, player: Player) {
        val state = s.featherShields[player.uniqueId] ?: return
        val current = player.absorptionAmount
        val consumed = (state.observedAbsorption - current).coerceAtLeast(0.0)
        if (consumed > 0.001) {
            state.amount = (state.amount - consumed).coerceAtLeast(0.0)
            replaceFeatherShieldModifier(player, state.amount, current)
        }
        state.observedAbsorption = player.absorptionAmount
        if (state.amount <= 0.001) clearFeatherShield(s, player, false)
    }

    private fun replaceFeatherShieldModifier(player: Player, amount: Double, desiredAbsorption: Double) {
        val attribute = player.getAttribute(Attribute.MAX_ABSORPTION) ?: return
        attribute.getModifier(featherShieldKey)?.let(attribute::removeModifier)
        if (amount > 0.001) {
            attribute.addTransientModifier(
                AttributeModifier(featherShieldKey, amount, AttributeModifier.Operation.ADD_NUMBER)
            )
        }
        player.absorptionAmount = desiredAbsorption.coerceIn(0.0, attribute.value.coerceAtLeast(0.0))
    }

    private fun clearFeatherShield(s: Session, player: Player, subtractRemaining: Boolean) {
        val state = s.featherShields.remove(player.uniqueId) ?: return
        val current = player.absorptionAmount
        val attribute = player.getAttribute(Attribute.MAX_ABSORPTION) ?: return
        attribute.getModifier(featherShieldKey)?.let(attribute::removeModifier)
        val desired = if (subtractRemaining) (current - state.amount).coerceAtLeast(0.0) else current
        player.absorptionAmount = desired.coerceAtMost(attribute.value.coerceAtLeast(0.0))
        if (subtractRemaining) player.sendMessage("§7星河令带来的星辉护盾已经消散。")
    }

    private fun isOnEntryPlatform(player: Player): Boolean {
        if (player.gameMode == GameMode.SPECTATOR || player.world.name != worldName) return false
        val location = player.location
        if (location.x < 1378.0 || location.x >= 1383.0 || location.z < 3016.0 || location.z >= 3021.0) return false
        return player.world.getBlockAt(location.blockX, 42, location.blockZ).type == Material.GOLD_BLOCK &&
            location.y >= 43.0 && location.y < 46.0
    }

    private fun startDungeon(players: List<Player>, difficulty: QixiDifficulty) {
        val world = Bukkit.getWorld(worldName) ?: run {
            players.forEach { it.sendMessage("§c副本世界 $worldName 未加载，请联系管理员！") }
            return
        }
        val shuffled = players.shuffled()
        val current = Session(
            world.name,
            shuffled.mapTo(HashSet()) { it.uniqueId },
            difficulty
        )
        session = current
        plugin.logger.info("鹊桥星愿副本已按 ${difficulty.displayName} 启动，入场人数：${shuffled.size}")
        loadBridgeChunks(current)
        clearDungeonResidue(world)
        world.getBlockAt(-955, 104, 2404).type = Material.BLUE_WOOL
        world.getBlockAt(-947, 104, 2404).type = Material.RED_WOOL
        shuffled.forEachIndexed { index, player ->
            plugin.playerManager.getData(player.uniqueId)?.let { data ->
                data.updateStatus(5)
                plugin.databaseManager.savePlayerAsync(data)
            }
            player.setPlayerTime(DUNGEON_NIGHT_TIME, false)
            player.addScoreboardTag(PLAYER_TAG)
            val destination = if (index % 2 == 0) {
                Location(world, -1045.59, 81.0, 2404.43, 270.86f, -11.70f)
            } else {
                Location(world, -850.30, 77.0, 2404.74, 90.41f, -6.90f)
            }
            player.teleport(destination)
            player.sendMessage("§d§l【鹊桥星愿】§f你已踏入星河秘境。")
            player.sendMessage("§f本次难度：${difficulty.color}${difficulty.displayName}")
        }
        repeating(current, 5L, 5L) { rescueFallenPlayers(current) }
        repeating(current, 5L, 5L) { maintainFeatherShields(current) }
        repeating(current, MOB_BOUNDARY_CHECK_TICKS, MOB_BOUNDARY_CHECK_TICKS) { monitorKnockedOffMobs(current) }
        current.niulang = spawnEscort(
            current,
            "§a§l牛郎",
            Location(world, -1046.40, 81.0, 2398.73, 359.85f, 0.60f),
            Location(world, -954.5, 105.0, 2404.5, 270.0f, 0.0f)
        )
        current.zhinv = spawnEscort(
            current,
            "§a§l织女",
            Location(world, -849.58, 77.0, 2412.38, 180.75f, -3.60f),
            Location(world, -946.5, 105.0, 2404.5, 90.0f, 0.0f)
        )
        spawnSpirit(current, current.niulang!!.start, "§b§l牛郎的鹊灵", Parrot.Variant.BLUE)
        spawnSpirit(current, current.zhinv!!.start, "§c§l织女的鹊灵", Parrot.Variant.RED)
        repeating(current, spiritUpdateTicks, spiritUpdateTicks) { updateSpirits(current) }

        val intro = listOf(
            "§7鹊桥已架，星光却乱如麻绪。",
            "§7桥上的每一缕光芒都在不安地颤动着，仿佛天河深处有什么正在苏醒。",
            "§b牛郎：§f这些§e星卫§f……为何会在这里？为何阻我二人相会？",
            "§b牛郎：§f他们的敕令不对——是有什么东西篡改了令谕！",
            "§d织女：§f勇士们，这些星卫被未知之力所困，敌我不分。若放任不管，我和牛郎今夜便无法核对星图。",
            "§d织女：§f请助我们击退他们，唤醒这座沉睡的§e鹊桥§f！",
            "§b牛郎：§f他们体内埋藏着维持鹊桥的§e晶核§f。击破后，鹊灵会自行收集散落的晶核——在运送晶核时，它会为你们降下§e祝福§f。",
            "§b牛郎：§f你们触碰晶核也能呼唤尚未就位的鹊灵。集满§e五枚§f，它便飞往桥心，重新点亮一段星路！",
            "§d织女：§f我和牛郎的鹊灵会一路相助。莫要犹豫，动手吧！"
        )
        scheduleDialogue(current, intro, 0L) { beginAwakening(current) }
    }

    private fun spawnEscort(s: Session, name: String, start: Location, target: Location): Escort {
        val maxHealth = settings(s).escortMaxHealth
        val villager = start.world.spawn(start, Villager::class.java) { npc ->
            npc.customName = name
            npc.isCustomNameVisible = true
            npc.profession = Villager.Profession.NONE
            npc.setAI(false)
            npc.isInvulnerable = true
            npc.isCollidable = false
            npc.removeWhenFarAway = false
            npc.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth.toDouble()
            npc.health = maxHealth.toDouble()
        }
        tagEntity(s, villager)
        return Escort(name, villager.uniqueId, start.clone(), target.clone(), maxHealth)
    }

    private fun spawnSpirit(s: Session, location: Location, name: String, variant: Parrot.Variant) {
        val parrot = location.world.spawn(location.clone().add(0.0, 1.8, 0.0), Parrot::class.java) {
            it.customName = name
            it.isCustomNameVisible = true
            it.variant = variant
            it.setAI(false)
            it.isSitting = false
            it.isInvulnerable = true
            it.isCollidable = false
            it.setGravity(false)
            it.removeWhenFarAway = false
        }
        tagEntity(s, parrot)
        Bukkit.getMobGoals().removeAllGoals(parrot)
        parrot.addScoreboardTag(if (variant == Parrot.Variant.BLUE) NIULANG_SPIRIT_TAG else ZHINV_SPIRIT_TAG)
        s.spiritGroundCrystals[parrot.uniqueId] = 0
        s.spiritBlessingCharge[parrot.uniqueId] = 0.0
    }

    private fun beginAwakening(s: Session) {
        if (!isCurrent(s, Phase.INTRO)) return
        s.phase = Phase.AWAKENING
        s.awakeningBar = createBar(s, "§d§l鹊桥唤醒进度 §f0%", BarColor.PINK)
        s.niulangSpiritBar = createBar(s, "§b§l牛郎的鹊灵携带晶核 §f0/$SPIRIT_GROUND_CRYSTAL_LIMIT", BarColor.BLUE)
        s.zhinvSpiritBar = createBar(s, "§c§l织女的鹊灵携带晶核 §f0/$SPIRIT_GROUND_CRYSTAL_LIMIT", BarColor.RED)
        s.niulangSpiritBar?.progress = 0.0
        s.zhinvSpiritBar?.progress = 0.0
        repeat(initialGuards) { spawnAwakeningGuard(s, it, initialGuards) }
        broadcast(s, "§6鹊灵会在运送晶核时降下祝福；触碰晶核可呼唤尚未就位的鹊灵，集满五枚后它将飞往桥心！")
        playPhaseOneTwoBgm(s)

        repeating(s, 5L, 5L) { updateAwakeningMechanics(s) }
        repeating(s, PROGRESS_MECHANIC_PERIOD_TICKS, PROGRESS_MECHANIC_PERIOD_TICKS) { spawnProgressMechanic(s) }
        repeating(s, phaseOneReplenishTicks, phaseOneReplenishTicks) { replenishAwakeningGuards(s) }
        later(s, phaseOneTimeoutTicks) {
            if (isCurrent(s, Phase.AWAKENING)) {
                failSession(s, "§c十分钟已至，鹊桥仍未完全唤醒。秘境挑战失败！", true)
            }
        }
    }

    private fun spawnAwakeningGuard(s: Session, index: Int? = null, total: Int = 1) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val mobId = if (ThreadLocalRandom.current().nextBoolean()) "qixi_zombie" else "qixi_skeleton"
        val entity = MobFactory.spawnMob(plugin, bridgeSpawnLocation(world, index, total), mobId, false) ?: return
        configureDungeonMob(s, entity)
        tagEntity(s, entity)
    }

    private fun replenishAwakeningGuards(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        val desired = activePlayers(s).size * guardsPerPlayer
        val current = taggedMobs(s, AWAKENING_MOB_IDS).size
        if (current >= desired) return
        repeat(desired - current) { spawnAwakeningGuard(s) }
        broadcast(s, "§c新的星卫已到达……")
    }

    private fun submitSpiritCrystals(s: Session, spirit: Parrot, amount: Int) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        val difficulty = settings(s)
        var gained = 0
        repeat(amount) {
            gained += if (difficulty.crystalProgressMin == difficulty.crystalProgressMax) {
                difficulty.crystalProgressMin
            } else {
                ThreadLocalRandom.current().nextInt(difficulty.crystalProgressMin, difficulty.crystalProgressMax + 1)
            }
        }
        s.awakeningProgress = min(100, s.awakeningProgress + gained)
        updateAwakeningBar(s)

        val center = Location(spirit.world, -950.5, 105.0, 2404.5)
        spirit.world.spawnParticle(Particle.END_ROD, center, 35, 1.2, 1.0, 1.2, 0.08)
        spirit.world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.2f)
        val spiritName = if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) "牛郎的鹊灵" else "织女的鹊灵"
        broadcast(s, "§b${spiritName}送达§e${amount}§b枚晶核，鹊桥唤醒进度增加§e${gained}%§b！")

        if (s.awakeningProgress >= 100) {
            beginEscortDialogue(s)
            return
        }
    }

    private fun updateAwakeningBar(s: Session) {
        s.awakeningBar?.apply {
            progress = s.awakeningProgress.coerceIn(0, 100) / 100.0
            setTitle("§d§l鹊桥唤醒进度 §f${s.awakeningProgress.coerceIn(0, 100)}%")
        }
    }

    private fun spawnProgressMechanic(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        val mechanic = when (s.awakeningProgress) {
            in 15..30 -> AwakeningMechanic.SILVER_SPIDERS
            in 31..60 -> AwakeningMechanic.STAR_COMMANDERS
            in 61..99 -> AwakeningMechanic.CHAOS_WALKERS
            else -> return
        }
        val world = Bukkit.getWorld(s.worldName) ?: return
        val (mobId, amount) = when (mechanic) {
            AwakeningMechanic.SILVER_SPIDERS -> SILVER_SPIDER_ID to SILVER_SPIDERS_PER_WAVE
            AwakeningMechanic.STAR_COMMANDERS -> STAR_COMMANDER_ID to STAR_COMMANDERS_PER_WAVE
            AwakeningMechanic.CHAOS_WALKERS -> CHAOS_WALKER_ID to CHAOS_WALKERS_PER_WAVE
        }
        if (s.announcedAwakeningMechanics.add(mechanic)) {
            broadcast(s, when (mechanic) {
                AwakeningMechanic.SILVER_SPIDERS ->
                    "§c银灵蛛嗅到了晶核的气息！它们会优先追踪并吞噬晶核，吞满三枚后追向最近的玩家，并在靠近后蓄力三秒自爆！"
                AwakeningMechanic.STAR_COMMANDERS ->
                    "§c星河统领率军登桥！他们会在脚下凝聚星团，三秒后大幅击退六格内的玩家！"
                AwakeningMechanic.CHAOS_WALKERS ->
                    "§c乱星者正从桥面上方飞向桥心！它们抵达后若停留五秒，将吞噬鹊桥星光并使唤醒进度倒退！"
            })
        }
        broadcast(s, "§c一批${when (mechanic) {
            AwakeningMechanic.SILVER_SPIDERS -> "银灵蛛"
            AwakeningMechanic.STAR_COMMANDERS -> "星河统领"
            AwakeningMechanic.CHAOS_WALKERS -> "乱星者"
        }}已经登上鹊桥！")

        repeat(amount) { index ->
            val location = if (mechanic == AwakeningMechanic.CHAOS_WALKERS) {
                chaosWalkerSpawnLocation(world, index)
            } else {
                bridgeSpawnLocation(world, index, amount)
            }
            val entity = MobFactory.spawnMob(plugin, location, mobId, false) ?: return@repeat
            configureDungeonMob(s, entity)
            tagEntity(s, entity)
            when (mechanic) {
                AwakeningMechanic.SILVER_SPIDERS -> s.spiderCrystals[entity.uniqueId] = 0
                AwakeningMechanic.CHAOS_WALKERS -> {
                    if (entity is Mob) entity.setAI(false)
                    entity.setGravity(false)
                    entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                }
                AwakeningMechanic.STAR_COMMANDERS -> Unit
            }
        }
    }

    private fun updateAwakeningMechanics(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        updateSilverSpiders(s)
        updateChaosWalkers(s)
    }

    private fun updateSilverSpiders(s: Session) {
        val now = System.currentTimeMillis()
        val availableCrystals = s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Item>()
            .filter { it.isValid && resourceId(it.itemStack) == CRYSTAL_ID && it.scoreboardTags.contains(ENTITY_TAG) }
            .toMutableList()
        val players = activePlayers(s)
        taggedMobs(s, setOf(SILVER_SPIDER_ID)).forEach { spider ->
            val spiderId = spider.uniqueId
            val fuseEnd = s.spiderFuseEndsAt[spiderId]
            if (fuseEnd != null) {
                spider.velocity = Vector(0.0, 0.0, 0.0)
                spider.world.spawnParticle(Particle.FLAME, spider.location.clone().add(0.0, 0.55, 0.0), 8, 0.45, 0.35, 0.45, 0.03)
                spider.world.spawnParticle(Particle.END_ROD, spider.location.clone().add(0.0, 0.55, 0.0), 5, 0.35, 0.35, 0.35, 0.02)
                if (now >= fuseEnd) explodeSilverSpider(s, spider)
                return@forEach
            }

            var stored = s.spiderCrystals[spiderId] ?: 0
            if (stored < SILVER_SPIDER_CRYSTALS_TO_EXPLODE) {
                val crystal = availableCrystals.minByOrNull { it.location.distanceSquared(spider.location) }
                if (crystal != null) {
                    availableCrystals.remove(crystal)
                    (spider as? Mob)?.apply {
                        target = null
                        pathfinder.moveTo(crystal.location, SILVER_SPIDER_PATH_SPEED)
                    }
                    if (crystal.location.distanceSquared(spider.location) <= SILVER_SPIDER_ABSORB_DISTANCE_SQUARED) {
                        val taken = min(SILVER_SPIDER_CRYSTALS_TO_EXPLODE - stored, crystal.itemStack.amount)
                        stored += taken
                        if (taken >= crystal.itemStack.amount) {
                            s.entityIds.remove(crystal.uniqueId)
                            crystal.remove()
                        } else {
                            crystal.itemStack = crystal.itemStack.clone().apply { amount -= taken }
                        }
                        spider.world.spawnParticle(Particle.WITCH, spider.location.clone().add(0.0, 0.5, 0.0), 12, 0.45, 0.4, 0.45, 0.05)
                    }
                    s.spiderCrystals[spiderId] = stored
                    if (stored < SILVER_SPIDER_CRYSTALS_TO_EXPLODE) return@forEach
                }
                if (stored < SILVER_SPIDER_CRYSTALS_TO_EXPLODE) {
                    (spider as? Mob)?.target = null
                    return@forEach
                }
            }
            s.spiderCrystals[spiderId] = stored

            val nearestPlayer = players.minByOrNull { it.location.distanceSquared(spider.location) } ?: return@forEach
            if (nearestPlayer.location.distanceSquared(spider.location) > SILVER_SPIDER_EXPLOSION_RADIUS_SQUARED) {
                (spider as? Mob)?.apply {
                    setAI(true)
                    target = nearestPlayer
                    pathfinder.moveTo(nearestPlayer.location, SILVER_SPIDER_PATH_SPEED)
                }
            } else {
                (spider as? Mob)?.setAI(false)
                spider.velocity = Vector(0.0, 0.0, 0.0)
                s.spiderFuseEndsAt[spiderId] = now + SILVER_SPIDER_FUSE_MILLIS
                broadcast(s, "§c一只银灵蛛已经吞下三枚晶核并追上目标，三秒后即将爆炸！")
                spider.world.playSound(spider.location, Sound.ENTITY_CREEPER_PRIMED, 1.1f, 1.2f)
            }
        }
    }

    private fun explodeSilverSpider(s: Session, spider: LivingEntity) {
        s.spiderCrystals.remove(spider.uniqueId)
        s.spiderFuseEndsAt.remove(spider.uniqueId)
        s.entityIds.remove(spider.uniqueId)
        val location = spider.location.clone().add(0.0, 0.5, 0.0)
        spider.remove()
        location.world.spawnParticle(Particle.EXPLOSION, location, 4, 0.8, 0.6, 0.8, 0.05)
        location.world.spawnParticle(Particle.WITCH, location, 55, 2.2, 1.3, 2.2, 0.12)
        location.world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.9f)
        activePlayers(s).filter { it.world == location.world && it.location.distanceSquared(location) <= 36.0 }
            .forEach { it.damage(SILVER_SPIDER_EXPLOSION_DAMAGE * settings(s).attributeMultiplier) }
    }

    private fun updateChaosWalkers(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = Location(world, -950.5, 109.0, 2404.5)
        val now = System.currentTimeMillis()
        taggedMobs(s, setOf(CHAOS_WALKER_ID)).forEach { walker ->
            val arrivedAt = s.chaosArrivedAt[walker.uniqueId]
            if (arrivedAt == null) {
                val direction = center.toVector().subtract(walker.location.toVector())
                if (direction.lengthSquared() <= 6.25) {
                    s.chaosArrivedAt[walker.uniqueId] = now
                    walker.velocity = Vector(0.0, 0.0, 0.0)
                    broadcast(s, "§c一名乱星者已经抵达桥心，五秒内必须将它击杀！")
                } else {
                    val waypoint = chaosWalkerFlightWaypoint(walker, center)
                    walker.velocity = waypoint.toVector().subtract(walker.location.toVector())
                        .normalize()
                        .multiply(CHAOS_WALKER_SPEED)
                }
                return@forEach
            }

            walker.velocity = Vector(0.0, 0.0, 0.0)
            walker.world.spawnParticle(Particle.REVERSE_PORTAL, walker.location.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.8, 0.5, 0.04)
            if (now - arrivedAt < CHAOS_WALKER_CHANNEL_MILLIS) return@forEach

            s.awakeningProgress = max(0, s.awakeningProgress - CHAOS_WALKER_PROGRESS_LOSS)
            updateAwakeningBar(s)
            broadcast(s, "§c乱星者吞噬了桥心星光，鹊桥唤醒进度降低§e${CHAOS_WALKER_PROGRESS_LOSS}%§c！")
            s.chaosArrivedAt.remove(walker.uniqueId)
            s.entityIds.remove(walker.uniqueId)
            walker.world.spawnParticle(Particle.SMOKE, walker.location, 35, 0.7, 1.0, 0.7, 0.08)
            walker.remove()
        }
    }

    private fun chaosWalkerFlightWaypoint(walker: LivingEntity, center: Location): Location {
        val dx = center.x - walker.location.x
        val dz = center.z - walker.location.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
        if (horizontalDistance <= CHAOS_WALKER_FINAL_APPROACH_DISTANCE) return center

        return bridgeFlightWaypoint(
            walker.location,
            center,
            CHAOS_WALKER_WAYPOINT_STEP,
            CHAOS_WALKER_BRIDGE_CLEARANCE
        )
    }

    private fun chaosWalkerSpawnLocation(world: org.bukkit.World, index: Int): Location {
        val x = if (index % 2 == 0) {
            ThreadLocalRandom.current().nextDouble(-1034.0, -1010.0)
        } else {
            ThreadLocalRandom.current().nextDouble(-892.0, -868.0)
        }
        return findBridgeFeetLocation(world, x, ThreadLocalRandom.current().nextDouble(2401.0, 2408.0))
            ?.add(0.0, CHAOS_WALKER_BRIDGE_CLEARANCE, 0.0)
            ?: Location(world, x, 100.0, 2404.5)
    }

    private fun updateSpirits(s: Session) {
        if (session !== s || s.ending || s.phase == Phase.TRANSITION || s.phase == Phase.THIRD_PHASE) return
        val time = System.currentTimeMillis() / 1000.0
        val droppedCrystals = if (s.phase == Phase.AWAKENING) {
            s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Item>()
                .filter { it.isValid && resourceId(it.itemStack) == CRYSTAL_ID }
        } else {
            emptyList()
        }
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Parrot>().forEach { spirit ->
            spirit.isSitting = false
            if (s.phase == Phase.INTRO || s.phase == Phase.AWAKENING) {
                val carried = s.spiritGroundCrystals[spirit.uniqueId] ?: 0
                val delivering = s.phase == Phase.AWAKENING && carried >= SPIRIT_GROUND_CRYSTAL_LIMIT
                updateSpiritBlessingCharge(s, spirit, delivering)
                if (delivering) {
                    s.spiritCrystalTargets.remove(spirit.uniqueId)
                    val center = Location(spirit.world, -950.5, SPIRIT_PAVILION_FLIGHT_Y, 2404.5)
                    if (spirit.location.distanceSquared(center) <= SPIRIT_DELIVERY_DISTANCE_SQUARED) {
                        s.spiritGroundCrystals[spirit.uniqueId] = 0
                        updateSpiritCrystalBar(s, spirit)
                        submitSpiritCrystals(s, spirit, carried)
                    } else {
                        steerSpirit(spirit, spiritDeliveryWaypoint(spirit, center), SPIRIT_DELIVERY_SPEED)
                        spirit.world.spawnParticle(Particle.END_ROD, spirit.location, 2, 0.2, 0.2, 0.2, 0.01)
                    }
                    return@forEach
                }

                val assignedTarget = s.spiritCrystalTargets[spirit.uniqueId]
                    ?.let(Bukkit::getEntity) as? Item
                if (assignedTarget == null || !assignedTarget.isValid || resourceId(assignedTarget.itemStack) != CRYSTAL_ID) {
                    s.spiritCrystalTargets.remove(spirit.uniqueId)
                }
                val claimedByOthers = s.spiritCrystalTargets
                    .filterKeys { it != spirit.uniqueId }
                    .values
                    .toHashSet()
                val nearestCrystal = assignedTarget?.takeIf { it.isValid } ?: droppedCrystals
                    .filter { it.isValid && it.uniqueId !in claimedByOthers }
                    .minByOrNull { it.location.distanceSquared(spirit.location) }
                if (nearestCrystal != null) {
                    s.spiritCrystalTargets[spirit.uniqueId] = nearestCrystal.uniqueId
                    if (nearestCrystal.location.distanceSquared(spirit.location) <= SPIRIT_CRYSTAL_ABSORB_DISTANCE_SQUARED) {
                        collectGroundCrystals(s, spirit, droppedCrystals)
                        if ((s.spiritGroundCrystals[spirit.uniqueId] ?: 0) >= SPIRIT_GROUND_CRYSTAL_LIMIT ||
                            !nearestCrystal.isValid
                        ) {
                            s.spiritCrystalTargets.remove(spirit.uniqueId)
                        }
                    } else {
                        steerSpirit(spirit, spiritHighBridgeWaypoint(spirit, nearestCrystal.location), SPIRIT_NORMAL_SPEED)
                    }
                    return@forEach
                }

                val idleTarget = (if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) s.niulang?.start else s.zhinv?.start)
                    ?.let(::elevatedSpiritTarget)
                if (idleTarget != null && spirit.location.distanceSquared(idleTarget) > SPIRIT_IDLE_RETURN_DISTANCE_SQUARED) {
                    steerSpirit(spirit, idleTarget, SPIRIT_NORMAL_SPEED)
                    return@forEach
                }
            } else {
                updateSpiritBlessingCharge(s, spirit, false)
                val owner = if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) {
                    s.niulang?.let(::escortEntity)
                } else {
                    s.zhinv?.let(::escortEntity)
                }
                if (owner != null && owner.isValid) {
                    val offset = if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) 0.0 else Math.PI
                    val angle = time * 1.25 + offset
                    val target = owner.location.clone().add(
                        kotlin.math.cos(angle) * 2.0,
                        kotlin.math.sin(time * 1.7 + offset) * 0.55,
                        kotlin.math.sin(angle) * 2.0
                    )
                    steerSpirit(spirit, elevatedSpiritTarget(target), SPIRIT_ESCORT_SPEED)
                    return@forEach
                }
            }
            spirit.velocity = spirit.velocity.multiply(0.82)
        }
    }

    private fun collectGroundCrystals(s: Session, spirit: Parrot, crystals: List<Item>) {
        var carried = s.spiritGroundCrystals[spirit.uniqueId] ?: 0
        crystals.sortedBy { it.location.distanceSquared(spirit.location) }.forEach { item ->
            if (carried >= SPIRIT_GROUND_CRYSTAL_LIMIT || !item.isValid ||
                item.location.distanceSquared(spirit.location) > SPIRIT_CRYSTAL_ABSORB_DISTANCE_SQUARED
            ) return@forEach
            val taken = min(SPIRIT_GROUND_CRYSTAL_LIMIT - carried, item.itemStack.amount)
            carried += taken
            spirit.world.spawnParticle(Particle.END_ROD, item.location, 8, 0.25, 0.25, 0.25, 0.05)
            if (taken >= item.itemStack.amount) {
                s.entityIds.remove(item.uniqueId)
                s.spiritCrystalTargets.entries.removeIf { it.value == item.uniqueId }
                item.remove()
            } else {
                item.itemStack = item.itemStack.clone().apply { amount -= taken }
            }
        }
        s.spiritGroundCrystals[spirit.uniqueId] = carried
        updateSpiritCrystalBar(s, spirit)
        if (carried >= SPIRIT_GROUND_CRYSTAL_LIMIT) s.spiritCrystalTargets.remove(spirit.uniqueId)
        if (carried > 0) {
            spirit.world.spawnParticle(Particle.HAPPY_VILLAGER, spirit.location, 12, 0.45, 0.45, 0.45, 0.05)
            spirit.world.playSound(spirit.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.5f)
        }
    }

    private fun updateSpiritCrystalBar(s: Session, spirit: Parrot) {
        val carried = (s.spiritGroundCrystals[spirit.uniqueId] ?: 0).coerceIn(0, SPIRIT_GROUND_CRYSTAL_LIMIT)
        val isNiulangSpirit = spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)
        val bar = if (isNiulangSpirit) s.niulangSpiritBar else s.zhinvSpiritBar
        val owner = if (isNiulangSpirit) "牛郎" else "织女"
        val color = if (isNiulangSpirit) "§b" else "§c"
        bar?.apply {
            progress = carried / SPIRIT_GROUND_CRYSTAL_LIMIT.toDouble()
            setTitle("$color§l${owner}的鹊灵携带晶核 §f$carried/$SPIRIT_GROUND_CRYSTAL_LIMIT")
        }
    }

    private fun steerSpirit(spirit: Parrot, target: Location, speed: Double) {
        val delta = target.toVector().subtract(spirit.location.toVector())
        val distanceSquared = delta.lengthSquared()
        if (distanceSquared <= 0.08) {
            spirit.velocity = spirit.velocity.multiply(0.72)
            return
        }
        val desired = delta.clone().normalize().multiply(speed)
        // 鹊桥为连续台阶拱面；若仍有明显距离但速度被墙体归零，小步传送至航点脱困。
        if (spirit.velocity.lengthSquared() < 0.0025 && distanceSquared > 4.0) {
            val escapeStep = min(SPIRIT_STUCK_ESCAPE_STEP, kotlin.math.sqrt(distanceSquared))
            spirit.teleport(spirit.location.clone().add(delta.normalize().multiply(escapeStep)))
            spirit.velocity = Vector(0.0, 0.0, 0.0)
            return
        }
        spirit.velocity = spirit.velocity.multiply(0.62).add(desired.multiply(0.38))
    }

    private fun spiritDeliveryWaypoint(spirit: Parrot, center: Location): Location {
        val dx = center.x - spirit.location.x
        val dz = center.z - spirit.location.z
        if (dx * dx + dz * dz <= SPIRIT_FINAL_APPROACH_DISTANCE_SQUARED) return center

        return bridgeFlightWaypoint(
            spirit.location,
            center,
            SPIRIT_DELIVERY_WAYPOINT_STEP,
            SPIRIT_BRIDGE_CLEARANCE
        ).apply {
            yaw = spirit.location.yaw
            pitch = 0.0f
        }
    }

    private fun spiritHighBridgeWaypoint(spirit: Parrot, destination: Location): Location {
        val dx = destination.x - spirit.location.x
        val dz = destination.z - spirit.location.z
        if (dx * dx + dz * dz <= SPIRIT_CRYSTAL_FINAL_APPROACH_DISTANCE_SQUARED) {
            return destination.clone().add(0.0, SPIRIT_CRYSTAL_APPROACH_HEIGHT, 0.0)
        }
        return bridgeFlightWaypoint(
            spirit.location,
            destination,
            SPIRIT_DELIVERY_WAYPOINT_STEP,
            SPIRIT_BRIDGE_CLEARANCE
        )
    }

    private fun bridgeFlightWaypoint(current: Location, destination: Location, stepSize: Double, clearance: Double): Location {
        val dx = destination.x - current.x
        val dz = destination.z - current.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
        val step = min(stepSize, horizontalDistance)
        val x = current.x + dx / horizontalDistance * step
        val z = current.z + dz / horizontalDistance * step
        val bridgeFeet = findBridgeFeetLocation(current.world, x, z)
            ?: findBridgeFeetLocation(current.world, x, BRIDGE_CENTER_Z)
        if (bridgeFeet == null) {
            return Location(current.world, x, max(current.y, destination.y + clearance), z)
        }
        val safeY = max(
            bridgeFeet.y + clearance,
            if (x in SPIRIT_PAVILION_MIN_X..SPIRIT_PAVILION_MAX_X) SPIRIT_PAVILION_FLIGHT_Y else Double.NEGATIVE_INFINITY
        )
        val y = when {
            current.y < safeY -> safeY
            current.y > safeY + 1.0 -> max(safeY, current.y - 0.75)
            else -> current.y
        }
        return Location(current.world, x, y, z)
    }

    private fun elevatedSpiritTarget(target: Location): Location = target.clone().apply {
        y += SPIRIT_ESCORT_HEIGHT
        if (x in SPIRIT_PAVILION_MIN_X..SPIRIT_PAVILION_MAX_X) y = max(y, SPIRIT_PAVILION_FLIGHT_Y)
    }

    private fun updateSpiritBlessingCharge(s: Session, spirit: Parrot, delivering: Boolean) {
        if (s.phase != Phase.AWAKENING && s.phase != Phase.ESCORT) return
        val multiplier = if (delivering) SPIRIT_DELIVERY_BLESSING_RATE else 1.0
        val charge = (s.spiritBlessingCharge[spirit.uniqueId] ?: 0.0) + spiritUpdateTicks * multiplier
        if (charge < SPIRIT_BLESSING_COOLDOWN_TICKS) {
            s.spiritBlessingCharge[spirit.uniqueId] = charge
            return
        }
        s.spiritBlessingCharge[spirit.uniqueId] = charge - SPIRIT_BLESSING_COOLDOWN_TICKS
        applySpiritBlessing(s, spirit)
    }

    private fun applySpiritBlessing(s: Session, spirit: Parrot) {
        if (session !== s || s.ending) return
        if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) {
            activePlayers(s).filter {
                it.world == spirit.world && horizontalDistanceSquared(it.location, spirit.location) <= SPIRIT_BLESSING_RADIUS_SQUARED
            }.forEach { player ->
                val data = plugin.playerManager.getData(player.uniqueId) ?: return@forEach
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "attack_percent"] = 0.10
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "archer_damage_percent"] = 0.10
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "zf_str_percent"] = 0.10
                plugin.playerManager.updateStats(player)
                player.sendMessage("§b牛郎的鹊灵洒下星辉：§f进攻属性提高§e10%§f，持续7秒！")
                player.world.spawnParticle(Particle.ENCHANT, player.location.clone().add(0.0, 1.0, 0.0), 35, 0.7, 1.0, 0.7, 0.1)
                later(s, 140L) { removeAttackBlessing(player) }
            }
        } else if (spirit.scoreboardTags.contains(ZHINV_SPIRIT_TAG)) {
            if (s.phase == Phase.AWAKENING) {
                activePlayers(s).filter { player ->
                    player.world == spirit.world &&
                        horizontalDistanceSquared(player.location, spirit.location) <= SPIRIT_BLESSING_RADIUS_SQUARED
                }.forEach { player ->
                    val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return@forEach
                    if (player.health >= maximumHealth) return@forEach
                    player.health = min(maximumHealth, player.health + ZHINV_SPIRIT_PLAYER_HEAL_AMOUNT)
                    sendActionBar(player, "§d织女的鹊灵洒下星辉：§f恢复§a10§f点生命！")
                    player.world.spawnParticle(
                        Particle.HEART,
                        player.location.clone().add(0.0, 1.0, 0.0),
                        10,
                        0.55,
                        0.75,
                        0.55,
                        0.04
                    )
                }
                spirit.world.playSound(spirit.location, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.75f, 1.55f)
                return
            }
            if (s.phase != Phase.ESCORT) return
            var healedAnyEscort = false
            listOfNotNull(s.niulang, s.zhinv).forEach { escort ->
                if (escort.health >= escort.maxHealth) return@forEach
                escort.health = min(escort.maxHealth, escort.health + ZHINV_SPIRIT_HEAL_AMOUNT)
                healedAnyEscort = true
                escortEntity(escort)?.let { npc ->
                    npc.health = escort.health.toDouble()
                    npc.world.spawnParticle(Particle.HEART, npc.location.clone().add(0.0, 1.2, 0.0), 14, 0.65, 0.85, 0.65, 0.05)
                }
            }
            if (healedAnyEscort) {
                spirit.world.playSound(spirit.location, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.75f, 1.55f)
                updateEscortBars(s)
            }
        }
    }

    private fun removeAttackBlessing(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        data.tempBonuses.keys.removeIf { it.startsWith(NIULANG_ATTACK_PREFIX) }
        plugin.playerManager.updateStats(player)
    }

    private fun beginEscortDialogue(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        s.phase = Phase.ESCORT_DIALOGUE
        s.awakeningBar?.removeAll()
        s.awakeningBar = null
        s.niulangSpiritBar?.removeAll(); s.niulangSpiritBar = null
        s.zhinvSpiritBar?.removeAll(); s.zhinvSpiritBar = null
        removeMobs(s, AWAKENING_MOB_IDS + AWAKENING_SPECIAL_MOB_IDS)
        removeHostileProjectiles(s)
        s.spiritGroundCrystals.keys.forEach { s.spiritGroundCrystals[it] = 0 }
        s.spiritCrystalTargets.clear()
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Item>()
            .filter { resourceId(it.itemStack) == CRYSTAL_ID }
            .forEach {
                s.entityIds.remove(it.uniqueId)
                it.remove()
            }
        awakeningEffect(s)
        val dialogue = listOf(
            "§b牛郎：§f鹊桥被唤醒了！§d织女：§f星光重新亮起来了！",
            "§b牛郎：§f事不宜迟，我们即刻出发，于桥心相会！",
            "§d织女：§f等等——那是§c天庭的精英守卫§f！他们比方才的星卫更难对付。",
            "§b牛郎：§f勇士们，请你们§e兵分两路§f，护送我与织女抵达桥心的§e亭子§f下相会！",
            "§d织女：§f我们的鹊灵虽可助战，却会消耗自身§c气血§f。若没有你们从旁守护，恐怕撑不到桥心。"
        )
        scheduleDialogue(s, dialogue, 0L) { beginEscort(s) }
    }

    private fun beginEscort(s: Session) {
        if (!isCurrent(s, Phase.ESCORT_DIALOGUE)) return
        s.phase = Phase.ESCORT
        resetEscortState(s, false)
        teleportSpiritsToEscorts(s)
        s.meetingBar = createBar(s, "§d§l牛郎与织女相聚进度 §f0%", BarColor.PURPLE)
        val escortMaxHealth = settings(s).escortMaxHealth
        s.niulangBar = createBar(s, "§b§l牛郎气血 §f$escortMaxHealth/$escortMaxHealth", BarColor.BLUE)
        s.zhinvBar = createBar(s, "§d§l织女气血 §f$escortMaxHealth/$escortMaxHealth", BarColor.PINK)
        spawnEliteGuardBatch(s, desiredPhaseTwoMobCount(s))
        broadcast(s, "§6兵分两路，护送牛郎与织女抵达桥心的亭子！")

        repeating(s, escortUpdateTicks, escortUpdateTicks) { escortTick(s) }
        repeating(s, 2L, 2L) { drawStarLock(s) }
        repeating(s, 10L, 10L) { attractMonsters(s) }
        repeating(s, phaseTwoReplenishTicks, phaseTwoReplenishTicks) { replenishEliteGuards(s) }
        scheduleNextStarLock(s)
        schedulePhaseTimeout(s, Phase.ESCORT, "phase-two")
    }

    private fun teleportSpiritsToEscorts(s: Session) {
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Parrot>().forEach { spirit ->
            val owner = if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) {
                s.niulang?.let(::escortEntity)
            } else {
                s.zhinv?.let(::escortEntity)
            } ?: return@forEach
            spirit.teleport(elevatedSpiritTarget(owner.location))
            spirit.velocity = Vector(0.0, 0.0, 0.0)
        }
    }

    private fun escortTick(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        val niu = s.niulang ?: return
        val zhi = s.zhinv ?: return
        syncExternalHealing(s, niu)
        syncExternalHealing(s, zhi)
        if (!s.starLocked) {
            s.escortProgress = min(1.0, s.escortProgress + escortUpdateTicks / escortDurationTicks)
            escortEntity(niu)?.teleport(snapEscortToBridge(lerp(niu.start, niu.target, s.escortProgress)))
            escortEntity(zhi)?.teleport(snapEscortToBridge(lerp(zhi.start, zhi.target, s.escortProgress)))
        }
        updateMeetingBar(s)
        if (s.escortProgress >= 1.0) finishEscort(s)
    }

    private fun syncExternalHealing(s: Session, escort: Escort) {
        val entity = escortEntity(escort) ?: return
        if (isCurrent(s, Phase.ESCORT)) entity.health = escort.health.coerceAtLeast(1).toDouble()
    }

    private fun recordHealingAction(s: Session, escort: Escort) {
        if (!isCurrent(s, Phase.ESCORT) || escort.health >= escort.maxHealth) return
        escort.healingActions++
        if (escort.healingActions % 2 == 0) {
            escort.health = min(escort.maxHealth, escort.health + 1)
            escortEntity(escort)?.world?.spawnParticle(Particle.HAPPY_VILLAGER, escortEntity(escort)!!.location.clone().add(0.0, 1.0, 0.0), 12, 0.6, 0.8, 0.6, 0.05)
        }
    }

    private fun spawnEliteGuard(s: Session, nearEscort: Escort? = null, forcedMobId: String? = null) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val mobId = forcedMobId
            ?: if (ThreadLocalRandom.current().nextBoolean()) "yinhebuwei" else "yinyugongwei"
        val location = nearEscort?.let { eliteSpawnNearEscort(world, it) } ?: bridgeSpawnLocation(world)
        val entity = MobFactory.spawnMob(plugin, location, mobId, false) ?: return
        configureDungeonMob(s, entity)
        tagEntity(s, entity)
        targetNearestEscort(s, entity)
    }

    private fun spawnEliteGuardBatch(s: Session, amount: Int) {
        if (amount <= 0) return
        val escorts = listOfNotNull(s.niulang, s.zhinv).shuffled()
        val nearAmount = min(amount, escorts.size * MAX_ELITES_NEAR_EACH_ESCORT_PER_BATCH)
        val commanderAmount = (amount * phaseTwoStarCommanderChance).roundToInt().coerceIn(0, amount)
        val mobIds = (MutableList(commanderAmount) { STAR_COMMANDER_ID } +
            MutableList(amount - commanderAmount) {
                if (ThreadLocalRandom.current().nextBoolean()) "yinhebuwei" else "yinyugongwei"
            }).shuffled()
        repeat(nearAmount) { index ->
            spawnEliteGuard(s, escorts[index % escorts.size], mobIds[index])
        }
        repeat(amount - nearAmount) { offset ->
            spawnEliteGuard(s, forcedMobId = mobIds[nearAmount + offset])
        }
    }

    private fun eliteSpawnNearEscort(world: org.bukkit.World, escort: Escort): Location {
        val npcLocation = escortEntity(escort)?.location ?: escort.start
        val random = ThreadLocalRandom.current()
        val forward = escort.target.toVector().subtract(npcLocation.toVector()).setY(0.0).let {
            if (it.lengthSquared() < 0.01) Vector(1.0, 0.0, 0.0) else it.normalize()
        }
        val perpendicular = Vector(-forward.z, 0.0, forward.x)
        repeat(24) {
            val distance = random.nextDouble(4.5, 9.5)
            val lateral = random.nextDouble(-3.0, 3.0)
            val x = npcLocation.x + forward.x * distance + perpendicular.x * lateral
            val z = npcLocation.z + forward.z * distance + perpendicular.z * lateral
            findBridgeFeetLocation(world, x, z)?.let { return it }
        }
        return bridgeSpawnLocation(world)
    }

    private fun replenishEliteGuards(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        trimExcessEliteGuards(s)
        val desired = desiredPhaseTwoMobCount(s)
        val current = taggedMobs(s, PHASE_TWO_MOB_IDS).size
        spawnEliteGuardBatch(s, (desired - current).coerceAtLeast(0))
    }

    private fun attractMonsters(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        taggedMobs(s, PHASE_TWO_MOB_IDS).forEach { targetNearestEscort(s, it) }
    }

    private fun targetNearestEscort(s: Session, mob: LivingEntity) {
        val candidate = listOfNotNull(s.niulang?.let(::escortEntity), s.zhinv?.let(::escortEntity))
            .filter { it.location.distanceSquared(mob.location) <= 400.0 }
            .minByOrNull { it.location.distanceSquared(mob.location) }
        if (mob is Mob && candidate != null) mob.target = candidate
    }

    private fun scheduleNextStarLock(s: Session) {
        val version = ++s.starLockScheduleVersion
        later(s, phaseTwoStarLockTicks) {
            if (version != s.starLockScheduleVersion) return@later
            if (isCurrent(s, Phase.ESCORT)) {
                if (!s.starLocked) {
                    triggerStarLock(s)
                    if (!s.starLocked) scheduleNextStarLock(s)
                } else scheduleNextStarLock(s)
            }
        }
    }

    private fun triggerStarLock(s: Session) {
        val escort = if (ThreadLocalRandom.current().nextBoolean()) s.niulang else s.zhinv ?: s.niulang
        escort ?: return
        val npc = escortEntity(escort) ?: return
        val forward = escort.target.toVector().subtract(npc.location.toVector()).setY(0).normalize()
        val center = npc.location.clone().add(forward.multiply(5.5))
        val perpendicular = Vector(-forward.z, 0.0, forward.x).normalize()
        val guard = spawnStarLockGuard(s, center) ?: return
        s.starLockIds.add(guard.uniqueId)
        s.starLockCenter = guard.location.clone().add(0.0, 1.0, 0.0)
        s.starLockDirection = perpendicular
        s.starLocked = true
        s.starLockBarLabel = ChatColor.stripColor(escort.displayName) ?: "护送目标"
        s.starLockLastDisplayedHealth = -1
        s.starLockBar = createBar(s, "§c§l拦住${s.starLockBarLabel}的星锁守卫", BarColor.RED)
        updateStarLockBar(s, guard)
        broadcast(s, "${escort.displayName}:§f不好，是§c星锁守卫§f！ 它在我面前展开星锁，拦住了前路！")
        broadcast(s, "${escort.displayName}:§f快击杀它，破开星锁！")
        broadcast(s, "§6请击杀§b${ChatColor.stripColor(escort.displayName)}§6面前的星锁守卫，否则牛郎和织女都将无法移动！")
    }

    private fun spawnStarLockGuard(s: Session, location: Location): LivingEntity? {
        val safe = findSafeFeetLocation(location.world, location.x, location.z, location.y)
            ?: Location(location.world, location.blockX + 0.5, location.y, location.blockZ + 0.5)
        val entity = MobFactory.spawnMob(plugin, safe, "xingsuoshouwei", false) ?: return null
        configureDungeonMob(s, entity)
        tagEntity(s, entity)
        if (entity is Mob) entity.setAI(false)
        entity.setGravity(false)
        return entity
    }

    private fun drawStarLock(s: Session) {
        if (!isCurrent(s, Phase.ESCORT) || !s.starLocked) return
        val guard = s.starLockIds.firstOrNull()?.let(Bukkit::getEntity)
            ?.takeIf { it.isValid } as? LivingEntity
        val direction = s.starLockDirection
        if (guard == null || direction == null) {
            breakStarLock(s)
            return
        }
        val center = guard.location.clone().add(0.0, 1.0, 0.0)
        s.starLockCenter = center
        updateStarLockBar(s, guard)
        val a = center.clone().add(direction.clone().multiply(6.0))
        val b = center.clone().subtract(direction.clone().multiply(6.0))
        val step = b.toVector().subtract(a.toVector()).multiply(1.0 / 28.0)
        repeat(29) { i ->
            val point = a.clone().add(step.clone().multiply(i))
            a.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.AQUA, 1.35f))
        }
    }

    private fun breakStarLock(s: Session) {
        val wasLocked = s.starLocked
        s.starLockIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.starLockIds.clear()
        s.starLockScheduleVersion++
        s.starLockCenter = null
        s.starLockDirection = null
        s.starLocked = false
        clearStarLockBar(s)
        if (wasLocked) broadcast(s, "§a星锁已经断裂，牛郎与织女可以继续前进！")
        if (wasLocked && isCurrent(s, Phase.ESCORT)) scheduleNextStarLock(s)
    }

    private fun updateStarLockBar(s: Session, guard: LivingEntity) {
        val maxHealth = guard.getAttribute(Attribute.MAX_HEALTH)?.value
            ?: guard.health.coerceAtLeast(1.0)
        val displayedHealth = ceil(guard.health.coerceAtLeast(0.0)).toInt()
        if (displayedHealth == s.starLockLastDisplayedHealth) return
        s.starLockLastDisplayedHealth = displayedHealth
        val label = s.starLockBarLabel ?: "护送目标"
        s.starLockBar?.apply {
            progress = (guard.health / maxHealth).coerceIn(0.0, 1.0)
            setTitle("§c§l拦住${label}的星锁守卫")
        }
    }

    private fun clearStarLockBar(s: Session) {
        s.starLockBar?.removeAll()
        s.starLockBar = null
        s.starLockBarLabel = null
        s.starLockLastDisplayedHealth = -1
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEscortDamage(event: EntityDamageEvent) {
        val s = session ?: return
        if (s.ending) return
        val escort = escortByEntity(s, event.entity.uniqueId) ?: return
        event.isCancelled = true
        if (s.phase != Phase.ESCORT || event !is EntityDamageByEntityEvent) return
        val hostileDungeonProjectile = event.damager is Projectile &&
            event.damager.scoreboardTags.contains(ENTITY_TAG)
        val attacker = actualAttacker(event.damager)
        if (attacker is Player) return
        if (!hostileDungeonProjectile && (attacker == null || mobId(attacker) !in PHASE_TWO_MOB_IDS)) return
        val now = System.currentTimeMillis()
        if ((s.escortInvulnerableUntil[escort.entityId] ?: 0L) > now) return
        if ((s.escortDamageReadyAt[escort.entityId] ?: 0L) > now) return
        s.escortDamageReadyAt[escort.entityId] = now + ESCORT_DAMAGE_COOLDOWN_MILLIS
        escort.health--
        val difficulty = settings(s)
        if (difficulty.otherEscortInvulnerableMillis > 0L && now >= s.escortProtectionReadyAt) {
            val other = if (escort.entityId == s.niulang?.entityId) s.zhinv else s.niulang
            if (other != null) {
                s.escortInvulnerableUntil[other.entityId] = now + difficulty.otherEscortInvulnerableMillis
                s.escortProtectionReadyAt = now + difficulty.otherEscortInvulnerabilityCooldownMillis
            }
        }
        if (escort.health <= 0) {
            resetEscortState(s, true)
            return
        }
        if (attacker != null) retaliate(s, escort, attacker)
        updateEscortBars(s)
    }

    /** 非副本玩家不能影响副本怪物，副本怪物也不能伤害围观者。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonCombatIsolation(event: EntityDamageByEntityEvent) {
        val s = session ?: return
        val attacker = actualAttacker(event.damager)
        val victim = event.entity
        if (victim.scoreboardTags.contains(ENTITY_TAG) && mobId(victim as? LivingEntity ?: return) != null) {
            if (attacker is Player && !isSessionPlayer(s, attacker)) event.isCancelled = true
        }
        val dungeonSource = event.damager.scoreboardTags.contains(ENTITY_TAG) ||
            (attacker != null && attacker.scoreboardTags.contains(ENTITY_TAG) && mobId(attacker) != null)
        if (dungeonSource && victim is Player) {
            if (!isSessionPlayer(s, victim)) event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonMobTarget(event: EntityTargetLivingEntityEvent) {
        val s = session ?: return
        val mob = event.entity as? LivingEntity ?: return
        if (!mob.scoreboardTags.contains(ENTITY_TAG) || mobId(mob) == null) return
        val target = event.target
        if (target is Player && !isSessionPlayer(s, target)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDungeonProjectile(event: ProjectileLaunchEvent) {
        val s = session ?: return
        val shooter = event.entity.shooter as? Entity ?: return
        if (shooter.scoreboardTags.contains(ENTITY_TAG)) tagEntity(s, event.entity)
    }

    private fun retaliate(s: Session, escort: Escort, attacker: LivingEntity) {
        val now = System.currentTimeMillis()
        if ((s.retaliationReadyAt[escort.entityId] ?: 0L) > now) return
        s.retaliationReadyAt[escort.entityId] = now + 2500L
        val maxHealth = attacker.getAttribute(Attribute.MAX_HEALTH)?.value ?: attacker.health
        attacker.health = max(0.0, attacker.health - maxHealth * 0.5)
        attacker.world.spawnParticle(Particle.ELECTRIC_SPARK, attacker.location.clone().add(0.0, 1.0, 0.0), 30, 0.7, 1.0, 0.7, 0.12)
        attacker.world.playSound(attacker.location, Sound.ENTITY_ALLAY_HURT, 1.0f, 1.5f)
        if (attacker is Mob && attacker.isValid) {
            attacker.setAI(false)
            later(s, 30L) {
                if (attacker.isValid && mobId(attacker) != "xingsuoshouwei") attacker.setAI(true)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEscortHeal(event: EntityRegainHealthEvent) {
        val s = session ?: return
        val escort = escortByEntity(s, event.entity.uniqueId) ?: return
        event.isCancelled = true
        escortEntity(escort)?.health = escort.health.coerceAtLeast(1).toDouble()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMedicalEscortHeal(event: MedicalHealEvent) {
        val s = session ?: return
        if (!isCurrent(s, Phase.ESCORT) || event.actualHeal <= 0.0 || !isSessionPlayer(s, event.caster)) return
        val escort = escortByEntity(s, event.target.uniqueId) ?: return
        recordHealingAction(s, escort)
        escortEntity(escort)?.health = escort.health.coerceAtLeast(1).toDouble()
        updateEscortBars(s)
    }

    private fun resetEscortState(s: Session, announce: Boolean) {
        if (announce) broadcast(s, "§c牛郎或织女气血耗尽，双方已回到起点，护送重新开始！")
        removeMobs(s, PHASE_TWO_MOB_IDS + "xingsuoshouwei")
        removeHostileProjectiles(s)
        s.starLockScheduleVersion++
        s.starLockIds.clear()
        s.starLockCenter = null
        s.starLockDirection = null
        s.starLocked = false
        s.escortProgress = 0.0
        s.retaliationReadyAt.clear()
        s.escortDamageReadyAt.clear()
        s.escortInvulnerableUntil.clear()
        s.escortProtectionReadyAt = 0L
        listOfNotNull(s.niulang, s.zhinv).forEach { escort ->
            escort.health = escort.maxHealth
            escort.healingActions = 0
            escortEntity(escort)?.apply {
                isInvulnerable = false
                isCollidable = true
                getAttribute(Attribute.MAX_HEALTH)?.baseValue = escort.maxHealth.toDouble()
                health = escort.maxHealth.toDouble()
                teleport(escort.start)
            }
        }
        if (announce) {
            spawnEliteGuardBatch(s, desiredPhaseTwoMobCount(s))
            scheduleNextStarLock(s)
        }
        updateEscortBars(s)
    }

    private fun updateEscortBars(s: Session) {
        updateMeetingBar(s)
        s.niulang?.let { escort ->
            s.niulangBar?.apply {
                progress = escort.health / escort.maxHealth.toDouble()
                setTitle("§b§l牛郎气血 §f${escort.health}/${escort.maxHealth}")
            }
        }
        s.zhinv?.let { escort ->
            s.zhinvBar?.apply {
                progress = escort.health / escort.maxHealth.toDouble()
                setTitle("§d§l织女气血 §f${escort.health}/${escort.maxHealth}")
            }
        }
    }

    private fun updateMeetingBar(s: Session) {
        s.meetingBar?.apply {
            progress = s.escortProgress.coerceIn(0.0, 1.0)
            setTitle("§d§l牛郎与织女相聚进度 §f${(s.escortProgress * 100).toInt()}%")
        }
    }

    private fun fastForwardToThirdPhaseTransition(s: Session) {
        if (session !== s || s.ending || s.phase == Phase.TRANSITION || s.phase == Phase.THIRD_PHASE) return

        s.awakeningBar?.removeAll(); s.awakeningBar = null
        s.niulangSpiritBar?.removeAll(); s.niulangSpiritBar = null
        s.zhinvSpiritBar?.removeAll(); s.zhinvSpiritBar = null
        s.meetingBar?.removeAll(); s.meetingBar = null
        s.niulangBar?.removeAll(); s.niulangBar = null
        s.zhinvBar?.removeAll(); s.zhinvBar = null
        clearStarLockBar(s)

        s.starLockScheduleVersion++
        s.starLockIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.starLockIds.clear()
        s.starLockCenter = null
        s.starLockDirection = null
        s.starLocked = false
        s.retaliationReadyAt.clear()
        s.escortDamageReadyAt.clear()
        s.escortInvulnerableUntil.clear()
        s.escortProtectionReadyAt = 0L
        s.awakeningProgress = 100
        s.escortProgress = 1.0

        listOfNotNull(s.niulang, s.zhinv).forEach { escort ->
            escort.health = escort.maxHealth
            escort.healingActions = 0
            escortEntity(escort)?.apply {
                getAttribute(Attribute.MAX_HEALTH)?.baseValue = escort.maxHealth.toDouble()
                health = escort.maxHealth.toDouble()
                velocity = Vector(0.0, 0.0, 0.0)
                teleport(escort.target)
            }
        }

        removeMobs(s, AWAKENING_MOB_IDS + AWAKENING_SPECIAL_MOB_IDS + PHASE_TWO_MOB_IDS + "xingsuoshouwei")
        removeHostileProjectiles(s)
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Item>()
            .filter { resourceId(it.itemStack) in DUNGEON_ITEM_IDS }
            .forEach {
                s.entityIds.remove(it.uniqueId)
                it.remove()
            }

        s.phase = Phase.ESCORT
        finishEscort(s)
    }

    private fun finishEscort(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        s.phase = Phase.TRANSITION
        stopPhaseOneTwoBgm(s)
        s.meetingBar?.removeAll(); s.meetingBar = null
        s.niulangBar?.removeAll(); s.niulangBar = null
        s.zhinvBar?.removeAll(); s.zhinvBar = null
        removeMobs(s, AWAKENING_MOB_IDS + AWAKENING_SPECIAL_MOB_IDS + PHASE_TWO_MOB_IDS + "xingsuoshouwei")
        removeHostileProjectiles(s)
        meetingEffect(s)
        val dialogue = listOf(
            "§b牛郎：§f终于赶上了。织女，你还好吗？",
            "§d织女：§f我没事。只是今年的§e星图§f有些异常，我正想与你核对——",
            "§7突然，一道金光毫无征兆地自天顶劈下，将鹊桥拦腰截断。星光如碎羽般四散纷飞……",
            "§4§n王母娘娘：§f擅动天河，击退守卫，还敢在此私会——尔等眼中可还有天规？",
            "§b牛郎：§f娘娘息怒！今年的天河出现了§e异动§f，我们只是想核对星图——",
            "§4§n王母娘娘：§f是否异动，自有天庭查明。轮不到尔等越俎代庖。",
            "§4§n王母娘娘：§f至于你们这些替他们开路的凡人——",
            "§4§n王母娘娘：§f既敢扰乱天河，便随本宫一同来吧。"
        )
        dialogue.forEachIndexed { index, line ->
            later(s, index * 60L) {
                broadcast(s, line)
                playBridgeDialogueCue(s, index)
                if (index == 2) bridgeGoldRiftEffect(s)
            }
        }
        later(s, dialogue.size * 60L) {
            startThirdPhase(s)
        }
    }

    private fun playBridgeDialogueCue(s: Session, index: Int) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = escortMeetingCenter(s) ?: Location(world, -950.15, 105.5, 2404.5)
        when (index) {
            0 -> world.playSound(center, Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.1f)
            1 -> world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.35f)
            2 -> world.playSound(center, Sound.ITEM_TRIDENT_THUNDER, 1.6f, 0.55f)
            3, 5, 6, 7 -> {
                world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.32f, 1.45f)
                world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.95f, 0.50f)
                world.playSound(center, Sound.ENTITY_WITHER_AMBIENT, 0.45f, 0.70f)
            }
            4 -> world.playSound(center, Sound.ENTITY_VILLAGER_NO, 1.0f, 0.75f)
        }
    }

    private fun bridgeGoldRiftEffect(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = escortMeetingCenter(s) ?: Location(world, -950.15, 105.0, 2404.5)
        val first = s.niulang?.let(::escortEntity)?.location
        val second = s.zhinv?.let(::escortEntity)?.location
        val pairDirection = if (first != null && second != null) {
            second.toVector().subtract(first.toVector()).setY(0.0)
        } else Vector(1.0, 0.0, 0.0)
        val riftDirection = if (pairDirection.lengthSquared() < 0.01) Vector(0.0, 0.0, 1.0)
        else Vector(-pairDirection.z, 0.0, pairDirection.x).normalize()
        val dust = Particle.DustOptions(Color.fromRGB(255, 210, 45), 1.45f)
        // 金光先从天顶急速劈落，再于桥面爆散成碎羽般的星芒。
        repeat(22) { step ->
            later(s, step.toLong()) {
                val y = center.y + 28.0 - step * (28.0 / 21.0)
                val beam = Location(world, center.x, y, center.z)
                world.spawnParticle(Particle.DUST, beam, 12, 0.22, 0.72, 0.22, 0.0, dust)
                world.spawnParticle(Particle.END_ROD, beam, 7, 0.30, 0.70, 0.30, 0.04)
                if (step == 21) {
                    world.spawnParticle(Particle.FLASH, center.clone().add(0.0, 1.0, 0.0), 5, 0.0, 0.0, 0.0, 0.0)
                    world.spawnParticle(Particle.FIREWORK, center.clone().add(0.0, 1.2, 0.0), 220, 5.5, 2.6, 5.5, 0.20)
                    world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, 1.2, 0.0), 180, 6.0, 3.0, 6.0, 0.18)
                    world.spawnParticle(Particle.DUST, center.clone().add(0.0, 1.0, 0.0), 130, 5.8, 2.8, 5.8, 0.0, dust)
                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.15f, 1.45f)
                    world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.25f, 0.55f)
                }
            }
        }
        repeat(30) { step ->
            later(s, step * 2L) {
                val height = 22.0 * (step + 1) / 30.0
                repeat(9) { part ->
                    val beam = center.clone().add(0.0, part * height / 8.0, 0.0)
                    world.spawnParticle(Particle.DUST, beam, 1, 0.0, 0.0, 0.0, 0.0, dust)
                    world.spawnParticle(Particle.END_ROD, beam, 1, 0.08, 0.08, 0.08, 0.01)
                }
                val halfLength = 7.0 * (step + 1) / 30.0
                repeat(11) { part ->
                    val offset = -halfLength + part * halfLength * 2.0 / 10.0
                    val point = center.clone().add(riftDirection.clone().multiply(offset)).add(0.0, 0.15, 0.0)
                    world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
                }
                if (step == 15) {
                    world.spawnParticle(Particle.FLASH, center.clone().add(0.0, 1.0, 0.0), 2, 0.0, 0.0, 0.0, 0.0)
                    world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.3f, 0.8f)
                }
            }
        }
    }

    private fun escortMeetingCenter(s: Session): Location? {
        val first = s.niulang?.let(::escortEntity)?.location ?: return null
        val second = s.zhinv?.let(::escortEntity)?.location ?: return null
        return Location(
            first.world,
            (first.x + second.x) / 2.0,
            (first.y + second.y) / 2.0,
            (first.z + second.z) / 2.0
        )
    }

    private fun startThirdPhase(s: Session) {
        if (session !== s || s.ending || s.phase != Phase.TRANSITION) return
        val world = Bukkit.getWorld(s.worldName) ?: return
        val niulang = s.niulang?.let(::escortEntity) ?: return
        val zhinv = s.zhinv?.let(::escortEntity) ?: return
        s.phase = Phase.THIRD_PHASE
        loadPlazaChunks(s)
        // 广场区块此刻已经载入，可覆盖服务器重启前保存在未加载区块中的残留物。
        clearDungeonResidue(world)
        val arrivals = listOf(
            Location(world, -547.5, 4.0, 2412.5, 180.0f, 0.0f),
            Location(world, -551.0, 4.0, 2410.5, 180.0f, 0.0f),
            Location(world, -554.5, 4.0, 2412.5, 180.0f, 0.0f),
            Location(world, -549.0, 4.0, 2408.5, 180.0f, 0.0f),
            Location(world, -553.0, 4.0, 2408.5, 180.0f, 0.0f)
        )
        activePlayers(s).forEachIndexed { index, player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 500, 0, false, false, true))
            player.teleport(arrivals[index % arrivals.size])
        }
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Parrot>().forEach { spirit ->
            val owner = if (spirit.scoreboardTags.contains(NIULANG_SPIRIT_TAG)) niulang else zhinv
            val side = if (owner == niulang) 1.8 else -1.8
            spirit.teleport(owner.location.clone().add(side, 3.0, 0.0))
            spirit.velocity = Vector(0.0, 0.0, 0.0)
        }
        val controller = QixiThirdPhaseController(
            plugin,
            world,
            s.playerIds,
            niulang,
            zhinv,
            config,
            s.difficulty,
            onFinished = { finishThirdPhase(s) }
        )
        s.thirdPhase = controller
        controller.start()
        schedulePhaseTimeout(s, Phase.THIRD_PHASE, "phase-three")
    }

    private fun schedulePhaseTimeout(s: Session, expectedPhase: Phase, configPath: String) {
        val solo = activePlayers(s).size <= 1
        val populationKey = if (solo) "solo" else "multiplayer"
        val defaultSeconds = when (expectedPhase) {
            Phase.ESCORT -> when (s.difficulty) {
                QixiDifficulty.EASY -> if (solo) 20 * 60 else 10 * 60
                QixiDifficulty.HARD -> if (solo) 15 * 60 else 8 * 60
            }
            Phase.THIRD_PHASE -> when (s.difficulty) {
                QixiDifficulty.EASY -> if (solo) 20 * 60 else 25 * 60
                QixiDifficulty.HARD -> if (solo) 25 * 60 else 30 * 60
            }
            else -> return
        }
        val seconds = config.getInt(
            "$configPath.time-limit-seconds.${s.difficulty.configKey}.$populationKey",
            defaultSeconds
        ).coerceAtLeast(1)
        later(s, secondsToTicks(seconds)) {
            if (!isCurrent(s, expectedPhase)) return@later
            val phaseName = if (expectedPhase == Phase.ESCORT) "护送阶段" else "王母娘娘战"
            failSession(s, "§c${phaseName}已超过隐式时间限制，秘境挑战失败！", true)
        }
    }

    private fun finishThirdPhase(s: Session) {
        if (session !== s || s.ending || s.phase != Phase.THIRD_PHASE) return
        val world = Bukkit.getWorld(s.worldName) ?: return
        s.phase = Phase.TRANSITION
        s.thirdPhase?.shutdown()
        s.thirdPhase = null
        removeHostileProjectiles(s)
        val lines = listOf(
            "§4§n王母娘娘§f手中的金簪终于失去光芒，笼罩广场的天威随之消散。",
            "§b牛郎：§f多谢诸位相助……至少今夜，我们终于不必再隔着天河遥望。",
            "§d织女：§f星路尚未真正安定，但今日的情谊，我们绝不会忘记。",
            "§b牛郎：§f今夜七夕，若各位不嫌，便请稍作停留——",
            "§d织女：§f与我们一同看看这片你们亲手守护的天河星辉吧。"
        )
        lines.forEachIndexed { index, line -> later(s, index * 40L) { broadcast(s, line) } }
        later(s, lines.size * 40L) {
            settleThirdPhaseVictory(s, world)
            cleanup(s)
        }
    }

    private fun settleThirdPhaseVictory(s: Session, world: org.bukkit.World) {
        val easy = s.difficulty == QixiDifficulty.EASY
        val chestId = if (easy) QIXI_EASY_CHEST_ID else QIXI_HARD_CHEST_ID
        val destination = if (easy) {
            Location(world, 1385.06, 46.0, 2954.48, 89.95f, -1.95f)
        } else {
            Location(world, 1377.44, 46.0, 3082.62, -89.59f, 7.65f)
        }
        activePlayers(s).forEach { player ->
            plugin.playerManager.getData(player.uniqueId)?.let { data ->
                data.updateStatus(3)
                val record = data.dungeonRecords.getOrPut(chestId) { DungeonRecord() }
                record.clears++
                record.availableOpens++
                plugin.databaseManager.savePlayerAsync(data)
                player.sendMessage(
                    "§e[秘境] §a${s.difficulty.displayName}通关记录 +1，" +
                        "§6[${s.difficulty.displayName}]金宝箱§a可开箱次数 +1！"
                )
                if (data.questStatuses[Side_Qixi_StarWish.ID] == QuestStatus.IN_PROGRESS &&
                    data.questProgress[Side_Qixi_StarWish.ID] == 3
                ) {
                    plugin.questManager.updateProgress(player, Side_Qixi_StarWish.ID, 4)
                    player.sendMessage("§d[限时支线] §f真正的鹊桥已经恢复平静，回去向守桥人柳安报平安吧。")
                }
            }
            player.teleport(destination)
        }
    }

    private fun awakeningEffect(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = Location(world, -950.5, 105.0, 2404.5)
        repeat(50) { tick ->
            later(s, tick.toLong()) {
                val radius = 0.5 + tick * 0.08
                repeat(16) { part ->
                    val angle = part * Math.PI / 8.0 + tick * 0.25
                    val point = center.clone().add(kotlin.math.cos(angle) * radius, tick * 0.07, kotlin.math.sin(angle) * radius)
                    world.spawnParticle(Particle.END_ROD, point, 2, 0.05, 0.05, 0.05, 0.02)
                    world.spawnParticle(Particle.FIREWORK, point, 1, 0.02, 0.02, 0.02, 0.01)
                }
            }
        }
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 2.0f, 1.1f)
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 1.4f)
    }

    private fun meetingEffect(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = Location(world, -950.15, 105.5, 2404.5)
        world.spawnParticle(Particle.HEART, center, 45, 2.0, 1.8, 2.0, 0.08)
        world.spawnParticle(Particle.END_ROD, center, 100, 3.0, 2.5, 3.0, 0.12)
        world.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 1.2f)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) {
        val s = session ?: return
        val deadMobId = mobId(event.entity)
        val fellFromBridge = s.mobOutOfBoundsSince.remove(event.entity.uniqueId) != null
        val recoveryLocation = if (fellFromBridge && s.phase == Phase.AWAKENING) {
            nearestBridgeDropLocation(event.entity.location)
        } else {
            null
        }
        when (deadMobId) {
            SILVER_SPIDER_ID -> {
                val stored = s.spiderCrystals.remove(event.entity.uniqueId) ?: 0
                s.spiderFuseEndsAt.remove(event.entity.uniqueId)
                if (stored > 0) {
                    plugin.resourceManager.getItem(CRYSTAL_ID)?.let { crystal ->
                        crystal.amount = stored
                        event.drops += crystal
                    }
                }
            }
            CHAOS_WALKER_ID -> s.chaosArrivedAt.remove(event.entity.uniqueId)
        }
        if (event.entity.scoreboardTags.contains(ENTITY_TAG) && deadMobId != null) {
            event.drops.filter { resourceId(it) in DUNGEON_ITEM_IDS }.forEach { stack ->
                val meta = stack.itemMeta ?: return@forEach
                meta.persistentDataContainer.set(dungeonDropItemKey, PersistentDataType.BYTE, 1)
                if (recoveryLocation != null && resourceId(stack) == CRYSTAL_ID) {
                    meta.persistentDataContainer.set(bridgeRecoveryKey, PersistentDataType.BYTE, 1)
                    meta.persistentDataContainer.set(bridgeRecoveryXKey, PersistentDataType.DOUBLE, recoveryLocation.x)
                    meta.persistentDataContainer.set(bridgeRecoveryYKey, PersistentDataType.DOUBLE, recoveryLocation.y)
                    meta.persistentDataContainer.set(bridgeRecoveryZKey, PersistentDataType.DOUBLE, recoveryLocation.z)
                }
                stack.itemMeta = meta
            }
        }
        if (event.entity.uniqueId in s.starLockIds) breakStarLock(s)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val s = session ?: return
        val player = event.entity
        if (!isSessionPlayer(s, player)) return
        removePlayerFromSession(s, player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        pendingDifficultySelection?.let { pending ->
            if (event.player.uniqueId == pending.selectorId) {
                timeoutDifficultySelection(pending)
                return
            }
            pending.playerIds.remove(event.player.uniqueId)
        }
        val s = session ?: return
        if (!isSessionPlayer(s, event.player)) return
        plugin.logger.info("玩家 ${event.player.name} 中途退出鹊桥星愿，按放弃副本处置并直接死亡")
        if (!event.player.isDead && event.player.health > 0.0) event.player.health = 0.0
        if (isSessionPlayer(s, event.player)) removePlayerFromSession(s, event.player)
    }

    private fun removePlayerFromSession(s: Session, player: Player) {
        stopQixiBgm(player)
        clearFeatherShield(s, player, true)
        removeDungeonItems(player)
        player.removeScoreboardTag(PLAYER_TAG)
        removeAttackBlessing(player)
        player.resetPlayerTime()
        s.playerIds.remove(player.uniqueId)
        s.fallTicks.remove(player.uniqueId)
        s.thirdPhase?.removePlayer(player)
        listOf(
            s.awakeningBar, s.niulangSpiritBar, s.zhinvSpiritBar,
            s.meetingBar, s.niulangBar, s.zhinvBar, s.starLockBar
        ).forEach { it?.removePlayer(player) }
        if (!s.ending && activePlayers(s).isEmpty()) failSession(s, "§c所有副本玩家均已离开或阵亡，秘境挑战失败！", false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDropsItem(event: PlayerDropItemEvent) {
        val s = session ?: return
        if (isSessionPlayer(s, event.player) && resourceId(event.itemDrop.itemStack) in DUNGEON_ITEM_IDS) {
            tagEntity(s, event.itemDrop)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        val s = session ?: return
        val pdc = event.entity.itemStack.itemMeta?.persistentDataContainer
        val markedDrop = pdc?.has(dungeonDropItemKey, PersistentDataType.BYTE) == true
        if (event.location.world?.name == s.worldName && markedDrop && resourceId(event.entity.itemStack) in DUNGEON_ITEM_IDS) {
            tagEntity(s, event.entity)
        }
        if (pdc?.has(bridgeRecoveryKey, PersistentDataType.BYTE) == true && event.location.world?.name == s.worldName) {
            val x = pdc.get(bridgeRecoveryXKey, PersistentDataType.DOUBLE) ?: return
            val y = pdc.get(bridgeRecoveryYKey, PersistentDataType.DOUBLE) ?: return
            val z = pdc.get(bridgeRecoveryZKey, PersistentDataType.DOUBLE) ?: return
            event.entity.teleport(Location(event.location.world, x, y, z))
            event.entity.world.spawnParticle(Particle.END_ROD, event.entity.location, 12, 0.35, 0.35, 0.35, 0.04)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonItemPickup(event: EntityPickupItemEvent) {
        val s = session ?: return
        val itemId = resourceId(event.item.itemStack)
        if (itemId !in DUNGEON_ITEM_IDS) return
        val player = event.entity as? Player ?: return
        if (!isSessionPlayer(s, player)) {
            event.isCancelled = true
            return
        }
        if (itemId != CRYSTAL_ID) return

        // 晶核永远不进入玩家背包；玩家触碰后会呼唤最近一只尚未开始运送的鹊灵。
        event.isCancelled = true
        if (!isCurrent(s, Phase.AWAKENING)) {
            s.entityIds.remove(event.item.uniqueId)
            event.item.remove()
            return
        }
        prepareWaitingCrystal(event.item)
        val spirit = s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Parrot>()
            .filter { it.isValid && (s.spiritGroundCrystals[it.uniqueId] ?: 0) < SPIRIT_GROUND_CRYSTAL_LIMIT }
            .minByOrNull { it.location.distanceSquared(event.item.location) }
        if (spirit == null) {
            sendActionBar(player, "§b两只鹊灵都在运送晶核，这枚晶核已浮起并等待鹊灵返回。")
            return
        }
        s.spiritCrystalTargets[spirit.uniqueId] = event.item.uniqueId
        sendActionBar(player, "§b你触碰了晶核，距离最近且尚未运送的鹊灵正从鹊桥另一处赶来！")
        spirit.world.spawnParticle(Particle.END_ROD, spirit.location, 15, 0.5, 0.5, 0.5, 0.06)
        spirit.world.playSound(spirit.location, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.9f, 1.4f)
    }

    private fun prepareWaitingCrystal(item: Item) {
        if (!item.scoreboardTags.contains(CRYSTAL_WAITING_TAG)) {
            item.addScoreboardTag(CRYSTAL_WAITING_TAG)
            item.teleport(item.location.clone().add(0.0, 0.35, 0.0))
        }
        item.setGravity(false)
        item.velocity = Vector(0.0, 0.0, 0.0)
        item.pickupDelay = Int.MAX_VALUE
    }

    private fun failSession(s: Session, reason: String, killPlayers: Boolean) {
        if (s.ending || session !== s) return
        s.ending = true
        broadcast(s, reason)
        val players = activePlayers(s).toList()
        if (killPlayers) players.forEach { it.health = 0.0 }
        cleanup(s)
    }

    private fun cleanup(s: Session) {
        if (session !== s) return
        s.ending = true
        s.playerIds.mapNotNull(Bukkit::getPlayer).forEach(::stopQixiBgm)
        s.thirdPhase?.shutdown()
        s.thirdPhase = null
        s.tasks.forEach(BukkitTask::cancel)
        s.tasks.clear()
        s.featherShields.keys.toList().mapNotNull(Bukkit::getPlayer)
            .forEach { clearFeatherShield(s, it, true) }
        listOf(
            s.awakeningBar, s.niulangSpiritBar, s.zhinvSpiritBar,
            s.meetingBar, s.niulangBar, s.zhinvBar, s.starLockBar
        ).forEach { it?.removeAll() }
        s.entityIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        Bukkit.getWorld(s.worldName)?.entities?.filter { it.scoreboardTags.contains(ENTITY_TAG) }?.forEach(Entity::remove)
        Bukkit.getWorld(s.worldName)?.let(::clearDungeonResidue)
        Bukkit.getWorld(s.worldName)?.let { world ->
            s.chunkTickets.forEach { (x, z) -> world.getChunkAt(x, z).removePluginChunkTicket(plugin) }
        }
        s.chunkTickets.clear()
        s.playerIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach(Player::resetPlayerTime)
        // 回收副本道具：地面实体以及本实例玩家/误捡者背包中的晶核和星河令均不带出实例。
        Bukkit.getWorld(s.worldName)?.entities?.filterIsInstance<Item>()
            ?.filter { resourceId(it.itemStack) in DUNGEON_ITEM_IDS }
            ?.forEach(Entity::remove)
        Bukkit.getOnlinePlayers().filter { it.world.name == s.worldName }.forEach { player ->
            removeDungeonItems(player)
            player.removeScoreboardTag(PLAYER_TAG)
            removeAttackBlessing(player)
        }
        QixiCollisionSupport.clear()
        session = null
    }

    fun shutdown() {
        pendingDifficultySelection?.task?.cancel()
        pendingDifficultySelection = null
        session?.let(::cleanup)
        Bukkit.getOnlinePlayers().forEach {
            stopQixiBgm(it)
            it.removeScoreboardTag(PLAYER_TAG)
            removeAttackBlessing(it)
            it.getAttribute(Attribute.MAX_ABSORPTION)?.let { attribute ->
                attribute.getModifier(featherShieldKey)?.let(attribute::removeModifier)
                it.absorptionAmount = it.absorptionAmount.coerceAtMost(attribute.value.coerceAtLeast(0.0))
            }
        }
    }

    private fun removeDungeonItems(player: Player) {
        player.inventory.contents.forEachIndexed { index, stack ->
            if (stack != null && resourceId(stack) in DUNGEON_ITEM_IDS) player.inventory.setItem(index, null)
        }
    }

    private fun scheduleDialogue(s: Session, lines: List<String>, initialDelay: Long, after: () -> Unit) {
        val expectedPhase = s.phase
        lines.forEachIndexed { index, line ->
            later(s, initialDelay + index * 60L) {
                if (isCurrent(s, expectedPhase)) broadcast(s, line)
            }
        }
        later(s, initialDelay + lines.size * 60L) {
            if (isCurrent(s, expectedPhase)) after()
        }
    }

    private fun createBar(s: Session, title: String, color: BarColor): BossBar {
        return Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10).also { bar ->
            activePlayers(s).forEach(bar::addPlayer)
            bar.progress = 1.0.coerceAtMost(if (title.contains("0%")) 0.0 else 1.0)
        }
    }

    private fun activePlayers(s: Session): List<Player> = s.playerIds.mapNotNull(Bukkit::getPlayer)
        .filter { it.isOnline && it.scoreboardTags.contains(PLAYER_TAG) }

    private fun isSessionPlayer(s: Session, player: Player): Boolean =
        player.uniqueId in s.playerIds && player.scoreboardTags.contains(PLAYER_TAG)

    private fun broadcast(s: Session, message: String) = activePlayers(s).forEach { it.sendMessage(message) }

    private fun playPhaseOneTwoBgm(s: Session) {
        stopPhaseOneTwoBgm(s)
        playPhaseOneTwoBgmOnce(s)
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (session !== s || s.ending ||
                (s.phase != Phase.AWAKENING && s.phase != Phase.ESCORT_DIALOGUE && s.phase != Phase.ESCORT)
            ) return@Runnable
            playPhaseOneTwoBgmOnce(s)
        }, QIXI_PHASE_ONE_TWO_BGM_LOOP_TICKS, QIXI_PHASE_ONE_TWO_BGM_LOOP_TICKS)
        s.phaseOneTwoBgmTask = task
        s.tasks += task
    }

    private fun playPhaseOneTwoBgmOnce(s: Session) {
        activePlayers(s).forEach { player ->
            stopSound(player, QIXI_PHASE_ONE_TWO_BGM)
            player.playSound(player.location, QIXI_PHASE_ONE_TWO_BGM, SoundCategory.RECORDS, 1.0f, 1.0f)
        }
    }

    private fun stopPhaseOneTwoBgm(s: Session) {
        s.phaseOneTwoBgmTask?.let { task ->
            task.cancel()
            s.tasks.remove(task)
        }
        s.phaseOneTwoBgmTask = null
        activePlayers(s).forEach { stopSound(it, QIXI_PHASE_ONE_TWO_BGM) }
    }

    private fun stopQixiBgm(player: Player) {
        stopSound(player, QIXI_PHASE_ONE_TWO_BGM)
        stopSound(player, QIXI_PHASE_THREE_BGM)
    }

    private fun stopSound(player: Player, sound: String) {
        player.stopSound(sound, SoundCategory.RECORDS)
        player.stopSound(sound)
    }

    private fun sendActionBar(player: Player, message: String) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message))
    }

    private fun isCurrent(s: Session, phase: Phase): Boolean = session === s && !s.ending && s.phase == phase

    private fun later(s: Session, delay: Long, action: () -> Unit) {
        s.tasks += plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (session === s && !s.ending) action()
        }, delay.coerceAtLeast(0L))
    }

    private fun repeating(s: Session, delay: Long, period: Long, action: () -> Unit) {
        s.tasks += plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (session === s && !s.ending) action()
        }, delay.coerceAtLeast(1L), period.coerceAtLeast(1L))
    }

    private fun tagEntity(s: Session, entity: Entity) {
        entity.addScoreboardTag(ENTITY_TAG)
        entity.persistentDataContainer.set(dungeonEntityKey, PersistentDataType.BYTE, 1)
        if (entity is LivingEntity && mobId(entity) != null) {
            QixiCollisionSupport.enableProjectileHits(entity)
        }
        s.entityIds.add(entity.uniqueId)
    }

    private fun loadBridgeChunks(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        for (chunkX in Math.floorDiv(-1048, 16)..Math.floorDiv(-854, 16)) {
            for (chunkZ in Math.floorDiv(2390, 16)..Math.floorDiv(2418, 16)) {
                val chunk = world.getChunkAt(chunkX, chunkZ)
                if (chunk.addPluginChunkTicket(plugin)) s.chunkTickets += chunkX to chunkZ
            }
        }
    }

    /** 清除七夕副本两个战斗场景内遗留的掉落物和玩家/怪物箭矢。 */
    private fun clearDungeonResidue(world: org.bukkit.World) {
        world.entities.asSequence()
            .filter { it is Item || it is AbstractArrow }
            .filter { entity ->
                val location = entity.location
                isInsideBridgeArea(location) || isInsidePlazaArea(location)
            }
            .forEach(Entity::remove)
    }

    private fun isInsideBridgeArea(location: Location): Boolean =
        location.x in QIXI_BRIDGE_MIN_X..QIXI_BRIDGE_MAX_X &&
            location.z in QIXI_BRIDGE_MIN_Z..QIXI_BRIDGE_MAX_Z

    private fun isInsidePlazaArea(location: Location): Boolean =
        location.x in QIXI_PLAZA_MIN_X..QIXI_PLAZA_MAX_X &&
            location.z in QIXI_PLAZA_MIN_Z..QIXI_PLAZA_MAX_Z

    private fun loadPlazaChunks(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        for (chunkX in Math.floorDiv(-602, 16)..Math.floorDiv(-499, 16)) {
            for (chunkZ in Math.floorDiv(2364, 16)..Math.floorDiv(2504, 16)) {
                val chunk = world.getChunkAt(chunkX, chunkZ)
                if (chunk.addPluginChunkTicket(plugin)) s.chunkTickets += chunkX to chunkZ
            }
        }
    }

    private fun taggedMobs(s: Session, ids: Set<String>): List<LivingEntity> = s.entityIds
        .mapNotNull(Bukkit::getEntity)
        .filterIsInstance<LivingEntity>()
        .filter { it.isValid && mobId(it) in ids }

    private fun removeMobs(s: Session, ids: Set<String>) {
        taggedMobs(s, ids).forEach {
            s.starLockIds.remove(it.uniqueId)
            s.spiderCrystals.remove(it.uniqueId)
            s.spiderFuseEndsAt.remove(it.uniqueId)
            s.chaosArrivedAt.remove(it.uniqueId)
            s.mobOutOfBoundsSince.remove(it.uniqueId)
            s.entityIds.remove(it.uniqueId)
            it.remove()
        }
        if (s.starLockIds.isEmpty()) {
            s.starLocked = false
            s.starLockCenter = null
            s.starLockDirection = null
            clearStarLockBar(s)
        }
    }

    private fun removeHostileProjectiles(s: Session) {
        Bukkit.getWorld(s.worldName)?.entities
            ?.filterIsInstance<Projectile>()
            ?.filter { it.scoreboardTags.contains(ENTITY_TAG) }
            ?.forEach(Entity::remove)
    }

    private fun mobId(entity: LivingEntity): String? =
        entity.persistentDataContainer.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING)

    private fun resourceId(stack: org.bukkit.inventory.ItemStack): String? =
        stack.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)

    private fun escortEntity(escort: Escort): Villager? = Bukkit.getEntity(escort.entityId) as? Villager

    private fun escortByEntity(s: Session, uuid: UUID): Escort? = when (uuid) {
        s.niulang?.entityId -> s.niulang
        s.zhinv?.entityId -> s.zhinv
        else -> null
    }

    private fun actualAttacker(entity: Entity): LivingEntity? = when (entity) {
        is Projectile -> entity.shooter as? LivingEntity
        is LivingEntity -> entity
        else -> null
    }

    private fun bridgeSpawnLocation(world: org.bukkit.World, index: Int? = null, total: Int = 1): Location {
        val random = ThreadLocalRandom.current()
        val minX = -1044.0
        val maxX = -858.0
        val segmentMin = if (index == null) minX else minX + index * (maxX - minX) / total.coerceAtLeast(1)
        val segmentMax = if (index == null) maxX else minX + (index + 1) * (maxX - minX) / total.coerceAtLeast(1)
        repeat(25) {
            val x = random.nextDouble(segmentMin, segmentMax)
            val z = random.nextDouble(2399.0, 2410.0)
            findBridgeFeetLocation(world, x, z)?.let { return it }
        }
        val fallbackX = (segmentMin + segmentMax) / 2.0
        return findBridgeFeetLocation(world, fallbackX, 2404.5)
            ?: Location(world, fallbackX, 106.0, 2404.5)
    }

    /**
     * 拱桥不是线性斜坡。限定在桥面/亭子地板高度内，从上向下取首个可站立方块，
     * 从而跳过桥底的白色装饰、支柱和低层结构。
     */
    private fun findBridgeFeetLocation(world: org.bukkit.World, x: Double, z: Double): Location? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        for (floorY in 104 downTo 70) {
            val floor = world.getBlockAt(blockX, floorY, blockZ)
            if (!floor.type.isAir && floor.type.isSolid &&
                world.getBlockAt(blockX, floorY + 1, blockZ).isPassable &&
                world.getBlockAt(blockX, floorY + 2, blockZ).isPassable
            ) {
                return Location(world, blockX + 0.5, floorY + 1.0, blockZ + 0.5)
            }
        }
        return null
    }

    private fun snapEscortToBridge(location: Location): Location {
        val offsets = doubleArrayOf(0.0, -0.5, 0.5, -1.0, 1.0, -1.5, 1.5, -2.0, 2.0)
        for (offset in offsets) {
            val surface = findBridgeFeetLocation(location.world, location.x, location.z + offset) ?: continue
            surface.yaw = location.yaw
            surface.pitch = location.pitch
            return surface
        }
        return location
    }

    private fun monitorKnockedOffMobs(s: Session) {
        if (session !== s || s.ending || (s.phase != Phase.AWAKENING && s.phase != Phase.ESCORT)) {
            s.mobOutOfBoundsSince.clear()
            return
        }
        val now = System.currentTimeMillis()
        val checkedIds = HashSet<UUID>()
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<LivingEntity>().forEach { entity ->
            val id = mobId(entity) ?: return@forEach
            if (id == CHAOS_WALKER_ID || !entity.isValid || entity.isDead) return@forEach
            checkedIds += entity.uniqueId
            if (!isOutsideBridgeDeck(entity.location)) {
                s.mobOutOfBoundsSince.remove(entity.uniqueId)
                return@forEach
            }
            val outSince = s.mobOutOfBoundsSince.getOrPut(entity.uniqueId) { now }
            if (now - outSince < MOB_OUT_OF_BOUNDS_KILL_MILLIS) return@forEach
            entity.health = 0.0
        }
        s.mobOutOfBoundsSince.keys.removeIf { it !in checkedIds && Bukkit.getEntity(it) == null }
    }

    private fun isOutsideBridgeDeck(location: Location): Boolean {
        if (location.z < BRIDGE_MIN_Z || location.z > BRIDGE_MAX_Z) return true
        val centerX = location.x.coerceIn(BRIDGE_MIN_X, BRIDGE_MAX_X)
        val expectedFeet = findBridgeFeetLocation(location.world, centerX, BRIDGE_CENTER_Z)?.y ?: return false
        return location.y < expectedFeet - MOB_BELOW_DECK_TOLERANCE
    }

    private fun nearestBridgeDropLocation(location: Location): Location {
        val world = location.world
        val baseX = location.x.coerceIn(BRIDGE_MIN_X, BRIDGE_MAX_X)
        val offsets = doubleArrayOf(0.0, -1.0, 1.0, -2.0, 2.0, -4.0, 4.0, -8.0, 8.0)
        offsets.forEach { offset ->
            findBridgeFeetLocation(world, (baseX + offset).coerceIn(BRIDGE_MIN_X, BRIDGE_MAX_X), BRIDGE_CENTER_Z)
                ?.let { return it.add(0.0, 0.35, 0.0) }
        }
        return Location(world, baseX, 106.0, BRIDGE_CENTER_Z)
    }

    private fun rescueFallenPlayers(s: Session) {
        if (session !== s || s.ending) return
        if (s.phase != Phase.INTRO && s.phase != Phase.AWAKENING && s.phase != Phase.ESCORT_DIALOGUE && s.phase != Phase.ESCORT) {
            s.fallTicks.clear()
            return
        }
        val world = Bukkit.getWorld(s.worldName) ?: return
        val leftRescue = Location(world, -1045.59, 81.0, 2404.43, 270.86f, -11.70f)
        val rightRescue = Location(world, -850.30, 77.0, 2404.74, 90.41f, -6.90f)
        activePlayers(s).forEach { player ->
            val falling = (!player.isOnGround && player.velocity.y < -0.08) || player.location.y < 75.5
            if (!falling) {
                s.fallTicks.remove(player.uniqueId)
                return@forEach
            }
            val ticks = (s.fallTicks[player.uniqueId] ?: 0) + 5
            s.fallTicks[player.uniqueId] = ticks
            if (ticks < 40) return@forEach

            val destination = if (horizontalDistanceSquared(player.location, leftRescue) <=
                horizontalDistanceSquared(player.location, rightRescue)
            ) leftRescue else rightRescue
            player.teleport(destination)
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 2, false, true, true))
            player.sendMessage("§7你掉出了鹊桥，鹊灵把你捞了上来，但你因此行动迟缓……")
            s.fallTicks.remove(player.uniqueId)
        }
    }

    private fun horizontalDistanceSquared(first: Location, second: Location): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun findSafeFeetLocation(world: org.bukkit.World, x: Double, z: Double, expectedFeetY: Double): Location? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        val expectedFloor = kotlin.math.floor(expectedFeetY - 1.0).toInt()
        val candidates = (expectedFloor - 5..expectedFloor + 5).filter { y ->
            val floor = world.getBlockAt(blockX, y, blockZ)
            !floor.type.isAir && floor.type.isSolid && world.getBlockAt(blockX, y + 1, blockZ).isPassable &&
                world.getBlockAt(blockX, y + 2, blockZ).isPassable
        }
        val floorY = candidates.minByOrNull { abs(it - expectedFloor) } ?: return null
        return Location(world, blockX + 0.5, floorY + 1.0, blockZ + 0.5)
    }

    private fun lerp(start: Location, end: Location, progress: Double): Location {
        val t = progress.coerceIn(0.0, 1.0)
        return Location(
            start.world,
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t,
            start.z + (end.z - start.z) * t,
            start.yaw + (end.yaw - start.yaw) * t.toFloat(),
            start.pitch + (end.pitch - start.pitch) * t.toFloat()
        )
    }

    private fun settings(s: Session): DifficultySettings = difficultySettings.getValue(s.difficulty)

    private fun configureDungeonMob(s: Session, entity: LivingEntity) {
        val multiplier = settings(s).attributeMultiplier
        entity.getAttribute(Attribute.MAX_HEALTH)?.let { attribute ->
            val health = (attribute.baseValue * multiplier).coerceAtLeast(1.0)
            attribute.baseValue = health
            entity.health = health
        }
        entity.persistentDataContainer.get(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE)?.let {
            entity.persistentDataContainer.set(MobFactory.KEY_CUSTOM_ARMOR, PersistentDataType.DOUBLE, it * multiplier)
        }
        entity.persistentDataContainer.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE)?.let {
            entity.persistentDataContainer.set(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE, it * multiplier)
        }
    }

    private fun desiredPhaseTwoMobCount(s: Session): Int {
        val playerCount = activePlayers(s).size
        if (playerCount <= 0 || elitePerPlayer <= 0) return 0
        return (playerCount * elitePerPlayer).coerceAtMost(maxPlayers * elitePerPlayer)
    }

    private fun trimExcessEliteGuards(s: Session) {
        val desired = desiredPhaseTwoMobCount(s)
        val mobs = taggedMobs(s, PHASE_TWO_MOB_IDS)
        val excess = mobs.size - desired
        if (excess <= 0) return
        val players = activePlayers(s)
        mobs.sortedByDescending { mob ->
            players.minOfOrNull { it.location.distanceSquared(mob.location) } ?: 0.0
        }.take(excess).forEach { mob ->
            s.entityIds.remove(mob.uniqueId)
            mob.remove()
        }
    }

    private fun secondsToTicks(seconds: Int): Long = seconds.coerceAtLeast(1) * 20L

    private companion object {
        const val FEATHER_HEAL_AMOUNT = 10
        const val ZHINV_SPIRIT_PLAYER_HEAL_AMOUNT = 10.0
        const val ZHINV_SPIRIT_HEAL_AMOUNT = 5
        const val ESCORT_DAMAGE_COOLDOWN_MILLIS = 800L
        const val FEATHER_COOLDOWN_MILLIS = 10_000L
        const val SOLO_GALAXY_ORDER_COOLDOWN_MILLIS = 7_000L
        const val SOLO_GALAXY_ORDER_DAMAGE_RADIUS = 5.0
        const val SOLO_GALAXY_ORDER_MAX_HEALTH_DAMAGE_RATIO = 0.50
        const val GALAXY_ORDER_STAR_LOCK_RADIUS = 5.0
        const val GALAXY_ORDER_STAR_LOCK_DISABLE_TICKS = 3 * 20
        const val GALAXY_ORDER_STAR_LOCK_ARMOR_BREAK_TICKS = 5 * 20
        // OGG 实际时长约 194.250 秒，向上取整到完整 tick，避免循环重叠。
        const val QIXI_PHASE_ONE_TWO_BGM_LOOP_TICKS = 3_886L
        const val QIXI_BRIDGE_MIN_X = -1055.0
        const val QIXI_BRIDGE_MAX_X = -845.0
        const val QIXI_BRIDGE_MIN_Z = 2385.0
        const val QIXI_BRIDGE_MAX_Z = 2425.0
        const val QIXI_PLAZA_MIN_X = -610.0
        const val QIXI_PLAZA_MAX_X = -490.0
        const val QIXI_PLAZA_MIN_Z = 2355.0
        const val QIXI_PLAZA_MAX_Z = 2510.0
        const val DUNGEON_NIGHT_TIME = 18_000L
        const val FEATHER_SHIELD_AMOUNT = 20.0
        const val FEATHER_SHIELD_DURATION_MILLIS = 30_000L
        const val SPIRIT_CRYSTAL_ABSORB_DISTANCE_SQUARED = 6.0 * 6.0
        const val SPIRIT_DELIVERY_DISTANCE_SQUARED = 2.25 * 2.25
        const val SPIRIT_GROUND_CRYSTAL_LIMIT = 5
        const val SPIRIT_NORMAL_SPEED = 0.58
        const val SPIRIT_DELIVERY_SPEED = SPIRIT_NORMAL_SPEED * 2.0
        const val SPIRIT_ESCORT_SPEED = 0.65
        const val SPIRIT_DELIVERY_WAYPOINT_STEP = 4.0
        const val SPIRIT_BRIDGE_CLEARANCE = 8.0
        const val SPIRIT_ESCORT_HEIGHT = 7.5
        const val SPIRIT_PAVILION_MIN_X = -963.0
        const val SPIRIT_PAVILION_MAX_X = -938.0
        const val SPIRIT_PAVILION_FLIGHT_Y = 118.0
        const val SPIRIT_IDLE_RETURN_DISTANCE_SQUARED = 4.0 * 4.0
        const val SPIRIT_STUCK_ESCAPE_STEP = 1.25
        const val SPIRIT_CRYSTAL_FINAL_APPROACH_DISTANCE_SQUARED = 3.0 * 3.0
        const val SPIRIT_CRYSTAL_APPROACH_HEIGHT = 1.25
        const val SPIRIT_BLESSING_RADIUS_SQUARED = 10.0 * 10.0
        const val SPIRIT_FINAL_APPROACH_DISTANCE_SQUARED = 4.0 * 4.0
        const val SPIRIT_BLESSING_COOLDOWN_TICKS = 15.0 * 20.0
        const val SPIRIT_DELIVERY_BLESSING_RATE = 1.5
        const val PROGRESS_MECHANIC_PERIOD_TICKS = 20L * 20L
        const val SILVER_SPIDERS_PER_WAVE = 6
        const val STAR_COMMANDERS_PER_WAVE = 3
        const val CHAOS_WALKERS_PER_WAVE = 3
        const val SILVER_SPIDER_CRYSTALS_TO_EXPLODE = 3
        const val SILVER_SPIDER_FUSE_MILLIS = 3_000L
        const val SILVER_SPIDER_PATH_SPEED = 1.25
        const val SILVER_SPIDER_ABSORB_DISTANCE_SQUARED = 2.75 * 2.75
        const val SILVER_SPIDER_EXPLOSION_RADIUS_SQUARED = 6.0 * 6.0
        const val SILVER_SPIDER_EXPLOSION_DAMAGE = 25.0
        const val CHAOS_WALKER_CHANNEL_MILLIS = 5_000L
        const val CHAOS_WALKER_PROGRESS_LOSS = 10
        const val CHAOS_WALKER_SPEED = 0.65
        const val CHAOS_WALKER_WAYPOINT_STEP = 5.0
        const val CHAOS_WALKER_BRIDGE_CLEARANCE = 5.5
        const val CHAOS_WALKER_FINAL_APPROACH_DISTANCE = 4.0
        const val MAX_ELITES_NEAR_EACH_ESCORT_PER_BATCH = 4
        const val MOB_BOUNDARY_CHECK_TICKS = 10L
        const val DIFFICULTY_SELECTION_SECONDS = 10
        const val DIFFICULTY_SELECTION_COMMAND = "/qixi-difficulty-select"
        const val MOB_OUT_OF_BOUNDS_KILL_MILLIS = 3_000L
        const val MOB_BELOW_DECK_TOLERANCE = 2.5
        const val BRIDGE_MIN_X = -1047.0
        const val BRIDGE_MAX_X = -849.0
        const val BRIDGE_MIN_Z = 2391.30
        const val BRIDGE_MAX_Z = 2417.70
        const val BRIDGE_CENTER_Z = 2404.5
        const val PLAYER_TAG = "qixi_queqiao_player"
        const val ENTITY_TAG = "qixi_queqiao_entity"
        const val CRYSTAL_WAITING_TAG = "qixi_crystal_waiting"
        const val NIULANG_SPIRIT_TAG = "qixi_niulang_spirit"
        const val ZHINV_SPIRIT_TAG = "qixi_zhinv_spirit"
        const val CRYSTAL_ID = "queqiaojinghe"
        const val GALAXY_ORDER_ID = "xingheling"
        const val SILVER_SPIDER_ID = "yinlingzhu"
        const val STAR_COMMANDER_ID = "xinghetongling"
        const val CHAOS_WALKER_ID = "luanxingzhe"
        const val STAR_LOCK_GUARD_ID = "xingsuoshouwei"
        const val NIULANG_ATTACK_PREFIX = "qixi_niulang_spirit::"
        const val JADE_ORDER_ID = "yaochiling"
        const val PHASE_THREE_TEST_ITEM_ID = "qixi_phase_three_test"
        const val QIXI_EASY_CHEST_ID = "qixi_easy"
        const val QIXI_HARD_CHEST_ID = "qixi_hard"
        const val EASY_EXIT_BELL_X = 1373
        const val HARD_EXIT_BELL_X = 1387
        const val EXIT_BELL_Y = 46
        const val EASY_EXIT_BELL_Z = 2954
        const val HARD_EXIT_BELL_Z = 3082
        const val QIXI_BRIDGE_RETURN_X = 1301.78
        const val QIXI_BRIDGE_RETURN_Y = 37.0
        const val QIXI_BRIDGE_RETURN_Z = 3018.47
        const val QIXI_BRIDGE_RETURN_YAW = -89.14f
        const val QIXI_BRIDGE_RETURN_PITCH = 0.15f
        val DUNGEON_ITEM_IDS = setOf(CRYSTAL_ID, GALAXY_ORDER_ID, JADE_ORDER_ID)
        val AWAKENING_MOB_IDS = setOf("qixi_zombie", "qixi_skeleton")
        val AWAKENING_SPECIAL_MOB_IDS = setOf(SILVER_SPIDER_ID, STAR_COMMANDER_ID, CHAOS_WALKER_ID)
        val ELITE_MOB_IDS = setOf("yinhebuwei", "yinyugongwei")
        val PHASE_TWO_MOB_IDS = ELITE_MOB_IDS + STAR_COMMANDER_ID
    }
}
