package com.hjh_database.dungeon.qixi

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.block.data.type.Lantern
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
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
import kotlin.math.max
import kotlin.math.min

/** 鹊桥星愿副本（当前完成入口、第一阶段、第二阶段和第三阶段测试落点）。 */
class QixiDungeonManager(private val plugin: Hjh_database) : Listener {
    private enum class Phase { INTRO, AWAKENING, ESCORT_DIALOGUE, ESCORT, TRANSITION }

    private data class Escort(
        val displayName: String,
        val entityId: UUID,
        val start: Location,
        val target: Location,
        var health: Int = 100,
        var healingActions: Int = 0
    )

    private data class Session(
        val worldName: String,
        val playerIds: MutableSet<UUID>,
        val entityIds: MutableSet<UUID> = HashSet(),
        val tasks: MutableSet<BukkitTask> = HashSet(),
        val chunkTickets: MutableSet<Pair<Int, Int>> = HashSet(),
        val starLockIds: MutableSet<UUID> = HashSet(),
        val retaliationReadyAt: MutableMap<UUID, Long> = HashMap(),
        val fallTicks: MutableMap<UUID, Int> = HashMap(),
        var phase: Phase = Phase.INTRO,
        var niulang: Escort? = null,
        var zhinv: Escort? = null,
        var awakeningProgress: Int = 0,
        var escortProgress: Double = 0.0,
        var awakeningBar: BossBar? = null,
        var meetingBar: BossBar? = null,
        var niulangBar: BossBar? = null,
        var zhinvBar: BossBar? = null,
        var starLocked: Boolean = false,
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
    private val starLockMinTicks: Long
    private val starLockMaxTicks: Long

    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val dungeonEntityKey = NamespacedKey(plugin, "qixi_entity")
    private val dungeonDropItemKey = NamespacedKey(plugin, "qixi_drop_item")
    private var session: Session? = null
    private val spiritDirections = HashMap<UUID, Double>()

    init {
        val file = File(plugin.dataFolder, "dungeon/qixi.yml")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            plugin.saveResource("dungeon/qixi.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file)
        worldName = config.getString("world", "world") ?: "world"
        minLevel = config.getInt("entry.min-level", 40)
        minRarity = config.getInt("entry.min-total-rarity", 40)
        maxPlayers = config.getInt("entry.max-players", 5).coerceAtLeast(1)
        initialGuards = config.getInt("phase-one.initial-guards", 40).coerceAtLeast(0)
        guardsPerPlayer = config.getInt("phase-one.guards-per-player", 8).coerceAtLeast(0)
        phaseOneReplenishTicks = secondsToTicks(config.getInt("phase-one.replenish-seconds", 10))
        phaseOneTimeoutTicks = secondsToTicks(config.getInt("phase-one.timeout-seconds", 600))
        escortDurationTicks = secondsToTicks(config.getInt("phase-two.escort-seconds", 180)).toDouble()
        elitePerPlayer = config.getInt("phase-two.elite-guards-per-player", 6).coerceAtLeast(0)
        phaseTwoReplenishTicks = secondsToTicks(config.getInt("phase-two.replenish-seconds", 10))
        starLockMinTicks = secondsToTicks(config.getInt("phase-two.star-lock-min-seconds", 20))
        starLockMaxTicks = max(starLockMinTicks, secondsToTicks(config.getInt("phase-two.star-lock-max-seconds", 30)))

        // 热重载或崩服重启后，在线玩家身上残留的旧实例标签不能进入新实例的选择范围。
        Bukkit.getOnlinePlayers().forEach { it.removeScoreboardTag(PLAYER_TAG) }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onEntranceInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.world.name != worldName || block.x != 586 || block.y != 34 || block.z != -557) return
        if (block.type != Material.LANTERN) return
        val lantern = block.blockData as? Lantern ?: return
        if (lantern.isHanging || lantern.isWaterlogged) return
        event.isCancelled = true

        if (session != null) {
            event.player.sendMessage("§c鹊桥星愿秘境正在进行中，请稍后再试！")
            return
        }
        val candidates = block.world.players.filter(::isOnEntryPlatform)
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
        startDungeon(candidates)
    }

    private fun isOnEntryPlatform(player: Player): Boolean {
        if (player.gameMode == GameMode.SPECTATOR || player.world.name != worldName) return false
        val location = player.location
        if (location.x < 587.0 || location.x >= 590.0 || location.z < -558.0 || location.z >= -555.0) return false
        return player.world.getBlockAt(location.blockX, 32, location.blockZ).type == Material.GOLD_BLOCK &&
            location.y >= 33.0 && location.y < 36.0
    }

    private fun startDungeon(players: List<Player>) {
        val world = Bukkit.getWorld(worldName) ?: run {
            players.forEach { it.sendMessage("§c副本世界 $worldName 未加载，请联系管理员！") }
            return
        }
        val shuffled = players.shuffled()
        val current = Session(world.name, shuffled.mapTo(HashSet()) { it.uniqueId })
        session = current
        loadBridgeChunks(current)
        shuffled.forEachIndexed { index, player ->
            player.addScoreboardTag(PLAYER_TAG)
            val destination = if (index % 2 == 0) {
                Location(world, -1045.59, 31.0, 2404.43, 3150.86f, -11.70f)
            } else {
                Location(world, -850.30, 27.0, 2404.74, 2970.41f, -6.90f)
            }
            player.teleport(destination)
            player.sendMessage("§d§l【鹊桥星愿】§f你已踏入星河秘境。")
        }
        repeating(current, 5L, 5L) { rescueFallenPlayers(current) }

        current.niulang = spawnEscort(
            current,
            "§a§l牛郎",
            Location(world, -1046.54, 31.0, 2398.59, 4678.94f, -2.55f),
            Location(world, -950.55, 55.0, 2404.20, 270.0f, 0.0f)
        )
        current.zhinv = spawnEscort(
            current,
            "§a§l织女",
            Location(world, -849.53, 27.0, 2412.13, 4499.85f, -2.10f),
            Location(world, -949.75, 55.0, 2404.80, 90.0f, 0.0f)
        )
        spawnSpirit(current, current.niulang!!.start, "§b§l牛郎的鹊灵", Parrot.Variant.BLUE)
        spawnSpirit(current, current.zhinv!!.start, "§c§l织女的鹊灵", Parrot.Variant.RED)

        val intro = listOf(
            "§7鹊桥已经架起,桥上的星光却显得十分混乱……",
            "§b牛郎:§f这些守卫……是从何而来？",
            "§b牛郎:§f为何要阻止我们相遇……",
            "§b织女:§f勇士们，这些守卫的力量恐怕让我和牛郎难以碰面",
            "§b织女:§f请帮助我们击退他们，唤醒这座鹊桥！",
            "§b牛郎:§f他们身上应该有能唤醒鹊桥的晶核；",
            "§b牛郎:§f击败他们，将晶核丢入到桥中央的亭子中心就好！",
            "§b织女:§f我和牛郎的鹊灵会协助你们，加油！"
        )
        scheduleDialogue(current, intro, 0L) { beginAwakening(current) }
    }

    private fun spawnEscort(s: Session, name: String, start: Location, target: Location): Escort {
        val villager = start.world.spawn(start, Villager::class.java) { npc ->
            npc.customName = name
            npc.isCustomNameVisible = true
            npc.profession = Villager.Profession.NONE
            npc.setAI(false)
            npc.isInvulnerable = true
            npc.isCollidable = false
            npc.removeWhenFarAway = false
            npc.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 100.0
            npc.health = 100.0
        }
        tagEntity(s, villager)
        return Escort(name, villager.uniqueId, start.clone(), target.clone())
    }

    private fun spawnSpirit(s: Session, location: Location, name: String, variant: Parrot.Variant) {
        val parrot = location.world.spawn(location.clone().add(0.0, 1.8, 0.0), Parrot::class.java) {
            it.customName = name
            it.isCustomNameVisible = true
            it.variant = variant
            it.setAI(false)
            it.isInvulnerable = true
            it.isCollidable = false
            it.setGravity(false)
            it.removeWhenFarAway = false
        }
        tagEntity(s, parrot)
        parrot.addScoreboardTag(if (variant == Parrot.Variant.BLUE) NIULANG_SPIRIT_TAG else ZHINV_SPIRIT_TAG)
        spiritDirections[parrot.uniqueId] = if (variant == Parrot.Variant.BLUE) 1.0 else -1.0
    }

    private fun beginAwakening(s: Session) {
        if (!isCurrent(s, Phase.INTRO)) return
        s.phase = Phase.AWAKENING
        s.awakeningBar = createBar(s, "§d§l鹊桥唤醒进度 §f0%", BarColor.PINK)
        repeat(initialGuards) { spawnAwakeningGuard(s, it, initialGuards) }
        broadcast(s, "§d§l【第一阶段】§f收集鹊桥晶核，并投入桥中央的钻石方块！")

        repeating(s, 5L, 5L) { collectCrystals(s) }
        repeating(s, 2L, 2L) { updateSpirits(s) }
        repeating(s, 300L, 300L) { applySpiritBlessings(s) }
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
        tagEntity(s, entity)
    }

    private fun replenishAwakeningGuards(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        val desired = activePlayers(s).size * guardsPerPlayer
        val current = taggedMobs(s, AWAKENING_MOB_IDS).size
        if (current >= desired) return
        repeat(desired - current) { spawnAwakeningGuard(s) }
        broadcast(s, "§c新的鹊桥守卫已到达……")
    }

    private fun collectCrystals(s: Session) {
        if (!isCurrent(s, Phase.AWAKENING)) return
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = Location(world, -950.5, 55.0, 2404.5)
        val items = world.getNearbyEntities(center, 2.75, 2.75, 2.75).filterIsInstance<Item>()
        for (item in items) {
            if (resourceId(item.itemStack) != CRYSTAL_ID || !item.scoreboardTags.contains(ENTITY_TAG)) continue
            val amount = item.itemStack.amount
            item.remove()
            var gained = 0
            repeat(amount) { gained += ThreadLocalRandom.current().nextInt(1, 3) }
            s.awakeningProgress = min(100, s.awakeningProgress + gained)
            s.awakeningBar?.apply {
                progress = s.awakeningProgress / 100.0
                setTitle("§d§l鹊桥唤醒进度 §f${s.awakeningProgress}%")
            }
            world.spawnParticle(Particle.END_ROD, center, 35, 1.2, 1.0, 1.2, 0.08)
            world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.2f)
            broadcast(s, "§b鹊桥晶核融入星光，唤醒进度增加了§e${gained}%§b！")
            if (s.awakeningProgress >= 100) {
                beginEscortDialogue(s)
                return
            }
        }
    }

