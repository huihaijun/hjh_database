package com.hjh_database.dungeon.shengshan

import com.hjh_database.Hjh_database
import com.hjh_database.combat.MonsterDamageClassification
import com.hjh_database.dungeon.DungeonRecord
import com.hjh_database.listener.CombatDamageCalculationEvent
import com.hjh_database.listener.CombatListener
import com.hjh_database.spawner.MobFactory
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.FaceAttachable
import org.bukkit.block.data.type.Switch
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Guardian
import org.bukkit.entity.LargeFireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import org.bukkit.util.EulerAngle
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.math.max

class ShengShanDungeonManager(internal val plugin: Hjh_database) : Listener {
    companion object {
        const val PLAYER_TAG = "shengshan_dungeon_player"
        const val ENTITY_TAG = "shengshan_dungeon_entity"
        const val DUNGEON_RECORD_ID = "shengshan"
        private const val WORLD_NAME = "world"
        private const val GUESS_COMMAND = "/shengshanguess"
        private const val CURRENT_TRIGRAM_TEST_ITEM_ID = "shengshan_current_trigram_test"
        private const val LOOP_TEST_ITEM_ID = "shengshan_loop_test"
        private const val LOOP_TEST_VICTORY_ARGUMENT = "victory"
        private const val EXIT_BELL_X = 3238
        private const val EXIT_BELL_Y = 128
        private const val EXIT_BELL_Z = -1874
        private const val SILVER_NOTE_ID = "yinpiao"
        private const val SPIRIT_JADE_SLIP_ID = "lingyujian"
        private const val SACRED_BEAST_BADGE_ID = "shengshouhuiji"
        private const val ELEMENT_EXCHANGE_TICKET_ID = "yuansuduihuanquan"
        private const val EXIT_EXPERIENCE = 2000
        private const val FIRST_CLEAR_TITLE_ID = "zhenxiang"
        private const val FIRST_CLEAR_MILESTONE_ID = "shengshan_first_clear"
        private const val GUESS_MASTER_TITLE_ID = "shenjimiaosuan"
        private const val GUESS_MASTER_MILESTONE_ID = "shengshan_four_correct_guesses"
        private const val SHENGSHAN_BGM = "hjh:bgm_shengshan"
        private const val SHENGSHAN_BGM_LOOP_TICKS = 3_050L
        private const val MAX_RESIDUAL_WATER_BLOCKS = 50_000
        private val WATER_EYE_BLOCKS = listOf(
            Triple(3191, 150, -1839), Triple(3191, 151, -1839),
            Triple(3166, 168, -1782), Triple(3166, 169, -1782),
            Triple(3074, 174, -1870), Triple(3074, 175, -1870),
            Triple(3133, 135, -1877), Triple(3134, 135, -1877),
            Triple(3064, 175, -1821), Triple(3064, 176, -1821),
            Triple(3123, 150, -1826), Triple(3123, 151, -1826),
            Triple(3165, 157, -1784), Triple(3165, 158, -1784)
        )
    }

    private data class TrialResult(val playerId: UUID, val playerName: String, val missing: List<String>)

    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val entityKindKey = NamespacedKey(plugin, "shengshan_entity_kind")
    private val guessSpeedKey = NamespacedKey(plugin, "shengshan_guess_speed")
    private val introEscapeKey = NamespacedKey(plugin, "shengshan_intro_escape")
    private val terrain = ShengShanTerrainJournal(plugin)
    private val guardianInvulnerableUntilTick = HashMap<UUID, Int>()
    private var session: ShengShanSession? = null
    private var starting = false

    init {
        terrain.recoverOnStartup()
        Bukkit.getWorld(WORLD_NAME)?.let(::clearResidualWater)
        Bukkit.getWorld(WORLD_NAME)?.let(::clearResidualLava)
        Bukkit.getWorld(WORLD_NAME)?.let(::ensureExitBell)
        Bukkit.getOnlinePlayers().forEach { player ->
            player.removeScoreboardTag(PLAYER_TAG)
            player.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(guessSpeedKey)?.let {
                player.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(it)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onEntranceInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.world.name != WORLD_NAME || block.x != -184 || block.y != 99 || block.z != -832) return
        if (block.type != Material.STONE_BUTTON) return
        val button = block.blockData as? Switch ?: return
        if (button.attachedFace != FaceAttachable.AttachedFace.WALL || button.facing != BlockFace.NORTH) return
        event.isCancelled = true

        if (session != null || starting) {
            event.player.sendMessage("§c圣山秘境正在进行或准备中，请稍后再试！")
            return
        }
        val candidates = block.world.players.filter(::isOnEntryPlatform)
        if (candidates.isEmpty()) {
            event.player.sendMessage("§c传送阵上没有可进入圣山秘境的玩家！")
            return
        }
        if (candidates.size > 5) {
            candidates.forEach { it.sendMessage("§c圣山秘境至多允许§e5§c名玩家进入，请先离开多余成员！") }
            return
        }

        for (candidate in candidates) {
            plugin.playerManager.updateStats(candidate)
            val data = plugin.playerManager.getData(candidate.uniqueId)
            if (data == null) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c的数据尚未加载，无法进入秘境！") }
                return
            }
            if (data.lv < 40) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c等级不满40级，无法进入秘境！") }
                return
            }
            if (data.totalRarity < 30) {
                candidates.forEach { it.sendMessage("§c玩家§e${candidate.name}§c装备稀有度总和不满30点，无法进入秘境！") }
                return
            }
        }

