package com.hjh_database.spawner

import com.hjh_database.Hjh_database
import com.hjh_database.util.ItemUtil
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.ceil

class BaihuTownFireManager(private val plugin: Hjh_database) : Listener {
    private data class FirePoint(
        val index: Int,
        val x: Int,
        val y: Int,
        val z: Int,
        var remainingSeconds: Double = 0.0,
        var elementWindowStart: Long = 0L,
        var elementCount: Int = 0,
        var miasmaCoalWindowStart: Long = 0L,
        var miasmaCoalCount: Int = 0,
        var reliveWindowStart: Long = 0L,
        var reliveCount: Int = 0
    )

    private data class Fuel(
        val seconds: Int,
        val limitType: LimitType? = null
    )

    private data class StatusDisplay(
        val fireIndex: Int,
        val expireAtSecond: Int
    )

    private enum class LimitType {
        ELEMENT,
        MIASMA_COAL,
        RELIVE_STONE
    }

    private val points = listOf(
        FirePoint(0, -577, 110, 156),
        FirePoint(1, -572, 105, 245),
        FirePoint(2, -650, 132, 107),
        FirePoint(3, -698, 120, -14),
        FirePoint(4, -724, 124, -65),
        FirePoint(5, -774, 143, -104),
        FirePoint(6, -842, 149, -79),
        FirePoint(7, -822, 159, -160),
        FirePoint(8, -877, 152, -126)
    )

    private val playersInRange = mutableMapOf<UUID, Int>()
    private val activeStatusDisplays = mutableMapOf<UUID, StatusDisplay>()
    private val limitWarningCooldowns = mutableMapOf<String, Long>()
    private var task: BukkitTask? = null
    private var elapsedSeconds = 0