    private fun updateSpirits(s: Session) {
        if (session !== s || s.ending || s.phase == Phase.INTRO || s.phase == Phase.TRANSITION) return
        val players = activePlayers(s)
        if (players.isEmpty()) return
        val world = Bukkit.getWorld(s.worldName) ?: return
        s.entityIds.mapNotNull(Bukkit::getEntity).filterIsInstance<Parrot>().forEach { spirit ->
            val nearest = players.minByOrNull { it.location.distanceSquared(spirit.location) }
            if (nearest != null && nearest.location.distanceSquared(spirit.location) <= 32.0 * 32.0) {
                val target = nearest.location.clone().add(0.0, 1.8, 0.0)
                val delta = target.toVector().subtract(spirit.location.toVector())
                if (delta.lengthSquared() > 225.0) {
                    spirit.teleport(target)
                } else if (delta.lengthSquared() > 0.1) {
                    spirit.velocity = delta.normalize().multiply(0.42)
                }
            } else {
                var direction = spiritDirections[spirit.uniqueId] ?: 1.0
                if (spirit.location.x > -858.0) direction = -1.0
                if (spirit.location.x < -1042.0) direction = 1.0
                spiritDirections[spirit.uniqueId] = direction
                val nextX = spirit.location.x + direction * 0.38
                val bridgeFeet = findBridgeFeetLocation(world, nextX, 2404.5)
                    ?: Location(world, nextX, 55.0, 2404.5)
                val next = bridgeFeet.add(0.0, 2.5, 0.0)
                spirit.velocity = next.toVector().subtract(spirit.location.toVector()).multiply(0.35)
            }
        }
    }

