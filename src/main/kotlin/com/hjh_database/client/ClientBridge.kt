package com.hjh_database.client

import com.hjh_database.Hjh_database
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
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

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = scheduleClientCheck(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        verified.remove(event.player.uniqueId)
        lastHud.remove(event.player.uniqueId)
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

        // 客户端最初只会在进服时主动 HELLO。插件被 PlugMan 等工具重载后，
        // 新 ClientBridge 会失去内存中的验证状态，因此必须由服务端重新发起挑战。
        HELLO_REQUEST_DELAYS.forEach { delay ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && !verified.contains(playerId)) requestHello(player)
            }, delay)
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !verified.contains(playerId)) {
                player.kickPlayer("§c本服务器必须安装《画江湖》客户端 Mod。\n§7请安装 hjh_mod 1.0.1（Minecraft 1.21.3 / Fabric）后重新进入。")
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
        verified.toList().forEach { uuid ->
            val player = plugin.server.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                verified.remove(uuid)
                lastHud.remove(uuid)
            } else {
                sendHud(player, force = false)
            }
        }
    }

    private fun scheduleInitialHudRefresh(player: Player) {
        // 握手可能早于玩家数据库资料和衍生属性完成装载; 分阶段强制补发初始值。
        longArrayOf(5L, 20L, 60L, 100L).forEach { delay ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && verified.contains(player.uniqueId)) sendHud(player, force = true)
            }, delay)
        }
    }

    private fun sendHud(player: Player, force: Boolean) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val snapshot = HudSnapshot(data.armor.coerceAtLeast(0.0), data.lingli.coerceAtLeast(0.0), data.maxLingli.coerceAtLeast(0.0))
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

    companion object {
        const val CHANNEL = "hjh_mod:main"
        const val PROTOCOL_VERSION = 1
        private const val HELLO = 1
        private const val HUD_SYNC = 2
        private const val PARTICLE_EFFECT = 3
        private const val HELLO_ACK = 4
        private const val HELLO_REQUEST = 5
        private val HELLO_REQUEST_DELAYS = longArrayOf(1L, 20L, 60L)
        private const val HANDSHAKE_TIMEOUT_TICKS = 120L
        private const val PARTICLE_VIEW_DISTANCE_SQUARED = 96.0 * 96.0
    }
}

enum class ClientParticleEffect(val id: Int) {
    LINGYUN_CAST(1), LINGYUN_ANCHOR(2), LINGYUN_AMBIENT(3), LINGYUN_HELD(4),
    LINGYUN_LINE(5), LINGYUN_ENDPOINT(6), LINGYUN_TRAIL(7),
    QUESHUANG_WEAVE(20), QUESHUANG_MARK(21), QUESHUANG_ATTACH(22), QUESHUANG_AMBIENT(23), QUESHUANG_FADE(24),
    TIANHE_LANDING(40), TIANHE_ORBIT(41), TIANHE_TRAIL(42), TIANHE_LINK(43), TIANHE_EXPLOSION(44), TIANHE_FAILURE(45),
    QUEQIAO_CAST(60), QUEQIAO_CONSTRUCT(61), QUEQIAO_AMBIENT(62), QUEQIAO_ACCELERATE(63), QUEQIAO_DISMISS(64)
}
