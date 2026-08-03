package com.hjh_database.qixiazhen.farming.manager

import com.hjh_database.Hjh_database
import com.hjh_database.qixiazhen.farming.config.FarmingConfig
import com.hjh_database.qixiazhen.farming.data.FarmBlockKey
import com.hjh_database.qixiazhen.farming.data.FarmController
import com.hjh_database.qixiazhen.farming.data.FarmPlot
import com.hjh_database.qixiazhen.farming.data.FarmLocationStore
import com.hjh_database.qixiazhen.farming.data.FarmingRepository
import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.display.FarmDisplayManager
import com.hjh_database.qixiazhen.farming.effect.FarmToolEffect
import com.hjh_database.qixiazhen.farming.effect.impl.FarmGrowthTorchEffect
import com.hjh_database.qixiazhen.farming.event.FarmDisasterEffect
import com.hjh_database.qixiazhen.farming.event.impl.FarmInsectDisasterEffect
import com.hjh_database.qixiazhen.farming.util.FarmingItems
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class FarmingManager(val plugin: Hjh_database) {
    private data class PendingCropRemoval(val plotId: Long, val expiresAt: Long)

    val config = FarmingConfig(plugin)
    private val repository = FarmingRepository(plugin)
    private val locationStore = FarmLocationStore(plugin)
    private val plots = ConcurrentHashMap<FarmBlockKey, FarmPlot>()
    private val controllers = ConcurrentHashMap<FarmBlockKey, FarmController>()
    private val playerStates = ConcurrentHashMap<UUID, MutableMap<Long, PlayerFarmState>>()
    private val playerNames = ConcurrentHashMap<UUID, String>()
    private val loading = ConcurrentHashMap<UUID, CompletableFuture<MutableMap<Long, PlayerFarmState>>>()
    private val pendingCropRemovals = ConcurrentHashMap<UUID, PendingCropRemoval>()
    private val pendingFacilityChanges = ConcurrentHashMap.newKeySet<FarmBlockKey>()
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val tools: Map<String, FarmToolEffect> = listOf(FarmGrowthTorchEffect()).associateBy { it.resourceId }
    private val disasters: List<FarmDisasterEffect> = listOf(FarmInsectDisasterEffect())
    val displays = FarmDisplayManager(plugin, this)
    private var eventTask: BukkitTask? = null

    init {
        config.load()
    }

    fun start() {
        reloadLocationsAsync()
        restartEventTask()
    }

    fun reload() {
        displays.clearAll()
        config.load()
        reloadLocationsAsync()
        restartEventTask()
    }

    private fun reloadLocationsAsync() {
        plugin.databaseManager.submitDatabaseOperation {
            locationStore.loadAndMigrate(repository)
        }.whenComplete { loaded, error ->
            runOnMain {
                if (error != null || loaded == null) {
                    plugin.logger.severe("异步加载灵田设施失败：${error?.cause?.message ?: error?.message ?: "未知错误"}")
                    return@runOnMain
                }
                plots.clear()
                loaded.first.forEach { plots[it.key] = it }
                controllers.clear()
                loaded.second.forEach { controllers[it.key] = it }
            }
        }
    }

    private fun restartEventTask() {
        eventTask?.cancel()
        val period = config.eventCheckIntervalSeconds * 20L
        eventTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::runOnlineDisasters), period, period)
    }

    fun shutdown() {
        eventTask?.cancel()
        displays.clearAll()
        saveAllOnline()
        playerStates.clear()
        playerNames.clear()
        loading.clear()
        pendingCropRemovals.clear()
        pendingFacilityChanges.clear()
    }

    fun loadAndCache(player: Player): CompletableFuture<MutableMap<Long, PlayerFarmState>> {
        playerNames[player.uniqueId] = player.name
        playerStates[player.uniqueId]?.let { return CompletableFuture.completedFuture(it) }
        return loading.computeIfAbsent(player.uniqueId) { id ->
            plugin.databaseManager.submitDatabaseOperation { repository.loadPlayer(id) }.whenComplete { data, _ ->
                runOnMain {
                    loading.remove(id)
                    if (data != null && plugin.server.getPlayer(id)?.isOnline == true) playerStates[id] = data
                }
            }
        }
    }

    fun saveAndRemove(player: Player) {
        displays.clearPlayer(player)
        pendingCropRemovals.remove(player.uniqueId)
        val states = playerStates.remove(player.uniqueId) ?: return
        playerNames.remove(player.uniqueId)
        val snapshot = states.values.map(PlayerFarmState::copy)
        submitSave { repository.saveAll(player.name, snapshot) }
    }

    fun resetPlayerData(player: Player) {
        displays.clearPlayer(player)
        pendingCropRemovals.remove(player.uniqueId)
        loading.remove(player.uniqueId)?.cancel(false)
        playerNames[player.uniqueId] = player.name
        playerStates[player.uniqueId] = mutableMapOf()
    }

    fun saveAllOnline() {
        playerStates.forEach { (id, states) ->
            val playerName = playerNames[id] ?: plugin.server.getOfflinePlayer(id).name.orEmpty()
            val snapshot = states.values.map(PlayerFarmState::copy)
            submitSave { repository.saveAll(playerName, snapshot) }
        }
    }

    fun plot(block: Block): FarmPlot? = plots[key(block.location)]
    fun controller(block: Block): FarmController? = controllers[key(block.location)]
    fun isFarmBlock(block: Block): Boolean = plot(block) != null

    fun registerPlacedBlock(player: Player, block: Block, resourceId: String): Boolean {
        if (resourceId != FIELD_RESOURCE_ID && resourceId != CONTROLLER_RESOURCE_ID) return false
        val blockKey = key(block.location)
        if (!pendingFacilityChanges.add(blockKey)) {
            player.sendMessage("§e该位置的灵田设施正在登记，请稍候。")
            return false
        }
        val worldName = block.world.name
        val playerId = player.uniqueId
        val playerName = player.name
        val expectedMaterial = block.type
        plugin.databaseManager.submitDatabaseOperation {
            when (resourceId) {
                FIELD_RESOURCE_ID -> locationStore.createPlot(blockKey, worldName, playerId)
                else -> locationStore.createController(blockKey, worldName, playerId)
            }
        }.whenComplete { created, error ->
            runOnMain {
                pendingFacilityChanges.remove(blockKey)
                val onlinePlayer = plugin.server.getPlayer(playerId)
                if (error != null || created == null) {
                    blockAt(blockKey)?.takeIf { it.type == expectedMaterial }?.setType(Material.AIR, false)
                    refundFacilityItem(onlinePlayer, blockKey, resourceId)
                    onlinePlayer?.sendMessage("§c灵田设施登记失败，物品已经退还。")
                    plugin.logger.severe("异步登记灵田设施失败 [$playerName]：${error?.cause?.message ?: error?.message ?: "数据库未返回设施记录"}")
                    return@runOnMain
                }
                when (created) {
                    is FarmPlot -> {
                        plots[created.key] = created
                        onlinePlayer?.sendMessage("§a已注册灵田田位 #${created.id}。")
                    }
                    is FarmController -> {
                        controllers[created.key] = created
                        onlinePlayer?.sendMessage("§a已注册灵田观测钟 #${created.id}。")
                    }
                }
            }
        }
        return true
    }

    fun removeRegisteredBlock(player: Player, block: Block): Boolean {
        val blockKey = key(block.location)
        val plot = plots[blockKey]
        if (plot != null) {
            if (!pendingFacilityChanges.add(blockKey)) {
                player.sendMessage("§e该灵田田位正在处理中，请稍候。")
                return false
            }
            removePlotAsync(player, blockKey, plot)
            return true
        }
        val controller = controllers[blockKey]
        if (controller != null) {
            if (!pendingFacilityChanges.add(blockKey)) {
                player.sendMessage("§e该灵田观测钟正在处理中，请稍候。")
                return false
            }
            removeControllerAsync(player, blockKey, controller)
            return true
        }
        return false
    }

    private fun removePlotAsync(player: Player, blockKey: FarmBlockKey, plot: FarmPlot) {
        val playerId = player.uniqueId
        plugin.databaseManager.submitDatabaseOperation {
            val deleted = locationStore.deletePlot(plot.id)
            if (deleted) {
                try {
                    repository.deletePlotStates(plot.id)
                } catch (ex: Exception) {
                    plugin.logger.warning("灵田田位 #${plot.id} 已从 YML 删除，但清理玩家种植记录失败：${ex.message}")
                }
            }
            deleted
        }.whenComplete { deleted, error ->
            runOnMain {
                pendingFacilityChanges.remove(blockKey)
                val onlinePlayer = plugin.server.getPlayer(playerId)
                if (error != null || deleted != true) {
                    onlinePlayer?.sendMessage("§c灵田田位删除失败，原方块保持不变。")
                    plugin.logger.severe("异步删除灵田田位 #${plot.id} 失败：${error?.cause?.message ?: error?.message ?: "未删除数据库记录"}")
                    return@runOnMain
                }
                plots.remove(blockKey)
                playerStates.values.forEach { it.remove(plot.id) }
                blockAt(blockKey)?.setType(Material.AIR, false)
                refundFacilityItem(onlinePlayer, blockKey, FIELD_RESOURCE_ID)
                onlinePlayer?.sendMessage("§a已移除灵田田位 #${plot.id}。")
            }
        }
    }

    private fun removeControllerAsync(player: Player, blockKey: FarmBlockKey, controller: FarmController) {
        val playerId = player.uniqueId
        plugin.databaseManager.submitDatabaseOperation { locationStore.deleteController(controller.id) }.whenComplete { deleted, error ->
            runOnMain {
                pendingFacilityChanges.remove(blockKey)
                val onlinePlayer = plugin.server.getPlayer(playerId)
                if (error != null || deleted != true) {
                    onlinePlayer?.sendMessage("§c灵田观测钟删除失败，原方块保持不变。")
                    plugin.logger.severe("异步删除灵田观测钟 #${controller.id} 失败：${error?.cause?.message ?: error?.message ?: "未删除数据库记录"}")
                    return@runOnMain
                }
                controllers.remove(blockKey)
                blockAt(blockKey)?.setType(Material.AIR, false)
                refundFacilityItem(onlinePlayer, blockKey, CONTROLLER_RESOURCE_ID)
                onlinePlayer?.sendMessage("§a已移除灵田观测钟 #${controller.id}。")
            }
        }
    }

    fun interactPlot(player: Player, plot: FarmPlot) {
        val states = playerStates[player.uniqueId]
        if (states == null) {
            player.sendMessage("§7灵田数据正在加载，请稍后再试。")
            loadAndCache(player)
            return
        }

        val heldId = resourceId(player.inventory.itemInMainHand)
        var state = states[plot.id]
        if (state == null) {
            if (heldId != DEED_RESOURCE_ID) {
                player.sendMessage(config.message("deed_required"))
                return
            }
            consumeMainHand(player, 1)
            state = PlayerFarmState(player.uniqueId, plot.id)
            states[plot.id] = state
            saveState(player, state)
            player.sendMessage(config.message("plot_bound"))
            showPlotDetails(player, plot, state)
            return
        }

        if (heldId == DEED_RESOURCE_ID) {
            player.sendMessage(config.message("plot_already_bound"))
            showPlotDetails(player, plot, state)
            return
        }

        val tool = tools[heldId]
        if (tool != null) {
            if (!tool.apply(player, state, this)) player.sendMessage(config.message("no_crop"))
            showPlotDetails(player, plot, state)
            return
        }

        val seed = config.seed(heldId)
        if (!state.planted && seed != null) {
            val crop = config.crop(seed.cropId)
            if (crop == null) {
                player.sendMessage("§c种子指向不存在的作物：${seed.cropId}")
                return
            }
            consumeMainHand(player, 1)
            val now = System.currentTimeMillis()
            state.cropId = seed.cropId
            state.seedResourceId = seed.resourceId
            state.plantedAt = now
            state.maturesAt = now + seed.matureSeconds * 1000L
            state.yieldMultiplier = 1.0
            saveState(player, state)
            player.sendMessage(config.message("planted").replace("{crop}", crop.name))
            player.playSound(plotLocation(plot), Sound.ITEM_CROP_PLANT, 1.0f, 1.0f)
            showPlotDetails(player, plot, state)
            return
        }

        if (state.ready()) {
            harvest(player, state)
            showPlotDetails(player, plot, state)
            return
        }

        if (state.planted && seed != null) {
            val cropName = config.crop(state.cropId)?.name ?: state.cropId.orEmpty()
            player.sendMessage(config.message("occupied").replace("{crop}", cropName))
        }
        showPlotDetails(player, plot, state)
    }

    fun confirmCropRemoval(player: Player, plot: FarmPlot) {
        val states = playerStates[player.uniqueId]
        if (states == null) {
            player.sendMessage("§7灵田数据正在加载，请稍后再试。")
            loadAndCache(player)
            return
        }
        val state = states[plot.id]
        if (state == null) {
            pendingCropRemovals.remove(player.uniqueId)
            player.sendMessage(config.message("deed_required"))
            return
        }
        if (!state.planted) {
            pendingCropRemovals.remove(player.uniqueId)
            player.sendMessage(config.message("no_crop"))
            return
        }

        val now = System.currentTimeMillis()
        val pending = pendingCropRemovals[player.uniqueId]
        val cropName = config.crop(state.cropId)?.name ?: state.cropId.orEmpty()
        if (pending?.plotId == plot.id && now <= pending.expiresAt) {
            pendingCropRemovals.remove(player.uniqueId)
            state.clearCrop()
            saveState(player, state)
            displays.clear(player)
            displays.ensureCrop(player, plot, state)
            player.playSound(plotLocation(plot), Sound.BLOCK_CROP_BREAK, 1.0f, 0.85f)
            player.sendMessage(config.message("crop_removed").replace("{crop}", cropName))
            return
        }

        pendingCropRemovals[player.uniqueId] = PendingCropRemoval(plot.id, now + CROP_REMOVAL_CONFIRM_MILLIS)
        player.sendMessage(config.message("crop_remove_confirm").replace("{crop}", cropName))
    }

    private fun harvest(player: Player, state: PlayerFarmState) {
        val seed = config.seed(state.seedResourceId) ?: return
        val crop = config.crop(state.cropId) ?: return
        val rolled = Random.nextInt(seed.harvestMin, seed.harvestMax + 1)
        val amount = (rolled * state.yieldMultiplier).toInt().coerceAtLeast(1)
        giveResource(player, crop.resourceId, amount)
        player.playSound(player.location, Sound.BLOCK_CROP_BREAK, 1.0f, 1.0f)
        state.clearCrop()
        saveState(player, state)
        player.sendMessage(config.message("harvested").replace("{crop}", crop.name).replace("{amount}", amount.toString()))
    }

    fun showController(player: Player, controller: FarmController) {
        val states = playerStates[player.uniqueId] ?: return
        val radiusSquared = config.controllerRadius * config.controllerRadius
        val entries = plots.values.asSequence()
            .filter { it.key.worldId == controller.key.worldId }
            .filter {
                val dx = (it.key.x - controller.key.x).toDouble()
                val dy = (it.key.y - controller.key.y).toDouble()
                val dz = (it.key.z - controller.key.z).toDouble()
                dx * dx + dy * dy + dz * dz <= radiusSquared
            }
            .mapNotNull { plot -> states[plot.id]?.let { plot to it } }
            .toList()
        displays.showCrops(player, entries)
        displays.showSummary(player, entries)
        if (entries.isEmpty()) player.sendMessage("§7附近没有你已绑定的灵田。")
    }

    fun saveState(player: Player, state: PlayerFarmState) {
        val snapshot = state.copy()
        submitSave { repository.saveState(player.name, snapshot) }
    }

    fun displayEntries(player: Player): List<Pair<FarmPlot, PlayerFarmState>> {
        val states = playerStates[player.uniqueId] ?: return emptyList()
        val byId = plots.values.associateBy { it.id }
        return states.values.mapNotNull { state -> byId[state.plotId]?.let { it to state } }
    }

    private fun showPlotDetails(player: Player, plot: FarmPlot, state: PlayerFarmState) {
        displays.ensureCrop(player, plot, state)
        displays.showDetailed(player, plot, state)
    }

    fun consumeMainHand(player: Player, amount: Int) {
        val item = player.inventory.itemInMainHand
        item.amount -= amount
        player.inventory.setItemInMainHand(if (item.amount > 0) item else ItemStack(Material.AIR))
    }

    fun resourceId(item: ItemStack?): String? = item?.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)

    private fun giveResource(player: Player, id: String, amount: Int) {
        FarmingItems.giveResource(plugin, player, id, amount)
    }

    private fun runOnlineDisasters() {
        for (player in plugin.server.onlinePlayers) {
            val states = playerStates[player.uniqueId] ?: continue
            for (state in states.values.filter { it.planted && !it.ready() }) {
                val effect = disasters.firstOrNull { it.shouldTrigger() } ?: continue
                val presentation = config.event(effect.eventId) ?: continue
                effect.apply(state)
                saveState(player, state)
                val cropName = config.crop(state.cropId)?.name ?: state.cropId.orEmpty()
                player.sendMessage(presentation.message.replace("{XX作物}", cropName).replace("{crop}", cropName))
            }
        }
    }

    fun handleAdminCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", true)) {
            sender.sendMessage("§6=== /hjhadmin farm ===")
            sender.sendMessage("§e/hjhadmin farm get <field|bell|deed> [数量]")
            sender.sendMessage("§e/hjhadmin farm get seed <种子ID> [数量]")
            sender.sendMessage("§e/hjhadmin farm get crop <作物ResourceID> [数量]")
            sender.sendMessage("§e/hjhadmin farm get tool <道具ID> [数量]")
            sender.sendMessage("§e/hjhadmin farm info §7- 查看准星所指田位/钟")
            sender.sendMessage("§e/hjhadmin farm reload")
            sender.sendMessage("§e/hjhadmin farm player <玩家> reset")
            return true
        }
        return when (args[0].lowercase()) {
            "get" -> {
                val player = sender as? Player ?: return adminError(sender, "只有玩家可以获得物品。")
                val type = args.getOrNull(1)?.lowercase()
                val dynamic = type == "seed" || type == "crop" || type == "tool"
                val requestedId = if (dynamic) args.getOrNull(2) else null
                val id = when (type) {
                    "field" -> FIELD_RESOURCE_ID
                    "bell" -> CONTROLLER_RESOURCE_ID
                    "deed" -> DEED_RESOURCE_ID
                    "seed" -> resolveId(requestedId, config.allSeedIds(), "farm_seed_")
                    "crop" -> resolveId(requestedId, config.allCropResourceIds(), "farm_crop_")
                    "tool" -> resolveId(requestedId, tools.keys, "farm_")
                    else -> null
                } ?: return adminError(sender, "物品类型或 ID 无效，使用 /hjhadmin farm help 查看用法。")
                val amountIndex = if (dynamic) 3 else 2
                val amount = args.getOrNull(amountIndex)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                val item = plugin.resourceManager.getItem(id)
                if (item == null) {
                    return adminError(sender, "Resource 物品不存在：$id")
                }
                giveResource(player, id, amount)
                sender.sendMessage("§a已获得 $id x$amount。")
                true
            }
            "info" -> {
                val player = sender as? Player ?: return adminError(sender, "只有玩家可以查看方块。")
                val block = player.getTargetBlockExact(8) ?: return adminError(sender, "未指向有效方块。")
                val plot = plot(block)
                val controller = controller(block)
                when {
                    plot != null -> sender.sendMessage("§a灵田田位 #${plot.id}，绑定玩家数：${playerStates.values.count { plot.id in it }}")
                    controller != null -> sender.sendMessage("§a灵田观测钟 #${controller.id}")
                    else -> sender.sendMessage("§7该方块不是已登记的灵田设施。")
                }
                true
            }
            "reload" -> {
                plugin.resourceManager.reload()
                reload()
                sender.sendMessage(config.message("reloaded"))
                true
            }
            "player" -> {
                if (!args.getOrNull(2).equals("reset", true)) return adminError(sender, "用法: /hjhadmin farm player <玩家> reset")
                val name = args.getOrNull(1) ?: return adminError(sender, "请提供玩家名。")
                val target = plugin.server.getOfflinePlayer(name)
                val targetId = target.uniqueId
                plugin.databaseManager.submitDatabaseOperation { repository.resetPlayer(targetId) }.whenComplete { success, error ->
                    runOnMain {
                        if (error != null || success != true) {
                            sender.sendMessage("§c清空玩家 $name 的灵田数据失败。")
                            plugin.logger.severe("异步清空玩家 $name 的灵田数据失败：${error?.cause?.message ?: error?.message ?: "数据库操作失败"}")
                        } else {
                            playerStates[targetId]?.clear()
                            sender.sendMessage("§a已清空玩家 $name 的全部灵田绑定和种植数据。")
                        }
                    }
                }
                true
            }
            else -> adminError(sender, "未知 farm 子指令，使用 /hjhadmin farm help。")
        }
    }

    fun tabComplete(args: List<String>): List<String> {
        if (args.size <= 1) return listOf("help", "get", "info", "reload", "player").filter { it.startsWith(args.getOrElse(0) { "" }.lowercase()) }
        return when (args[0].lowercase()) {
            "get" -> when {
                args.size == 2 -> listOf("field", "bell", "deed", "seed", "crop", "tool").filter { it.startsWith(args[1].lowercase()) }
                args.size == 3 && args[1].equals("seed", true) -> config.allSeedIds().filter { it.startsWith(args[2], true) }
                args.size == 3 && args[1].equals("crop", true) -> config.allCropResourceIds().filter { it.startsWith(args[2], true) }
                args.size == 3 && args[1].equals("tool", true) -> tools.keys.filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            "player" -> when (args.size) {
                2 -> plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[1], true) }
                3 -> listOf("reset").filter { it.startsWith(args[2].lowercase()) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun adminError(sender: CommandSender, message: String): Boolean {
        sender.sendMessage("§c$message")
        return true
    }

    private fun resolveId(input: String?, available: Collection<String>, prefix: String): String? {
        if (input.isNullOrBlank()) return null
        return available.firstOrNull { it.equals(input, true) }
            ?: available.firstOrNull { it.removePrefix(prefix).equals(input, true) }
    }

    private fun plotLocation(plot: FarmPlot): Location {
        val world = plugin.server.getWorld(plot.key.worldId) ?: return plugin.server.worlds.first().spawnLocation
        return Location(world, plot.key.x + 0.5, plot.key.y + 1.0, plot.key.z + 0.5)
    }

    private fun submitSave(block: () -> Unit) {
        plugin.databaseManager.submitDatabaseOperation(block).whenComplete { _, error ->
            if (error != null) plugin.logger.severe("异步保存灵田数据失败：${error.cause?.message ?: error.message}")
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun blockAt(key: FarmBlockKey): Block? {
        val world = plugin.server.getWorld(key.worldId) ?: return null
        return world.getBlockAt(key.x, key.y, key.z)
    }

    private fun refundFacilityItem(player: Player?, key: FarmBlockKey, resourceId: String) {
        if (player != null && player.isOnline) {
            giveResource(player, resourceId, 1)
            return
        }
        val block = blockAt(key) ?: return
        val item = plugin.resourceManager.getItem(resourceId)?.clone() ?: return
        block.world.dropItemNaturally(block.location.add(0.5, 0.7, 0.5), item)
    }

    private fun key(location: Location): FarmBlockKey = FarmBlockKey(
        location.world!!.uid,
        location.blockX,
        location.blockY,
        location.blockZ
    )

    companion object {
        private const val CROP_REMOVAL_CONFIRM_MILLIS = 3_000L
        const val FIELD_RESOURCE_ID = "farm_field"
        const val CONTROLLER_RESOURCE_ID = "farm_controller_bell"
        const val DEED_RESOURCE_ID = "farm_plot_deed"
    }
}
