package com.hjh_database.feather

import com.hjh_database.Hjh_database
import com.hjh_database.feather.impl.HumanSpeedFeather
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FeatherManager(private val plugin: Hjh_database) : Listener {

    private val feathers = HashMap<String, FeatherBase>()

    // 记录正在生效的羽毛效果: <玩家UUID, <羽毛ID, 任务>>
    // 这里简化设计：假设一个玩家同一时间只能激活一种羽毛状态，方便受伤时打断
    // 如果需要多羽毛共存，可以改为 Pair<FeatherBase, BukkitTask>
    private val activeEffects = ConcurrentHashMap<UUID, ActiveEffectInfo>()

    data class ActiveEffectInfo(
        val feather: FeatherBase,
        val task: BukkitTask
    )

    // 读取 ResourceID 的 Key
    private val keyResourceId = NamespacedKey(plugin, "resource_id")

    init {
        // === 在这里注册所有的羽毛 ===
        register(HumanSpeedFeather())

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
            // 5. 顶替旧效果 (如果存在)
            stopEffect(player)

            // 6. 激活新效果
            feather.onStart(player)

            // 7. 设置冷却 (Ticks)
            player.setCooldown(item.type, feather.cooldownSeconds * 20)

            // 8. 启动定时器 (10分钟后自动结束)
            val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                stopEffect(player)
            }, 10L * 60L * 20L) // 10分钟 * 60秒 * 20Tick

            activeEffects[player.uniqueId] = ActiveEffectInfo(feather, task)
        }
    }

    // === 2. 受伤打断逻辑 ===
    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity is Player) {
            // 只要受伤，如果有正在进行的效果，立即打断
            if (activeEffects.containsKey(entity.uniqueId)) {
                entity.sendMessage("§c[羽毛] 你受到了伤害，加速效果被打断了！")
                stopEffect(entity)
            }
        }
    }

    // === 3. 退服清理逻辑 ===
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        stopEffect(event.player)
    }

    /**
     * 停止并移除玩家当前的羽毛效果
     */
    fun stopEffect(player: Player) {
        val info = activeEffects.remove(player.uniqueId) ?: return

        // 取消定时任务
        try {
            info.task.cancel()
        } catch (e: Exception) {}

        // 执行结束逻辑 (移除属性修饰符等)
        info.feather.onEnd(player)
    }
}