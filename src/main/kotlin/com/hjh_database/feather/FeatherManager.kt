package com.hjh_database.feather

import com.hjh_database.Hjh_database
import com.hjh_database.feather.impl.HumanSpeedFeather
import com.hjh_database.feather.impl.JifengSpeedFeather
import com.hjh_database.feather.impl.LuoyuXingheSpeedFeather
import com.hjh_database.feather.impl.QingyingSpeedFeather
import com.hjh_database.feather.impl.SpeedFeather
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.entity.Monster
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
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
    private val activeFeatherDamageHitsKey = NamespacedKey(plugin, "active_feather_damage_hits")
    private val featherCooldownUntilKey = NamespacedKey(plugin, "feather_cooldown_until")
    private val environmentTask: BukkitTask

    init {
        // === 在这里注册所有的羽毛 ===
        register(HumanSpeedFeather(plugin))
        register(QingyingSpeedFeather(plugin))
        register(JifengSpeedFeather(plugin))
        register(LuoyuXingheSpeedFeather(plugin))

        // 兼容热重载及旧版可能遗留的持久修饰器。
        Bukkit.getOnlinePlayers().forEach(SpeedFeather::removeSharedModifier)

        // 注册监听器
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // /reload 时玩家不会重新触发 JoinEvent，延迟到插件完成启用后恢复在线玩家。
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach(::restorePersistedEffect)
        })

        // 状态可能在不换世界时改变；低频同步即可保证从大陆进入指定状态后立即套用秘境倍率。
        environmentTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for ((uuid, info) in activeEffects) {
                val player = Bukkit.getPlayer(uuid) ?: continue
                (info.feather as? SpeedFeather)?.refreshEnvironment(player)
            }
        }, 20L, 20L)
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

        // 3. 羽毛使用独立的绝对时间冷却，不读取玩家冷却缩减属性；原版冷却仅负责视觉反馈。
        val remainingCooldownTicks = remainingCooldownTicks(player)
        val displayedCooldownTicks = maxOf(remainingCooldownTicks, player.getCooldown(item.type))
        if (displayedCooldownTicks > 0) {
            val remainingSeconds = (displayedCooldownTicks + 19) / 20
            showActionBarWarning(player, "羽毛【${feather.displayName}】处于冷却中，剩余${remainingSeconds}秒")
            return
        }

        // 羽毛第一次受到有效伤害后才进入冷却。在此之前禁止重复释放或换另一种羽毛，
        // 避免玩家持续右键刷新完整持续时间、受伤衰减次数和技能表现。
        val activeInfo = activeEffects[player.uniqueId]
        val activeSpeedFeather = activeInfo?.feather as? SpeedFeather
        if (activeInfo != null && activeSpeedFeather != null && !activeSpeedFeather.hasTakenDamage(player)) {
            showActionBarWarning(
                player,
                "羽毛【${activeInfo.feather.displayName}】效果仍在持续，当前尚未进入冷却"
            )
            return
        }

        // 4. 获取玩家数据进行资格检查
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            player.sendMessage("§c数据加载中，请稍后再试。")
            return
        }

        if (feather.canUse(player, data)) {
            // 5. 已经受伤并进入过冷却的旧效果可被新羽毛正常顶替。
            stopEffect(player, FeatherEndReason.REPLACED)

            // 6. 激活新效果
            feather.onStart(player)

            // 7. 冷却延后至第一次有效受伤时启动。

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
        if (!isFeatherInterruptingDamage(event, player)) return

        val info = activeEffects[player.uniqueId] ?: return
        val speedFeather = info.feather as? SpeedFeather
        if (speedFeather != null && !speedFeather.hasTakenDamage(player)) {
            startFixedCooldown(player, info.feather.cooldownSeconds)
        }
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
            if (event.player.isOnline) {
                restoreCooldownVisual(event.player)
                restorePersistedEffect(event.player)
            }
        })
    }

    private fun isFeatherInterruptingDamage(event: EntityDamageEvent, player: Player): Boolean {
        if (player.hasMetadata(MIASMA_DAMAGE_METADATA)) return true

        // 只认本次事件中直接存在攻击实体的伤害。即使火焰最初由怪物点燃，后续
        // FIRE/FIRE_TICK 也属于环境持续伤害，不应打断羽毛。
        if (event !is EntityDamageByEntityEvent) return false
        val source = resolveDamageSource(event.damager) ?: return false
        return source is Monster || source.scoreboardTags.contains("monster")
    }

    private fun resolveDamageSource(entity: Entity): Entity? {
        if (entity is Projectile) return entity.shooter as? Entity
        return entity
    }

    private fun startFixedCooldown(player: Player, seconds: Int) {
        val expiresAt = System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1000L
        player.persistentDataContainer.set(featherCooldownUntilKey, PersistentDataType.LONG, expiresAt)
        player.setCooldown(Material.FEATHER, seconds.coerceAtLeast(0) * 20)
    }

    private fun remainingCooldownTicks(player: Player): Int {
        val pdc = player.persistentDataContainer
        val expiresAt = pdc.get(featherCooldownUntilKey, PersistentDataType.LONG) ?: return 0
        val remainingMillis = expiresAt - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            pdc.remove(featherCooldownUntilKey)
            return 0
        }
        return ((remainingMillis + 49L) / 50L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun restoreCooldownVisual(player: Player) {
        val ticks = remainingCooldownTicks(player)
        if (ticks > 0) player.setCooldown(Material.FEATHER, ticks)
    }

    private fun showActionBarWarning(player: Player, message: String) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED, TextDecoration.BOLD))
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
        player.persistentDataContainer.set(
            activeFeatherDamageHitsKey,
            PersistentDataType.INTEGER,
            speedFeather.currentDamageHits(player)
        )
    }

    private fun restorePersistedEffect(player: Player) {
        if (activeEffects.containsKey(player.uniqueId)) return
        SpeedFeather.removeSharedModifier(player)

        val pdc = player.persistentDataContainer
        val featherId = pdc.get(activeFeatherIdKey, PersistentDataType.STRING)
        val expiresAt = pdc.get(activeFeatherExpiresAtKey, PersistentDataType.LONG)
        val savedBonus = pdc.get(activeFeatherBonusKey, PersistentDataType.DOUBLE)
        val savedDamageHits = pdc.get(activeFeatherDamageHitsKey, PersistentDataType.INTEGER) ?: 0
        val feather = featherId?.let(feathers::get) as? SpeedFeather
        if (feather == null || expiresAt == null || savedBonus == null ||
            expiresAt <= System.currentTimeMillis() || savedBonus <= 0.000001
        ) {
            clearPersistedEffect(player)
            return
        }

        feather.restoreState(player, savedBonus, savedDamageHits)
        trackEffect(player, feather, expiresAt)
    }

    private fun clearPersistedEffect(player: Player) {
        val pdc = player.persistentDataContainer
        pdc.remove(activeFeatherIdKey)
        pdc.remove(activeFeatherExpiresAtKey)
        pdc.remove(activeFeatherBonusKey)
        pdc.remove(activeFeatherDamageHitsKey)
    }

    fun shutdown() {
        environmentTask.cancel()
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

    companion object {
        const val MIASMA_DAMAGE_METADATA = "HJH_MIASMA_DAMAGE"
    }
}