        starting = true
        val snapshots = candidates.map { it.uniqueId to it.name }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val results = checkSacredBeastTrials(snapshots)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!starting || session != null) return@Runnable
                if (results == null) {
                    candidates.filter(Player::isOnline).forEach {
                        it.sendMessage("§c四圣兽试炼记录读取失败，暂时无法进入圣山秘境！")
                    }
                    starting = false
                    return@Runnable
                }
                val online = snapshots.mapNotNull { (id, _) -> Bukkit.getPlayer(id) }
                if (online.size != snapshots.size || online.any { !isOnEntryPlatform(it) }) {
                    online.forEach { it.sendMessage("§c队伍成员在入场校验期间离开了传送阵，本次开启已取消！") }
                    starting = false
                    return@Runnable
                }
                val blocked = results.filter { it.missing.isNotEmpty() }
                if (blocked.isNotEmpty()) {
                    blocked.forEach { result ->
                        result.missing.forEach { trial ->
                            online.forEach { it.sendMessage("§c玩家§e${result.playerName}§c未完成${trial}试炼，无法进入秘境！") }
                        }
                    }
                    starting = false
                    return@Runnable
                }
                starting = false
                startDungeon(online)
            })
        })
    }

    private fun ensureExitBell(world: World) {
        world.getBlockAt(EXIT_BELL_X, EXIT_BELL_Y, EXIT_BELL_Z).setBlockData(
            Bukkit.createBlockData("minecraft:bell[attachment=floor,facing=west,powered=false]"),
            false
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onExitBellInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!isExitBell(block)) return
        event.isCancelled = true
        grantExitReward(event.player, block.location)
    }

    private fun grantExitReward(player: Player, bellLocation: Location) {
        bellLocation.world.playSound(
            bellLocation.clone().add(.5, .5, .5),
            Sound.BLOCK_BELL_USE,
            1.0f,
            1.0f
        )
        val data = plugin.playerManager.getPlayerData(player)
        val record = data?.dungeonRecords?.getOrPut(DUNGEON_RECORD_ID) { DungeonRecord() }
        player.teleport(Location(bellLocation.world, -24.50, 47.00, -914.50, 448.76f, 1.65f))
        if (data == null || record == null) {
            player.sendMessage("§c玩家数据尚未加载，离场奖励发放失败，请联系管理员。")
            return
        }

        plugin.playerManager.giveExp(player, EXIT_EXPERIENCE)
        giveExitResource(player, SILVER_NOTE_ID, 1, "银票")
        giveExitResource(player, SACRED_BEAST_BADGE_ID, 3, "圣兽徽记")
        giveExitResource(player, ELEMENT_EXCHANGE_TICKET_ID, 32, "元素兑换券")
        player.sendMessage(
            "§f你离开了§b§l圣山§f，获得了" +
                "§9银票 §f× §e1§f、§b圣兽徽记 §f× §e3§f、" +
                "§e经验 §f× §e$EXIT_EXPERIENCE§f、§b元素兑换券 §f× §e32§f。"
        )

        if (data.job == 3) grantShengShanMedicalInsight(player, data)

        if (record.clears == 1) {
            giveExitResource(player, SPIRIT_JADE_SLIP_ID, 1, "灵玉简")
            giveExitResource(player, SACRED_BEAST_BADGE_ID, 4, "圣兽徽记")
            player.sendMessage(
                "§f由于你首次通过§b§l圣山§f秘境，你额外获得了：" +
                    "§b灵玉简 §f× §e1§f、§b圣兽徽记 §f× §e4§f。"
            )
        }
        plugin.databaseManager.savePlayerAsync(data)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.1f)
    }

    private fun grantShengShanMedicalInsight(player: Player, data: com.hjh_database.data.PlayerData) {
        val skills = listOf("wanxiangsu" to "万象苏", "bazhenjue" to "八阵诀")
        val available = skills.filterNot { (id, _) -> data.hasLearnedMedicalSkill(id) }
        val learned = available.randomOrNull() ?: return
        if (!data.learnMedicalSkill(learned.first)) return
        val remaining = skills.count { (id, _) -> !data.hasLearnedMedicalSkill(id) }
        player.sendMessage(
            "§f你在§b§l圣山§f的秘境中领悟了医术——§e§l${learned.second}§f，" +
                "此秘境你还可领悟的医术数为§b$remaining/2§f。"
        )
    }

    private fun giveExitResource(player: Player, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            plugin.logger.warning("圣山副本离场奖励缺少资源：$resourceId")
            player.sendMessage("§c[错误] 未找到离场奖励：$displayName，请联系管理员。")
            return
        }
        item.amount = amount
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
            player.sendMessage("§e背包已满，$displayName 已掉落在你的脚下。")
        }
    }

    private fun isExitBell(block: Block): Boolean =
        block.world.name == WORLD_NAME && block.type == Material.BELL &&
            block.x == EXIT_BELL_X && block.y == EXIT_BELL_Y && block.z == EXIT_BELL_Z

    private fun checkSacredBeastTrials(players: List<Pair<UUID, String>>): List<TrialResult>? = try {
        val result = ArrayList<TrialResult>()
        val dataSource = plugin.databaseManager.dataSource ?: return null
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT qinglong, baihu, zhuque, xuanwu FROM player_test WHERE uuid = ?"
            ).use { statement ->
                players.forEach { (uuid, name) ->
                    statement.setString(1, uuid.toString())
                    val missing = ArrayList<String>()
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) {
                            missing += listOf("青龙", "白虎", "朱雀", "玄武")
                        } else {
                            if (rows.getInt("qinglong") != 1) missing += "青龙"
                            if (rows.getInt("baihu") != 1) missing += "白虎"
                            if (rows.getInt("zhuque") != 1) missing += "朱雀"
                            if (rows.getInt("xuanwu") != 1) missing += "玄武"
                        }
                    }
                    result += TrialResult(uuid, name, missing)
                }
            }
        }
        result
    } catch (exception: Exception) {
        plugin.logger.warning("读取圣山入场试炼记录失败: ${exception.message}")
        null
    }

    private fun isOnEntryPlatform(player: Player): Boolean {
        if (!player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR || player.world.name != WORLD_NAME) return false
        val location = player.location
        if (location.blockX !in -186..-182 || location.blockZ !in -836..-832 || location.y !in 99.0..102.5) return false
        return player.world.getBlockAt(location.blockX, 98, location.blockZ).type == Material.GOLD_BLOCK
    }

    private fun startDungeon(players: List<Player>) {
        if (players.isEmpty() || session != null) return
        val world = Bukkit.getWorld(WORLD_NAME) ?: run {
            players.forEach { it.sendMessage("§c主世界尚未加载，无法开启圣山秘境！") }
            return
        }
        // 新一轮开始前先恢复上一次异常终止留下的全部临时建筑（包括艮山六座山丘）。
        terrain.restoreAll()
        clearResidualWater(world)
        clearResidualLava(world)
        val previous = HashMap<UUID, Int>()
        players.forEach { player ->
            plugin.playerManager.getData(player.uniqueId)?.let { data ->
                previous[player.uniqueId] = data.status
                data.updateStatus(5)
                plugin.databaseManager.savePlayerAsync(data)
            }
        }
        val current = ShengShanSession(
            worldName = world.name,
            playerIds = players.mapTo(LinkedHashSet()) { it.uniqueId },
            entryPlayerCount = players.size,
            previousStatuses = previous
        )
        session = current
        players.forEach { player ->
            player.addScoreboardTag(PLAYER_TAG)
        }
        repeating(current, 5L, 5L) { punishIntroEscapees(current) }
        repeating(current, 20L, 20L) { rescueOutOfBoundsPlayers(current) }
        playEntranceDialogue(current)
    }

    private fun punishIntroEscapees(s: ShengShanSession) {
        if (s.arenaEntered || s.ending) return
        val world = s.world() ?: return
        val center = Location(world, -184.0, 99.0, -834.0)
        activePlayers(s).filter { player ->
            val dx = player.location.x - center.x
            val dz = player.location.z - center.z
            player.world.uid != world.uid || dx * dx + dz * dz > 100.0 * 100.0
        }.forEach { punishIntroEscape(s, it) }
    }

    private fun punishIntroEscape(s: ShengShanSession, player: Player) {
        if (s.arenaEntered || player.uniqueId !in s.playerIds || player.isDead) return
        player.sendMessage("§c盘古(?)撕碎了一切试图逃离的人……")
        player.health = 0.0
    }

    private fun rescueOutOfBoundsPlayers(s: ShengShanSession) {
        if (s.phase == ShengShanPhase.INTRO || s.phase == ShengShanPhase.ENDING) return
        val world = s.world() ?: return
        val safePoints = listOf(
            Location(world, 3181.70, 129.0, -1838.15),
            Location(world, 3056.30, 129.0, -1841.04),
            Location(world, 3115.70, 129.0, -1892.70),
            Location(world, 3096.97, 129.0, -1784.30)
        )
        activePlayers(s).filter { !isInsideArena(it.location) }.forEach { player ->
            val destination = safePoints.minByOrNull { point ->
                val dx = point.x - player.location.x
                val dz = point.z - player.location.z
                dx * dx + dz * dz
            } ?: return@forEach
            player.teleport(destination)
            player.sendActionBar("§e你被圣山的力量送回了战斗区域。")
        }
    }

    private fun playEntranceDialogue(s: ShengShanSession) {
        val world = s.world() ?: return
        val cocoon = Location(world, -184.30, 114.0, -824.30)
        val sources = listOf(
            Location(world, -163.30, 129.30, -812.21) to 0x55DDB8,
            Location(world, -166.30, 126.31, -842.21) to 0xF04B3E,
            Location(world, -187.53, 125.56, -801.30) to 0xE8F1FF,
            Location(world, -204.93, 124.06, -831.15) to 0x244E9E
        )
        playDungeonSound(s, cocoon, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, .75f, .55f)
        sources.forEachIndexed { index, (source, color) ->
            playDungeonEffect(s, ShengShanEffect.SPIRIT_GATHER, source, cocoon,
                ShengShanEffectOptions(color = color, durationTicks = 250, intervalTicks = 4, key = "shengshan_intro_stream_$index"))
        }
        playDungeonEffect(s, ShengShanEffect.COCOON_BREATH, cocoon,
            options = ShengShanEffectOptions(radius = 4.5, height = 5.0, durationTicks = 520, intervalTicks = 8, key = "shengshan_intro_cocoon"))

        later(s, 30L) { broadcast(s, "§f你终于抵达了圣山深处，却发现§b四圣兽§f被束缚在§4一颗巨大的茧§f周围。") }
        later(s, 90L) {
            broadcast(s, "§f四股力量正在不断汇入中央，巨茧也随着力量的注入缓缓搏动。")
            playDungeonSound(s, cocoon, Sound.BLOCK_BEACON_AMBIENT, 1.1f, .55f)
        }
        later(s, 155L) {
            val elderLocation = Location(world, -189.0, 112.0, -832.0, -30f, 25f)
            playDungeonEffect(s, ShengShanEffect.DIVINE_DESCENT, elderLocation.clone().add(0.0, 14.0, 0.0), elderLocation,
                ShengShanEffectOptions(radius = .35, height = .35, durationTicks = 30, intervalTicks = 2, key = "shengshan_elder_descent"))
            playDungeonSound(s, elderLocation, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f)
            spawnElder(s, elderLocation)
        }
        later(s, 190L) {
            activePlayers(s).forEach { player ->
                val race = plugin.playerManager.getData(player.uniqueId)?.race
                player.sendMessage(if (race == 0)
                    "§7一道声音从天上传来，你看到§b神族长老§7浮在半空，有些鄙夷地看着中央的巨茧。"
                else "§7一道声音从天上传来，你看到一名§b神秘神族§7浮在半空，俯视着中央的巨茧。")
            }
        }
        later(s, 250L) { sendElderLine(s, "……原来如此。") }
        later(s, 310L) { sendElderLine(s, "四圣兽竟然在这里布下了某种阵法。") }
        later(s, 385L) { sendElderLine(s, "至于里面那个东西……") }
        later(s, 430L) {
            playDungeonEffect(s, ShengShanEffect.COCOON_BREATH, cocoon,
                options = ShengShanEffectOptions(radius = 6.0, height = 6.0))
            playDungeonSound(s, cocoon, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, .55f)
        }
        later(s, 465L) { sendElderLine(s, "恐怕就是§4§n盘古§f。") }
        later(s, 520L) {
            val sky = cocoon.clone().add(0.0, 55.0, 0.0)
            playDungeonEffect(s, ShengShanEffect.LIGHTNING_COLUMN, sky, cocoon,
                ShengShanEffectOptions(durationTicks = 12, intervalTicks = 2, key = "shengshan_intro_lightning"))
            playDungeonSound(s, cocoon, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.8f, .65f)
        }
        later(s, 538L) { playDungeonSound(s, cocoon, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, .82f) }
        later(s, 565L) {
            playDungeonEffect(s, ShengShanEffect.COLLAPSE, cocoon,
                options = ShengShanEffectOptions(radius = 8.0, height = 7.0))
            playDungeonSound(s, cocoon, Sound.ENTITY_GENERIC_EXPLODE, 1.7f, .55f)
            broadcast(s, "§7一道雷光从阵法中央炸开，巨茧表面迅速布满裂纹。")
        }
        later(s, 620L) {
            broadcast(s, "§7下一刻，一个§c高大的身影§7从破碎的巨茧中缓缓站起。")
            activePlayers(s).forEach { it.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 320, 0, false, false, false)) }
            playDungeonEffect(s, ShengShanEffect.DARK_CONVERGE, cocoon.clone().add(-9.0, 5.0, 0.0), cocoon,
                ShengShanEffectOptions(durationTicks = 120, intervalTicks = 3, key = "shengshan_intro_dark"))
            playDungeonSound(s, cocoon, Sound.ENTITY_WITHER_SPAWN, 1.35f, .52f)
        }
        later(s, 680L) { broadcast(s, "§4§n盘古(?)：§f……") }
        later(s, 720L) {
            playDungeonEffect(s, ShengShanEffect.ROAR_SHOCKWAVE, cocoon,
                options = ShengShanEffectOptions(radius = 14.0, height = .3))
            playDungeonEffect(s, ShengShanEffect.FALLING_DEBRIS, cocoon,
                options = ShengShanEffectOptions(radius = 18.0, height = 12.0, durationTicks = 60, intervalTicks = 5, key = "shengshan_intro_debris"))
            playDungeonSound(s, cocoon, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.8f, .5f)
            playDungeonSound(s, cocoon, Sound.ENTITY_GENERIC_EXPLODE, 1.15f, .55f)
            broadcast(s, "§7它没有说话，只是缓缓抬起头，随后发出了一声震动整座圣山的怒吼。")
        }
        later(s, 780L) { broadcast(s, "§e「不好，它会§c毁掉这个世界§e的！」§f不知道谁惊恐地喊了一声。") }
        later(s, 840L) {
            playDungeonEffect(s, ShengShanEffect.GROUND_RUPTURE, cocoon,
                options = ShengShanEffectOptions(radius = 16.0, height = .4, durationTicks = 35, intervalTicks = 4, key = "shengshan_intro_rupture"))
            playDungeonSound(s, cocoon, Sound.BLOCK_BASALT_BREAK, 1.25f, .5f)
        }
        later(s, 875L) {
            playDungeonSound(s, cocoon, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, .45f)
            playDungeonSound(s, cocoon, Sound.BLOCK_DEEPSLATE_BREAK, 1.6f, .48f)
            playDungeonSound(s, cocoon, Sound.ENTITY_RAVAGER_STEP, 1.35f, .55f)
            playDungeonSound(s, cocoon, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.25f, .6f)
            broadcast(s, "§c盘古§f猛然跺向地面，整座圣山开始崩塌！")
            dropPlayersIntoArena(s)
        }
        later(s, 915L) { activePlayers(s).forEach { it.removePotionEffect(PotionEffectType.DARKNESS) } }
        later(s, 930L) { sendElderLine(s, "啧……") }
        later(s, 980L) { sendElderLine(s, "刚刚复生便只知道§c吞噬§f。") }
        later(s, 1030L) { sendElderLine(s, "果然只是个没有神智的§c仿冒品§f。") }
        later(s, 1100L) { sendElderLine(s, "来吧，小朋友们。") }
        later(s, 1150L) { sendElderLine(s, "让我看看，它究竟从盘古那里学到了几成本事。") }
        later(s, 1210L) { broadcast(s, "§6击破§4§n盘古(?)§6的八道卦象，阻止它继续吞噬圣山！") }
        later(s, 1260L) { beginGuess(s) }
    }

    internal fun spawnElder(s: ShengShanSession, location: Location): ArmorStand? {
        val stand = location.world?.spawn(location, ArmorStand::class.java) { entity ->
            entity.setGravity(false)
            entity.isInvulnerable = true
            entity.isPersistent = false
            entity.setBasePlate(false)
            entity.setArms(true)
            entity.headPose = EulerAngle(Math.toRadians(25.0), 0.0, 0.0)
            entity.leftArmPose = EulerAngle(Math.toRadians(20.0), 0.0, Math.toRadians(30.0))
            entity.rightArmPose = EulerAngle(Math.toRadians(20.0), 0.0, Math.toRadians(-30.0))
            entity.equipment.helmet = ItemStack(Material.PLAYER_HEAD)
            entity.equipment.chestplate = ItemStack(Material.CHAINMAIL_CHESTPLATE)
            entity.equipment.leggings = ItemStack(Material.CHAINMAIL_LEGGINGS)
            entity.equipment.boots = ItemStack(Material.CHAINMAIL_BOOTS)
        } ?: return null
        trackEntity(s, stand, "shen_elder")
        s.elderEntityId = stand.uniqueId
        return stand
    }

    private fun dropPlayersIntoArena(s: ShengShanSession) {
        val world = s.world() ?: return
        s.arenaEntered = true
        playDungeonEffect(s, ShengShanEffect.FALLING_DEBRIS, Location(world, -184.3, 112.0, -824.3),
            options = ShengShanEffectOptions(radius = 8.0, height = 12.0))
        activePlayers(s).forEach { player ->
            player.teleport(shengShanPlayerStart(world))
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 25, 4, false, false, false), true)
        }
        s.elderEntityId?.let(Bukkit::getEntity)?.remove()
        s.elderEntityId = null
        s.clientEffectKeys.toList().filter { it.startsWith("shengshan_intro_") }.forEach { stopDungeonEffect(s, it) }
    }

    private fun beginGuess(s: ShengShanSession) {
        if (!isCurrent(s) || s.ending) return
        if (s.loopTestMode) {
            beginLoopTestGuess(s)
            return
        }
        if (s.remaining.isEmpty()) {
            startVictory(s)
            return
        }
        if (s.remaining.size == 1) {
            beginFinalTrigram(s, s.remaining.single())
            return
        }
        clearGuessBuff(s)
        s.phase = ShengShanPhase.GUESSING
        s.round++
        s.guesses.clear()
        s.nextTrigram = s.remaining.random()
        s.guessToken = UUID.randomUUID().toString().replace("-", "").take(12)
        val firstGuess = s.round == 1
        if (firstGuess) startShengShanBgm(s)
        val guessSeconds = if (firstGuess) 12 else 6
        val guessTicks = guessSeconds * 20
        val center = s.world()?.let(::shengShanBossSpawn) ?: return
        playDungeonEffect(s, ShengShanEffect.TRIGRAM_ARRAY, center,
            options = ShengShanEffectOptions(radius = 13.0, height = .35, durationTicks = guessTicks + 5,
                intervalTicks = 4, key = "shengshan_guess_${s.round}"))
        playDungeonSound(s, center, Sound.BLOCK_END_PORTAL_FRAME_FILL, .9f, .58f)
        broadcast(s, "§6八卦轮转……")
        if (firstGuess) {
            broadcast(s, "§6§l【圣山推演】")
            broadcast(s, "§f盘古§8(?)§f将从下方§e§l八道卦象§f中随机召来一位。")
            broadcast(s, "§f请推测本轮即将降临的§d§l卦象§f，并在§c§l12秒§f内作出选择！")
            broadcast(s, "§a§l推演成功§f后，你将获得§b§l圣山祝福§f的临时加成。")
        } else {
            broadcast(s, "§e请在§c6秒§e内推演下一道卦象！")
        }
        announcePendingEcho(s)

        val serializer = LegacyComponentSerializer.legacySection()
        activePlayers(s).forEach { player ->
            s.remaining.forEach { trigram ->
                val command = "$GUESS_COMMAND ${s.guessToken} ${trigram.name.lowercase()}"
                player.sendMessage(
                    serializer.deserialize("${trigram.color}§l[${trigram.displayName}]")
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(serializer.deserialize("§f点击选择 ${trigram.color}${trigram.displayName}")))
                )
            }
        }
        val bar = createBar(s, "§e猜测下一卦象：§c${guessSeconds}秒", BarColor.YELLOW, BarStyle.SEGMENTED_10)
        var seconds = guessSeconds
        val task = repeating(s, 20L, 20L) {
            seconds--
            bar.progress = (seconds / guessSeconds.toDouble()).coerceIn(0.0, 1.0)
            bar.setTitle("§e猜测下一卦象：§c${seconds}秒")
            if (seconds in 1..3) activePlayers(s).forEach {
                it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_HAT, .75f, 1.15f + (3 - seconds) * .15f)
            }
            if (seconds <= 0) finishGuess(s, bar)
        }
        later(s, guessTicks + 1L) { task.cancel() }
    }

    private fun beginLoopTestGuess(s: ShengShanSession) {
        if (!isCurrent(s) || s.ending || !s.loopTestMode) return
        clearGuessBuff(s)
        s.phase = ShengShanPhase.GUESSING
        s.round++
        s.guesses.clear()
        s.nextTrigram = null
        s.guessToken = UUID.randomUUID().toString().replace("-", "").take(12)
        val center = s.world()?.let(::shengShanBossSpawn) ?: return
        playDungeonEffect(s, ShengShanEffect.TRIGRAM_ARRAY, center,
            options = ShengShanEffectOptions(radius = 13.0, height = .35))
        playDungeonSound(s, center, Sound.BLOCK_END_PORTAL_FRAME_FILL, .9f, .58f)
        broadcast(s, "§6八卦轮转……")
        broadcast(s, "§e测试模式：选择的卦象将立即成为下一卦，选择时间不受限制。")
        announcePendingEcho(s)

        val serializer = LegacyComponentSerializer.legacySection()
        activePlayers(s).forEach { player ->
            Trigram.entries.forEach { trigram ->
                val command = "$GUESS_COMMAND ${s.guessToken} ${trigram.name.lowercase()}"
                player.sendMessage(
                    serializer.deserialize("${trigram.color}§l[${trigram.displayName}]")
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(serializer.deserialize("§f点击后立即挑战 ${trigram.color}${trigram.displayName}")))
                )
            }
            val victoryCommand = "$GUESS_COMMAND ${s.guessToken} $LOOP_TEST_VICTORY_ARGUMENT"
            player.sendMessage(
                serializer.deserialize("§c§l[立刻结束副本（判定胜利）]")
                    .clickEvent(ClickEvent.runCommand(victoryCommand))
                    .hoverEvent(HoverEvent.showText(serializer.deserialize("§c点击后立即进入圣山副本胜利结局")))
            )
        }
    }

    private fun announcePendingEcho(s: ShengShanSession) {
        val echo = s.pendingEcho ?: return
        broadcast(s, "§6${echo.displayName}的死亡留下了残响。")
        broadcast(s, "§e${ECHO_DESCRIPTIONS.getValue(echo)}")
    }

    private fun beginFinalTrigram(s: ShengShanSession, trigram: Trigram) {
        if (!isCurrent(s) || s.ending) return
        clearGuessBuff(s)
        s.phase = ShengShanPhase.GUESSING
        s.round++
        s.nextTrigram = trigram
        val center = s.world()?.let(::shengShanBossSpawn) ?: return
        playDungeonEffect(s, ShengShanEffect.TRIGRAM_REVEAL, center,
            options = ShengShanEffectOptions(radius = 13.0, height = .35, color = TRIGRAM_REVEAL_COLORS.getValue(trigram)))
        playDungeonSound(s, center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.1f)
        broadcast(s, "§6八卦仅余最后一位——无需再行推演。")
        announcePendingEcho(s)
        broadcast(s, "${trigram.color}§l===${trigram.revealName()}===")
        later(s, 10L) { startFight(s, trigram) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGuessCommand(event: PlayerCommandPreprocessEvent) {
        val parts = event.message.trim().split(Regex("\\s+"))
        if (parts.firstOrNull()?.lowercase() != GUESS_COMMAND) return
        event.isCancelled = true
        val s = session ?: return
        if (s.phase != ShengShanPhase.GUESSING || event.player.uniqueId !in s.playerIds || parts.size != 3) return
        if (parts[1] != s.guessToken) {
            event.player.sendMessage("§c这次竞猜已经失效。")
            return
        }
        if (s.loopTestMode) {
            if (event.player.uniqueId != s.loopTestOwnerId ||
                (!event.player.isOp && !event.player.hasPermission("hjh.admin"))
            ) {
                event.player.sendMessage("§c只有启动循环测试模式的管理员可以作出选择。")
                return
            }
            if (parts[2].equals(LOOP_TEST_VICTORY_ARGUMENT, true)) {
                event.player.sendMessage("§a已将本轮圣山副本判定为胜利。")
                plugin.logger.info("管理员 ${event.player.name} 结束圣山循环测试并判定胜利")
                startVictory(s)
                return
            }
            val selected = Trigram.entries.firstOrNull { it.name.equals(parts[2], true) } ?: return
            s.nextTrigram = selected
            s.guesses[event.player.uniqueId] = selected
            s.phase = ShengShanPhase.COMBAT
            applyGuessBuff(s, event.player)
            event.player.sendMessage("§a你指定了下一道卦象：${selected.color}§l${selected.displayName}§a。")
            event.player.sendMessage("§6圣山祝福受到引动：§e你的步伐加快、力量增强，生息涌动，持续至本卦结束。")
            playDungeonEffect(s, ShengShanEffect.TRIGRAM_REVEAL,
                s.world()?.let(::shengShanBossSpawn) ?: return,
                options = ShengShanEffectOptions(radius = 13.0, height = .35, color = TRIGRAM_REVEAL_COLORS.getValue(selected)))
            playDungeonSound(s, s.world()?.let(::shengShanBossSpawn) ?: return,
                Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.2f)
            broadcast(s, "${selected.color}§l===${selected.revealName()}===")
            later(s, 10L) { startFight(s, selected) }
            return
        }
        val trigram = Trigram.entries.firstOrNull { it.name.equals(parts[2], true) }
        if (trigram == null || trigram !in s.remaining) return
        s.guesses[event.player.uniqueId] = trigram
        event.player.sendMessage("§f你选择了 ${trigram.color}§l${trigram.displayName}§f；倒计时结束前仍可修改。")
        event.player.playSound(event.player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
    }

    private fun finishGuess(s: ShengShanSession, bar: BossBar) {
        if (!isCurrent(s) || s.phase != ShengShanPhase.GUESSING) return
        bar.removeAll()
        s.bars.remove(bar)
        val trigram = s.nextTrigram ?: return
        stopDungeonEffect(s, "shengshan_guess_${s.round}")
        val center = s.world()?.let(::shengShanBossSpawn) ?: return
        playDungeonEffect(s, ShengShanEffect.TRIGRAM_REVEAL, center,
            options = ShengShanEffectOptions(radius = 13.0, height = .35, color = TRIGRAM_REVEAL_COLORS.getValue(trigram)))
        playDungeonSound(s, center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.2f)
        broadcast(s, "${trigram.color}§l===${trigram.revealName()}===")
        activePlayers(s).forEach { player ->
            if (s.guesses[player.uniqueId] == trigram) {
                applyGuessBuff(s, player)
                val successes = (s.successfulGuessCounts[player.uniqueId] ?: 0) + 1
                s.successfulGuessCounts[player.uniqueId] = successes
                if (!s.loopTestMode && successes == 4) {
                    plugin.titleManager.grantMilestoneTitle(
                        player,
                        GUESS_MASTER_TITLE_ID,
                        GUESS_MASTER_MILESTONE_ID
                    )
                }
                player.sendMessage("§a你猜对了卦象！§6圣山祝福受到引动：§e你的步伐加快、力量增强，生息涌动，持续至本卦结束。")
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.2f)
            }
        }
        later(s, 10L) { startFight(s, trigram) }
    }

    private fun applyGuessBuff(s: ShengShanSession, player: Player) {
        s.guessBuffPlayers += player.uniqueId
        s.previousRegeneration[player.uniqueId] = player.getPotionEffect(PotionEffectType.REGENERATION)
        s.previousNightVision[player.uniqueId] = player.getPotionEffect(PotionEffectType.NIGHT_VISION)
        player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 20 * 60 * 30, 0, false, true, true), true)
        player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 60 * 30, 0, false, false, true), true)
        player.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attribute ->
            attribute.getModifier(guessSpeedKey)?.let(attribute::removeModifier)
            attribute.addTransientModifier(AttributeModifier(guessSpeedKey, 0.15, AttributeModifier.Operation.ADD_SCALAR))
        }
    }

    private fun clearGuessBuff(s: ShengShanSession) {
        s.guessBuffPlayers.toList().forEach { id ->
            Bukkit.getPlayer(id)?.let { player ->
                player.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(guessSpeedKey)?.let {
                    player.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(it)
                }
                player.removePotionEffect(PotionEffectType.REGENERATION)
                s.previousRegeneration[id]?.let { player.addPotionEffect(it, true) }
                player.removePotionEffect(PotionEffectType.NIGHT_VISION)
                s.previousNightVision[id]?.let { player.addPotionEffect(it, true) }
            }
        }
        s.guessBuffPlayers.clear()
        s.previousRegeneration.clear()
        s.previousNightVision.clear()
    }

    private fun startFight(s: ShengShanSession, trigram: Trigram) {
        if (!isCurrent(s) || s.ending) return
        val world = s.world() ?: return
        s.phase = ShengShanPhase.COMBAT
        if (!s.loopTestMode) s.remaining.remove(trigram)
        s.activeEcho = s.pendingEcho
        s.pendingEcho = null
        val boss = MobFactory.spawnMob(plugin, trigram.spawn(world), trigram.mobId) ?: run {
            failSession(s, "§c${trigram.displayName}生成失败，圣山秘境已经关闭。")
            return
        }
        trackEntity(s, boss, "boss_${trigram.name.lowercase()}")
        // 注册表本身已经声明 instance_boss；运行时再补一次，避免热更新或旧实体数据漏标。
        boss.addScoreboardTag("instance_boss")
        boss.removePotionEffect(PotionEffectType.SLOWNESS)
        boss.removePotionEffect(PotionEffectType.WEAKNESS)
        val scaledHealth = max(1.0, boss.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 1.0) +
            1500.0 * (s.entryPlayerCount - 1)
        boss.getAttribute(Attribute.MAX_HEALTH)?.baseValue = scaledHealth
        boss.health = scaledHealth
        boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = when (trigram) {
            Trigram.SKY, Trigram.WIND -> 0.8
            else -> 1.0
        }
        when (trigram) {
            Trigram.MOUNTAIN, Trigram.EARTH -> boss.getAttribute(Attribute.SCALE)?.baseValue = 1.75
            else -> Unit
        }
        boss.isInvisible = true
        boss.isInvulnerable = false
        val controller = createShengShanBossController(this, s, trigram, boss)
        s.current = controller
        controller.start()
    }

    internal fun onBossDefeated(controller: ShengShanBossController) {
        val s = session ?: return
        if (!isCurrent(s) || s.current !== controller || s.ending) return
        val deathCenter = controller.boss.location.clone()
        val defeatedBossId = controller.boss.uniqueId
        s.pendingEcho = controller.trigram
        controller.shutdown(restoreTerrain = true)
        s.current = null
        // EntityDeathEvent 会提前把 UUID 从 entityIds 移除；因此不能只依赖最终 session 清场。
        // 显式移除实体并清扫本轮所有带副本标签的残留，兼容正常击杀和管理员跳关两条路径。
        controller.boss.remove()
        s.entityIds.remove(defeatedBossId)
        purgeEncounterResidue(s)
        clearGuessBuff(s)
        playDungeonEffect(s, ShengShanEffect.COLLAPSE, deathCenter,
            options = ShengShanEffectOptions(radius = 5.0, height = 4.0))
        playDungeonSound(s, deathCenter, Sound.BLOCK_GLASS_BREAK, 1.1f, .65f)
        broadcast(s, "§6${controller.trigram.displayName}的卦位已经崩毁。")
        teleportPlayersToArenaEntry(s)
        if (s.loopTestMode) later(s, 20L) { beginLoopTestGuess(s) }
        else later(s, 20L) { startCorePhase(s) }
    }

    private fun startCorePhase(s: ShengShanSession) {
        if (!isCurrent(s) || s.ending || s.current != null) return
        val world = s.world() ?: return
        s.phase = ShengShanPhase.CORE
        val spawn = Location(world, 3084.50, 131.50, -1839.50, -90.0f, 0.0f)
        val core = MobFactory.spawnMob(plugin, spawn, "shengshan_pangu_core") ?: run {
            failSession(s, "§c盘古的内核生成失败，圣山秘境已经关闭。")
            return
        }
        trackEntity(s, core, "pangu_core")
        core.setAI(false)
        core.setGravity(false)
        core.velocity = Vector()
        core.isInvulnerable = false
        core.isCollidable = true
        s.coreEntityId = core.uniqueId
        val maximum = core.getAttribute(Attribute.MAX_HEALTH)?.value ?: 5000.0
        val bar = createBar(s, "§c§l盘古(?)的内核", BarColor.RED, BarStyle.SEGMENTED_10)
        s.coreBar = bar
        lateinit var barTask: BukkitTask
        barTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (session !== s || s.coreEntityId != core.uniqueId || !core.isValid || core.isDead) {
                barTask.cancel()
                s.tasks.remove(barTask)
                return@Runnable
            }
            bar.progress = (core.health / maximum).coerceIn(0.0, 1.0)
        }, 0L, 1L)
        s.tasks += barTask
        playDungeonEffect(s, ShengShanEffect.FIRE_BURST, spawn,
            options = ShengShanEffectOptions(radius = 3.0, height = 4.0))
        playDungeonSound(s, spawn, Sound.ENTITY_BLAZE_AMBIENT, 1.0f, .65f)
        sendElderLine(s, "卦象已破！它的内核暂时暴露出来了，快冲进去将它杀死！")
    }

    private fun onCoreDefeated(s: ShengShanSession, core: LivingEntity) {
        if (!isCurrent(s) || s.coreEntityId != core.uniqueId || s.ending) return
        s.coreEntityId = null
        s.coreBar?.let { bar -> bar.removeAll(); s.bars.remove(bar) }
        s.coreBar = null
        playDungeonEffect(s, ShengShanEffect.BREAK_SUCCESS, core.location,
            options = ShengShanEffectOptions(radius = 4.0, height = 4.0, color = 0xFF6A33))
        playDungeonSound(s, core.location, Sound.ENTITY_BLAZE_DEATH, 1.2f, .7f)
        teleportPlayersToArenaEntry(s)
        if (s.remaining.isEmpty()) later(s, 20L) { startFinalCoreCinematic(s) }
        else later(s, 40L) { beginGuess(s) }
    }

    private fun teleportPlayersToArenaEntry(s: ShengShanSession) {
        val world = s.world() ?: return
        activePlayers(s).forEach { player ->
            player.teleport(shengShanPlayerStart(world))
        }
    }

    private fun startVictory(s: ShengShanSession) {
        if (!isCurrent(s) || s.ending) return
        s.phase = ShengShanPhase.ENDING
        s.ending = true
        clearGuessBuff(s)
        playVictoryTimeline(s)
    }

    private fun startFinalCoreCinematic(s: ShengShanSession) {
        if (!isCurrent(s) || s.ending) return
        // 最后一卦到结尾演出之间不再保留任何战斗实体或临时地形。
        // 即使某个 Boss 的死亡/建筑崩塌任务被事件顺序打断，这里也会再次兜底。
        purgeEncounterResidue(s)
        s.phase = ShengShanPhase.ENDING
        s.ending = true
        clearGuessBuff(s)
        ShengShanEndShow(this, s) { playVictoryTimeline(s) }.start()
    }

    private fun purgeEncounterResidue(s: ShengShanSession) {
        val world = s.world()
        s.entityIds.toList().mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.entityIds.clear()
        world?.entities
            ?.filter { it.scoreboardTags.contains(ENTITY_TAG) }
            ?.forEach(Entity::remove)
        s.clientEffectKeys.toList().forEach { stopDungeonEffect(s, it) }
        terrain.restoreAll()
        world?.let(::clearResidualWater)
        world?.let(::clearResidualLava)
    }

    private fun playVictoryTimeline(s: ShengShanSession) {
        val center = s.world()?.let(::shengShanBossSpawn) ?: return
        Trigram.entries.forEachIndexed { index, trigram ->
            laterEnding(s, index * 8L) {
                playDungeonEffect(s, ShengShanEffect.COLLAPSE, center,
                    options = ShengShanEffectOptions(radius = 4.0 + index, height = 2.0, color = TRIGRAM_REVEAL_COLORS.getValue(trigram)))
                playDungeonSound(s, center, Sound.BLOCK_GLASS_BREAK, .55f, .55f + index * .04f)
            }
        }
        laterEnding(s, 70L) {
            broadcast(s, "§7八道卦象接连崩毁，支撑盘古肉身的力量终于彻底消散。")
            playDungeonEffect(s, ShengShanEffect.DARK_CONVERGE, center.clone().add(0.0, 8.0, 0.0), center,
                ShengShanEffectOptions(color = 0xF4F4F4, durationTicks = 90, intervalTicks = 4, key = "shengshan_victory_dissolve"))
        }
        laterEnding(s, 125L) {
            playDungeonEffect(s, ShengShanEffect.ROAR_SHOCKWAVE, center,
                options = ShengShanEffectOptions(radius = 15.0, height = .4))
            playDungeonSound(s, center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.6f, .45f)
            broadcast(s, "§7巨大的身躯布满裂痕，在最后一声怒吼中逐渐化作光点。")
        }
        laterEnding(s, 205L) { broadcast(s, "§f「……这真的是盘古吗？」") }
        laterEnding(s, 260L) { broadcast(s, "§f「它从苏醒开始，就只想着吞噬这里的一切……」") }
        laterEnding(s, 335L) { broadcast(s, "§b神族长老：§f……") }
        laterEnding(s, 390L) { broadcast(s, "§b神族长老：§f先离开这里。") }
        laterEnding(s, 445L) { broadcast(s, "§b神族长老：§f看来有些事情，必须重新查清楚了。") }
        laterEnding(s, 505L) {
            val world = s.world()
            if (world != null) {
                ensureExitBell(world)
                activePlayers(s).forEach { player ->
                    plugin.playerManager.getPlayerData(player)?.let { data ->
                        val record = data.dungeonRecords.getOrPut(DUNGEON_RECORD_ID) { DungeonRecord() }
                        record.clears += 1
                        record.availableOpens += 1
                        if (record.clears == 1) {
                            plugin.titleManager.grantMilestoneTitle(
                                player,
                                FIRST_CLEAR_TITLE_ID,
                                FIRST_CLEAR_MILESTONE_ID
                            )
                        }
                        plugin.databaseManager.savePlayerAsync(data)
                        player.sendMessage("§e[秘境] §a圣山通关记录 +1，§6[圣山]金宝箱§a可开箱次数 +1！")
                    }
                    player.teleport(Location(world, 3245.48, 128.0, -1873.14, 11609.19f, 6.30f))
                    player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.0f)
                }
            }
            cleanup(s)
        }
    }

    private fun failSession(s: ShengShanSession, reason: String) {
        if (!isCurrent(s) || s.ending) return
        s.ending = true
        broadcast(s, reason)
        cleanup(s)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onCurrentTrigramTestItemUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK) ||
            resourceId(event.item) != CURRENT_TRIGRAM_TEST_ITEM_ID
        ) return
        event.isCancelled = true
        val player = event.player
        if (!player.isOp && !player.hasPermission("hjh.admin")) {
            player.sendMessage("§c该测试道具仅限管理员使用。")
            return
        }
        val s = session
        val controller = s?.current
        if (s == null || controller == null || s.ending || s.phase != ShengShanPhase.COMBAT || !isParticipant(s, player)) {
            player.sendMessage("§c你必须处于正在战斗的圣山卦象中才能使用该测试道具。")
            return
        }
        player.sendMessage("§a已将当前卦象 §e${controller.trigram.displayName}§a 标记为通关。")
        plugin.logger.info("管理员 ${player.name} 使用测试道具跳过圣山卦象 ${controller.trigram.displayName}")
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (session !== s || s.current !== controller || s.ending) return@Runnable
            val skippedBoss = controller.boss
            onBossDefeated(controller)
            s.entityIds.remove(skippedBoss.uniqueId)
            skippedBoss.remove()
        })
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onLoopTestItemUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK) ||
            resourceId(event.item) != LOOP_TEST_ITEM_ID
        ) return
        event.isCancelled = true
        val player = event.player
        if (!player.isOp && !player.hasPermission("hjh.admin")) {
            player.sendMessage("§c该测试道具仅限管理员使用。")
            return
        }
        val s = session
        if (s == null || s.ending || !isParticipant(s, player)) {
            player.sendMessage("§c请先正常开启圣山副本，再使用该测试道具。")
            return
        }
        if (s.current != null || s.coreEntityId != null || s.phase in setOf(ShengShanPhase.COMBAT, ShengShanPhase.CORE)) {
            player.sendMessage("§c当前战斗尚未结束，不能切换到循环测试模式。")
            return
        }
        if (s.loopTestMode) {
            player.sendMessage("§e圣山循环测试模式已经开启，请直接在聊天框选择卦象。")
            beginLoopTestGuess(s)
            return
        }

        s.tasks.toList().forEach(BukkitTask::cancel)
        s.tasks.clear()
        s.bars.toList().forEach(BossBar::removeAll)
        s.bars.clear()
        purgeEncounterResidue(s)
        s.elderEntityId = null
        s.arenaEntered = true
        s.loopTestMode = true
        s.loopTestOwnerId = player.uniqueId
        s.remaining.clear()
        s.remaining.addAll(Trigram.entries)
        val world = s.world() ?: return
        activePlayers(s).forEach { participant ->
            participant.removePotionEffect(PotionEffectType.DARKNESS)
            participant.teleport(shengShanPlayerStart(world))
            participant.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 25, 4, false, false, false), true)
        }
        repeating(s, 20L, 20L) { rescueOutOfBoundsPlayers(s) }
        player.sendMessage("§a圣山循环测试模式已开启：前置剧情已跳过，卦象可无限重复挑战。")
        plugin.logger.info("管理员 ${player.name} 开启圣山循环测试模式")
        beginLoopTestGuess(s)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamageIsolation(event: EntityDamageByEntityEvent) {
        val s = session ?: return
        val victim = event.entity
        val attackerPlayer = actualPlayer(event.damager)
        val attackerEntity = actualLivingEntity(event.damager)
        val victimIsDungeon = victim.scoreboardTags.contains(ENTITY_TAG)
        val attackerIsDungeon = attackerEntity?.scoreboardTags?.contains(ENTITY_TAG) == true
        val attackerIsParticipant = attackerPlayer?.let { isParticipant(s, it) } == true
        val victimIsParticipant = victim is Player && isParticipant(s, victim)
        val current = s.current
        val reflectedSkyFireball = current?.trigram == Trigram.SKY &&
            victim.uniqueId == current.boss.uniqueId && event.damager is LargeFireball &&
            (attackerPlayer == null || attackerIsParticipant)
        if (victimIsDungeon && !reflectedSkyFireball && !attackerIsParticipant) {
            event.isCancelled = true
            return
        }
        if (attackerIsDungeon && victim is Player && !isParticipant(s, victim)) {
            event.isCancelled = true
            return
        }
        if (victimIsParticipant && !attackerIsDungeon && !attackerIsParticipant) {
            event.isCancelled = true
            return
        }
        if (attackerIsParticipant && victim is LivingEntity && !victimIsDungeon && !victimIsParticipant) {
            event.isCancelled = true
            return
        }
        if (victimIsParticipant && attackerIsDungeon && attackerEntity is Guardian) {
            val currentTick = Bukkit.getCurrentTick()
            if (currentTick < (guardianInvulnerableUntilTick[victim.uniqueId] ?: 0)) {
                event.isCancelled = true
                return
            }
            val invulnerabilityTicks = victim.maximumNoDamageTicks.coerceAtLeast(10)
            guardianInvulnerableUntilTick[victim.uniqueId] = currentTick + invulnerabilityTicks
            victim.noDamageTicks = invulnerabilityTicks
        }
        if (current != null && victim.uniqueId == current.boss.uniqueId && current.invulnerable) {
            event.isCancelled = true
            return
        }
        if (reflectedSkyFireball) {
            event.damage = current?.boss?.persistentDataContainer
                ?.get(MobFactory.KEY_CUSTOM_DAMAGE, PersistentDataType.DOUBLE) ?: 12.0
        }
        if (current != null && victim.uniqueId == current.boss.uniqueId) {
            event.damage *= current.damageTakenMultiplier(event)
        }
        if (current?.handleEchoDamageEvent(event) == true) return
        current?.onDamageByEntity(event)
        if (!event.isCancelled && current != null && victim.uniqueId == current.boss.uniqueId) {
            current.onEchoBossDamaged(event)
        }
        if (!event.isCancelled && current != null && victimIsParticipant && attackerEntity?.uniqueId == current.boss.uniqueId) {
            if (s.activeEcho == Trigram.FIRE) {
                victim.setMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, FixedMetadataValue(plugin, 0.20))
            }
            current.onEchoBossDealtDamage(victim as? Player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFireEchoArmorPenetration(event: EntityDamageByEntityEvent) {
        val s = session ?: return
        if (s.activeEcho != Trigram.FIRE) return
        val player = event.entity as? Player ?: return
        if (!isParticipant(s, player)) return
        val attacker = actualLivingEntity(event.damager) ?: return
        if (attacker.uniqueId != s.current?.boss?.uniqueId) return
        player.setMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, FixedMetadataValue(plugin, .20))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonEnvironmentalDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val s = session ?: return
        if (!event.entity.scoreboardTags.contains(ENTITY_TAG)) return
        val current = s.current
        if (current == null || event.entity.uniqueId != current.boss.uniqueId || current.invulnerable ||
            event.cause != EntityDamageEvent.DamageCause.CUSTOM
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onGuessDamage(event: CombatDamageCalculationEvent) {
        val s = session ?: return
        if (s.phase != ShengShanPhase.COMBAT) return
        val attacker = event.attacker as? Player ?: return
        if (attacker.uniqueId in s.guessBuffPlayers && isParticipant(s, attacker)) event.damage *= 1.05
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBossControlDebuff(event: EntityPotionEffectEvent) {
        val s = session ?: return
        val currentBoss = s.current?.boss ?: return
        if (event.entity.uniqueId != currentBoss.uniqueId ||
            !event.entity.scoreboardTags.contains("instance_boss")
        ) return
        if (event.newEffect?.type == PotionEffectType.SLOWNESS ||
            event.newEffect?.type == PotionEffectType.WEAKNESS
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMiningFatigue(event: EntityPotionEffectEvent) {
        val s = session ?: return
        val player = event.entity as? Player ?: return
        if (!isParticipant(s, player) || s.current?.trigram != Trigram.WATER) return
        if (event.newEffect?.type == PotionEffectType.MINING_FATIGUE) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDungeonTarget(event: EntityTargetLivingEntityEvent) {
        val s = session ?: return
        if (!event.entity.scoreboardTags.contains(ENTITY_TAG)) return
        val target = event.target
        val currentBoss = s.current?.boss
        if (target == null && currentBoss?.uniqueId == event.entity.uniqueId) {
            // 不在原版目标事件栈内强行改写；下一tick恢复主Boss强索敌，守卫者仍可正常清空激光目标。
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val mob = currentBoss as? org.bukkit.entity.Mob ?: return@Runnable
                if (!mob.isValid || mob.isDead || s.current?.boss?.uniqueId != mob.uniqueId) return@Runnable
                mob.target = activePlayers(s).minByOrNull { it.location.distanceSquared(mob.location) }
            })
        }
        // 允许原版 AI 主动清空目标；若把 null 也取消，守卫者会永远保留上一名玩家作为攻击目标。
        if (target != null && (target !is Player || !isParticipant(s, target))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) {
        val s = session ?: return
        if (!event.entity.scoreboardTags.contains(ENTITY_TAG)) return
        event.drops.clear()
        event.droppedExp = 0
        val kind = event.entity.persistentDataContainer.get(entityKindKey, PersistentDataType.STRING)
        s.entityIds.remove(event.entity.uniqueId)
        if (kind == "pangu_core") {
            onCoreDefeated(s, event.entity)
            return
        }
        val current = s.current
        current?.onEntityDeath(event.entity)
        if (current != null && event.entity.uniqueId == current.boss.uniqueId) onBossDefeated(current)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSlimeSplit(event: SlimeSplitEvent) {
        if (event.entity.scoreboardTags.contains(ENTITY_TAG)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onProjectileHit(event: ProjectileHitEvent) {
        session?.current?.onEchoProjectileHit(event)
        session?.current?.onProjectileHit(event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        session?.current?.onProjectileLaunch(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMechanicInteract(event: PlayerInteractEvent) {
        val s = session ?: return
        if (!isParticipant(s, event.player)) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK &&
            event.action != Action.LEFT_CLICK_AIR && event.action != Action.LEFT_CLICK_BLOCK &&
            event.action != Action.PHYSICAL
        ) return
        if (s.current?.onPlayerInteract(event) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (isExitBell(event.block) || session != null && isInsideArena(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (session != null && isInsideArena(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (session != null && (isInsideArena(event.blockClicked.location) ||
                isInsideArena(event.blockClicked.getRelative(event.blockFace).location))
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (session != null && (isInsideArena(event.blockClicked.location) ||
                isInsideArena(event.blockClicked.getRelative(event.blockFace).location))
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isExitBell(it) || session != null && isInsideArena(it.location) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isExitBell(it) || session != null && isInsideArena(it.location) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val s = session ?: return
        if (isParticipant(s, event.entity)) {
            s.current?.onEchoParticipantDeath()
            removeParticipant(s, event.entity)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val s = session ?: return
        val player = event.player
        if (!isParticipant(s, player)) return

        // QuitEvent 中直接处决，避免玩家下次上线时仍存活在副本场地内；
        // 同时持久化标记，兜底处理断线阶段未被服务端完整结算的死亡。
        player.persistentDataContainer.set(introEscapeKey, PersistentDataType.BYTE, 1.toByte())
        if (!player.isDead && player.health > 0.0) player.health = 0.0

        // 直接处决通常会同步触发 PlayerDeathEvent 并完成移除；若未触发，则在这里补齐清理。
        restorePlayerStatus(s, player)
        if (player.uniqueId in s.playerIds) {
            removeParticipant(s, player, statusAlreadyRestored = true)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.persistentDataContainer.has(introEscapeKey, PersistentDataType.BYTE)) return
        player.persistentDataContainer.remove(introEscapeKey)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            player.sendMessage("§c圣山的禁制撕碎了一切试图中途逃离的人……")
            if (!player.isDead && player.health > 0.0) player.health = 0.0
        })
    }

    private fun removeParticipant(s: ShengShanSession, player: Player, statusAlreadyRestored: Boolean = false) {
        stopShengShanBgm(player)
        s.current?.removePlayer(player)
        clearDungeonEffectsFor(s, player)
        player.removeScoreboardTag(PLAYER_TAG)
        player.removePotionEffect(PotionEffectType.DARKNESS)
        removeGuessBuffFor(s, player)
        s.bars.forEach { it.removePlayer(player) }
        guardianInvulnerableUntilTick.remove(player.uniqueId)
        s.playerIds.remove(player.uniqueId)
        if (!statusAlreadyRestored) restorePlayerStatus(s, player)
        if (!s.ending && activePlayers(s).isEmpty()) failSession(s, "§c所有圣山副本玩家均已离开或阵亡，挑战失败！")
    }

    private fun removeGuessBuffFor(s: ShengShanSession, player: Player) {
        player.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(guessSpeedKey)?.let {
            player.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(it)
        }
        if (player.uniqueId in s.guessBuffPlayers) {
            player.removePotionEffect(PotionEffectType.REGENERATION)
            s.previousRegeneration[player.uniqueId]?.let { player.addPotionEffect(it, true) }
            player.removePotionEffect(PotionEffectType.NIGHT_VISION)
            s.previousNightVision[player.uniqueId]?.let { player.addPotionEffect(it, true) }
        }
        s.guessBuffPlayers.remove(player.uniqueId)
        s.previousRegeneration.remove(player.uniqueId)
        s.previousNightVision.remove(player.uniqueId)
    }

    private fun restorePlayerStatus(s: ShengShanSession, player: Player) {
        val previous = s.previousStatuses.remove(player.uniqueId) ?: return
        plugin.playerManager.getData(player.uniqueId)?.let { data ->
            data.updateStatus(previous)
            plugin.databaseManager.savePlayerAsync(data)
        }
    }

    private fun cleanup(s: ShengShanSession) {
        if (session !== s) return
        val dungeonWorld = s.world()
        stopShengShanBgm(s)
        s.current?.shutdown(restoreTerrain = true)
        s.current = null
        s.tasks.forEach(BukkitTask::cancel)
        s.tasks.clear()
        clearGuessBuff(s)
        s.bars.forEach(BossBar::removeAll)
        s.bars.clear()
        s.entityIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        s.world()?.entities?.filter { it.scoreboardTags.contains(ENTITY_TAG) }?.forEach(Entity::remove)
        s.clientEffectKeys.toList().forEach { stopDungeonEffect(s, it) }
        terrain.restoreAll()
        dungeonWorld?.let(::clearResidualWater)
        dungeonWorld?.let(::clearResidualLava)
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.uniqueId in s.playerIds || player.scoreboardTags.contains(PLAYER_TAG)) {
                player.removeScoreboardTag(PLAYER_TAG)
                player.removePotionEffect(PotionEffectType.DARKNESS)
                restorePlayerStatus(s, player)
            }
        }
        s.playerIds.clear()
        guardianInvulnerableUntilTick.clear()
        session = null
    }

    private fun startShengShanBgm(s: ShengShanSession) {
        if (s.bgmStarted || session !== s) return
        s.bgmStarted = true
        playShengShanBgmOnce(s)
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (session !== s) return@Runnable
            playShengShanBgmOnce(s)
        }, SHENGSHAN_BGM_LOOP_TICKS, SHENGSHAN_BGM_LOOP_TICKS)
        s.bgmTask = task
        s.tasks += task
    }

    private fun playShengShanBgmOnce(s: ShengShanSession) {
        activePlayers(s).forEach { player ->
            stopShengShanBgm(player)
            player.playSound(player.location, SHENGSHAN_BGM, SoundCategory.RECORDS, 1.0f, 1.0f)
        }
    }

    private fun stopShengShanBgm(s: ShengShanSession) {
        s.bgmTask?.let { task ->
            task.cancel()
            s.tasks.remove(task)
        }
        s.bgmTask = null
        s.bgmStarted = false
        val players = LinkedHashMap<UUID, Player>()
        s.playerIds.mapNotNull(Bukkit::getPlayer).forEach { players[it.uniqueId] = it }
        Bukkit.getOnlinePlayers()
            .filter { it.scoreboardTags.contains(PLAYER_TAG) }
            .forEach { players[it.uniqueId] = it }
        players.values.forEach(::stopShengShanBgm)
    }

    private fun stopShengShanBgm(player: Player) {
        player.stopSound(SHENGSHAN_BGM, SoundCategory.RECORDS)
        player.stopSound(SHENGSHAN_BGM)
    }

    fun clearBossBars(player: Player) {
        session?.let { s ->
            s.current?.removePlayer(player)
            s.bars.forEach { it.removePlayer(player) }
        }
    }

    fun shutdown() {
        starting = false
        session?.let(::cleanup)
        terrain.restoreAll()
        Bukkit.getWorld(WORLD_NAME)?.let(::clearResidualWater)
        Bukkit.getWorld(WORLD_NAME)?.let(::clearResidualLava)
        Bukkit.getOnlinePlayers().forEach {
            it.removeScoreboardTag(PLAYER_TAG)
            it.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(guessSpeedKey)?.let { modifier ->
                it.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(modifier)
            }
        }
    }

    /**
     * 水眼源方块由地形日志精确还原；这里仅清扫其向外扩散、未被日志直接记录的流水。
     * 搜索限制在圣山场景边界内，并设置硬上限，避免异常地图状态导致无界遍历。
     */
    internal fun clearResidualWater(world: World) {
        val queue = ArrayDeque<Block>()
        val visited = HashSet<String>()
        WATER_EYE_BLOCKS.forEach { (x, y, z) ->
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                queue += world.getBlockAt(x + dx, y + dy, z + dz)
            }
        }
        val water = ArrayList<Block>()
        while (queue.isNotEmpty() && visited.size < MAX_RESIDUAL_WATER_BLOCKS) {
            val block = queue.removeFirst()
            if (block.x !in 3035..3210 || block.y !in 110..185 || block.z !in -1915..-1750) continue
            if (!visited.add("${block.x},${block.y},${block.z}")) continue
            if (block.type != Material.WATER && block.type != Material.BUBBLE_COLUMN) continue
            water += block
            BlockFace.entries.filter(BlockFace::isCartesian).forEach { queue += block.getRelative(it) }
        }
        water.forEach { it.type = Material.AIR }
        if (water.isNotEmpty()) {
            plugin.logger.info("已清理圣山坎水场景残留流水 ${water.size} 格。")
        }
    }

    /** 地形日志还原熔岩源方块后，清扫离火场景范围内可能自行扩散出的流动熔岩。 */
    internal fun clearResidualLava(world: World) {
        var removed = 0
        for (x in 3050..3142) {
            for (y in 115..132) {
                for (z in -1880..-1795) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type == Material.LAVA) {
                        block.type = Material.AIR
                        removed++
                    }
                }
            }
        }
        if (removed > 0) {
            plugin.logger.info("已清理圣山离火场景残留熔岩 $removed 格。")
        }
    }

    // ------------------------- Boss 控制层公用能力 -------------------------

    internal fun activePlayers(s: ShengShanSession): List<Player> = s.playerIds.mapNotNull(Bukkit::getPlayer)
        .filter { it.isOnline && !it.isDead && it.scoreboardTags.contains(PLAYER_TAG) &&
            it.gameMode != GameMode.SPECTATOR }

    internal fun isParticipant(s: ShengShanSession, player: Player): Boolean =
        player.uniqueId in s.playerIds && player.scoreboardTags.contains(PLAYER_TAG)

    internal fun broadcast(s: ShengShanSession, message: String) = activePlayers(s).forEach { it.sendMessage(message) }

    internal fun trackEntity(s: ShengShanSession, entity: Entity, kind: String): Entity {
        entity.addScoreboardTag(ENTITY_TAG)
        entity.persistentDataContainer.set(entityKindKey, PersistentDataType.STRING, kind)
        s.entityIds += entity.uniqueId
        return entity
    }

    internal fun spawnMob(s: ShengShanSession, mobId: String, location: Location, kind: String = mobId): LivingEntity? {
        val entity = MobFactory.spawnMob(plugin, location, mobId) ?: return null
        trackEntity(s, entity, kind)
        return entity
    }

    internal fun createBar(
        s: ShengShanSession,
        title: String,
        color: BarColor,
        style: BarStyle = BarStyle.SEGMENTED_10
    ): BossBar = Bukkit.createBossBar(title, color, style).also { bar ->
        activePlayers(s).forEach(bar::addPlayer)
        s.bars += bar
    }

    internal fun later(s: ShengShanSession, delay: Long, action: () -> Unit): BukkitTask {
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            s.tasks.remove(task)
            if (isCurrent(s) && !s.ending) action()
        }, delay.coerceAtLeast(0L))
        s.tasks += task
        return task
    }

    private fun laterEnding(s: ShengShanSession, delay: Long, action: () -> Unit): BukkitTask {
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            s.tasks.remove(task)
            if (session === s) action()
        }, delay.coerceAtLeast(0L))
        s.tasks += task
        return task
    }

    internal fun repeating(s: ShengShanSession, delay: Long, period: Long, action: () -> Unit): BukkitTask {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (isCurrent(s) && !s.ending) action()
        }, delay.coerceAtLeast(0L), period.coerceAtLeast(1L))
        s.tasks += task
        return task
    }

    internal fun dealPhysicalDamage(
        target: LivingEntity,
        amount: Double,
        source: LivingEntity?,
        armorPenetration: Double = 0.0,
        normalAttack: Boolean = false
    ) {
        if (!target.isValid || target.isDead || amount <= 0.0) return
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        if (armorPenetration > 0.0) {
            target.setMetadata(
                CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA,
                FixedMetadataValue(plugin, armorPenetration.coerceIn(0.0, 1.0))
            )
        }
        target.noDamageTicks = 0
        val damageAction = {
            if (source != null && source.isValid) target.damage(amount, source) else target.damage(amount)
        }
        if (normalAttack) {
            MonsterDamageClassification.withNormalAttack(plugin, target, damageAction)
        } else {
            damageAction()
        }
    }

    internal fun healBossPercent(boss: LivingEntity, ratio: Double) {
        if (!boss.isValid || boss.isDead || ratio <= 0.0) return
        val maximum = boss.getAttribute(Attribute.MAX_HEALTH)?.value ?: boss.health
        boss.health = (boss.health + maximum * ratio).coerceAtMost(maximum)
    }

    internal fun applyBlocks(group: String, changes: Map<Block, BlockData>) {
        if (changes.isEmpty()) return
        terrain.prepare(group, changes.keys)
        changes.forEach { (block, data) -> block.blockData = data }
    }

    internal fun prepareTerrain(group: String, blocks: Collection<Block>) {
        if (blocks.isNotEmpty()) terrain.prepare(group, blocks)
    }

    internal fun restoreTerrainBlocks(group: String, blocks: Collection<Block>) {
        if (blocks.isNotEmpty()) terrain.restoreBlocks(group, blocks)
    }

    internal fun restoreTerrain(group: String) = terrain.restoreGroup(group)

    internal fun groundLocation(world: World, x: Double, z: Double, startY: Int = 210, minY: Int = 120): Location {
        val bx = kotlin.math.floor(x).toInt()
        val bz = kotlin.math.floor(z).toInt()
        for (y in startY downTo minY) {
            val block = world.getBlockAt(bx, y, bz)
            if (!block.isPassable && !block.isLiquid) return Location(world, x, y + 1.0, z)
        }
        return Location(world, x, 129.0, z)
    }

    internal fun resourceId(stack: ItemStack?): String? =
        stack?.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)

    internal fun isInsideArena(location: Location): Boolean = location.world?.name == WORLD_NAME &&
        location.x in 3048.0..3198.0 && location.z in -1902.0..-1774.0 && location.y in 115.0..225.0

    private fun isCurrent(s: ShengShanSession): Boolean = session === s

    private fun actualPlayer(entity: Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        else -> null
    }

    private fun actualLivingEntity(entity: Entity): LivingEntity? = when (entity) {
        is LivingEntity -> entity
        is Projectile -> entity.shooter as? LivingEntity
        else -> null
    }

    private fun ShengShanSession.world(): World? = Bukkit.getWorld(worldName)
}

private val TRIGRAM_REVEAL_COLORS = mapOf(
    Trigram.THUNDER to 0xFFE45A,
    Trigram.SKY to 0xFFD46A,
    Trigram.WATER to 0x4BA9FF,
    Trigram.MOUNTAIN to 0xA47A51,
    Trigram.FIRE to 0xFF4C2E,
    Trigram.WIND to 0xE4F3FF,
    Trigram.SWAMP to 0x8F5CC7,
    Trigram.EARTH to 0x5D4A70
)

private val ECHO_DESCRIPTIONS = mapOf(
    Trigram.THUNDER to "下一卦象将继承震雷之势，进攻属性提高20%。",
    Trigram.SKY to "下一卦象将继承乾天之势，进攻属性提高20%。",
    Trigram.WATER to "下一卦象将继承坎水之势，最大生命提高10%。",
    Trigram.MOUNTAIN to "下一卦象将继承艮山之势，护甲提高20%。",
    Trigram.FIRE to "下一卦象将继承离火之势，造成的伤害附带20%穿甲率。",
    Trigram.WIND to "下一卦象将继承巽风之势，移动速度提高15%。",
    Trigram.SWAMP to "下一卦象命中时有50%概率施加短暂中毒。",
    Trigram.EARTH to "下一卦象命中时有50%概率施加短暂凋零。"
)

private fun Trigram.revealName(): String = when (this) {
    Trigram.THUNDER -> "震雷-雷霆震怒"
    Trigram.SKY -> "乾天-曜日凌空"
    Trigram.WATER -> "坎水-逆水归渊"
    Trigram.MOUNTAIN -> "艮山-移山填脉"
    Trigram.FIRE -> "离火-熔岩炼狱"
    Trigram.WIND -> "巽风-风蚀圣山"
    Trigram.SWAMP -> "兑泽-毒菌沼泽"
    Trigram.EARTH -> "坤地-鬼门阴土"
}
