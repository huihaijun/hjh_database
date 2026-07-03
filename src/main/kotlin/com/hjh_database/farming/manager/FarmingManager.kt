package com.hjh_database.farming.manager

import com.hjh_database.Hjh_database
import com.hjh_database.farming.config.FarmingConfig
import com.hjh_database.farming.data.FarmingField
import com.hjh_database.farming.data.FarmingPlayerData
import com.hjh_database.farming.data.FarmingRepository
import com.hjh_database.farming.data.PlantType
import com.hjh_database.farming.gui.FarmingGui
import com.hjh_database.farming.util.FarmingItems
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FarmingManager(val plugin: Hjh_database) {
    val config = FarmingConfig(plugin)
    private val repository: FarmingRepository
    private val cache = ConcurrentHashMap<UUID, FarmingPlayerData>()
    private val loading = ConcurrentHashMap<UUID, CompletableFuture<FarmingPlayerData>>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hjh-farming-writer").apply { isDaemon = true }
    }

    val gui: FarmingGui
    private var eventTask: BukkitTask? = null
    private var autosaveTask: BukkitTask? = null
    private val onlineSeconds = ConcurrentHashMap<UUID, Long>()

    init {
        config.load()
        repository = FarmingRepository(plugin, config)
        gui = FarmingGui(this)
    }

    fun start() {
        startEventTask()
    }

    private fun startEventTask() {
        eventTask?.cancel()
        autosaveTask?.cancel()
        eventTask = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { runRandomEvents() },
            config.eventCheckInterval * 20L,
            config.eventCheckInterval * 20L
        )
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { player ->
                onlineSeconds.merge(player.uniqueId, 60L, Long::plus)
            }
        }, 1200L, 1200L)
    }

    fun reload() {
        config.load()
        eventTask?.cancel()
        eventTask = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { runRandomEvents() },
            config.eventCheckInterval * 20L,
            config.eventCheckInterval * 20L
        )
    }

    fun shutdown() {
        eventTask?.cancel()
        autosaveTask?.cancel()
        saveAllOnline()
        writer.shutdown()
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            writer.shutdownNow()
        }
        cache.clear()
        loading.clear()
        onlineSeconds.clear()
    }

    fun loadAndCache(player: Player): CompletableFuture<FarmingPlayerData> {
        val existing = cache[player.uniqueId]
        if (existing != null) return CompletableFuture.completedFuture(existing)
        return loading.computeIfAbsent(player.uniqueId) { uuid ->
            CompletableFuture.supplyAsync {
                repository.load(uuid.toString(), player.name)
            }.whenComplete { data, _ ->
                loading.remove(uuid)
                if (data != null && player.isOnline) {
                    cache[uuid] = data
                }
            }
        }
    }

    fun saveAndRemove(player: Player) {
        val data = cache.remove(player.uniqueId) ?: return
        onlineSeconds.remove(player.uniqueId)
        submitSave { repository.saveAll(data) }
    }

    fun saveAllOnline() {
        cache.values.forEach { data ->
            repository.saveAll(data)
        }
    }

    fun openMain(player: Player) {
        val data = cache[player.uniqueId]
        if (data != null) {
            gui.openMain(player, data)
            return
        }

        player.sendMessage("§7灵田数据正在加载...")
        loadAndCache(player).thenAccept { loaded ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) {
                    gui.openMain(player, loaded)
                }
            })
        }
    }

    fun cached(player: Player): FarmingPlayerData? = cache[player.uniqueId]

    fun unlockField(player: Player, data: FarmingPlayerData, field: FarmingField): Boolean {
        if (field.unlocked) {
            player.sendMessage(FarmingItems.color(config.message("field_already_unlocked")))
            return false
        }
        if (data.fields.count { it.unlocked } >= config.maxTotalFields || data.fields.count { it.unlocked } >= data.maxFields) {
            player.sendMessage("§c可开辟的灵田数量已达上限。")
            return false
        }

        val cost = config.unlockCost(field.index)
        if (!FarmingItems.consumeResource(plugin, player, config.unlockItemId, cost)) {
            player.sendMessage(FarmingItems.color(config.message("not_enough_unlock_items", mapOf("cost" to cost.toString(), "item" to FarmingItems.resourceDisplayName(plugin, config.unlockItemId)))))
            return false
        }

        field.unlocked = true
        data.maxFields = (data.maxFields + 1).coerceAtMost(config.maxTotalFields)
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("field_unlocked", mapOf("index" to (field.index + 1).toString()))))
        return true
    }

    fun plant(player: Player, data: FarmingPlayerData, field: FarmingField, plant: PlantType): Boolean {
        if (!field.unlocked) {
            player.sendMessage(FarmingItems.color(config.message("field_locked")))
            return false
        }
        if (field.planted) {
            player.sendMessage(FarmingItems.color(config.message("field_occupied", mapOf("plant" to (config.plant(field.plantType)?.name ?: field.plantType.orEmpty())))))
            return false
        }
        if (!FarmingItems.consumeResource(plugin, player, plant.seedItemId, 1)) {
            player.sendMessage(FarmingItems.color(config.message("not_enough_seeds")))
            return false
        }

        val now = System.currentTimeMillis()
        field.plantType = plant.id
        field.plantedAt = now
        field.maturesAt = now + plant.totalGrowthMs
        field.growthStage = 0
        field.acceleratorId = null
        field.boosterId = null
        field.yieldPenalty = 0.0
        field.delayPenaltyMs = 0L
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("plant_success", mapOf("plant" to plant.name))))
        return true
    }

    fun harvest(player: Player, data: FarmingPlayerData, field: FarmingField): Boolean {
        if (!field.planted) {
            player.sendMessage(FarmingItems.color(config.message("field_empty")))
            return false
        }
        val plant = config.plant(field.plantType) ?: return false
        if (!field.ready) {
            val progress = progressPercent(field)
            player.sendMessage(FarmingItems.color(config.message("field_not_ready", mapOf("progress" to progress.toString()))))
            player.sendMessage("§7剩余成熟时间：§e${FarmingItems.formatDuration(field.remainingMs())}")
            return false
        }

        var amount = Random.nextInt(plant.harvestMin, plant.harvestMax + 1)
        if (field.yieldPenalty > 0.0) {
            amount = maxOf(1, (amount * (1.0 - field.yieldPenalty)).toInt())
        }
        val booster = config.booster(field.boosterId)
        if (booster != null) {
            amount = maxOf(1, (amount * booster.yieldMultiplier).toInt())
        }

        if (!FarmingItems.giveResource(plugin, player, plant.harvestItemId, amount)) {
            player.sendMessage("§c采收物品配置不存在：${plant.harvestItemId}")
            return false
        }
        player.giveExp(plant.expReward)

        field.resetPlanting()
        data.totalHarvests += 1
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("harvest_success", mapOf("amount" to amount.toString(), "item" to FarmingItems.resourceDisplayName(plugin, plant.harvestItemId)))))
        return true
    }

    fun destroyPlanting(data: FarmingPlayerData, field: FarmingField) {
        field.resetPlanting()
        saveFieldAsync(data, field)
    }

    fun applyFirstAccelerator(player: Player, data: FarmingPlayerData, field: FarmingField): Boolean {
        val accelerator = config.allAccelerators().firstOrNull {
            FarmingItems.countResource(plugin, player, it.itemId) > 0
        } ?: run {
            player.sendMessage("§c背包中无催生灵液。")
            return false
        }
        if (!FarmingItems.consumeResource(plugin, player, accelerator.itemId, 1)) return false
        field.acceleratorId = accelerator.id
        val now = System.currentTimeMillis()
        if (field.planted && field.maturesAt > now) {
            val remaining = field.maturesAt - now
            field.maturesAt = now + (remaining / accelerator.speedMultiplier).toLong().coerceAtLeast(1000L)
        }
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("accelerator_applied", mapOf("accelerator" to FarmingItems.resourceDisplayName(plugin, accelerator.itemId)))))
        return true
    }

    fun applyFirstBooster(player: Player, data: FarmingPlayerData, field: FarmingField): Boolean {
        val booster = config.allBoosters().firstOrNull {
            FarmingItems.countResource(plugin, player, it.itemId) > 0
        } ?: run {
            player.sendMessage("§c背包中无丰饶宝箓。")
            return false
        }
        if (!FarmingItems.consumeResource(plugin, player, booster.itemId, 1)) return false
        field.boosterId = booster.id
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("booster_applied", mapOf("booster" to FarmingItems.resourceDisplayName(plugin, booster.itemId)))))
        return true
    }

    fun buyAndApplyProtection(player: Player, data: FarmingPlayerData, field: FarmingField, protectionId: String): Boolean {
        val protection = config.protection(protectionId) ?: return false
        if (!FarmingItems.consumeResource(plugin, player, "jinyuanbao", protection.price)) {
            val have = FarmingItems.countResource(plugin, player, "jinyuanbao")
            player.sendMessage("§c金元宝不足！需要 §e${protection.price} §c个，当前：§e$have")
            return false
        }
        field.protectionId = protection.id
        field.protectionUntil = System.currentTimeMillis() + protection.durationSeconds * 1000L
        saveFieldAsync(data, field)
        player.sendMessage(FarmingItems.color(config.message("protection_applied", mapOf("protection" to FarmingItems.resourceDisplayName(plugin, protection.itemId)))))
        return true
    }

    fun matchSeed(player: Player): List<PlantType> {
        return config.allPlants().filter { FarmingItems.countResource(plugin, player, it.seedItemId) > 0 }
    }

    fun progressPercent(field: FarmingField): Int {
        if (!field.planted || field.plantedAt <= 0L || field.maturesAt <= field.plantedAt) return 0
        val now = System.currentTimeMillis()
        return (((now - field.plantedAt).toDouble() / (field.maturesAt - field.plantedAt).toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun saveFieldAsync(data: FarmingPlayerData, field: FarmingField) {
        submitSave { repository.saveField(data, field) }
    }

    private fun submitSave(block: () -> Unit) {
        writer.execute(block)
    }

    private fun runRandomEvents() {
        val events = config.allEvents().toList()
        if (events.isEmpty()) return

        for (player in Bukkit.getOnlinePlayers()) {
            val data = cache[player.uniqueId] ?: continue
            if ((onlineSeconds[player.uniqueId] ?: 0L) < config.eventMinOnlineTime) continue

            for (field in data.fields.filter { it.planted }) {
                val event = events.firstOrNull { event ->
                    val affects = event.affectedPlants
                    (affects.isEmpty() || affects.contains(field.plantType)) && Random.nextDouble() <= event.chance
                } ?: continue

                val protection = config.protection(field.protectionId)
                if (protection != null && System.currentTimeMillis() < field.protectionUntil && protection.against.contains(event.id)) {
                    if (config.eventNotification) {
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            player.sendMessage(FarmingItems.color(config.message("protected", mapOf("protection" to FarmingItems.resourceDisplayName(plugin, protection.itemId)))))
                        })
                    }
                    continue
                }

                val plant = config.plant(field.plantType)
                when (event.damageType.lowercase()) {
                    "yield" -> field.yieldPenalty = (field.yieldPenalty + event.damagePercent).coerceAtMost(0.95)
                    "delay" -> {
                        val delay = ((plant?.totalGrowthMs ?: 0L) * event.damagePercent).toLong()
                        field.delayPenaltyMs += delay
                        field.maturesAt += delay
                    }
                    "destroy" -> {
                        if (Random.nextDouble() <= event.damagePercent) {
                            field.resetPlanting()
                        }
                    }
                }
                saveFieldAsync(data, field)
                if (config.eventNotification) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        player.sendMessage(FarmingItems.color(event.message
                            .replace("{player}", player.name)
                            .replace("{plant}", plant?.name ?: field.plantType.orEmpty())))
                        player.sendMessage(FarmingItems.color(config.message("event_warning")))
                    })
                }
                break
            }
        }
    }
}
