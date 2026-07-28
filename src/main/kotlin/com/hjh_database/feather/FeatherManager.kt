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
        val activationId: Long
    )

    // 读取 ResourceID 的 Key
    private val keyResourceId = NamespacedKey(plugin, "resource_id")

    init {
        // === 在这里注册所有的羽毛 ===
        register(HumanSpeedFeather())
        register(QingyingSpeedFeather())
        register(JifengSpeedFeather())

        // 兼容热重载及旧版可能遗留的持久修饰器。
        Bukkit.getOnlinePlayers().forEach(SpeedFeather::removeSharedModifier)

        // 注册监听器
        Bukkit.getPluginManager().registerEvents(this, plugin)
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

            // 8. 按各羽毛自己的持续时间启动一次性结束任务。
            val activationId = ++nextActivationId
            val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val current = activeEffects[player.uniqueId]
                if (current?.activationId == activationId) {
                    stopEffect(player, FeatherEndReason.EXPIRED)
                }
            }, feather.durationSeconds * 20L)

            activeEffects[player.uniqueId] = ActiveEffectInfo(feather, task, activationId)
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
        }
    }

    // === 3. 退服清理逻辑 ===
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
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
    }

    fun shutdown() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (activeEffects.containsKey(player.uniqueId)) {
                stopEffect(player, FeatherEndReason.DISABLE)
            } else {
                SpeedFeather.removeSharedModifier(player)
            }
        }
        activeEffects.clear()
    }
}
