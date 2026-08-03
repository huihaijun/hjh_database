package com.hjh_database.feather

import com.hjh_database.Hjh_database
import com.hjh_database.feather.impl.HumanSpeedFeather
import com.hjh_database.feather.impl.JifengSpeedFeather
import com.hjh_database.feather.impl.QingyingSpeedFeather
import com.hjh_database.feather.impl.SpeedFeather
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.*

class FeatherManager(private val plugin: Hjh_database) : Listener {

    private val feathers = HashMap<String, FeatherBase>()

    // 每名玩家只允许一个羽毛状态，从管理层和共享属性标签两层阻止不同羽毛叠加。
    private val activeEffects = HashMap<UUID, ActiveEffectInfo>()
    private var nextActivationId = 0L

    data class ActiveEffectInfo(
        val feather: FeatherBase,
        val task: BukkitTask,
        val activationId: Long,
        val expiresAt: Long
    )

    // 读取 ResourceID 的 Key
    private val keyResourceId = NamespacedKey(plugin, "resource_id")
    private val activeFeatherIdKey = NamespacedKey(plugin, "active_feather_id")
    private val activeFeatherExpiresAtKey = NamespacedKey(plugin, "active_feather_expires_at")
    private val activeFeatherBonusKey = NamespacedKey(plugin, "active_feather_bonus")

    init {
        // === 在这里注册所有的羽毛 ===
        register(HumanSpeedFeather())
        register(QingyingSpeedFeather())
        register(JifengSpeedFeather())

        // 兼容热重载及旧版可能遗留的持久修饰器。
        Bukkit.getOnlinePlayers().forEach(SpeedFeather::removeSharedModifier)

        // 注册监听器
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // /reload 时玩家不会重新触发 JoinEvent，延迟到插件完成启用后恢复在线玩家。
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach(::restorePersistedEffect)
        })
    }

    private fun register(feather: FeatherBase) {
        feathers[feather.id] = feather
    }

    /**
     * 获取手中物品的 ResourceID
     */
    private fun getResourceId(item: org.bukkit.inventory.ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(keyResourceId, PersistentDataType.STRING)
    }

    // === 1. 右键使用逻辑 ===
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (!event.action.isRightClick) return

        val player = event.player
        val item = event.item ?: return

        // 1. 获取 ID 并查找对应羽毛实现
        val resId = getResourceId(item) ?: return
        val feather = feathers[resId] ?: return // 不是羽毛，忽略

        // 2. 阻止原版动作（防止乱挥手）
        event.isCancelled = true

        // 3. 检查原版冷却 (不受冷却缩减属性影响，固定 CD)
        if (player.getCooldown(item.type) > 0) {
            return
        }

        // 4. 获取玩家数据进行资格检查
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            player.sendMessage("§c数据加载中，请稍后再试。")
            return
        }

        if (feather.canUse(player, data)) {
            // 5. 静默顶替旧效果；同种羽毛重复释放也会恢复完整初始加成。
            stopEffect(player, FeatherEndReason.REPLACED)

            // 6. 激活新效果
            feather.onStart(player)

            // 7. 设置冷却 (Ticks)
            player.setCooldown(item.type, feather.cooldownSeconds * 20)

            // 8. 使用绝对到期时间追踪，退服时间也计入原持续时间。
            val expiresAt = System.currentTimeMillis() + feather.durationSeconds * 1000L
            trackEffect(player, feather, expiresAt)
            persistEffect(player)
        }
    }

    // === 2. 受伤衰减/打断逻辑 ===
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.finalDamage <= 0.0) return

        val info = activeEffects[player.uniqueId] ?: return
        if (info.feather.onDamage(player)) {
            stopEffect(player, FeatherEndReason.DAMAGED)
        } else {
            // 轻盈/疾风之羽受伤后会降低加成，必须同步保存衰减后的数值。
            persistEffect(player)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        // 等玩家原版属性加载完成后，再恢复瞬时属性修饰器。
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (event.player.isOnline) restorePersistedEffect(event.player)
        })
    }

    // === 3. 退服保存与内存清理逻辑 ===
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        persistEffect(event.player)
        stopEffect(event.player, FeatherEndReason.QUIT)
    }

    /**
     * 停止并移除玩家当前的羽毛效果
     */
    private fun stopEffect(player: Player, reason: FeatherEndReason) {
        val info = activeEffects.remove(player.uniqueId) ?: return

        // 取消定时任务
        try {
            info.task.cancel()
        } catch (e: Exception) {}

        // 执行结束逻辑 (移除属性修饰符等)
        info.feather.onEnd(player, reason)

        // 退出和插件关闭只清理瞬时内存/属性，持久状态留待重新加入或重载后恢复。
        if (reason != FeatherEndReason.QUIT && reason != FeatherEndReason.DISABLE) {
            clearPersistedEffect(player)
        }
    }

    private fun trackEffect(player: Player, feather: FeatherBase, expiresAt: Long) {
        val activationId = ++nextActivationId
        val remainingMillis = (expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)
        val remainingTicks = ((remainingMillis + 49L) / 50L).coerceAtLeast(1L)
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val current = activeEffects[player.uniqueId]
            if (current?.activationId == activationId) {
                stopEffect(player, FeatherEndReason.EXPIRED)
            }
        }, remainingTicks)
        activeEffects[player.uniqueId] = ActiveEffectInfo(feather, task, activationId, expiresAt)
    }

    private fun persistEffect(player: Player) {
        val info = activeEffects[player.uniqueId] ?: return
        val speedFeather = info.feather as? SpeedFeather ?: return
        val bonus = speedFeather.currentBonus(player) ?: return
        player.persistentDataContainer.set(activeFeatherIdKey, PersistentDataType.STRING, info.feather.id)
        player.persistentDataContainer.set(activeFeatherExpiresAtKey, PersistentDataType.LONG, info.expiresAt)
        player.persistentDataContainer.set(activeFeatherBonusKey, PersistentDataType.DOUBLE, bonus)
    }

    private fun restorePersistedEffect(player: Player) {
        if (activeEffects.containsKey(player.uniqueId)) return
        SpeedFeather.removeSharedModifier(player)

        val pdc = player.persistentDataContainer
        val featherId = pdc.get(activeFeatherIdKey, PersistentDataType.STRING)
        val expiresAt = pdc.get(activeFeatherExpiresAtKey, PersistentDataType.LONG)
        val savedBonus = pdc.get(activeFeatherBonusKey, PersistentDataType.DOUBLE)
        val feather = featherId?.let(feathers::get) as? SpeedFeather
        if (feather == null || expiresAt == null || savedBonus == null ||
            expiresAt <= System.currentTimeMillis() || savedBonus <= 0.000001
        ) {
            clearPersistedEffect(player)
            return
        }

        feather.restoreState(player, savedBonus)
        trackEffect(player, feather, expiresAt)
    }

    private fun clearPersistedEffect(player: Player) {
        val pdc = player.persistentDataContainer
        pdc.remove(activeFeatherIdKey)
        pdc.remove(activeFeatherExpiresAtKey)
        pdc.remove(activeFeatherBonusKey)
    }

    fun shutdown() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (activeEffects.containsKey(player.uniqueId)) {
                persistEffect(player)
                stopEffect(player, FeatherEndReason.DISABLE)
            } else {
                SpeedFeather.removeSharedModifier(player)
            }
        }
        activeEffects.clear()
    }
}
