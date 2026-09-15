package com.hjh_database.dungeon.zhenyao

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonPartyProvider
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.data.PlayerData
import com.hjh_database.spawner.MobFactory
import com.hjh_database.spawner.dungeon.impl.Zhenyao
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** 镇妖塔正塔：固定场地、单局占用，二至九层波次战斗，十层蚩尤决战。 */
class ZhenyaoTowerManager(internal val plugin: Hjh_database) : Listener, DungeonPartyProvider {
    companion object {
        const val PLAYER_TAG = "zhenyao_tower_player"
        private const val FLOOR_TEN_TEST_ITEM_ID = "zhenyao_floor_ten_test"
        private const val ENTITY_TAG = "zhenyao_tower_entity"
        private const val ACTIVE_STATUS = 5
        private const val ASCEND_TELEPORT_TICKS = 100L
        private const val LEVITATION_TICKS = 120
        private const val ARRIVAL_EFFECT_TICKS = 20L
        private val FLOOR_WORDS = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        private val FLOOR_CHIME_PITCHES = floatArrayOf(.8f, 1f, 1.2f, 1.4f, 1.6f)
    }

    // 后续难度选择插入 start() 之前，测试版不展示选择界面，也不按人数缩放。
    internal enum class Difficulty { TEST }
    internal enum class Phase { INTRO, OMEN, COMBAT, CHOICE, ASCEND, FINALE, ENDING }
    internal class Session(val world: World, val previous: MutableMap<UUID, Int>, val difficulty: Difficulty) {
        val id = UUID.randomUUID().toString()
        val players = previous.keys.toMutableSet()
        val entities = hashSetOf<UUID>()
        val displays = hashSetOf<UUID>()
        val anchors = hashMapOf<UUID, Location>()
        val arrivalEffects = hashMapOf<UUID, Long>()
        val mobHomes = hashMapOf<UUID, Location>()
        val chunks = hashSetOf<Pair<Int, Int>>()
        var floor = 2
        var tick = 0L
        var phaseStart = 0L
        var phase = Phase.INTRO
        var battle = TowerBattle(1)
        var bar: BossBar? = null
        var task: BukkitTask? = null
        var finale: ZhenyaoFinale? = null
        var testAdvancePending = false
    }

    internal val arena = TowerArena(plugin)
    internal val terrain = TowerTerrainJournal(plugin)
    internal val flight = TowerFlightState(plugin)
    private val sessionKey = NamespacedKey(plugin, "zhenyao_session")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val disconnecting = hashSetOf<UUID>()
    private val recoveryFile = File(plugin.dataFolder, "dungeon/zhenyao-participants.yml")
    // 入场先落盘；崩服或掉线后，数据加载完成时处决遗留玩家，不让其带状态重回场内。
    private val recovery = linkedMapOf<UUID, Int>()
    private var session: Session? = null
    private val entryWorld: String