    fun start() {
        initBlocks()
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    fun shutdown() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        elapsedSeconds++
        val world = world() ?: return

        points.forEach { point ->
            val center = point.center(world)
            val players = playersInside(center)
            if (point.remainingSeconds > 0.0) {
                applyBuffs(center, players)
                if (elapsedSeconds % 5 == 0) {
                    players.forEach { player ->
                        player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 5 * 20, 1, false, true, true))
                    }
                }

                val burnSpeed = 1.0 + ((players.size - 1).coerceAtLeast(0).coerceAtMost(4) * 0.25)
                point.remainingSeconds = (point.remainingSeconds - burnSpeed).coerceAtLeast(0.0)
                if (point.remainingSeconds <= 0.0) {
                    setLit(point, false)
                    center.world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.75f, 0.85f)
                }
            }

            consumeNearbyFuel(point, center)
        }

        updateRangeStatusDisplays(world)
        sendActiveStatusDisplays(world)
    }

    @EventHandler(ignoreCancelled = true)
    fun onCampfireInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val world = world() ?: return
        if (block.world != world) return
        val point = points.firstOrNull { it.x == block.x && it.y == block.y && it.z == block.z } ?: return

        event.isCancelled = true
        startStatusDisplay(event.player, point.index)
        sendActiveStatusDisplays(world)
    }

    private fun applyBuffs(center: Location, players: List<Player>) {
        players.forEach { player ->
            plugin.baihuMiasmaManager.setTemporaryIncreaseMultiplier(player, MIASMA_SOURCE, 0.0, 45L)
            if (elapsedSeconds % 2 == 0) {
                player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, player.location.clone().add(0.0, 0.35, 0.0), 2, 0.18, 0.08, 0.18, 0.01)
            }
        }
        if (players.isNotEmpty() && elapsedSeconds % 3 == 0) {
            center.world.spawnParticle(Particle.SOUL, center.clone().add(0.0, 0.9, 0.0), 8, 0.45, 0.35, 0.45, 0.01)
        }
    }

    private fun consumeNearbyFuel(point: FirePoint, center: Location) {
        val room = MAX_SECONDS - point.remainingSeconds
        if (room <= 0.01) return

        center.world.getNearbyEntities(center, FUEL_PICKUP_RADIUS, FUEL_PICKUP_RADIUS, FUEL_PICKUP_RADIUS)
            .asSequence()
            .mapNotNull { it as? Item }
            .filter { !it.isDead && it.itemStack.amount > 0 && it.location.distanceSquared(center) <= FUEL_PICKUP_RADIUS * FUEL_PICKUP_RADIUS }
            .forEach { entity ->
                val fuelId = ItemUtil.getPublicId(entity.itemStack).lowercase()
                val fuel = fuelById(fuelId) ?: return@forEach
                consumeFuel(point, center, entity, fuel, fuelId)
            }
    }

    private fun consumeFuel(point: FirePoint, center: Location, entity: Item, fuel: Fuel, fuelId: String) {
        val room = MAX_SECONDS - point.remainingSeconds
        if (room <= 0.01) return

        val categoryRemaining = categoryRemaining(point, fuel.limitType)
        if (categoryRemaining <= 0) {
            notifyFuelLimit(point, center, entity, fuel.limitType, fuelId)
            return
        }

        val maxByTime = ceil(room / fuel.seconds).toInt().coerceAtLeast(1)
        val consume = minOf(entity.itemStack.amount, categoryRemaining, maxByTime)
        if (consume <= 0) return

        val wasUnlit = point.remainingSeconds <= 0.01
        point.remainingSeconds = (point.remainingSeconds + consume * fuel.seconds).coerceAtMost(MAX_SECONDS)
        addCategoryCount(point, fuel.limitType, consume)
        shrinkItem(entity, consume)

        setLit(point, true)
        if (wasUnlit) {
            notifyLit(center)
        }
        center.world.playSound(center, Sound.BLOCK_SOUL_SAND_PLACE, 0.7f, 1.35f)
        center.world.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(0.0, 0.65, 0.0), 12, 0.35, 0.2, 0.35, 0.02)
    }

    private fun shrinkItem(entity: Item, amount: Int) {
        val stack = entity.itemStack
        stack.amount -= amount
        if (stack.amount <= 0) {
            entity.remove()
        } else {
            entity.itemStack = stack
        }
    }

    private fun categoryRemaining(point: FirePoint, type: LimitType?): Int {
        resetWindows(point)
        return when (type) {
            null -> Int.MAX_VALUE
            LimitType.ELEMENT -> (ELEMENT_LIMIT - point.elementCount).coerceAtLeast(0)
            LimitType.MIASMA_COAL -> (MIASMA_COAL_LIMIT - point.miasmaCoalCount).coerceAtLeast(0)
            LimitType.RELIVE_STONE -> (RELIVE_STONE_LIMIT - point.reliveCount).coerceAtLeast(0)
        }
    }

    private fun addCategoryCount(point: FirePoint, type: LimitType?, amount: Int) {
        resetWindows(point)
        when (type) {
            LimitType.ELEMENT -> point.elementCount += amount
            LimitType.MIASMA_COAL -> point.miasmaCoalCount += amount
            LimitType.RELIVE_STONE -> point.reliveCount += amount
            null -> return
        }
    }

    private fun resetWindows(point: FirePoint) {
        val now = System.currentTimeMillis()
        if (now - point.elementWindowStart >= LIMIT_WINDOW_MS) {
            point.elementWindowStart = now
            point.elementCount = 0
        }
        if (now - point.miasmaCoalWindowStart >= LIMIT_WINDOW_MS) {
            point.miasmaCoalWindowStart = now
            point.miasmaCoalCount = 0
        }
        if (now - point.reliveWindowStart >= LIMIT_WINDOW_MS) {
            point.reliveWindowStart = now
            point.reliveCount = 0
        }
    }

    private fun updateRangeStatusDisplays(world: org.bukkit.World) {
        val current = mutableMapOf<UUID, Int>()
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.world != world) return@forEach
            val nearest = points
                .filter { player.location.distanceSquared(it.center(world)) <= RANGE_SQUARED }
                .minByOrNull { player.location.distanceSquared(it.center(world)) }
                ?: return@forEach
            current[player.uniqueId] = nearest.index
            if (playersInRange[player.uniqueId] != nearest.index) {
                startStatusDisplay(player, nearest.index)
            }
        }
        playersInRange.clear()
        playersInRange.putAll(current)
    }

    private fun startStatusDisplay(player: Player, fireIndex: Int) {
        activeStatusDisplays[player.uniqueId] = StatusDisplay(fireIndex, elapsedSeconds + STATUS_DISPLAY_SECONDS)
    }

    private fun sendActiveStatusDisplays(world: org.bukkit.World) {
        val iterator = activeStatusDisplays.iterator()
        while (iterator.hasNext()) {
            val (uuid, display) = iterator.next()
            if (elapsedSeconds > display.expireAtSecond) {
                iterator.remove()
                continue
            }
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline || player.world != world) {
                iterator.remove()
                continue
            }
            val point = points.getOrNull(display.fireIndex)
            if (point == null) {
                iterator.remove()
                continue
            }
            sendStatusActionBar(player, point)
        }
    }

    private fun sendStatusActionBar(player: Player, point: FirePoint) {
        val state = if (point.remainingSeconds > 0.0) "燃烧" else "熄灭"
        val current = ceil(point.remainingSeconds).toInt().coerceAtLeast(0)
        sendActionBar(player, "&6【白虎镇火】 &f$state &7当前剩余时间：&b$current&7/${MAX_SECONDS.toInt()}秒")
    }

    private fun notifyLit(center: Location) {
        val recipients = playersInside(center).ifEmpty {
            nearestPlayer(center, 8.0)?.let { listOf(it) } ?: emptyList()
        }
        recipients.forEach {
            it.sendMessage(color("&a白虎篝火被点燃了，你感觉体内的瘴气得到了限制……"))
        }
    }

    private fun notifyFuelLimit(point: FirePoint, center: Location, entity: Item, type: LimitType?, fuelId: String) {
        type ?: return
        val player = nearestPlayer(entity.location, 8.0) ?: nearestPlayer(center, 8.0) ?: return
        val key = "${point.index}:${type.name}:${player.uniqueId}"
        val now = System.currentTimeMillis()
        if ((limitWarningCooldowns[key] ?: 0L) + LIMIT_WARNING_COOLDOWN_MS > now) return
        limitWarningCooldowns[key] = now

        val name = when (type) {
            LimitType.ELEMENT -> "火/土元素"
            LimitType.MIASMA_COAL -> "附满瘴气的煤炭"
            LimitType.RELIVE_STONE -> "重生石"
        }
        val limit = when (type) {
            LimitType.ELEMENT -> ELEMENT_LIMIT
            LimitType.MIASMA_COAL -> MIASMA_COAL_LIMIT
            LimitType.RELIVE_STONE -> RELIVE_STONE_LIMIT
        }
        player.sendMessage(color("&c【白虎镇火】&7此处镇火 8 分钟内可投入的 &e$name &7已达到上限 &c$limit&7。"))
    }

    private fun playersInside(center: Location): List<Player> {
        return Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.world == center.world && it.location.distanceSquared(center) <= RANGE_SQUARED }
            .toList()
    }

    private fun nearestPlayer(center: Location, radius: Double): Player? {
        val radiusSquared = radius * radius
        return Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.world == center.world && it.location.distanceSquared(center) <= radiusSquared }
            .minByOrNull { it.location.distanceSquared(center) }
    }

    private fun initBlocks() {
        val world = world() ?: return
        points.forEach { point ->
            point.remainingSeconds = 0.0
            setCampfire(point, world, false)
            val now = System.currentTimeMillis()
            point.elementWindowStart = now
            point.miasmaCoalWindowStart = now
            point.reliveWindowStart = now
        }
    }

    private fun setLit(point: FirePoint, lit: Boolean) {
        val world = world() ?: return
        setCampfire(point, world, lit)
    }

    private fun setCampfire(point: FirePoint, world: org.bukkit.World, lit: Boolean) {
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (block.type != Material.SOUL_CAMPFIRE) {
            block.setType(Material.SOUL_CAMPFIRE, false)
        }
        val data = block.blockData
        if (data is Lightable) {
            data.isLit = lit
            block.blockData = data
        }
    }

    private fun FirePoint.center(world: org.bukkit.World): Location {
        return Location(world, x + 0.5, y + 0.5, z + 0.5)
    }

    private fun world(): org.bukkit.World? {
        return Bukkit.getWorld(NamespacedKey.minecraft("overworld"))
            ?: Bukkit.getWorlds().firstOrNull { it.key.toString() == "minecraft:overworld" }
    }

    private fun fuelById(id: String): Fuel? {
        return when (id) {
            "meitan" -> Fuel(5)
            "fire", "earth" -> Fuel(3, LimitType.ELEMENT)
            "pojiupige" -> Fuel(7)
            "zhizhuyan" -> Fuel(4)
            "fumanzhangqidemeitan" -> Fuel(10, LimitType.MIASMA_COAL)
            "relive_stone" -> Fuel(30, LimitType.RELIVE_STONE)
            else -> null
        }
    }

    private fun sendActionBar(player: Player, message: String) {
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    companion object {
        private const val MAX_SECONDS = 120.0
        private const val RANGE = 5.0
        private const val RANGE_SQUARED = RANGE * RANGE
        private const val FUEL_PICKUP_RADIUS = 1.75
        private const val LIMIT_WINDOW_MS = 8 * 60 * 1000L
        private const val LIMIT_WARNING_COOLDOWN_MS = 5_000L
        private const val STATUS_DISPLAY_SECONDS = 5
        private const val ELEMENT_LIMIT = 100
        private const val MIASMA_COAL_LIMIT = 20
        private const val RELIVE_STONE_LIMIT = 5
        private const val MIASMA_SOURCE = "baihu_town_fire"
    }
}
