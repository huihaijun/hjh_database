package com.hjh_database.client

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.accessory.skill.core.ActiveAccessoryHudState
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffectType
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.scheduler.BukkitTask
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 画江湖 Fabric 客户端的唯一协议入口。游戏逻辑仍由服务端裁定。 */
class ClientBridge(private val plugin: Hjh_database) : Listener, PluginMessageListener {
    private val verified = ConcurrentHashMap.newKeySet<UUID>()
    private val lastHud = ConcurrentHashMap<UUID, HudSnapshot>()
    private val lastDungeonParty = ConcurrentHashMap<UUID, DungeonPartySnapshot>()
    private val lastAccessoryHud = ConcurrentHashMap<UUID, AccessoryHudSnapshot>()
    private var syncTask: BukkitTask? = null

    fun start() {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        plugin.server.pluginManager.registerEvents(this, plugin)
        syncTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { syncChangedHud() }, 2L, 2L)
        plugin.server.onlinePlayers.forEach(::scheduleClientCheck)
    }

    fun shutdown() {
        syncTask?.cancel()
        syncTask = null
        verified.clear()
        lastHud.clear()
        lastDungeonParty.clear()
        lastAccessoryHud.clear()
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }

    fun hasClient(player: Player): Boolean = verified.contains(player.uniqueId)

    /** 向同世界附近客户端只发送一次“效果语义”，粒子采样全部由客户端完成。 */
    fun emitParticle(effect: ClientParticleEffect, origin: Location, end: Location = origin, data: Int = 0) {
        val world = origin.world ?: return
        if (end.world != world) return
        val bytes = encode { out ->
            out.writeByte(PARTICLE_EFFECT)
            out.writeByte(effect.id)
            out.writeInt(data)
            out.writeDouble(origin.x); out.writeDouble(origin.y); out.writeDouble(origin.z)
            out.writeDouble(end.x); out.writeDouble(end.y); out.writeDouble(end.z)
        }
        world.players.asSequence()
            .filter { verified.contains(it.uniqueId) && it.location.distanceSquared(origin) <= PARTICLE_VIEW_DISTANCE_SQUARED }
            .forEach { it.sendPluginMessage(plugin, CHANNEL, bytes) }
    }

    /**
     * 通用数据驱动粒子入口。副本只负责组合形状和参数，客户端负责采样，新增效果不需要扩展协议枚举。
     * particle 使用原版资源 ID（例如 minecraft:electric_spark）；minecraft:dust 会读取 color/size。
     */
    fun emitParticles(origin: Location, layers: List<ClientParticleLayer>, end: Location = origin) {
        emitParticles(origin.world?.players.orEmpty(), origin, layers, end)
    }

    fun emitParticles(
        targets: Collection<Player>,
        origin: Location,
        layers: List<ClientParticleLayer>,
        end: Location = origin,
        ignoreDistance: Boolean = false
    ) {
        val world = origin.world ?: return
        if (end.world != world) return
        val safeLayers = layers.asSequence().filter { it.count > 0 }.take(MAX_GENERIC_PARTICLE_LAYERS).toList()
        if (safeLayers.isEmpty()) return
        val bytes = encode { out ->
            out.writeByte(GENERIC_PARTICLE_EFFECT)
            out.writeDouble(origin.x); out.writeDouble(origin.y); out.writeDouble(origin.z)
            out.writeDouble(end.x); out.writeDouble(end.y); out.writeDouble(end.z)
            writeParticleLayers(out, safeLayers)
        }
        targets.asSequence()
            .filter {
                it.isOnline && it.world == world && verified.contains(it.uniqueId) &&
                    (ignoreDistance || minOf(
                        it.location.distanceSquared(origin),
                        it.location.distanceSquared(end)
                    ) <= PARTICLE_VIEW_DISTANCE_SQUARED)
            }
            .forEach { it.sendPluginMessage(plugin, CHANNEL, bytes) }
    }

    /** 一次下发一个由客户端按 intervalTicks 自行刷新的通用粒子程序。 */
    fun emitTimedParticles(
        targets: Collection<Player>,
        origin: Location,
        layers: List<ClientParticleLayer>,
        end: Location = origin,
        durationTicks: Int,
        intervalTicks: Int = 2,
        key: String = "",
        ignoreDistance: Boolean = false
    ) {
        val world = origin.world ?: return
        if (end.world != world || durationTicks <= 0) return
        val safeLayers = layers.asSequence().filter { it.count > 0 }.take(MAX_GENERIC_PARTICLE_LAYERS).toList()
        if (safeLayers.isEmpty()) return
        val bytes = encode { out ->
            out.writeByte(TIMED_PARTICLE_EFFECT)
            out.writeDouble(origin.x); out.writeDouble(origin.y); out.writeDouble(origin.z)
            out.writeDouble(end.x); out.writeDouble(end.y); out.writeDouble(end.z)
            out.writeInt(durationTicks.coerceIn(1, MAX_TIMED_EFFECT_TICKS))
            out.writeShort(intervalTicks.coerceIn(1, 20))
            out.writeUTF(key.take(MAX_EFFECT_KEY_LENGTH))
            writeParticleLayers(out, safeLayers)
        }
        targets.asSequence()
            .filter {
                it.isOnline && it.world == world && verified.contains(it.uniqueId) &&
                    (ignoreDistance || minOf(
                        it.location.distanceSquared(origin),
                        it.location.distanceSquared(end)
                    ) <= PARTICLE_VIEW_DISTANCE_SQUARED)
            }
            .forEach { it.sendPluginMessage(plugin, CHANNEL, bytes) }
    }

    fun cancelTimedEffect(targets: Collection<Player>, key: String) {
        if (key.isBlank()) return
        val bytes = encode { out ->
            out.writeByte(CANCEL_TIMED_EFFECT)
            out.writeUTF(key.take(MAX_EFFECT_KEY_LENGTH))
        }
        targets.asSequence().filter { it.isOnline && verified.contains(it.uniqueId) }
            .forEach { it.sendPluginMessage(plugin, CHANNEL, bytes) }
    }

    private fun writeParticleLayers(out: DataOutputStream, layers: List<ClientParticleLayer>) {
        out.writeByte(layers.size)
        layers.forEach { layer ->
            out.writeByte(layer.shape.id)
            out.writeUTF(layer.particle.take(MAX_PARTICLE_ID_LENGTH))
            out.writeInt(layer.color and 0xFFFFFF)
            out.writeFloat(layer.size.coerceIn(0.1f, 4.0f))
            out.writeInt(layer.count.coerceIn(1, MAX_GENERIC_PARTICLE_COUNT))
            out.writeDouble(layer.radius.coerceIn(0.0, 64.0))
            out.writeDouble(layer.height.coerceIn(0.0, 64.0))
            out.writeDouble(layer.speed.coerceIn(0.0, 4.0))
            out.writeDouble(layer.offsetY.coerceIn(-64.0, 64.0))
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = scheduleClientCheck(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        verified.remove(event.player.uniqueId)
        lastHud.remove(event.player.uniqueId)
        lastDungeonParty.remove(event.player.uniqueId)
        lastAccessoryHud.remove(event.player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL || message.isEmpty()) return
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                if (input.readUnsignedByte() != HELLO) return
                val protocol = input.readInt()
                val version = input.readUTF().take(32)
                if (protocol != PROTOCOL_VERSION) {
                    player.kickPlayer("§c画江湖客户端协议不兼容。\n§7服务端协议：$PROTOCOL_VERSION，客户端协议：$protocol")
                    return
                }
                verified.add(player.uniqueId)
                plugin.logger.info("[ClientBridge] ${player.name} 已通过画江湖客户端验证（$version / 协议 $protocol）")
                send(player) { out -> out.writeByte(HELLO_ACK); out.writeInt(PROTOCOL_VERSION) }
                sendHud(player, force = true)
                sendDungeonParty(player, force = true)
                sendAccessoryHud(player, force = true)
                scheduleInitialHudRefresh(player)
            }
        }.onFailure { error ->
            plugin.logger.warning("[ClientBridge] 无法解析 ${player.name} 的握手：${error.message}")
        }
    }

    private fun scheduleClientCheck(player: Player) {
        val playerId = player.uniqueId
        verified.remove(playerId)
        lastHud.remove(playerId)
        lastDungeonParty.remove(playerId)
        lastAccessoryHud.remove(playerId)

        // 客户端最初只会在进服时主动 HELLO。插件被 PlugMan 等工具重载后，
        // 新 ClientBridge 会失去内存中的验证状态，因此必须由服务端重新发起挑战。
        HELLO_REQUEST_DELAYS.forEach { delay ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && !verified.contains(playerId)) requestHello(player)
            }, delay)
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !verified.contains(playerId)) {
                player.kickPlayer("§c本服务器必须安装《画江湖》客户端 Mod。\n§7请安装 hjh_mod 1.0.7（Minecraft 1.21.3 / Fabric）后重新进入。")
            }
        }, HANDSHAKE_TIMEOUT_TICKS)
    }

    private fun requestHello(player: Player) {
        // 玩家未安装模组或尚未声明频道时，服务端实现可能拒绝发送；超时检查会统一处理。
        runCatching {
            send(player) { out ->
                out.writeByte(HELLO_REQUEST)
                out.writeInt(PROTOCOL_VERSION)
            }
        }
    }

    private fun syncChangedHud() {
        // 同一时刻只允许一个副本，队伍快照每轮只计算一次，避免按查看者重复触发护甲修正链。
        val dungeonPartySnapshot = createDungeonPartySnapshot()
        verified.toList().forEach { uuid ->
            val player = plugin.server.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                verified.remove(uuid)
                lastHud.remove(uuid)
                lastDungeonParty.remove(uuid)
                lastAccessoryHud.remove(uuid)
            } else {
                sendHud(player, force = false)
                sendDungeonParty(player, force = false, sharedSnapshot = dungeonPartySnapshot)
                sendAccessoryHud(player, force = false)
            }
        }
    }

    private fun scheduleInitialHudRefresh(player: Player) {
        // 握手可能早于玩家数据库资料和衍生属性完成装载; 分阶段强制补发初始值。
        longArrayOf(5L, 20L, 60L, 100L).forEach { delay ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && verified.contains(player.uniqueId)) {
                    sendHud(player, force = true)
                    sendDungeonParty(player, force = true)
                    sendAccessoryHud(player, force = true)
                }
            }, delay)
        }
    }

    private fun sendHud(player: Player, force: Boolean) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val snapshot = HudSnapshot(effectiveArmor(player, data.armor), data.lingli.coerceAtLeast(0.0), data.maxLingli.coerceAtLeast(0.0))
        val previous = lastHud[player.uniqueId]
        if (!force && previous != null && snapshot.nearlyEquals(previous)) return
        lastHud[player.uniqueId] = snapshot
        send(player) { out ->
            out.writeByte(HUD_SYNC)
            out.writeDouble(snapshot.armor)
            out.writeDouble(snapshot.mana)
            out.writeDouble(snapshot.maxMana)
        }
    }

    private fun sendDungeonParty(player: Player, force: Boolean, sharedSnapshot: DungeonPartySnapshot? = null) {
        val viewerData = plugin.playerManager.getData(player.uniqueId) ?: return
        val baseSnapshot = sharedSnapshot ?: createDungeonPartySnapshot()
        val snapshot = if (viewerData.status == DUNGEON_STATUS) {
            DungeonPartySnapshot(baseSnapshot.members.sortedWith(
                compareBy<DungeonPartyMember> { it.uuid != player.uniqueId }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ))
        } else DungeonPartySnapshot(emptyList())
        val previous = lastDungeonParty[player.uniqueId]
        if (!force && previous != null && snapshot.nearlyEquals(previous)) return
        lastDungeonParty[player.uniqueId] = snapshot
        send(player) { out ->
            out.writeByte(DUNGEON_PARTY_SYNC)
            out.writeByte(snapshot.members.size)
            snapshot.members.forEach { member ->
                out.writeLong(member.uuid.mostSignificantBits)
                out.writeLong(member.uuid.leastSignificantBits)
                out.writeUTF(member.name)
                out.writeByte(member.job)
                out.writeDouble(member.health)
                out.writeDouble(member.maxHealth)
                out.writeDouble(member.armor)
                out.writeDouble(member.absorption)
                out.writeInt(member.statusFlags)
            }
        }
    }

    private fun sendAccessoryHud(player: Player, force: Boolean) {
        val snapshot = AccessoryHudSnapshot(plugin.accessorySkillManager.getActiveAccessoryHudState(player))
        val previous = lastAccessoryHud[player.uniqueId]
        if (!force && previous == snapshot) return
        lastAccessoryHud[player.uniqueId] = snapshot

        val now = System.currentTimeMillis()
        send(player) { out ->
            out.writeByte(ACCESSORY_HUD_SYNC)
            val accessory = snapshot.accessory
            out.writeBoolean(accessory != null)
            if (accessory == null) return@send

            val state = accessory.state
            out.writeUTF(accessory.accessoryId.take(64))
            out.writeUTF(accessory.materialId.take(64))
            out.writeInt(accessory.customModelData)
            out.writeLong((state.cooldownEndMillis - now).coerceAtLeast(0L))
            out.writeLong(state.cooldownDurationMillis.coerceAtLeast(0L))
            out.writeByte(state.valueKind.id)
            out.writeInt(state.currentValue)
            out.writeInt(state.maxValue)
            out.writeByte(when (state.refluxEnabled) {
                null -> -1
                false -> 0
                true -> 1
            })
            out.writeUTF(state.elementMark.orEmpty().take(16))
            out.writeLong((state.effectEndMillis - now).coerceAtLeast(0L))
            out.writeLong(state.effectDurationMillis.coerceAtLeast(0L))
        }
    }

    private fun createDungeonPartySnapshot(): DungeonPartySnapshot = DungeonPartySnapshot(
        plugin.server.onlinePlayers.asSequence()
            .mapNotNull { member ->
                val data = plugin.playerManager.getData(member.uniqueId) ?: return@mapNotNull null
                if (data.status != DUNGEON_STATUS) return@mapNotNull null
                DungeonPartyMember(
                    member.uniqueId,
                    member.name.take(MAX_PLAYER_NAME_LENGTH),
                    data.job ?: -1,
                    member.health.coerceAtLeast(0.0),
                    (member.getAttribute(Attribute.MAX_HEALTH)?.value ?: member.maxHealth).coerceAtLeast(1.0),
                    effectiveArmor(member, data.armor),
                    member.absorptionAmount.coerceAtLeast(0.0),
                    statusFlags(member)
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(MAX_DUNGEON_PARTY_SIZE)
            .toList()
    )

    /** 与天机令和实际受伤结算共用护甲事件链，确保临时破甲即时进入 HUD。 */
    private fun effectiveArmor(player: Player, baseArmor: Double): Double {
        val event = ElementCrystalArmorCalculationEvent(player, baseArmor.coerceAtLeast(0.0))
        plugin.server.pluginManager.callEvent(event)
        return event.armor.coerceAtLeast(0.0)
    }

    private fun statusFlags(player: Player): Int {
        var flags = 0
        if (player.hasPotionEffect(PotionEffectType.POISON)) flags = flags or STATUS_POISON
        if (player.hasPotionEffect(PotionEffectType.WITHER)) flags = flags or STATUS_WITHER
        if (player.hasPotionEffect(PotionEffectType.REGENERATION)) flags = flags or STATUS_REGENERATION
        if (player.hasPotionEffect(PotionEffectType.RESISTANCE)) flags = flags or STATUS_RESISTANCE
        return flags
    }

    private fun send(player: Player, writer: (DataOutputStream) -> Unit) {
        val bytes = encode(writer)
        player.sendPluginMessage(plugin, CHANNEL, bytes)
    }

    private fun encode(writer: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use(writer)
        buffer.toByteArray()
    }

    private data class HudSnapshot(val armor: Double, val mana: Double, val maxMana: Double) {
        fun nearlyEquals(other: HudSnapshot) = abs(armor - other.armor) < 0.001 &&
            abs(mana - other.mana) < 0.001 && abs(maxMana - other.maxMana) < 0.001
    }

    private data class DungeonPartyMember(
        val uuid: UUID,
        val name: String,
        val job: Int,
        val health: Double,
        val maxHealth: Double,
        val armor: Double,
        val absorption: Double,
        val statusFlags: Int
    )

    private data class DungeonPartySnapshot(val members: List<DungeonPartyMember>) {
        fun nearlyEquals(other: DungeonPartySnapshot): Boolean {
            if (members.size != other.members.size) return false
            return members.indices.all { index ->
                val current = members[index]
                val previous = other.members[index]
                current.uuid == previous.uuid && current.name == previous.name && current.job == previous.job &&
                    abs(current.health - previous.health) < 0.001 && abs(current.maxHealth - previous.maxHealth) < 0.001 &&
                    abs(current.armor - previous.armor) < 0.001 && abs(current.absorption - previous.absorption) < 0.001 &&
                    current.statusFlags == previous.statusFlags
            }
        }
    }

    private data class AccessoryHudSnapshot(val accessory: ActiveAccessoryHudState?)

    companion object {
        const val CHANNEL = "hjh_mod:main"
        const val PROTOCOL_VERSION = 5
        private const val HELLO = 1
        private const val HUD_SYNC = 2
        private const val PARTICLE_EFFECT = 3
        private const val HELLO_ACK = 4
        private const val HELLO_REQUEST = 5
        private const val GENERIC_PARTICLE_EFFECT = 6
        private const val TIMED_PARTICLE_EFFECT = 7
        private const val CANCEL_TIMED_EFFECT = 8
        private const val DUNGEON_PARTY_SYNC = 9
        private const val ACCESSORY_HUD_SYNC = 10
        private const val DUNGEON_STATUS = 5
        private const val MAX_DUNGEON_PARTY_SIZE = 16
        private const val MAX_PLAYER_NAME_LENGTH = 32
        private const val STATUS_POISON = 1
        private const val STATUS_WITHER = 1 shl 1
        private const val STATUS_REGENERATION = 1 shl 2
        private const val STATUS_RESISTANCE = 1 shl 3
        private val HELLO_REQUEST_DELAYS = longArrayOf(1L, 20L, 60L)
        private const val HANDSHAKE_TIMEOUT_TICKS = 120L
        private const val PARTICLE_VIEW_DISTANCE_SQUARED = 96.0 * 96.0
        private const val MAX_GENERIC_PARTICLE_LAYERS = 16
        private const val MAX_GENERIC_PARTICLE_COUNT = 512
        private const val MAX_PARTICLE_ID_LENGTH = 64
        private const val MAX_TIMED_EFFECT_TICKS = 20 * 180
        private const val MAX_EFFECT_KEY_LENGTH = 64
    }
}

enum class ClientParticleShape(val id: Int) {
    CLOUD(0), RING(1), LINE(2), SPHERE(3), BURST(4), FAN(5)
}

data class ClientParticleLayer(
    val particle: String,
    val shape: ClientParticleShape,
    val count: Int,
    val color: Int = 0xFFFFFF,
    val size: Float = 1.0f,
    val radius: Double = 1.0,
    val height: Double = radius,
    val speed: Double = 0.0,
    val offsetY: Double = 0.0
)

enum class ClientParticleEffect(val id: Int) {
    LINGYUN_CAST(1), LINGYUN_ANCHOR(2), LINGYUN_AMBIENT(3), LINGYUN_HELD(4),
    LINGYUN_LINE(5), LINGYUN_ENDPOINT(6), LINGYUN_TRAIL(7),
    QUESHUANG_WEAVE(20), QUESHUANG_MARK(21), QUESHUANG_ATTACH(22), QUESHUANG_AMBIENT(23), QUESHUANG_FADE(24),
    TIANHE_LANDING(40), TIANHE_ORBIT(41), TIANHE_TRAIL(42), TIANHE_LINK(43), TIANHE_EXPLOSION(44), TIANHE_FAILURE(45),
    QUEQIAO_CAST(60), QUEQIAO_CONSTRUCT(61), QUEQIAO_AMBIENT(62), QUEQIAO_ACCELERATE(63), QUEQIAO_DISMISS(64)
}