    private fun applySpiritBlessings(s: Session) {
        if (session !== s || s.ending || (s.phase != Phase.AWAKENING && s.phase != Phase.ESCORT)) return
        val entities = s.entityIds.mapNotNull(Bukkit::getEntity)
        val niuSpirit = entities.filterIsInstance<Parrot>().firstOrNull { it.scoreboardTags.contains(NIULANG_SPIRIT_TAG) }
        val zhiSpirit = entities.filterIsInstance<Parrot>().firstOrNull { it.scoreboardTags.contains(ZHINV_SPIRIT_TAG) }
        if (niuSpirit != null) {
            activePlayers(s).filter { it.location.distanceSquared(niuSpirit.location) <= 100.0 }.forEach { player ->
                val data = plugin.playerManager.getData(player.uniqueId) ?: return@forEach
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "attack_percent"] = 0.10
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "archer_damage_percent"] = 0.10
                data.tempBonuses[NIULANG_ATTACK_PREFIX + "zf_str_percent"] = 0.10
                plugin.playerManager.updateStats(player)
                player.sendMessage("§b牛郎的鹊灵洒下星辉：§f进攻属性提高§e10%§f，持续7秒！")
                player.world.spawnParticle(Particle.ENCHANT, player.location.clone().add(0.0, 1.0, 0.0), 35, 0.7, 1.0, 0.7, 0.1)
                later(s, 140L) { removeAttackBlessing(player) }
            }
        }
        if (zhiSpirit != null) {
            activePlayers(s).filter { it.location.distanceSquared(zhiSpirit.location) <= 100.0 }.forEach { player ->
                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                player.health = min(maxHealth, player.health + 20.0)
                player.sendMessage("§d织女的鹊灵拂过：§f恢复§a20§f点生命！")
                player.world.spawnParticle(Particle.HEART, player.location.clone().add(0.0, 1.0, 0.0), 10, 0.8, 0.8, 0.8, 0.05)
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
        removeMobs(s, AWAKENING_MOB_IDS)
        removeHostileProjectiles(s)
        awakeningEffect(s)
        val dialogue = listOf(
            "§b牛郎和织女:§f太好了！鹊桥被唤醒了",
            "§b牛郎:§f事不宜迟，织女，我们马上出发，于中心相聚！",
            "§b织女:§f等一下，好像来了些难缠的人！他们是天庭的精英守卫！",
            "§b牛郎:§f勇士们，请你们兵分两路，务必保护我和织女在桥中心的亭子下相会！",
            "§b织女:§f我们的鹊灵虽可以帮助反击，但也会动用我们自己的气血，若没有你们，恐难以坚持下去！"
        )
        scheduleDialogue(s, dialogue, 0L) { beginEscort(s) }
    }

    private fun beginEscort(s: Session) {
        if (!isCurrent(s, Phase.ESCORT_DIALOGUE)) return
        s.phase = Phase.ESCORT
        resetEscortState(s, false)
        s.meetingBar = createBar(s, "§d§l牛郎与织女相聚进度 §f0%", BarColor.PURPLE)
        s.niulangBar = createBar(s, "§b§l牛郎气血 §f100/100", BarColor.BLUE)
        s.zhinvBar = createBar(s, "§d§l织女气血 §f100/100", BarColor.PINK)
        repeat(activePlayers(s).size * elitePerPlayer) { spawnEliteGuard(s) }
        broadcast(s, "§d§l【第二阶段】§f保护牛郎与织女抵达鹊桥中央！")

        repeating(s, 1L, 1L) { escortTick(s) }
        repeating(s, 2L, 2L) { drawStarLock(s) }
        repeating(s, 10L, 10L) { attractMonsters(s) }
        repeating(s, phaseTwoReplenishTicks, phaseTwoReplenishTicks) { replenishEliteGuards(s) }
        scheduleNextStarLock(s)
    }

    private fun escortTick(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        val niu = s.niulang ?: return
        val zhi = s.zhinv ?: return
        syncExternalHealing(s, niu)
        syncExternalHealing(s, zhi)
        if (!s.starLocked) {
            s.escortProgress = min(1.0, s.escortProgress + 1.0 / escortDurationTicks)
            escortEntity(niu)?.teleport(snapEscortToBridge(lerp(niu.start, niu.target, s.escortProgress)))
            escortEntity(zhi)?.teleport(snapEscortToBridge(lerp(zhi.start, zhi.target, s.escortProgress)))
        }
        updateEscortBars(s)
        if (s.escortProgress >= 1.0) finishEscort(s)
    }

    private fun syncExternalHealing(s: Session, escort: Escort) {
        val entity = escortEntity(escort) ?: return
        if (entity.health > escort.health + 0.01) recordHealingAction(s, escort)
        if (isCurrent(s, Phase.ESCORT)) entity.health = escort.health.coerceAtLeast(1).toDouble()
    }

    private fun recordHealingAction(s: Session, escort: Escort) {
        if (!isCurrent(s, Phase.ESCORT) || escort.health >= 100) return
        escort.healingActions++
        if (escort.healingActions % 2 == 0) {
            escort.health = min(100, escort.health + 1)
            broadcast(s, "${escort.displayName}§f受到治疗，气血恢复§a1§f点。")
            escortEntity(escort)?.world?.spawnParticle(Particle.HAPPY_VILLAGER, escortEntity(escort)!!.location.clone().add(0.0, 1.0, 0.0), 12, 0.6, 0.8, 0.6, 0.05)
        }
    }

    private fun spawnEliteGuard(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val mobId = if (ThreadLocalRandom.current().nextBoolean()) "yinhebuwei" else "yinyugongwei"
        val entity = MobFactory.spawnMob(plugin, bridgeSpawnLocation(world), mobId, false) ?: return
        tagEntity(s, entity)
        targetNearestEscort(s, entity)
    }

    private fun replenishEliteGuards(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        val desired = activePlayers(s).size * elitePerPlayer
        val current = taggedMobs(s, ELITE_MOB_IDS).size
        repeat((desired - current).coerceAtLeast(0)) { spawnEliteGuard(s) }
    }

    private fun attractMonsters(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        taggedMobs(s, ELITE_MOB_IDS).forEach { targetNearestEscort(s, it) }
    }

    private fun targetNearestEscort(s: Session, mob: LivingEntity) {
        val candidate = listOfNotNull(s.niulang?.let(::escortEntity), s.zhinv?.let(::escortEntity))
            .filter { it.location.distanceSquared(mob.location) <= 400.0 }
            .minByOrNull { it.location.distanceSquared(mob.location) }
        if (mob is Mob && candidate != null) mob.target = candidate
    }

    private fun scheduleNextStarLock(s: Session) {
        val delay = if (starLockMaxTicks == starLockMinTicks) starLockMinTicks else
            ThreadLocalRandom.current().nextLong(starLockMinTicks, starLockMaxTicks + 1)
        later(s, delay) {
            if (isCurrent(s, Phase.ESCORT)) {
                if (!s.starLocked) triggerStarLock(s)
                scheduleNextStarLock(s)
            }
        }
    }

    private fun triggerStarLock(s: Session) {
        val escort = if (ThreadLocalRandom.current().nextBoolean()) s.niulang else s.zhinv ?: s.niulang
        escort ?: return
        val npc = escortEntity(escort) ?: return
        val forward = escort.target.toVector().subtract(npc.location.toVector()).setY(0).normalize()
        val center = npc.location.clone().add(forward.multiply(5.5))
        val perpendicular = Vector(-forward.z, 0.0, forward.x).normalize().multiply(6.0)
        val first = spawnStarLockGuard(s, center.clone().add(perpendicular)) ?: return
        val second = spawnStarLockGuard(s, center.clone().subtract(perpendicular)) ?: run { first.remove(); return }
        s.starLockIds.add(first.uniqueId)
        s.starLockIds.add(second.uniqueId)
        s.starLocked = true
        broadcast(s, "${escort.displayName}:§f不好，是§c星锁守卫§f！")
        broadcast(s, "${escort.displayName}:§f他们成对出现，用星锁拦住了我的去路")
        broadcast(s, "${escort.displayName}:§f不过他们很笨重，快去击杀他！")
        broadcast(s, "§6请击杀§b${ChatColor.stripColor(escort.displayName)}面前的星锁守卫，否则牛郎和织女都将无法移动！")
    }

    private fun spawnStarLockGuard(s: Session, location: Location): LivingEntity? {
        val safe = findSafeFeetLocation(location.world, location.x, location.z, location.y)
            ?: Location(location.world, location.blockX + 0.5, location.y, location.blockZ + 0.5)
        val entity = MobFactory.spawnMob(plugin, safe, "xingsuoshouwei", false) ?: return null
        tagEntity(s, entity)
        if (entity is Mob) entity.setAI(false)
        entity.setGravity(false)
        return entity
    }

    private fun drawStarLock(s: Session) {
        if (!isCurrent(s, Phase.ESCORT) || !s.starLocked) return
        val pair = s.starLockIds.mapNotNull(Bukkit::getEntity).filter { it.isValid }
        if (pair.size < 2) {
            breakStarLock(s)
            return
        }
        val a = pair[0].location.clone().add(0.0, 1.0, 0.0)
        val b = pair[1].location.clone().add(0.0, 1.0, 0.0)
        val step = b.toVector().subtract(a.toVector()).multiply(1.0 / 28.0)
        repeat(29) { i ->
            val point = a.clone().add(step.clone().multiply(i))
            a.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.AQUA, 1.35f))
        }
    }

    private fun breakStarLock(s: Session) {
        if (!s.starLocked) return
        s.starLockIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.starLockIds.clear()
        s.starLocked = false
        broadcast(s, "§a星锁已经断裂，牛郎与织女可以继续前进！")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEscortDamage(event: EntityDamageEvent) {
        val s = session ?: return
        if (s.ending) return
        val escort = escortByEntity(s, event.entity.uniqueId) ?: return
        event.isCancelled = true
        if (s.phase != Phase.ESCORT || event !is EntityDamageByEntityEvent) return
        val attacker = actualAttacker(event.damager) ?: return
        if (attacker is Player) return
        escort.health--
        if (escort.health <= 0) {
            resetEscortState(s, true)
            return
        }
        retaliate(s, escort, attacker)
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
        if (ThreadLocalRandom.current().nextBoolean()) {
            plugin.resourceManager.getItem(FEATHER_ID)?.let { stack ->
                val dropped = attacker.world.dropItemNaturally(attacker.location, stack)
                tagEntity(s, dropped)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEscortHeal(event: EntityRegainHealthEvent) {
        val s = session ?: return
        val escort = escortByEntity(s, event.entity.uniqueId) ?: return
        event.isCancelled = true
        recordHealingAction(s, escort)
        escortEntity(escort)?.health = escort.health.coerceAtLeast(1).toDouble()
        updateEscortBars(s)
    }

    private fun resetEscortState(s: Session, announce: Boolean) {
        if (announce) broadcast(s, "§c牛郎或织女气血耗尽，双方已回到起点，护送重新开始！")
        removeMobs(s, ELITE_MOB_IDS + "xingsuoshouwei")
        removeHostileProjectiles(s)
        s.starLockIds.clear()
        s.starLocked = false
        s.escortProgress = 0.0
        s.retaliationReadyAt.clear()
        listOfNotNull(s.niulang, s.zhinv).forEach { escort ->
            escort.health = 100
            escort.healingActions = 0
            escortEntity(escort)?.apply {
                isInvulnerable = false
                health = 100.0
                teleport(escort.start)
            }
        }
        if (announce) repeat(activePlayers(s).size * elitePerPlayer) { spawnEliteGuard(s) }
        updateEscortBars(s)
    }

    private fun updateEscortBars(s: Session) {
        s.meetingBar?.apply {
            progress = s.escortProgress.coerceIn(0.0, 1.0)
            setTitle("§d§l牛郎与织女相聚进度 §f${(s.escortProgress * 100).toInt()}%")
        }
        s.niulang?.let { escort ->
            s.niulangBar?.apply {
                progress = escort.health / 100.0
                setTitle("§b§l牛郎气血 §f${escort.health}/100")
            }
        }
        s.zhinv?.let { escort ->
            s.zhinvBar?.apply {
                progress = escort.health / 100.0
                setTitle("§d§l织女气血 §f${escort.health}/100")
            }
        }
    }

    private fun finishEscort(s: Session) {
        if (!isCurrent(s, Phase.ESCORT)) return
        s.phase = Phase.TRANSITION
        s.meetingBar?.removeAll(); s.meetingBar = null
        s.niulangBar?.removeAll(); s.niulangBar = null
        s.zhinvBar?.removeAll(); s.zhinvBar = null
        removeMobs(s, AWAKENING_MOB_IDS + ELITE_MOB_IDS + "xingsuoshouwei")
        removeHostileProjectiles(s)
        meetingEffect(s)
        val dialogue = listOf(
            "§b牛郎:§f终于赶上了。",
            "§d织女:§f今年的星图有些异常,我正想与你……",
            "§c一道金光从天而降,强行截断了桥上的星路。",
            "§4§n王母娘娘:§f擅动天河,击退守卫,还敢在此相会？",
            "§b牛郎:§f娘娘,今年的银河出现了异动,我们只是想……",
            "§4§n王母娘娘:§f是否异动,自有天庭查明。",
            "§4§n王母娘娘:§f至于你们……",
            "§4§n王母娘娘:§f既然敢替他们开路,就随本宫一同来吧。"
        )
        scheduleDialogue(s, dialogue, 0L) {
            val world = Bukkit.getWorld(s.worldName) ?: return@scheduleDialogue
            activePlayers(s).forEach { player ->
                player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, false, true))
                player.teleport(Location(world, -550.42, 4.0, 2411.99, 4319.99f, 0.45f))
                player.sendMessage("§a§l【鹊桥星愿】§f测试阶段结束。")
            }
            cleanup(s)
        }
    }

    private fun awakeningEffect(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        val center = Location(world, -950.5, 55.0, 2404.5)
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
        val center = Location(world, -950.15, 55.5, 2404.5)
        world.spawnParticle(Particle.HEART, center, 45, 2.0, 1.8, 2.0, 0.08)
        world.spawnParticle(Particle.END_ROD, center, 100, 3.0, 2.5, 3.0, 0.12)
        world.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 1.2f)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) {
        val s = session ?: return
        if (event.entity.scoreboardTags.contains(ENTITY_TAG) && mobId(event.entity) != null) {
            event.drops.filter { resourceId(it) in DUNGEON_ITEM_IDS }.forEach { stack ->
                val meta = stack.itemMeta ?: return@forEach
                meta.persistentDataContainer.set(dungeonDropItemKey, PersistentDataType.BYTE, 1)
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
        val s = session ?: return
        if (!isSessionPlayer(s, event.player)) return
        removePlayerFromSession(s, event.player)
    }

    private fun removePlayerFromSession(s: Session, player: Player) {
        player.removeScoreboardTag(PLAYER_TAG)
        removeAttackBlessing(player)
        s.playerIds.remove(player.uniqueId)
        s.fallTicks.remove(player.uniqueId)
        listOf(s.awakeningBar, s.meetingBar, s.niulangBar, s.zhinvBar).forEach { it?.removePlayer(player) }
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
        val markedDrop = event.entity.itemStack.itemMeta?.persistentDataContainer
            ?.has(dungeonDropItemKey, PersistentDataType.BYTE) == true
        if (event.location.world?.name == s.worldName && markedDrop && resourceId(event.entity.itemStack) in DUNGEON_ITEM_IDS) {
            tagEntity(s, event.entity)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonItemPickup(event: EntityPickupItemEvent) {
        val s = session ?: return
        if (resourceId(event.item.itemStack) !in DUNGEON_ITEM_IDS) return
        val player = event.entity as? Player ?: return
        if (!isSessionPlayer(s, player)) event.isCancelled = true
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
        s.tasks.forEach(BukkitTask::cancel)
        s.tasks.clear()
        listOf(s.awakeningBar, s.meetingBar, s.niulangBar, s.zhinvBar).forEach { it?.removeAll() }
        s.entityIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        Bukkit.getWorld(s.worldName)?.entities?.filter { it.scoreboardTags.contains(ENTITY_TAG) }?.forEach(Entity::remove)
        Bukkit.getWorld(s.worldName)?.let { world ->
            s.chunkTickets.forEach { (x, z) -> world.getChunkAt(x, z).removePluginChunkTicket(plugin) }
        }
        s.chunkTickets.clear()
        spiritDirections.keys.removeAll(s.entityIds)

        // 回收副本道具：地面实体以及本实例玩家/误捡者背包中的晶核和鹊羽均不带出实例。
        Bukkit.getWorld(s.worldName)?.entities?.filterIsInstance<Item>()
            ?.filter { resourceId(it.itemStack) in DUNGEON_ITEM_IDS }
            ?.forEach(Entity::remove)
        Bukkit.getOnlinePlayers().filter { it.world.name == s.worldName }.forEach { player ->
            removeDungeonItems(player)
            player.removeScoreboardTag(PLAYER_TAG)
            removeAttackBlessing(player)
        }
        session = null
    }

    fun shutdown() {
        session?.let(::cleanup)
        Bukkit.getOnlinePlayers().forEach {
            it.removeScoreboardTag(PLAYER_TAG)
            removeAttackBlessing(it)
        }
    }

    private fun removeDungeonItems(player: Player) {
        player.inventory.contents.forEachIndexed { index, stack ->
            if (stack != null && resourceId(stack) in DUNGEON_ITEM_IDS) player.inventory.setItem(index, null)
        }
    }

    private fun scheduleDialogue(s: Session, lines: List<String>, initialDelay: Long, after: () -> Unit) {
        lines.forEachIndexed { index, line -> later(s, initialDelay + index * 60L) { broadcast(s, line) } }
        later(s, initialDelay + lines.size * 60L, after)
    }

    private fun createBar(s: Session, title: String, color: BarColor): BossBar {
        return Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10).also { bar ->
            activePlayers(s).forEach(bar::addPlayer)
            bar.progress = 1.0.coerceAtMost(if (title.contains("0%")) 0.0 else 1.0)
        }
    }

    private fun activePlayers(s: Session): List<Player> = Bukkit.getOnlinePlayers().filter { isSessionPlayer(s, it) }

    private fun isSessionPlayer(s: Session, player: Player): Boolean =
        player.uniqueId in s.playerIds && player.scoreboardTags.contains(PLAYER_TAG)

    private fun broadcast(s: Session, message: String) = activePlayers(s).forEach { it.sendMessage(message) }

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
        s.entityIds.add(entity.uniqueId)
    }

    private fun loadBridgeChunks(s: Session) {
        val world = Bukkit.getWorld(s.worldName) ?: return
        for (chunkX in Math.floorDiv(-1057, 16)..Math.floorDiv(-849, 16)) {
            for (chunkZ in Math.floorDiv(2388, 16)..Math.floorDiv(2420, 16)) {
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
            s.entityIds.remove(it.uniqueId)
            it.remove()
        }
        if (s.starLockIds.isEmpty()) s.starLocked = false
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
            ?: Location(world, fallbackX, 56.0, 2404.5)
    }

    /**
     * 拱桥不是线性斜坡。限定在桥面/亭子地板高度内，从上向下取首个可站立方块，
     * 从而跳过桥底的白色装饰、支柱和低层结构。
     */
    private fun findBridgeFeetLocation(world: org.bukkit.World, x: Double, z: Double): Location? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        for (floorY in 54 downTo 20) {
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

    private fun rescueFallenPlayers(s: Session) {
        if (session !== s || s.ending) return
        val world = Bukkit.getWorld(s.worldName) ?: return
        val leftRescue = Location(world, -1045.59, 31.0, 2404.43, 270.86f, -11.70f)
        val rightRescue = Location(world, -850.30, 27.0, 2404.74, 90.41f, -6.90f)
        activePlayers(s).forEach { player ->
            val falling = (!player.isOnGround && player.velocity.y < -0.08) || player.location.y < 25.5
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

    private fun secondsToTicks(seconds: Int): Long = seconds.coerceAtLeast(1) * 20L

    private companion object {
        const val PLAYER_TAG = "qixi_queqiao_player"
        const val ENTITY_TAG = "qixi_queqiao_entity"
        const val NIULANG_SPIRIT_TAG = "qixi_niulang_spirit"
        const val ZHINV_SPIRIT_TAG = "qixi_zhinv_spirit"
        const val CRYSTAL_ID = "queqiaojinghe"
        const val FEATHER_ID = "queyu"
        const val NIULANG_ATTACK_PREFIX = "qixi_niulang_spirit::"
        val DUNGEON_ITEM_IDS = setOf(CRYSTAL_ID, FEATHER_ID)
        val AWAKENING_MOB_IDS = setOf("qixi_zombie", "qixi_skeleton")
        val ELITE_MOB_IDS = setOf("yinhebuwei", "yinyugongwei")
    }
}