    init {
        val configFile = File(plugin.dataFolder, "dungeon/zhenyao.yml")
        if (!configFile.exists()) plugin.saveResource("dungeon/zhenyao.yml", false)
        val config = YamlConfiguration.loadConfiguration(configFile)
        entryWorld = config.getString("entry-world", "world")!!
        if (config.getString("difficulty", "test") != "test") {
            plugin.logger.warning("镇妖塔目前仅实现 test 难度，暂按测试难度运行。")
        }
        if (recoveryFile.exists()) {
            val saved = YamlConfiguration.loadConfiguration(recoveryFile)
            saved.getConfigurationSection("players")?.getKeys(false)?.forEach { id ->
                recovery[UUID.fromString(id)] = saved.getInt("players.$id")
            }
        }
        Bukkit.getWorlds().forEach { world -> world.entities.filter(::owned).forEach(Entity::remove) }
        if (!terrain.restore()) plugin.logger.warning("镇妖塔楼板等待恢复，恢复完成前入口不会开放。")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntrance(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        if (isFloorTenTestItem(event)) return
        val block = event.clickedBlock ?: return
        if (block.world.name != entryWorld || block.type != Material.STONE_BUTTON ||
            block.x != -1043 || block.y != 7 || block.z != 3015) return
        event.isCancelled = true
        val candidates = block.world.players.filter {
            !it.isDead && TowerFloors.inEntry(it.location.x, it.location.y, it.location.z)
        }
        fun announce(message: String) = (candidates + event.player).distinctBy { it.uniqueId }.forEach { it.sendMessage(message) }
        if (session != null) {
            announce("§c塔内传来了打斗的声音，看来是战斗没停止，过会再来吧……")
            return
        }
        if (candidates.isEmpty()) { announce("§c传送阵上没有可进入镇妖塔的玩家！"); return }
        if (candidates.size > 5) { announce("§c镇妖塔至多允许§e5§c名玩家进入，当前人数超出限制！"); return }
        if (!terrain.restore()) { announce("§c镇妖塔正在恢复楼板，暂时无法进入。"); return }
        val previous = linkedMapOf<UUID, Int>()
        for (player in candidates) {
            plugin.playerManager.updateStats(player)
            val data = plugin.playerManager.getData(player.uniqueId)
            val error = when {
                data == null -> "§c玩家§e${player.name}§c的数据尚未加载，无法进入秘境！"
                player.uniqueId in recovery || player.scoreboardTags.contains(PLAYER_TAG) -> "§c玩家§e${player.name}§c尚有未处理的镇妖塔状态，请重新登录后再试！"
                data.lv < 40 -> "§c玩家§e${player.name}§c等级不满40级，无法进入秘境！"
                data.totalRarity < 42 -> "§c玩家§e${player.name}§c装备稀有度总和不满42点，无法进入秘境！"
                else -> null
            }
            if (error != null) { announce(error); return }
            previous[player.uniqueId] = data!!.status
        }
        start(block.world, candidates, previous)
    }

    private fun isFloorTenTestItem(event: PlayerInteractEvent) =
        event.item?.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING) == FLOOR_TEN_TEST_ITEM_ID

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onFloorTenTestItemUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK) || !isFloorTenTestItem(event)) return
        event.isCancelled = true
        val player = event.player
        if (!player.isOp && !player.hasPermission("hjh.admin")) {
            player.sendMessage("§c该测试道具仅限管理员使用。")
            return
        }
        val s = session
        if (s == null || s.phase == Phase.ENDING || player.isDead || !participant(s, player)) {
            player.sendMessage("§c请先正常进入镇妖塔，再使用该测试道具。")
            return
        }
        if (s.floor >= 10 || s.finale != null) {
            player.sendMessage("§e当前已经进入镇妖塔第十层，无需再次快进。")
            return
        }
        if (s.testAdvancePending) return
        s.testAdvancePending = true
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                if (session !== s || s.phase == Phase.ENDING || s.floor >= 10 || s.finale != null ||
                    !player.isOnline || player.isDead || !participant(s, player) ||
                    (!player.isOp && !player.hasPermission("hjh.admin"))) return@Runnable
                advanceToFinaleForTest(s, player)
            } catch (error: Exception) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "镇妖塔测试快进失败，正在终止并清理", error)
                failAll(s, "§c镇妖塔测试快进异常，本次挑战结束。")
            } finally { s.testAdvancePending = false }
        })
    }

    private fun advanceToFinaleForTest(s: Session, admin: Player) {
        val members = active(s)
        val offsets = listOf(0 to 0, 0 to 2, 0 to -2, -2 to 0, 2 to 0)
        val destinations = members.mapIndexed { index, player ->
            val (dx, dz) = offsets[index % offsets.size]
            player to Location(s.world, -992.5 + dx, 125.0, 3006.5 + dz, player.location.yaw, player.location.pitch)
        }
        // 先检查全队落点，场地异常时保留原楼层，让管理员修正后重试。
        if (destinations.any { (player, target) -> !arena.safe(target, player.width, player.height) }) {
            admin.sendMessage("§c第十层测试落点被遮挡或缺少楼板，未执行快进。")
            return
        }
        s.bar?.removeAll(); s.bar = null
        s.entities.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.world.entities.filter { ownedBy(s, it) }.forEach(Entity::remove)
        s.entities.clear(); s.displays.clear(); s.mobHomes.clear()
        members.forEach { player ->
            if (player.uniqueId in s.anchors || player.uniqueId in s.arrivalEffects) {
                player.removePotionEffect(PotionEffectType.LEVITATION)
            }
            player.resetTitle()
            listOf(Sound.BLOCK_BELL_RESONATE, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                Sound.BLOCK_BEACON_ACTIVATE, Sound.BLOCK_BEACON_AMBIENT).forEach {
                player.stopSound(it, SoundCategory.PLAYERS)
            }
            player.velocity = Vector(); player.fallDistance = 0f
        }
        s.anchors.clear(); s.arrivalEffects.clear()
        s.battle = TowerBattle(1)
        destinations.forEach { (player, target) ->
            if (!teleport(player, target)) failPlayer(s, player, "§c第十层测试传送失败，本次挑战结束。")
        }
        if (s.phase == Phase.ENDING || active(s).isEmpty()) return
        active(s).forEach { it.sendMessage("§e管理员已将本局镇妖塔快进至第十层，开始蚩尤测试。") }
        plugin.logger.info("管理员 ${admin.name} 将镇妖塔本局从第${s.floor}层快进至第十层")
        beginFinale(s)
    }

    private fun beginFinale(s: Session) {
        s.floor = 10
        active(s).forEach { it.sendMessage("§c你登上了镇妖塔-地上第十层") }
        phase(s, Phase.FINALE)
        s.finale = ZhenyaoFinale(this, s)
    }

    private fun start(world: World, players: List<Player>, previous: MutableMap<UUID, Int>) {
        val s = Session(world, previous, Difficulty.TEST)
        session = s // 先占用，再做传送；失败则整队回滚。
        val originals = players.associate { it.uniqueId to it.location.clone() }
        try {
            for (x in (-1034 shr 4)..(-970 shr 4)) for (z in (2974 shr 4)..(3038 shr 4)) {
                if (world.getChunkAt(x, z).addPluginChunkTicket(plugin)) s.chunks.add(x to z)
            }
            val entrance = Location(world, -1001.57, 5.0, 3006.39, 183.26f, -1.05f)
            check(arena.safe(entrance, .6, 1.8)) { "二层入场点被遮挡或缺少地板" }
            recovery.putAll(previous)
            persistRecovery()
            players.forEach { player ->
                player.addScoreboardTag(PLAYER_TAG)
                updateStatus(player, ACTIVE_STATUS)
                check(teleport(player, entrance)) { "玩家 ${player.name} 的入场传送被取消" }
                player.fallDistance = 0f
            }
            active(s).forEach { it.sendMessage("§c你进入了镇妖塔-地上层") }
            beginFloor(s)
            s.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (session !== s) return@Runnable
                try { tick(s) } catch (error: Exception) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "镇妖塔运行异常，正在终止并清理", error)
                    failAll(s, "§c镇妖塔运行异常，本次挑战结束。")
                }
            }, 1L, 1L)
        } catch (error: Exception) {
            plugin.logger.log(java.util.logging.Level.WARNING, "镇妖塔入场失败", error)
            players.forEach { player ->
                player.removeScoreboardTag(PLAYER_TAG)
                updateStatus(player, previous.getValue(player.uniqueId))
                teleport(player, originals.getValue(player.uniqueId))
                recovery.remove(player.uniqueId)
                player.sendMessage("§c镇妖塔准备失败，本次未开始，请联系管理员检查场地。")
            }
            persistRecovery()
            cleanup(s)
        }
    }

    private fun tick(s: Session) {
        s.tick++
        if (s.phase == Phase.ENDING) { if (s.tick - s.phaseStart >= 2) cleanup(s); return }
        // “中途离开”仅指退服，由 onQuit 处理；移动、法阵及传送不触发失败。
        if (active(s).isEmpty()) { end(s); return }
        updateArrivalEffects(s)
        val elapsed = s.tick - s.phaseStart
        when (s.phase) {
            Phase.INTRO -> {
                playFloorFanfare(s, elapsed)
                when (elapsed) {
                    40L -> active(s).forEach {
                        it.sendTitle("§6镇妖塔", "§e第${FLOOR_WORDS[s.floor]}层", 0, 50, 0)
                    }
                    90L -> active(s).forEach {
                        it.sendTitle("§e${TowerFloors.get(s.floor).name}", "§f请击杀所有怪物以前往下一层", 0, 40, 0)
                    }
                    130L -> phase(s, Phase.OMEN)
                }
            }
            Phase.OMEN -> {
                if (elapsed % 5 == 0L) arena.markers(s.world, feetY(s)).forEach {
                    particles(s, Particle.WITCH, it.clone().add(0.0, .8, 0.0), 16, .5, .7, .5)
                    particles(s, Particle.SMOKE, it, 12, .5, .4, .5)
                }
                if (elapsed >= 20) {
                    phase(s, Phase.COMBAT)
                    spawnWave(s)
                }
            }
            Phase.COMBAT -> {
                when (s.battle.action(s.tick)) {
                    TowerBattleAction.COMPLETE -> openCircles(s)
                    TowerBattleAction.TIMEOUT -> failAll(s, "§c镇妖塔本层挑战超时，镇塔禁制已降临！")
                    TowerBattleAction.SPAWN -> spawnWave(s)
                    TowerBattleAction.WAIT -> Unit
                }
                if (s.phase == Phase.COMBAT && s.tick % 10 == 0L) maintainMobs(s)
                if (s.phase == Phase.COMBAT && s.tick % 20 == 0L) updateBattleBar(s)
            }
            Phase.CHOICE -> {
                if (elapsed % 5 == 0L) drawCircles(s)
                s.bar?.progress = ((200 - elapsed).coerceAtLeast(0) / 200.0)
                s.bar?.setTitle("请前往对应法阵，继续/离开镇妖塔地上层｜${((200 - elapsed).coerceAtLeast(0) + 19) / 20}秒")
                if (elapsed >= 200) choose(s)
            }
            Phase.ASCEND -> {
                if (elapsed % 5 == 0L) active(s).forEach { liftParticles(s, it) }
                if (elapsed % 20 == 0L && elapsed < ASCEND_TELEPORT_TICKS) active(s).forEach {
                    it.playSound(it.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS,
                        .9f, .8f + elapsed.toFloat() / ASCEND_TELEPORT_TICKS * .7f)
                }
                if (elapsed >= ASCEND_TELEPORT_TICKS) arrive(s)
            }
            Phase.FINALE -> s.finale?.tick()
            Phase.ENDING -> Unit
        }
    }

    private fun beginFloor(s: Session) {
        active(s).forEach { it.sendMessage("§c你登上了镇妖塔-地上第${FLOOR_WORDS[s.floor]}层") }
        s.battle = TowerBattle(TowerFloors.get(s.floor).waves)
        s.mobHomes.clear()
        phase(s, Phase.INTRO)
    }

    internal fun playFloorFanfare(s: Session, elapsed: Long) {
        // 从首轮标题开始，五段钟音覆盖约五秒，配合低音共鸣和信标长音。
        if (elapsed !in 40L..120L || (elapsed - 40) % 20 != 0L) return
        val pitch = FLOOR_CHIME_PITCHES[((elapsed - 40) / 20).toInt()]
        active(s).forEach { player ->
            player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.8f, pitch)
            if (elapsed == 40L) {
                player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.8f, .7f)
            }
            if (elapsed == 40L || elapsed == 80L || elapsed == 120L) {
                player.playSound(player.location, Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 2f, pitch * .8f)
            }
        }
    }

    private fun liftParticles(s: Session, player: Player, strength: Double = 1.0) {
        fun count(base: Int) = (base * strength).toInt().coerceAtLeast(1)
        // 与圣山相同的通用接口：服务器只下发当前位置和形状，环绕采样交给 hjh_mod。
        // 每5 tick更新一次中心，让光环跟随自由移动中的玩家；不注册固定坐标的长时效果。
        plugin.clientBridge.emitParticles(active(s), player.location, listOf(
            ClientParticleLayer("minecraft:cloud", ClientParticleShape.CLOUD, count(10),
                radius = .9, height = 1.1, speed = .025, offsetY = 1.0),
            ClientParticleLayer("minecraft:dust", ClientParticleShape.SPHERE, count(16),
                color = 0x91E8EE, size = 1.15f, radius = 1.35, height = 1.2, speed = .015, offsetY = 1.0),
            ClientParticleLayer("minecraft:end_rod", ClientParticleShape.RING, count(12),
                radius = 1.15, height = .1, speed = .02, offsetY = .35),
            ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, count(16),
                color = 0xFFE4A0, size = 1.25f, radius = 1.3, height = .12, speed = .015, offsetY = 1.65)
        ), ignoreDistance = true)
    }

    private fun updateArrivalEffects(s: Session) {
        for ((id, until) in s.arrivalEffects.toMap()) {
            val player = Bukkit.getPlayer(id)
            if (player == null || !participant(s, player) || player.isDead) {
                s.arrivalEffects.remove(id)
                continue
            }
            val remaining = until - s.tick
            if (remaining <= 0) {
                player.removePotionEffect(PotionEffectType.LEVITATION)
                player.fallDistance = 0f
                s.arrivalEffects.remove(id)
            } else if (remaining % 5 == 0L) {
                liftParticles(s, player, remaining.toDouble() / ARRIVAL_EFFECT_TICKS)
            }
        }
    }

    private fun spawnWave(s: Session) {
        val floor = TowerFloors.get(s.floor)
        val chosen = mutableListOf<Pair<String, Location>>()
        val random = arena.candidates(s.world, s.floor).toMutableList()
        for (index in 0 until 10) {
            val type = floor.types[index % floor.types.size]
            val (width, height) = arena.size(type)
            val position = if (index < 4) {
                arena.markers(s.world, floor.feetY)[index].takeIf { arena.safe(it, width, height) }
            } else {
                random.firstOrNull { candidate ->
                    chosen.none { it.second.distanceSquared(candidate) < 9 } &&
                        active(s).none { it.world == s.world && it.location.distanceSquared(candidate) < 9 } &&
                        arena.safe(candidate, width, height, avoidEntities = true)
                }?.also { random.remove(it) }
            }
            if (position == null) {
                failAll(s, "§c当前楼层没有足够的安全刷怪位置，本次挑战终止，请检查建筑。")
                return
            }
            chosen += floor.mobId(type, index < 4) to position
        }
        val mobs = mutableListOf<LivingEntity>()
        for ((id, position) in chosen) {
            val mob = MobFactory.spawnMob(plugin, position, id)
            if (mob == null) { failAll(s, "§c镇妖塔怪物生成失败，本次挑战终止。"); return }
            own(s, mob)
            val box = mob.boundingBox
            if (!mob.isValid || !arena.safe(position, box.widthX, box.height)) {
                mob.remove()
                failAll(s, "§c镇妖塔怪物的实际体型与场地冲突，请检查建筑。")
                return
            }
            mob.isPersistent = true
            s.mobHomes[mob.uniqueId] = position.clone()
            (mob as? Mob)?.target = active(s).filter { it.world == s.world }.minByOrNull { it.location.distanceSquared(position) }
            mobs += mob
            particles(s, Particle.WITCH, position.clone().add(0.0, .8, 0.0), 18, .5, .7, .5)
        }
        s.battle.addWave(mobs.map { it.uniqueId }, s.tick)
        updateBattleBar(s)
    }

    private fun maintainMobs(s: Session) {
        for (id in s.battle.alive.keys.toList()) {
            val mob = Bukkit.getEntity(id) as? LivingEntity
            if (mob == null || !mob.isValid || mob.isDead) {
                // 未收到死亡事件的消失不能算击杀，否则 /remove 或卸载会免费跳层。
                failAll(s, "§c镇妖塔怪物异常丢失，本次挑战终止，请检查服务器日志。")
                plugin.logger.warning("镇妖塔第${s.floor}层存活怪物丢失: $id")
                return
            }
            val home = s.mobHomes.getValue(id)
            if (!arena.onFloor(mob.location, s.world, feetY(s)) ||
                mob.location.y > feetY(s) + 4 || !arena.clear(s.world, mob.boundingBox)) {
                if (!arena.safe(home, mob.width, mob.height) || !mob.teleport(home)) {
                    failAll(s, "§c镇妖塔怪物无法返回本层，本次挑战终止。")
                    return
                }
                mob.velocity = Vector()
            }
            if (mob is Mob) {
                if (mob.target !is Player || !participant(s, mob.target as Player) || mob.target?.world != s.world) {
                    mob.target = active(s).filter { it.world == s.world }.minByOrNull { it.location.distanceSquared(mob.location) }
                }
            }
        }
    }

    private fun updateBattleBar(s: Session) {
        val floor = TowerFloors.get(s.floor)
        val seconds = (s.battle.remainingTicks(s.tick) + 19) / 20
        val bar = s.bar ?: Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID).also { s.bar = it }
        bar.setTitle("镇妖塔第${FLOOR_WORDS[s.floor]}层-进度｜波次 ${s.battle.wavesSpawned}/${floor.waves}｜剩余 %02d:%02d".format(seconds / 60, seconds % 60))
        bar.progress = s.battle.kills.toDouble() / floor.totalMobs
        active(s).forEach(bar::addPlayer)
    }

    private fun openCircles(s: Session) {
        phase(s, Phase.CHOICE)
        active(s).forEach {
            it.sendMessage("§e你通过了第${FLOOR_WORDS[s.floor]}层，可选择继续征战下一层或携带本层奖励离开镇妖塔")
        }
        // 清场后移除遗留弹体，法阵选择期间不再被上一波攻击。
        s.entities.mapNotNull(Bukkit::getEntity).filterIsInstance<Projectile>().forEach(Entity::remove)
        s.bar?.removeAll()
        s.bar = Bukkit.createBossBar("请前往对应法阵，继续/离开镇妖塔地上层｜10秒", BarColor.YELLOW, BarStyle.SOLID)
        active(s).forEach { s.bar!!.addPlayer(it) }
        listOf(-992.5 to "§b站在此处，等待法阵传送至第${FLOOR_WORDS[s.floor + 1]}层",
            -1010.5 to "§6站在此处，等待法阵离开镇妖塔").forEach { (x, text) ->
            val display = s.world.spawn(Location(s.world, x, feetY(s) + 2.4, 3006.5), TextDisplay::class.java)
            own(s, display)
            s.displays.add(display.uniqueId)
            display.text = text
            display.billboard = Display.Billboard.CENTER
            display.isSeeThrough = false
            display.isShadowed = true
            display.lineWidth = 260
            display.brightness = Display.Brightness(15, 15)
        }
        drawCircles(s)
    }

    private fun drawCircles(s: Session) {
        for ((x, color) in listOf(-992.5 to Color.AQUA, -1010.5 to Color.ORANGE)) {
            for (i in 0 until 60) {
                val angle = i * Math.PI * 2 / 60
                val point = Location(s.world, x + cos(angle) * 5, feetY(s) + .12, 3006.5 + sin(angle) * 5)
                active(s).forEach { it.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.3f)) }
            }
        }
    }

    private fun choose(s: Session) {
        s.bar?.removeAll(); s.bar = null
        s.displays.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.displays.clear()
        val players = active(s)
        val continuing = players.filter {
            it.world == s.world && TowerFloors.inAdvanceCircle(it.location.x, it.location.y, it.location.z, feetY(s))
        }
        // B 阵和未选阵玩家均离场；只在零秒这一刻快照，后续移动不更改选择。
        (players - continuing.toSet()).forEach { leave(s, it) }
        if (session !== s || s.phase == Phase.ENDING) return
        if (continuing.isEmpty()) { end(s); return }
        phase(s, Phase.ASCEND)
        continuing.filter { participant(s, it) }.forEach {
            s.anchors[it.uniqueId] = it.location.clone()
            it.sendMessage("§7镇妖塔的法阵正在缓缓将你送往第${FLOOR_WORDS[s.floor + 1]}层。")
            it.playSound(it.location, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.3f, .8f)
            it.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, LEVITATION_TICKS, 2, false, false, true))
            it.fallDistance = 0f
            liftParticles(s, it)
        }
    }

    private fun arrive(s: Session) {
        for (player in active(s)) {
            // 正常按传送瞬间的X/Z升层；若移动到了上层无地板的位置，使用本次法阵起点兜底。
            val currentTarget = player.location.clone().apply { world = s.world; y = feetY(s) + 15 }
            val target = currentTarget.takeIf { arena.safe(it, player.width, player.height) }
                ?: s.anchors.getValue(player.uniqueId).clone().apply { y = feetY(s) + 15 }
            player.velocity = Vector()
            player.fallDistance = 0f
            if (!arena.safe(target, player.width, player.height) || !teleport(player, target)) {
                failPlayer(s, player, "§c上层传送位置不可用，挑战失败！")
            } else {
                s.arrivalEffects[player.uniqueId] = s.tick + ARRIVAL_EFFECT_TICKS
                player.stopSound(Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS)
                player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, .7f, 1.4f)
                liftParticles(s, player)
            }
        }
        s.anchors.clear()
        if (s.phase == Phase.ENDING || active(s).isEmpty()) return
        s.floor++
        if (s.floor == 10) {
            beginFinale(s)
        } else beginFloor(s)
    }

    private fun leave(s: Session, player: Player) {
        val target = Location(s.world, -178.70, 64.0, -179.91, 269.21f, -7.65f)
        if (!teleport(player, target)) { failPlayer(s, player, "§c离场传送失败，本次挑战结束。"); return }
        player.sendMessage("§e测试阶段，你已领取第${s.floor}层的奖励。")
        detach(s, player, s.previous.getValue(player.uniqueId))
    }

    private fun failPlayer(s: Session, player: Player, message: String) {
        if (!participant(s, player)) return
        player.sendMessage(message)
        // status 保持副本/死亡语义，复活交给 PlayerListener 现有的奈何桥流程。
        updateStatus(player, ACTIVE_STATUS)
        if (!player.isDead && player.health > 0) player.health = 0.0
        if (player.uniqueId in s.players) detach(s, player, null, player.uniqueId in disconnecting)
        if (s.players.isEmpty()) end(s)
    }

    internal fun failAll(s: Session, message: String) {
        if (s.phase == Phase.ENDING) return
        phase(s, Phase.ENDING)
        active(s).forEach { failPlayer(s, it, message) }
    }

    private fun detach(s: Session, player: Player, status: Int?, keepRecovery: Boolean = false) {
        s.finale?.removePlayer(player)
        flight.restore(player)
        s.players.remove(player.uniqueId)
        s.bar?.removePlayer(player)
        val ascending = s.anchors.remove(player.uniqueId) != null
        val arriving = s.arrivalEffects.remove(player.uniqueId) != null
        if (ascending || arriving) {
            player.removePotionEffect(PotionEffectType.LEVITATION)
            player.fallDistance = 0f
        }
        player.removeScoreboardTag(PLAYER_TAG)
        player.resetTitle()
        listOf(Sound.BLOCK_BELL_RESONATE, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
            Sound.BLOCK_BEACON_ACTIVATE, Sound.BLOCK_BEACON_AMBIENT, Sound.ENTITY_ENDERMAN_TELEPORT).forEach {
            player.stopSound(it, SoundCategory.PLAYERS)
        }
        if (status != null) updateStatus(player, status)
        if (!keepRecovery) { recovery.remove(player.uniqueId); persistRecovery() }
    }

    private fun end(s: Session) { if (s.phase != Phase.ENDING) phase(s, Phase.ENDING) }

    private fun cleanup(s: Session) {
        val terrainReady: Boolean
        try {
            s.finale?.close()
            s.bar?.removeAll()
            s.entities.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
            s.world.entities.filter { owned(it) && it.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == s.id }.forEach(Entity::remove)
        } finally {
            // 即使某个客户端效果、玩家状态或实体清理抛错，也必须尝试恢复建筑。
            terrainReady = terrain.restore()
        }
        if (!terrainReady) {
            plugin.logger.warning("镇妖塔楼板尚未全部恢复，保留日志与入口占用，稍后重试。")
            s.phaseStart = s.tick + 18
            return
        }
        s.task?.cancel()
        s.chunks.forEach { (x, z) -> s.world.getChunkAt(x, z).removePluginChunkTicket(plugin) }
        s.entities.clear(); s.displays.clear(); s.mobHomes.clear(); s.anchors.clear(); s.arrivalEffects.clear()
        if (session === s) session = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val s = session ?: return
        if (!participant(s, event.entity)) return
        detach(s, event.entity, null, event.entity.uniqueId in disconnecting)
        if (s.players.isEmpty()) end(s)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val s = session ?: return
        if (!participant(s, event.player)) return
        disconnecting.add(event.player.uniqueId)
        failPlayer(s, event.player, "§c中途离开服务器，镇妖塔挑战失败！")
        disconnecting.remove(event.player.uniqueId)
    }

    /** 由正常登录和热加载的数据加载回调调用，避免先写状态再被数据库旧值覆盖。 */
    fun onPlayerDataLoaded(player: Player, data: PlayerData) {
        if (session?.let { participant(it, player) } == true) return
        flight.restore(player)
        if (player.uniqueId !in recovery && !player.scoreboardTags.contains(PLAYER_TAG)) return
        player.removeScoreboardTag(PLAYER_TAG)
        player.removePotionEffect(PotionEffectType.LEVITATION)
        data.updateStatus(ACTIVE_STATUS)
        player.sendMessage("§c上次镇妖塔挑战因中途离开服务器或服务器重启而失败。")
        if (!player.isDead && player.health > 0) player.health = 0.0
        plugin.databaseManager.savePlayerAsync(data)
        recovery.remove(player.uniqueId)
        persistRecovery()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobDeath(event: EntityDeathEvent) {
        if (!event.entity.scoreboardTags.contains(Zhenyao.MOB_TAG)) return
        event.drops.clear(); event.droppedExp = 0
        val s = session ?: return
        if (s.finale?.onDeath(event) == true) return
        if (ownedBy(s, event.entity)) s.battle.killed(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFinalDamage(event: EntityDamageEvent) { session?.finale?.onDamage(event) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSplit(event: SlimeSplitEvent) {
        // 与凶神太岁的 preventXiongshenTaisuiSplit 一样，在分裂快照监听之前取消。
        if (event.entity.scoreboardTags.contains(Zhenyao.MOB_TAG)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        if (event.entity.scoreboardTags.contains(Zhenyao.MOB_TAG)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        val s = session ?: return
        if (!ownedBy(s, event.entity)) return
        val target = event.target
        if (target != null && (target !is Player || !participant(s, target))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val s = session ?: return
        if (s.finale?.blocksAttack(event) == true) { event.isCancelled = true; return }
        if (s.finale?.allowsEyeHit(event) == true) return
        val attacker = if (event.damager is Projectile) (event.damager as Projectile).shooter as? Entity else event.damager
        val victim = event.entity
        val attackerMember = attacker is Player && participant(s, attacker)
        val victimMember = victim is Player && participant(s, victim)
        if (ownedBy(s, victim) && !attackerMember ||
            attacker?.scoreboardTags?.contains(Zhenyao.MOB_TAG) == true && ownedBy(s, attacker) && !victimMember ||
            attackerMember && victim is Player && !victimMember ||
            victimMember && attacker is Player && !attackerMember) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFire(event: EntityCombustEvent) {
        if (event.entity.scoreboardTags.contains(Zhenyao.MOB_TAG) && event !is EntityCombustByEntityEvent && event !is EntityCombustByBlockEvent) event.isCancelled = true
        val s = session ?: return
        val ignition = (event as? EntityCombustByEntityEvent)?.combuster ?: return
        val source = if (ignition is Projectile) ignition.shooter as? Entity else ignition
        if (source?.scoreboardTags?.contains(Zhenyao.MOB_TAG) == true && ownedBy(s, source)) {
            val victim = event.entity
            if (victim !is Player || !participant(s, victim)) event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectile(event: ProjectileLaunchEvent) {
        val s = session ?: return
        val shooter = event.entity.shooter as? Entity ?: return
        if (ownedBy(s, shooter) || shooter is Player && participant(s, shooter)) own(s, event.entity)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItem(event: ItemSpawnEvent) {
        val s = session ?: return
        if (arena.inside(event.location, s.world)) own(s, event.entity)
    }

    @EventHandler
    fun onEntitiesLoaded(event: EntitiesLoadEvent) {
        val s = session
        event.entities.filter { owned(it) && (s == null || !ownedBy(s, it)) }.forEach(Entity::remove)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) { if (protected(event.block.location)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) { if (protected(event.block.location)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) { if (protected(event.block.location)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) { if (protected(event.block.location)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) { event.blockList().removeIf { protected(it.location) } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) { event.blockList().removeIf { protected(it.location) } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (protected(event.blockClicked.getRelative(event.blockFace).location)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) { if (protected(event.blockClicked.location)) event.isCancelled = true }

    fun shutdown() {
        val s = session
        if (s == null) { terrain.restore(); return }
        try {
            disconnecting.addAll(s.players)
            failAll(s, "§c服务器关闭，镇妖塔挑战结束。")
        } finally {
            try { cleanup(s) } finally { disconnecting.clear() }
        }
    }

    private fun protected(location: Location) = session?.let { arena.inside(location, it.world) } == true
    private fun phase(s: Session, phase: Phase) { s.phase = phase; s.phaseStart = s.tick }
    private fun feetY(s: Session) = 5.0 + (s.floor - 2) * 15
    override val dungeonId: String get() = "zhenyao"

    override fun activePartyMembers(): List<Set<UUID>> = session?.let { current ->
        listOf(active(current).mapTo(linkedSetOf()) { it.uniqueId })
    }.orEmpty()

    private fun participant(s: Session, player: Player) = player.scoreboardTags.contains(PLAYER_TAG) && player.uniqueId in s.players
    internal fun active(s: Session) = s.players.mapNotNull(Bukkit::getPlayer).filter { it.isOnline && !it.isDead && participant(s, it) }
    private fun owned(entity: Entity) = entity.scoreboardTags.contains(ENTITY_TAG)
    private fun ownedBy(s: Session, entity: Entity) = owned(entity) && entity.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == s.id
    internal fun own(s: Session, entity: Entity) {
        entity.addScoreboardTag(ENTITY_TAG)
        entity.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, s.id)
        s.entities.add(entity.uniqueId)
    }
    private fun teleport(player: Player, target: Location): Boolean = player.teleport(target)
    internal fun finishFinale(s: Session) {
        active(s).forEach { player ->
            if (teleport(player, Location(s.world, -178.70, 64.0, -179.91, 269.21f, -7.65f))) {
                player.sendMessage("§e测试阶段，你完成了镇妖塔。")
                detach(s, player, 3)
            } else failPlayer(s, player, "§c离场传送失败，本次挑战结束。")
        }
        end(s)
    }
    private fun updateStatus(player: Player, status: Int) {
        plugin.playerManager.getData(player.uniqueId)?.let {
            it.updateStatus(status)
            plugin.databaseManager.savePlayerAsync(it)
        }
    }
    private fun particles(s: Session, type: Particle, location: Location, count: Int, x: Double, y: Double, z: Double) {
        active(s).forEach { it.spawnParticle(type, location, count, x, y, z, .02) }
    }
    private fun persistRecovery() {
        recoveryFile.parentFile.mkdirs()
        val config = YamlConfiguration()
        recovery.forEach { (id, status) -> config.set("players.$id", status) }
        val temp = recoveryFile.toPath().resolveSibling("${recoveryFile.name}.tmp")
        Files.writeString(temp, config.saveToString(), Charsets.UTF_8)
        try { Files.move(temp, recoveryFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, recoveryFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
