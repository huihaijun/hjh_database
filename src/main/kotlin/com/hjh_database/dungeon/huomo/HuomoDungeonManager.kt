package com.hjh_database.dungeon.huomo

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonPartyProvider
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.data.PlayerData
import com.hjh_database.listener.CombatListener
import com.hjh_database.spawner.MobFactory
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
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
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.io.File
import java.util.UUID

/** 建筑式 Boss：核心承伤，建筑施法。所有修改、判定与清理都在同一主线程时间轴。 */
class HuomoDungeonManager(internal val plugin: Hjh_database) : Listener, DungeonPartyProvider {
    companion object {
        const val PLAYER_TAG = "huomo_player"
        private const val ENTITY_TAG = "huomo_entity"
        private const val BONUS_PREFIX = "huomo::"
    }
    internal val arena = HuomoArena(plugin)
    internal var session: HuomoSession? = null
    internal val combat = HuomoCombat(this)
    private val sessionKey = NamespacedKey(plugin, "huomo_session")
    private val itemKey = NamespacedKey(plugin, "huomo_temporary")
    private val recoveryFile = File(plugin.dataFolder, "dungeon/huomo-participants.yml")
    private val recovery = linkedMapOf<UUID, String>()
    internal val disconnecting = hashSetOf<UUID>()
    private val worldName: String

    init {
        val config = File(plugin.dataFolder, "dungeon/huomo.yml")
        if (!config.exists()) plugin.saveResource("dungeon/huomo.yml", false)
        val settings = YamlConfiguration.loadConfiguration(config)
        worldName = settings.getString("world", "world")!!
        if (settings.getString("difficulty", "test") != "test") plugin.logger.warning("火魔暂仅开放 test 难度。")
        if (recoveryFile.exists()) {
            val saved = YamlConfiguration.loadConfiguration(recoveryFile)
            saved.getConfigurationSection("players")?.getKeys(false)?.forEach {
                recovery[UUID.fromString(it)] = saved.getString("players.$it", "active")!!
            }
        }
        Bukkit.getWorlds().forEach { w -> w.entities.filter(::owned).forEach(Entity::remove) }
        arena.restore()
        plugin.server.pluginManager.registerEvents(HuomoEvents(this), plugin)
    }

    override val dungeonId: String get() = "huomo"

    override fun activePartyMembers(): List<Set<UUID>> = session?.let { current ->
        listOf(active(current).mapTo(linkedSetOf()) { it.uniqueId })
    }.orEmpty()

