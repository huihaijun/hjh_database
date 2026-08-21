package com.hjh_database.race.xian

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

class XianTalentManager(private val plugin: Hjh_database) : Listener {

    data class TalentData(
        val playerId: UUID,
        var playerName: String,
        val boundWaypointIds: MutableList<String> = mutableListOf(),
        var cooldownUntil: Long = 0L
    )

    private class TeleportMenuHolder(
        val ownerId: UUID,
        val waypointBySlot: Map<Int, String>
    ) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    private data class ChannelSession(val task: BukkitTask)

    private val gson = Gson()
    private val dataByPlayer = ConcurrentHashMap<UUID, TalentData>()
    private val loadedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingLoads = ConcurrentHashMap.newKeySet<UUID>()
    private val sessionTokens = ConcurrentHashMap<UUID, AtomicLong>()
    private val channels = ConcurrentHashMap<UUID, ChannelSession>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun bind(player: Player, waypointId: String) {
        if (!canUseTalent(player, requireProof = true)) return
        val waypoint = plugin.chonghuaManager.waypoints[waypointId]
        if (waypoint == null) {
            player.sendMessage("§c[仙风道骨] §f该重华晶打卡点配置已失效。")
            return
        }
        if (!ensureLoaded(player)) return

        val data = dataByPlayer[player.uniqueId] ?: return
        if (waypointId in data.boundWaypointIds) {
            player.sendMessage("§e[仙风道骨] §f你已经与 §b${waypoint.name} §f建立过共鸣。")
            return
        }
        if (data.boundWaypointIds.size >= MAX_BINDINGS) {
            player.sendMessage("§c[仙风道骨] §f最多绑定4个地点，请在传送菜单中右键解除一个旧地点。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        data.playerName = player.name
        data.boundWaypointIds.add(waypointId)
        saveSnapshot(data, player, "保存仙族共鸣地点失败")
        player.sendMessage("§a[仙风道骨] §f已与 §b${waypoint.name} §f建立共鸣。")
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.55f)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.75f, 1.35f)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.65f, 1.6f)
        player.world.spawnParticle(
            Particle.END_ROD,
            player.location.clone().add(0.0, 1.0, 0.0),
            24,
            0.45, 0.75, 0.45, 0.04
        )
    }

    fun openMenu(player: Player) {
        if (!canUseTalent(player, requireProof = true)) return
        if (!ensureLoaded(player)) return

        val data = dataByPlayer[player.uniqueId] ?: return
        val slotMap = LinkedHashMap<Int, String>()
        val holder = TeleportMenuHolder(player.uniqueId, slotMap)
        val inventory = Bukkit.createInventory(holder, MENU_SIZE, "§0仙风道骨")
        holder.backingInventory = inventory

        val remaining = remainingCooldownSeconds(data)
        BINDING_SLOTS.forEachIndexed { index, slot ->
            val waypointId = data.boundWaypointIds.getOrNull(index)
            if (waypointId == null) {
                inventory.setItem(slot, ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
                    itemMeta = itemMeta?.apply {
                        setDisplayName("§7未绑定的共鸣位")
                        lore = listOf("§8主手持有仙族证明", "§8右键重华晶打卡点进行绑定")
                    }
                })
                return@forEachIndexed
            }

            slotMap[slot] = waypointId
            val waypoint = plugin.chonghuaManager.waypoints[waypointId]
            inventory.setItem(slot, if (waypoint == null) {
                ItemStack(Material.BARRIER).apply {
                    itemMeta = itemMeta?.apply {
                        setDisplayName("§c失效的共鸣地点")
                        lore = listOf("§7地点ID: §f$waypointId", "§c该地点已从配置中移除", "§7右键: §c解除绑定")
                    }
                }
            } else {
                ItemStack(waypoint.material).apply {
                    itemMeta = itemMeta?.apply {
                        setDisplayName("§b${waypoint.name}")
                        lore = buildList {
                            if (remaining > 0L) add("§c共享冷却剩余: ${remaining}秒")
                            else add("§a左键: 静止吟唱3秒后传送")
                            add("§7右键: §c解除绑定")
                            add("§8此冷却独立于普通重华晶")
                        }
                    }
                }
            })
        }

        inventory.setItem(22, ItemStack(if (remaining > 0L) Material.CLOCK else Material.ENDER_EYE).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(if (remaining > 0L) "§c仙力调息中" else "§a仙力充盈")
                lore = if (remaining > 0L) listOf("§7共享冷却剩余: §c${remaining}秒")
                else listOf("§7当前可以发动仙风道骨")
            }
        })
        inventory.setItem(26, ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c关闭") }
        })
        player.openInventory(inventory)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? TeleportMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.ownerId || event.rawSlot !in 0 until event.view.topInventory.size) return
        if (event.rawSlot == 26) {
            player.closeInventory()
            return
        }

        val waypointId = holder.waypointBySlot[event.rawSlot] ?: return
        if (event.isRightClick) {
            removeBinding(player, waypointId)
            openMenu(player)
            return
        }
        if (!event.isLeftClick) return

        player.closeInventory()
        plugin.server.scheduler.runTask(plugin, Runnable { startTeleport(player, waypointId) })
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is TeleportMenuHolder) return
        if (event.rawSlots.any { it < event.view.topInventory.size }) event.isCancelled = true
    }

    private fun startTeleport(player: Player, waypointId: String) {
        if (!canUseTalent(player, requireProof = true)) return
        if (!ensureLoaded(player)) return
        val data = dataByPlayer[player.uniqueId] ?: return
        if (waypointId !in data.boundWaypointIds) {
            player.sendMessage("§c[仙风道骨] §f该地点已不在你的共鸣记录中。")
            return
        }
        val waypoint = plugin.chonghuaManager.waypoints[waypointId]
        if (waypoint == null) {
            player.sendMessage("§c[仙风道骨] §f目标地点配置已失效，请在菜单中解除绑定。")
            return
        }
        val remaining = remainingCooldownSeconds(data)
        if (remaining > 0L) {
            player.sendMessage("§c[仙风道骨] §f仙力尚未恢复，还需 §c${remaining}秒§f。")
            return
        }
        if (channels.containsKey(player.uniqueId)) {
            player.sendMessage("§e[仙风道骨] §f你已经在凝聚仙力。")
            return
        }

        val start = player.location.clone()
        player.sendMessage("§e[仙风道骨] §f正在前往 §b${waypoint.name}§f，请静止吟唱3秒……")
        player.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.35f)

        val runnable = object : BukkitRunnable() {
            private var elapsedTicks = 0

            override fun run() {
                if (plugin.playerManager.getPlayerData(player)?.status != MAINLAND_STATUS) {
                    cancelChannel(player, "§c当前状态下无法使用天赋-仙风道骨")
                    return
                }
                if (!player.isOnline || !canUseTalent(player, requireProof = true, sendFailure = false)) {
                    cancelChannel(player, "§c[仙风道骨] §f吟唱已中断。")
                    return
                }
                if (player.world.uid != start.world?.uid || player.location.distanceSquared(start) > MOVE_TOLERANCE_SQUARED) {
                    cancelChannel(player, "§c[仙风道骨] §f你移动了位置，吟唱已中断。")
                    return
                }
                if (plugin.chonghuaManager.waypoints[waypointId] == null) {
                    cancelChannel(player, "§c[仙风道骨] §f目标地点已经失效，吟唱已中断。")
                    return
                }

                elapsedTicks += CHANNEL_INTERVAL_TICKS
                player.world.spawnParticle(
                    Particle.END_ROD,
                    player.location.clone().add(0.0, 1.0, 0.0),
                    6,
                    0.35, 0.55, 0.35, 0.02
                )
                if (elapsedTicks % 20 == 0) {
                    val secondsLeft = (CHANNEL_DURATION_TICKS - elapsedTicks).coerceAtLeast(0) / 20
                    if (secondsLeft > 0) player.sendActionBar("§b仙风道骨 §f吟唱中…… §e${secondsLeft}秒")
                    player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.35f + elapsedTicks / 100f)
                }
                if (elapsedTicks < CHANNEL_DURATION_TICKS) return

                val currentData = dataByPlayer[player.uniqueId]
                val currentWaypoint = plugin.chonghuaManager.waypoints[waypointId]
                if (currentData == null || currentWaypoint == null || remainingCooldownSeconds(currentData) > 0L) {
                    cancelChannel(player, "§c[仙风道骨] §f传送条件已经改变，吟唱已取消。")
                    return
                }

                channels.remove(player.uniqueId)
                cancel()
                if (!player.teleport(currentWaypoint.targetLoc.clone())) {
                    player.sendMessage("§c[仙风道骨] §f传送失败，本次不会进入冷却。")
                    return
                }

                currentData.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MILLIS
                saveSnapshot(currentData, player, "保存仙风道骨冷却失败")
                player.sendMessage("§a[仙风道骨] §f已抵达 §b${currentWaypoint.name}§f。")
                player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.25f)
                player.world.spawnParticle(Particle.PORTAL, player.location.clone().add(0.0, 1.0, 0.0), 50, 0.5, 0.9, 0.5, 0.12)
            }
        }
        val task = runnable.runTaskTimer(plugin, 0L, CHANNEL_INTERVAL_TICKS.toLong())
        channels[player.uniqueId] = ChannelSession(task)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (channels.containsKey(player.uniqueId)) {
            cancelChannel(player, "§c[仙风道骨] §f你受到了伤害，吟唱已中断。")
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        cancelChannel(event.entity, null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (channels.containsKey(event.player.uniqueId)) {
            cancelChannel(event.player, "§c[仙风道骨] §f位置发生变化，吟唱已中断。")
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (event.player.isOnline && plugin.raceModule.getRace(XIAN_RACE_ID)?.isRaceActive(event.player) == true) {
                ensureLoaded(event.player, sendMessage = false)
            }
        }, 40L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cancelChannel(event.player, null)
        unload(event.player.uniqueId)
    }

    fun resetPlayerData(playerId: UUID) {
        Bukkit.getPlayer(playerId)?.let { cancelChannel(it, null) }
        unload(playerId)
        dataByPlayer[playerId] = TalentData(playerId, Bukkit.getOfflinePlayer(playerId).name ?: "unknown")
        loadedPlayers.add(playerId)
    }

    private fun cancelChannel(player: Player, message: String?) {
        val session = channels.remove(player.uniqueId) ?: return
        session.task.cancel()
        if (message != null && player.isOnline) {
            player.sendMessage(message)
            player.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.1f)
        }
    }

    private fun removeBinding(player: Player, waypointId: String) {
        val data = dataByPlayer[player.uniqueId] ?: return
        if (!data.boundWaypointIds.remove(waypointId)) return
        saveSnapshot(data, player, "删除仙族共鸣地点失败")
        val name = plugin.chonghuaManager.waypoints[waypointId]?.name ?: waypointId
        player.sendMessage("§e[仙风道骨] §f已解除与 §b$name §f的共鸣。")
    }

    private fun canUseTalent(player: Player, requireProof: Boolean, sendFailure: Boolean = true): Boolean {
        val race = plugin.raceModule.getRace(XIAN_RACE_ID)
        if (race?.isRaceActive(player) != true) {
            if (sendFailure) player.sendMessage("§c[仙风道骨] §f你尚未满足仙族天赋的解锁条件。")
            return false
        }
        if (plugin.playerManager.getPlayerData(player)?.status != MAINLAND_STATUS) {
            if (sendFailure) player.sendMessage("§c当前状态下无法使用天赋-仙风道骨")
            return false
        }
        if (requireProof && plugin.raceModule.getResourceId(player.inventory.itemInMainHand) != PROOF_ITEM_ID) {
            if (sendFailure) player.sendMessage("§c[仙风道骨] §f请将仙族证明持于主手。")
            return false
        }
        return true
    }

    private fun remainingCooldownSeconds(data: TalentData): Long {
        val remaining = data.cooldownUntil - System.currentTimeMillis()
        return if (remaining <= 0L) 0L else ceil(remaining / 1000.0).toLong()
    }

    private fun ensureLoaded(player: Player, sendMessage: Boolean = true): Boolean {
        if (loadedPlayers.contains(player.uniqueId)) return true
        if (pendingLoads.add(player.uniqueId)) {
            val token = sessionTokens.computeIfAbsent(player.uniqueId) { AtomicLong() }.incrementAndGet()
            plugin.databaseManager.submitDatabaseOperation { load(player.uniqueId, player.name) }
                .whenComplete { loaded, error ->
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        pendingLoads.remove(player.uniqueId)
                        if (sessionTokens[player.uniqueId]?.get() != token || !player.isOnline) return@Runnable
                        if (error != null) {
                            plugin.logger.warning("加载仙族天赋数据失败: ${error.message}")
                            player.sendMessage("§c[仙风道骨] §f共鸣数据同步失败，请稍后重试。")
                            return@Runnable
                        }
                        dataByPlayer[player.uniqueId] = loaded
                        loadedPlayers.add(player.uniqueId)
                        if (sendMessage) player.sendMessage("§7[仙风道骨] §f共鸣数据已同步，请再次使用仙族证明。")
                    })
                }
        }
        if (sendMessage) player.sendMessage("§7[仙风道骨] §f正在同步共鸣数据，请稍后再试。")
        return false
    }

    private fun load(playerId: UUID, playerName: String): TalentData {
        val sql = "SELECT player_name, bound_waypoints, cooldown_until FROM player_xian_talent WHERE player_uuid = ?"
        try {
            plugin.databaseManager.dataSource?.connection?.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, playerId.toString())
                    statement.executeQuery().use { result ->
                        if (!result.next()) return TalentData(playerId, playerName)
                        val listType = object : TypeToken<List<String>>() {}.type
                        val storedIds = runCatching {
                            gson.fromJson<List<String>>(result.getString("bound_waypoints"), listType) ?: emptyList()
                        }.getOrDefault(emptyList())
                        return TalentData(
                            playerId,
                            result.getString("player_name") ?: playerName,
                            storedIds.distinct().take(MAX_BINDINGS).toMutableList(),
                            result.getLong("cooldown_until")
                        )
                    }
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("读取仙族天赋数据失败", exception)
        }
        return TalentData(playerId, playerName)
    }

    private fun saveSnapshot(data: TalentData, player: Player, errorMessage: String) {
        val snapshot = TalentData(
            data.playerId,
            player.name,
            data.boundWaypointIds.toMutableList(),
            data.cooldownUntil
        )
        plugin.databaseManager.submitDatabaseOperation { save(snapshot) }.whenComplete { _, error ->
            if (error != null) {
                plugin.logger.warning("$errorMessage: ${error.message}")
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (player.isOnline) player.sendMessage("§c[仙风道骨] §f数据保存失败，请联系管理员。")
                })
            }
        }
    }

    private fun save(data: TalentData) {
        val sql = """
            INSERT INTO player_xian_talent (player_uuid, player_name, bound_waypoints, cooldown_until, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                player_name = excluded.player_name,
                bound_waypoints = excluded.bound_waypoints,
                cooldown_until = excluded.cooldown_until,
                updated_at = excluded.updated_at
        """.trimIndent()
        plugin.databaseManager.dataSource?.connection?.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, data.playerId.toString())
                statement.setString(2, data.playerName)
                statement.setString(3, gson.toJson(data.boundWaypointIds))
                statement.setLong(4, data.cooldownUntil)
                statement.setLong(5, System.currentTimeMillis())
                statement.executeUpdate()
            }
        } ?: throw IllegalStateException("数据库未连接")
    }

    private fun unload(playerId: UUID) {
        sessionTokens.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
        pendingLoads.remove(playerId)
        loadedPlayers.remove(playerId)
        dataByPlayer.remove(playerId)
    }

    companion object {
        const val PROOF_ITEM_ID = "xian_zm_begin"
        private const val XIAN_RACE_ID = 1
        private const val MAINLAND_STATUS = 3
        private const val MAX_BINDINGS = 4
        private const val MENU_SIZE = 27
        private const val CHANNEL_DURATION_TICKS = 60
        private const val CHANNEL_INTERVAL_TICKS = 5
        private const val MOVE_TOLERANCE_SQUARED = 0.01
        private const val COOLDOWN_MILLIS = 240_000L
        private val BINDING_SLOTS = intArrayOf(10, 12, 14, 16)
    }
}