    internal fun participant(s: HuomoSession, p: Player) = p.uniqueId in s.players && PLAYER_TAG in p.scoreboardTags
    internal fun active(s: HuomoSession) = s.players.mapNotNull(Bukkit::getPlayer).filter {
        participant(s, it) && it.isOnline && !it.isDead && it.world == s.world && it.gameMode != GameMode.SPECTATOR
    }
    internal fun owned(e: Entity) = ENTITY_TAG in e.scoreboardTags
    internal fun belongs(s: HuomoSession, e: Entity) = owned(e) && e.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == s.id
    internal fun track(s: HuomoSession, e: Entity) {
        e.addScoreboardTag(ENTITY_TAG)
        e.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, s.id)
        s.entities += e.uniqueId
        s.born[e.uniqueId] = s.tick
    }
    internal fun remove(s: HuomoSession, e: Entity) {
        s.entities.remove(e.uniqueId); s.mobs.remove(e.uniqueId); s.born.remove(e.uniqueId)
        e.remove()
    }
    internal fun spawn(s: HuomoSession, id: String, location: Location, stationary: Boolean = false): LivingEntity {
        val e = checkNotNull(MobFactory.spawnMob(plugin, location, id)) { "火魔怪物未注册：$id" }
        track(s, e)
        if (stationary) {
            (e as? Mob)?.setAI(false)
            e.setGravity(false)
            e.isGlowing = true
            e.isCollidable = false
            e.velocity = Vector()
        } else s.mobs += e.uniqueId
        return e
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun entrance(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val b = event.clickedBlock ?: return
        if (b.world.name != worldName || b.type != Material.STONE_BUTTON || b.x != 1150 || b.y != 23 || b.z != 924) return
        event.isCancelled = true
        val players = b.world.players.filter {
            !it.isDead && it.gameMode != GameMode.SPECTATOR && it.location.let { p ->
                p.x >= 1146 && p.x < 1155 && p.z >= 919 && p.z < 924 && p.y >= 23 && p.y <= 23.6
            }
        }
        fun tell(text: String) = (players + event.player).distinctBy { it.uniqueId }.forEach { it.sendMessage(text) }
        if (session != null) { tell("§c巢穴里传来了熊熊的火焰声，看来是战斗没停止，过会再来吧……"); return }
        if (players.isEmpty()) { tell("§c传送区域内没有可进入的玩家！"); return }
        if (players.size > 5) { tell("§c火魔副本至多允许§e5§c名玩家进入，当前人数超出限制！"); return }
        val previous = linkedMapOf<UUID, Int>()
        for (p in players) {
            plugin.playerManager.updateStats(p)
            val data = plugin.playerManager.getData(p.uniqueId)
            val error = when {
                data == null -> "§c玩家§e${p.name}§c的数据尚未加载，无法进入秘境！"
                p.uniqueId in recovery || PLAYER_TAG in p.scoreboardTags -> "§c玩家§e${p.name}§c尚有未处理的火魔状态，请重新登录！"
                data.lv < 40 -> "§c玩家§e${p.name}§c等级不满40级，无法进入秘境！"
                data.totalRarity < 42 -> "§c玩家§e${p.name}§c装备稀有度总和不满42点，无法进入秘境！"
                data.status == 5 -> "§c玩家§e${p.name}§c正在其他副本中，无法进入秘境！"
                else -> null
            }
            if (error != null) { tell(error); return }
            previous[p.uniqueId] = data!!.status
        }
        if (!arena.restore()) { tell("§c火魔场地正在恢复，暂时无法进入。"); return }
        // 将来仿七夕插入全队难度选择；选择完成后再调用 start。
        start(b.world, players, previous, HuomoDifficulty.TEST)
    }
    private fun start(world: World, players: List<Player>, previous: Map<UUID, Int>, difficulty: HuomoDifficulty) {
        val s = HuomoSession(world, previous, difficulty)
        session = s
        val originals = players.associate { it.uniqueId to it.location.clone() }
        try {
            for (x in (1054 shr 4)..(1122 shr 4)) for (z in (866 shr 4)..(926 shr 4)) {
                world.getChunkAt(x, z).addPluginChunkTicket(plugin); s.chunks += x to z
            }
            val entrance = Location(world, 1089.0, 15.0, 894.0, 0f, 0f)
            check(arena.safe(world, entrance)) { "火魔落点受阻" }
            check(arena.spawnCells.all { arena.safe(world, it.location(world, 1.0), .7, 2.0) }) { "刷怪点被遮挡" }
            check(arena.spawnCells.count { arena.safe(world, it.location(world, 1.0), 2.05, 2.05) } >= 2) { "缺少两个可容纳大型史莱姆的刷怪点" }
            arena.prepare(world)
            players.forEach { recovery[it.uniqueId] = "active" }
            persistRecovery()
            s.coreIndex = arena.coreCells.indices.random()
            s.core = spawn(s, "huomo_core", coreLocation(s), true)
            s.core.isInvulnerable = true
            s.healthBar = Bukkit.createBossBar("§c火焰魔王", BarColor.BLUE, BarStyle.SOLID)
            s.heatBar = Bukkit.createBossBar("§c炉温：0%", BarColor.YELLOW, BarStyle.SOLID).apply { progress = 0.0 }
            players.forEach { p ->
                p.addScoreboardTag(PLAYER_TAG)
                status(p, 5)
                check(p.teleport(entrance)) { "${p.name} 入场传送被取消" }
                p.fallDistance = 0f
                s.healthBar?.addPlayer(p); s.heatBar?.addPlayer(p)
            }
            s.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (session === s) try { tick(s) } catch (error: Exception) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "火魔运行异常，正在清理", error)
                    abort(s, "§c火魔运行异常，本次挑战结束。")
                }
            }, 1L, 1L)
        } catch (error: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "火魔入场失败", error)
            players.forEach { p ->
                p.removeScoreboardTag(PLAYER_TAG); clearBonuses(p)
                status(p, previous.getValue(p.uniqueId)); p.teleport(originals.getValue(p.uniqueId))
                recovery.remove(p.uniqueId)
                p.sendMessage("§c火魔准备失败，已取消本次入场，请联系管理员检查场地。")
            }
            persistRecovery()
            s.players.clear(); s.phase = HuomoPhase.CLEANUP
            cleanup(s)
            if (session === s && s.task == null) {
                s.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { if (session === s) cleanup(s) }, 20L, 20L)
            }
        }
    }
    internal fun coreLocation(s: HuomoSession): Location {
        val p = arena.coreCells[s.coreIndex]
        return Location(s.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), 180f, 0f)
    }
    private fun tick(s: HuomoSession) {
        s.tick++
        if (s.phase == HuomoPhase.CLEANUP) { if (s.tick % 20 == 0L) cleanup(s); return }
        if (active(s).isEmpty()) { abort(s, "§c火魔挑战失败。" ); return }
        if (s.phase == HuomoPhase.INTRO || s.phase == HuomoPhase.FIGHT) combat.shelterFeedback(s)
        when (s.phase) {
            HuomoPhase.INTRO -> {
                val lines = listOf(
                    "§7你来到巢穴最深处。地面不断传来低沉的震动，四周的火焰也像是在呼吸一般忽明忽暗。",
                    "§7前方那座燃烧着的巨大建筑忽然震动起来……",
                    "§6火焰魔王：§c嗯？又有不怕死的小东西自己送上门来了？",
                    "§6火焰魔王：§c哈哈哈！正好，我这“§4炉子§c”今天还没烧够呢！",
                    "§6火焰魔王：§c来吧！让我看看你们能在这里撑多久！")
                if ((s.tick - 1) % 60 == 0L && s.tick <= 241) {
                    say(s, lines[((s.tick - 1) / 60).toInt()])
                    sound(s, Sound.ENTITY_ENDER_DRAGON_GROWL, .6f)
                    plugin.clientBridge.cameraShake(active(s), 25, .10f)
                }
                if (s.tick % 8 == 0L) fx(s, coreLocation(s), ClientParticleShape.SPHERE, 60, 4.0)
                if (s.tick >= 301) {
                    say(s, "§6场内炉温每分钟会上涨20%，§4核心§c受伤时炉温上涨会更快！")
                    say(s, "§6炉温越高，火焰魔王释放技能的频率也就越快！伤害也会更高！")
                    s.phase = HuomoPhase.FIGHT; s.combatStart = s.tick
                    s.cooldownStart = s.tick; s.lastSpawn = s.tick
                    s.core.isInvulnerable = false
                    s.healthBar?.color = BarColor.RED
                }
            }
            HuomoPhase.FIGHT -> combat.tick(s)
            HuomoPhase.VICTORY -> {
                if (s.tick - s.phaseStart == 60L) say(s, "§6火焰魔王：§c明年最热的那一天中午……我一定会回来找你们算账的……")
                if (s.tick - s.phaseStart >= 120L) {
                    active(s).forEach { p ->
                        if (p.teleport(exit(s.world))) {
                            p.sendMessage("§e测试火魔结束。")
                            detach(s, p, 3)
                        } else { p.sendMessage("§c奖励房传送被取消，将在重新登录后重试。"); detach(s, p, 3, true) }
                    }
                    s.phase = HuomoPhase.CLEANUP; cleanup(s)
                }
            }
            else -> Unit
        }
    }
    internal fun victory(s: HuomoSession) {
        if (s.phase != HuomoPhase.FIGHT) return
        s.phase = HuomoPhase.VICTORY; s.phaseStart = s.tick
        s.players.forEach { recovery[it] = "victory" }; persistRecovery()
        combat.cancel(s)
        s.fires.clear(); s.pillars.clear()
        s.entities.mapNotNull(Bukkit::getEntity).forEach { remove(s, it) }
        s.healthBar?.progress = 0.0
        active(s).forEach(::clearBonuses)
        active(s).forEach { if (s.shelterHud.remove(it.uniqueId) != null) it.sendActionBar(net.kyori.adventure.text.Component.empty()) }
        say(s, "§6火焰魔王：§c你们这群小毛头……给我记着……")
        sound(s, Sound.ENTITY_ENDER_DRAGON_DEATH, .7f)
    }
    internal fun say(s: HuomoSession, text: String) = active(s).forEach { it.sendMessage(text) }
    private fun exit(world: World) = Location(world, 1154.30, 57.0, 891.09, 262.24f, 7.65f)
    internal fun sound(s: HuomoSession, sound: Sound, pitch: Float = 1f) = active(s).forEach {
        it.playSound(it.location, sound, SoundCategory.HOSTILE, 1f, pitch)
    }
    internal fun fx(s: HuomoSession, at: Location, shape: ClientParticleShape, count: Int = 80, radius: Double = 1.0,
                    height: Double = radius, end: Location = at, red: Boolean = false) {
        plugin.clientBridge.emitParticles(active(s), at, listOf(ClientParticleLayer(
            if (red) "minecraft:dust" else "minecraft:flame", shape, count, 0xEE2211, 1.4f, radius, height)), end)
    }
    internal fun damage(s: HuomoSession, p: Player, base: Double, penetration: Double = 0.0, furnaceScaled: Boolean = true) {
        if (s.phase != HuomoPhase.FIGHT || !participant(s, p) || p.isDead || p.world != s.world) return
        p.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        p.setMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, FixedMetadataValue(plugin, penetration))
        s.internalDamage = true
        try { p.noDamageTicks = 0; p.damage(base * (if (furnaceScaled) s.furnace.damageMultiplier else 1.0), s.core) }
        finally {
            s.internalDamage = false
            p.removeMetadata("hjh_physical_skill", plugin)
            p.removeMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, plugin)
        }
    }
    internal fun bonuses(s: HuomoSession) {
        active(s).forEach { p ->
            val data = plugin.playerManager.getData(p.uniqueId) ?: return@forEach
            var changed = false
            for ((key, enabled) in listOf("armor_multiplier" to s.furnace.armorReduced, "speed_multiplier" to s.furnace.slowed)) {
                val name = BONUS_PREFIX + key
                if (enabled) { if (data.tempBonuses.put(name, .8) != .8) changed = true }
                else if (data.tempBonuses.remove(name) != null) changed = true
            }
            if (changed) plugin.playerManager.updateStats(p)
        }
    }
    private fun clearBonuses(p: Player) {
        plugin.playerManager.getData(p.uniqueId)?.tempBonuses?.keys?.removeIf { it.startsWith(BONUS_PREFIX) }
        plugin.playerManager.updateStats(p)
        plugin.clientBridge.cameraShake(listOf(p), 0)
    }
    private fun status(p: Player, value: Int) {
        plugin.playerManager.getData(p.uniqueId)?.let { it.updateStatus(value); plugin.databaseManager.savePlayerAsync(it) }
    }
    internal fun temporary(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.has(itemKey) == true
    private fun reclaim(p: Player) {
        for (i in 0 until p.inventory.size) if (temporary(p.inventory.getItem(i))) p.inventory.setItem(i, null)
        if (temporary(p.itemOnCursor)) p.setItemOnCursor(null)
    }
    internal fun detach(s: HuomoSession, p: Player, status: Int?, keepRecovery: Boolean = false) {
        if (s.shelterHud.remove(p.uniqueId) != null) p.sendActionBar(net.kyori.adventure.text.Component.empty())
        s.players.remove(p.uniqueId)
        p.removeScoreboardTag(PLAYER_TAG)
        s.healthBar?.removePlayer(p); s.heatBar?.removePlayer(p); s.skillBar?.removePlayer(p)
        clearBonuses(p); reclaim(p)
        if (status != null) status(p, status)
        if (!keepRecovery) { recovery.remove(p.uniqueId); persistRecovery() }
    }
    internal fun fail(s: HuomoSession, p: Player, text: String) {
        if (p.uniqueId !in s.players) return
        p.sendMessage(text)
        status(p, 5)
        if (!p.isDead && p.health > 0) p.health = 0.0
        if (p.uniqueId in s.players) detach(s, p, null, p.uniqueId in disconnecting)
    }
    private fun abort(s: HuomoSession, text: String) {
        if (s.phase == HuomoPhase.CLEANUP) { cleanup(s); return }
        s.phase = HuomoPhase.CLEANUP
        s.players.mapNotNull(Bukkit::getPlayer).forEach { fail(s, it, text) }
        cleanup(s)
    }
    private fun cleanup(s: HuomoSession) {
        if (s.cleaning) return
        s.cleaning = true
        var complete = true
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                complete = false
                plugin.logger.warning("火魔清理未完成，将重试：${error.message}")
            }
        }
        try {
            attempt { combat.cancel(s) }
            listOf(s.healthBar, s.heatBar, s.skillBar).forEach { bar -> attempt { bar?.removeAll() } }
            s.fires.clear(); s.pillars.clear()
            val entities = (s.entities.mapNotNull(Bukkit::getEntity) + s.world.entities.filter { belongs(s, it) }).distinctBy { it.uniqueId }
            entities.forEach { e -> attempt { remove(s, e) } }
        } finally {
            s.cleaning = false
            if (arena.restore() && complete) {
                s.task?.cancel()
                s.chunks.forEach { (x, z) -> s.world.getChunkAt(x, z).removePluginChunkTicket(plugin) }
                if (session === s) session = null
            }
        }
    }
    private fun persistRecovery() {
        val cfg = YamlConfiguration()
        recovery.forEach { (id, value) -> cfg.set("players.$id", value) }
        atomicHuomoWrite(recoveryFile, cfg.saveToString())
    }
    fun onPlayerDataLoaded(p: Player, data: PlayerData) {
        if (session?.let { participant(it, p) } == true) return
        val result = recovery[p.uniqueId]
        if (result == null && PLAYER_TAG !in p.scoreboardTags) return
        clearBonuses(p); reclaim(p); p.removeScoreboardTag(PLAYER_TAG)
        if (result == "victory") {
            val world = Bukkit.getWorld(worldName) ?: return
            if (!p.teleport(exit(world))) return
            data.updateStatus(3); p.sendMessage("§e测试火魔结束。")
        } else {
            data.updateStatus(5)
            p.sendMessage("§c上次火魔挑战因离线或服务器重启而失败。")
            if (!p.isDead && p.health > 0) p.health = 0.0
        }
        plugin.databaseManager.savePlayerAsync(data)
        recovery.remove(p.uniqueId); persistRecovery()
    }
    fun shutdown() {
        session?.let { s ->
            if (s.phase == HuomoPhase.VICTORY) {
                s.players.mapNotNull(Bukkit::getPlayer).forEach { p ->
                    val arrived = p.teleport(exit(s.world))
                    detach(s, p, 3, !arrived)
                }
                s.phase = HuomoPhase.CLEANUP; cleanup(s)
            } else abort(s, "§c服务器关闭，火魔挑战结束。")
        }
        arena.restore()
    }
}
