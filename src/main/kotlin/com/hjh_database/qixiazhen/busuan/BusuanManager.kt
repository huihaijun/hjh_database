package com.hjh_database.qixiazhen.busuan

import com.hjh_database.Hjh_database
import com.hjh_database.qixiazhen.busuan.data.BusuanRepository
import com.hjh_database.qixiazhen.busuan.model.Fortune
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Piston
import org.bukkit.block.data.type.PistonHead
import org.bukkit.block.data.type.TechnicalPiston
import org.bukkit.command.CommandSender
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class BusuanManager(private val plugin: Hjh_database) {
    private val repository = BusuanRepository(plugin)
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val requestDateKey = NamespacedKey(plugin, "busuan_request_date")
    private val ownerKey = NamespacedKey(plugin, "busuan_owner")
    private val fortuneDateKey = NamespacedKey(plugin, "busuan_fortune_date")
    private val zone = ZoneId.of("Asia/Shanghai")

    private var seekingPlayer: UUID? = null
    private var seekTask: BukkitTask? = null
    private var seekDisplay: ItemDisplay? = null
    private var originalSeekPistonData: BlockData? = null
    private var originalSeekTableData: BlockData? = null
    private var originalSeekAboveData: BlockData? = null
    private var interpretingPlayer: UUID? = null
    private var potTask: BukkitTask? = null
    private var potWobbleWarningLogged = false

    init {
        // 修复服务器在仪式或插件异常中断后可能遗留的活塞头、悬空卜签台或缺失卜签台。
        plugin.server.scheduler.runTask(plugin, Runnable { normalizeSeekingStation() })
    }

    fun isSeekingBlock(block: Block): Boolean {
        if (block.world.name != WORLD_NAME || block.x != SEEK_X || block.z != SEEK_Z) return false
        return block.y == SEEK_Y || (block.y == SEEK_Y + 1 && seekingPlayer != null)
    }

    fun isPotBlock(block: Block): Boolean {
        return block.world.name == WORLD_NAME && block.x == POT_X && block.y == POT_Y && block.z == POT_Z
    }

    fun handleSeeking(player: Player) {
        if (resourceId(player.inventory.itemInMainHand) != EMPTY_SLIP_ID) {
            player.sendMessage("§7这座卜签台似乎在等待一张空白卜算签。")
            return
        }
        if (seekingPlayer != null) {
            player.sendMessage("§c求签仪式正在进行，请稍候。")
            return
        }

        val today = today()
        val world = plugin.server.getWorld(WORLD_NAME)
        if (world == null) {
            player.sendMessage("§c卜天居所在世界未加载。")
            return
        }
        if (world.getBlockAt(SEEK_X, SEEK_Y + 1, SEEK_Z).type != Material.AIR) {
            player.sendMessage("§c求签台上方被阻挡，暂时无法举行仪式。")
            return
        }

        val playerId = player.uniqueId
        val playerName = player.name
        val consumedSlip = player.inventory.itemInMainHand.clone().apply { amount = 1 }
        consumeMainHand(player)
        seekingPlayer = playerId
        plugin.databaseManager.submitDatabaseOperation {
            val existing = repository.find(playerId)
            if (existing?.requestDate == today) existing
            else {
                repository.markRequested(playerId, playerName, today)
                null
            }
        }.whenComplete { existing, error ->
            runOnMain {
                if (seekingPlayer != playerId) return@runOnMain
                val onlinePlayer = plugin.server.getPlayer(playerId)
                when {
                    error != null -> {
                        seekingPlayer = null
                        giveOrDrop(onlinePlayer, consumedSlip, stationLocation().add(0.0, 1.0, 0.0))
                        onlinePlayer?.sendMessage("§c求签记录保存失败，本次没有消耗卜算签。")
                        plugin.logger.severe("异步保存求签记录失败 [$playerName]：${error.cause?.message ?: error.message}")
                    }
                    existing != null -> {
                        seekingPlayer = null
                        giveOrDrop(onlinePlayer, consumedSlip, stationLocation().add(0.0, 1.0, 0.0))
                        val result = Fortune.byResourceId(existing.fortuneId)?.displayName ?: "§b待解签"
                        onlinePlayer?.sendMessage("§c你今日已经求过签了，今日签运：$result§c。")
                    }
                    else -> startSeekingRitual(playerId, today)
                }
            }
        }
    }

    private fun startSeekingRitual(playerId: UUID, date: LocalDate) {
        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        raiseSeekingTable()
        val center = Location(world, SEEK_X + 0.5, SEEK_Y + 2.25, SEEK_Z + 0.5)
        seekDisplay = world.spawn(center, ItemDisplay::class.java) { display ->
            display.setItemStack(plugin.resourceManager.getItem(EMPTY_SLIP_ID) ?: ItemStack(Material.PAPER))
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND)
            display.isPersistent = false
            display.isInvulnerable = true
            display.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                AxisAngle4f(0f, 0f, 0f, 1f),
                Vector3f(1.35f, 1.35f, 1.35f),
                AxisAngle4f(0f, 0f, 0f, 1f)
            )
        }
        world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.8f)

        var ticks = 0
        seekTask = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 60) {
                    finishSeeking(playerId, date)
                    cancel()
                    return
                }
                drawWritingParticles(center, ticks)
                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun drawWritingParticles(center: Location, ticks: Int) {
        val world = center.world ?: return
        world.spawnParticle(Particle.ENCHANT, center, 4, 0.62, 0.24, 0.62, 0.035)
        val blackInk = Particle.DustOptions(Color.fromRGB(18, 18, 22), 1.15f)
        val whiteInk = Particle.DustOptions(Color.fromRGB(238, 238, 232), 1.0f)
        val angle = ticks * 0.20

        // 两股水墨反向旋转，并通过多个相邻点形成带有拖尾感的环绕笔迹。
        repeat(5) { trail ->
            val blackAngle = angle - trail * 0.13
            val whiteAngle = -angle + Math.PI - trail * 0.13
            val blackPoint = center.clone().add(
                cos(blackAngle) * 0.62,
                sin(blackAngle * 1.7) * 0.13,
                sin(blackAngle) * 0.43
            )
            val whitePoint = center.clone().add(
                cos(whiteAngle) * 0.70,
                0.08 + cos(whiteAngle * 1.5) * 0.12,
                sin(whiteAngle) * 0.48
            )
            world.spawnParticle(Particle.DUST, blackPoint, 1, 0.015, 0.015, 0.015, 0.0, blackInk)
            world.spawnParticle(Particle.DUST, whitePoint, 1, 0.015, 0.015, 0.015, 0.0, whiteInk)
        }
        if (ticks % 8 == 0) {
            val inkPoint = center.clone().add(cos(angle) * 0.60, 0.02, sin(angle) * 0.42)
            world.spawnParticle(Particle.SQUID_INK, inkPoint, 2, 0.05, 0.04, 0.05, 0.01)
            world.spawnParticle(Particle.WHITE_ASH, center, 2, 0.50, 0.15, 0.50, 0.01)
        }
    }

    private fun finishSeeking(playerId: UUID, date: LocalDate) {
        seekDisplay?.remove()
        seekDisplay = null
        lowerSeekingTable()
        val item = createFilledSlip(playerId, date)
        val player = plugin.server.getPlayer(playerId)
        val dropAt = stationLocation().add(0.0, 1.2, 0.0)
        if (item != null) giveOrDrop(player, item, dropAt)
        player?.sendMessage("§b签文已成，去找采心的瓦罐解签吧。")
        seekingPlayer = null
        seekTask = null
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (seekingPlayer == null) normalizeSeekingStation()
        }, 2L)
    }

    fun handlePot(player: Player) {
        if (resourceId(player.inventory.itemInMainHand) != FILLED_SLIP_ID) {
            player.sendMessage("§7瓦罐没有反应，或许需要一张写满签文的算签。")
            return
        }
        if (interpretingPlayer != null) {
            player.sendMessage("§c瓦罐正在为他人解签，请稍候。")
            return
        }

        val held = player.inventory.itemInMainHand
        val meta = held.itemMeta
        val slipDate = meta?.persistentDataContainer?.get(requestDateKey, PersistentDataType.STRING)
        val slipOwner = meta?.persistentDataContainer?.get(ownerKey, PersistentDataType.STRING)
        val today = today()
        if (slipOwner != player.uniqueId.toString() || slipDate != today.toString()) {
            player.sendMessage("§c这张算签不属于你今日尚未解出的签文。")
            return
        }

        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        val potBlock = world.getBlockAt(POT_X, POT_Y, POT_Z)
        if (potBlock.type != Material.DECORATED_POT) {
            player.sendMessage("§c采心的瓦罐不在原位。")
            return
        }

        val playerId = player.uniqueId
        val playerName = player.name
        val consumedSlip = held.clone().apply { amount = 1 }
        consumeMainHand(player)
        interpretingPlayer = playerId
        plugin.databaseManager.submitDatabaseOperation {
            repository.find(playerId)
        }.whenComplete { record, error ->
            runOnMain {
                if (interpretingPlayer != playerId) return@runOnMain
                val onlinePlayer = plugin.server.getPlayer(playerId)
                if (error != null) {
                    interpretingPlayer = null
                    giveOrDrop(onlinePlayer, consumedSlip, potLocation().add(0.0, 1.0, 0.0))
                    onlinePlayer?.sendMessage("§c读取求签记录失败，本次没有消耗算签。")
                    plugin.logger.severe("异步读取解签记录失败 [$playerName]：${error.cause?.message ?: error.message}")
                    return@runOnMain
                }
                if (record?.requestDate != today || record.fortuneId != null) {
                    interpretingPlayer = null
                    giveOrDrop(onlinePlayer, consumedSlip, potLocation().add(0.0, 1.0, 0.0))
                    onlinePlayer?.sendMessage("§c这张算签不属于你今日尚未解出的签文。")
                    return@runOnMain
                }
                startPotRitual(playerId, today)
            }
        }
    }

    private fun startPotRitual(playerId: UUID, date: LocalDate) {
        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        val base = Location(world, POT_X.toDouble(), POT_Y.toDouble(), POT_Z.toDouble())

        var ticks = 0
        potTask = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 100) {
                    finishInterpretation(playerId, date)
                    cancel()
                    return
                }
                animatePot(base, ticks)
                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun animatePot(base: Location, ticks: Int) {
        val world = base.world ?: return
        val center = base.clone().add(0.5, 0.62, 0.5)
        val green = Particle.DustOptions(Color.fromRGB(66, 220, 105), 1.05f)
        val angle = ticks * 0.22
        repeat(4) { index ->
            val pointAngle = angle + index * (Math.PI / 2.0)
            val point = center.clone().add(
                cos(pointAngle) * 0.62,
                0.10 + sin(pointAngle * 1.8) * 0.22,
                sin(pointAngle) * 0.62
            )
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.0, green)
        }

        // 每 0.5 秒让真实瓦罐晃动并播放一次原版存物音效。
        if (ticks % 10 == 0) {
            world.getBlockAt(POT_X, POT_Y, POT_Z).let(::wobblePot)
            world.playSound(center, Sound.BLOCK_DECORATED_POT_INSERT, 1.0f, 0.95f + (ticks % 20) * 0.005f)
            world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.0, 0.42, 0.0), 5, 0.30, 0.16, 0.30, 0.01)
        }
        world.spawnParticle(Particle.DUST_PLUME, base.clone().add(0.5, 1.05, 0.5), 3, 0.12, 0.05, 0.12, 0.01)
    }

    private fun finishInterpretation(playerId: UUID, date: LocalDate) {
        potTask = null
        val fortune = Fortune.weightedRandom()
        plugin.databaseManager.submitDatabaseOperation {
            repository.setFortune(playerId, date, fortune.resourceId)
        }.whenComplete { _, error ->
            runOnMain {
                if (interpretingPlayer != playerId) return@runOnMain
                val player = plugin.server.getPlayer(playerId)
                if (error != null) {
                    createFilledSlip(playerId, date)?.let { giveOrDrop(player, it, potLocation().add(0.0, 1.0, 0.0)) }
                    player?.sendMessage("§c解签结果保存失败，算签已经退还，请稍后重试。")
                    interpretingPlayer = null
                    plugin.logger.severe("异步保存解签结果失败 [$playerId]：${error.cause?.message ?: error.message}")
                    return@runOnMain
                }
                revealFortune(playerId, date, fortune)
            }
        }
    }

    private fun revealFortune(playerId: UUID, date: LocalDate, fortune: Fortune) {
        val mouth = potLocation().add(0.0, 1.05, 0.0)
        sprayFortuneParticles(mouth, fortune)
        val item = createFortuneItem(playerId, date, fortune)
        val player = plugin.server.getPlayer(playerId)
        if (item != null) giveOrDrop(player, item, mouth)
        player?.sendMessage("§6解签完成：${fortune.displayName}")
        playFortuneSound(mouth, fortune)
        if (fortune == Fortune.SHANG_SHANG || fortune == Fortune.XIA_XIA) {
            val playerName = player?.name ?: plugin.server.getOfflinePlayer(playerId).name ?: playerId.toString()
            plugin.server.broadcastMessage("§6§l恭喜玩家§e$playerName§6§l在§b§l栖霞镇求签§6§l中，获得了${fortune.displayName}§6§l！")
        }
        interpretingPlayer = null
    }

    private fun sprayFortuneParticles(location: Location, fortune: Fortune) {
        val world = location.world ?: return
        val dust = Particle.DustOptions(fortune.particleColor, 1.65f)
        repeat(70) {
            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val radius = Random.nextDouble(0.05, 1.05)
            val point = location.clone().add(cos(angle) * radius, Random.nextDouble(-0.15, 1.65), sin(angle) * radius)
            world.spawnParticle(Particle.DUST, point, 2, 0.025, 0.025, 0.025, 0.0, dust)
        }
        world.spawnParticle(Particle.FLASH, location.clone().add(0.0, 0.45, 0.0), 2, 0.15, 0.15, 0.15, 0.0)
        world.spawnParticle(Particle.POOF, location, 35, 0.65, 0.65, 0.65, 0.13)
        world.spawnParticle(Particle.FIREWORK, location.clone().add(0.0, 0.35, 0.0), 24, 0.55, 0.70, 0.55, 0.10)
        spawnFortuneAccent(location, fortune)

        // 三层逐次扩大的签运色粒子环，让最终揭签更有爆发和扩散感。
        repeat(3) { wave ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val radius = 0.55 + wave * 0.42
                repeat(28) { index ->
                    val angle = index * (Math.PI * 2.0 / 28.0) + wave * 0.18
                    val point = location.clone().add(
                        cos(angle) * radius,
                        0.12 + sin(angle * 2.0) * 0.12 + wave * 0.22,
                        sin(angle) * radius
                    )
                    world.spawnParticle(Particle.DUST, point, 2, 0.015, 0.015, 0.015, 0.0, dust)
                }
                world.spawnParticle(Particle.END_ROD, location.clone().add(0.0, wave * 0.25, 0.0), 12, radius * 0.45, 0.30, radius * 0.45, 0.035)
            }, wave * 4L)
        }
    }

    private fun spawnFortuneAccent(location: Location, fortune: Fortune) {
        val world = location.world ?: return
        when (fortune) {
            Fortune.SHANG_SHANG -> world.spawnParticle(Particle.TOTEM_OF_UNDYING, location, 45, 0.75, 0.95, 0.75, 0.14)
            Fortune.SHANG -> {
                world.spawnParticle(Particle.CHERRY_LEAVES, location, 38, 0.85, 0.85, 0.85, 0.06)
                world.spawnParticle(Particle.HEART, location.clone().add(0.0, 0.4, 0.0), 10, 0.65, 0.55, 0.65, 0.04)
            }
            Fortune.ZHONG -> world.spawnParticle(Particle.NAUTILUS, location, 40, 0.75, 0.75, 0.75, 0.10)
            Fortune.XIA -> {
                world.spawnParticle(Particle.WHITE_ASH, location, 45, 0.85, 0.90, 0.85, 0.04)
                world.spawnParticle(Particle.SOUL, location, 15, 0.55, 0.65, 0.55, 0.05)
            }
            Fortune.XIA_XIA -> {
                world.spawnParticle(Particle.LARGE_SMOKE, location, 35, 0.85, 0.90, 0.85, 0.08)
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, location, 28, 0.70, 0.80, 0.70, 0.09)
            }
        }
    }

    private fun playFortuneSound(location: Location, fortune: Fortune) {
        val world = location.world ?: return
        when (fortune) {
            Fortune.SHANG_SHANG -> world.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.35f, 1.15f)
            Fortune.SHANG -> world.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.25f, 1.35f)
            Fortune.ZHONG -> world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.35f, 0.95f)
            Fortune.XIA -> world.playSound(location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.75f, 1.25f)
            Fortune.XIA_XIA -> world.playSound(location, Sound.ENTITY_WITHER_SPAWN, 0.55f, 1.45f)
        }
    }

    private fun raiseSeekingTable() {
        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        val pistonBlock = world.getBlockAt(SEEK_X, SEEK_Y - 1, SEEK_Z)
        val tableBlock = world.getBlockAt(SEEK_X, SEEK_Y, SEEK_Z)
        val aboveBlock = world.getBlockAt(SEEK_X, SEEK_Y + 1, SEEK_Z)
        originalSeekPistonData = pistonBlock.blockData.clone()
        originalSeekTableData = tableBlock.blockData.clone()
        originalSeekAboveData = aboveBlock.blockData.clone()

        // 先把卜签台复制到抬升位置，再用活塞头替换原位置；全程关闭方块物理更新。
        aboveBlock.setBlockData(originalSeekTableData!!, false)
        val pistonData = Material.STICKY_PISTON.createBlockData() as Piston
        pistonData.facing = org.bukkit.block.BlockFace.UP
        pistonData.isExtended = true
        pistonBlock.setBlockData(pistonData, false)
        val pistonHeadData = Material.PISTON_HEAD.createBlockData() as PistonHead
        pistonHeadData.facing = org.bukkit.block.BlockFace.UP
        pistonHeadData.type = TechnicalPiston.Type.STICKY
        pistonHeadData.isShort = false
        tableBlock.setBlockData(pistonHeadData, false)
        world.playSound(stationLocation(), Sound.BLOCK_PISTON_EXTEND, 1.0f, 1.0f)
    }

    private fun lowerSeekingTable() {
        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        val pistonBlock = world.getBlockAt(SEEK_X, SEEK_Y - 1, SEEK_Z)
        val tableBlock = world.getBlockAt(SEEK_X, SEEK_Y, SEEK_Z)
        val aboveBlock = world.getBlockAt(SEEK_X, SEEK_Y + 1, SEEK_Z)

        // 先清除活塞头，再恢复活塞、卜签台上方和卜签台本身的仪式前状态。
        tableBlock.setBlockData(Material.AIR.createBlockData(), false)
        pistonBlock.setBlockData(originalSeekPistonData ?: retractedPistonData(), false)
        aboveBlock.setBlockData(originalSeekAboveData ?: Material.AIR.createBlockData(), false)
        tableBlock.setBlockData(originalSeekTableData ?: Material.ENCHANTING_TABLE.createBlockData(), false)
        clearSeekingSnapshots()
        world.playSound(stationLocation(), Sound.BLOCK_PISTON_CONTRACT, 1.0f, 1.0f)
    }

    private fun normalizeSeekingStation() {
        if (seekingPlayer != null) return
        val world = plugin.server.getWorld(WORLD_NAME) ?: return
        val pistonBlock = world.getBlockAt(SEEK_X, SEEK_Y - 1, SEEK_Z)
        val tableBlock = world.getBlockAt(SEEK_X, SEEK_Y, SEEK_Z)
        val aboveBlock = world.getBlockAt(SEEK_X, SEEK_Y + 1, SEEK_Z)

        pistonBlock.setBlockData(retractedPistonData(), false)
        tableBlock.setBlockData(Material.ENCHANTING_TABLE.createBlockData(), false)
        if (aboveBlock.type == Material.ENCHANTING_TABLE || aboveBlock.type == Material.PISTON_HEAD) {
            aboveBlock.setBlockData(Material.AIR.createBlockData(), false)
        }
    }

    private fun retractedPistonData(): BlockData {
        return (Material.STICKY_PISTON.createBlockData() as Piston).apply {
            facing = org.bukkit.block.BlockFace.UP
            isExtended = false
        }
    }

    private fun clearSeekingSnapshots() {
        originalSeekPistonData = null
        originalSeekTableData = null
        originalSeekAboveData = null
    }

    /**
     * Bukkit 1.21.3 尚未暴露饰纹瓦罐的原版 wobble API，因此调用服务端方块实体的
     * POSITIVE wobble 事件。真实瓦罐始终保留在世界中，不再用 BlockDisplay 替换。
     */
    private fun wobblePot(block: Block) {
        if (block.type != Material.DECORATED_POT) return
        try {
            val level = block.world.javaClass.getMethod("getHandle").invoke(block.world)
            val blockPosClass = Class.forName("net.minecraft.core.BlockPos")
            val blockPos = blockPosClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(block.x, block.y, block.z)
            val blockEntity = level.javaClass.getMethod("getBlockEntity", blockPosClass).invoke(level, blockPos) ?: return
            val wobbleStyleClass = Class.forName("net.minecraft.world.level.block.entity.DecoratedPotBlockEntity\$WobbleStyle")
            val positive = wobbleStyleClass.enumConstants.first { (it as Enum<*>).name == "POSITIVE" }
            blockEntity.javaClass.getMethod("wobble", wobbleStyleClass).invoke(blockEntity, positive)
        } catch (exception: ReflectiveOperationException) {
            if (!potWobbleWarningLogged) {
                potWobbleWarningLogged = true
                plugin.logger.warning("无法触发瓦罐原版晃动动画，瓦罐仍会保持显示：${exception.message}")
            }
        }
    }

    private fun createFilledSlip(playerId: UUID, date: LocalDate): ItemStack? {
        val item = plugin.resourceManager.getItem(FILLED_SLIP_ID)?.clone() ?: return null
        val meta = item.itemMeta ?: return item
        meta.persistentDataContainer.set(requestDateKey, PersistentDataType.STRING, date.toString())
        meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, playerId.toString())
        item.itemMeta = meta
        return item
    }

    private fun createFortuneItem(playerId: UUID, date: LocalDate, fortune: Fortune): ItemStack? {
        val item = plugin.resourceManager.getItem(fortune.resourceId)?.clone() ?: return null
        val meta = item.itemMeta ?: return item
        meta.persistentDataContainer.set(fortuneDateKey, PersistentDataType.STRING, date.toString())
        meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, playerId.toString())
        item.itemMeta = meta
        BusuanItemLore.restore(plugin, item, fortune.resourceId)
        return item
    }

    private fun giveOrDrop(player: Player?, item: ItemStack, dropAt: Location) {
        if (player == null || !player.isOnline) {
            dropAt.world?.dropItemNaturally(dropAt, item)
            return
        }
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun consumeMainHand(player: Player) {
        val item = player.inventory.itemInMainHand
        item.amount -= 1
        player.inventory.setItemInMainHand(if (item.amount > 0) item else ItemStack(Material.AIR))
    }

    private fun resourceId(item: ItemStack?): String? {
        return item?.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)
    }

    /**
     * 处理签运物品的右键使用。返回 true 表示主手物品属于五种签运之一，监听器应屏蔽原版交互。
     */
    fun handleFortuneUse(player: Player): Boolean {
        val held = player.inventory.itemInMainHand
        val fortune = Fortune.byResourceId(resourceId(held)) ?: return false
        val meta = held.itemMeta
        val owner = meta?.persistentDataContainer?.get(ownerKey, PersistentDataType.STRING)
        val rawDate = meta?.persistentDataContainer?.get(fortuneDateKey, PersistentDataType.STRING)

        if (owner != player.uniqueId.toString()) {
            player.sendMessage("§c这张签运并不属于你，无法使用。")
            return true
        }
        if (rawDate != today().toString()) {
            player.sendMessage("§7这张签运已经过期，签中气运早已散去。")
            return true
        }

        val reward = when (fortune) {
            Fortune.SHANG_SHANG -> createReward("jinyuanbao", 3)
            Fortune.SHANG -> createReward("jinyuanbao", 1)
            Fortune.ZHONG -> createReward("hjh_tongqian", 7)
            Fortune.XIA -> createReward("hjh_tongqian", 3)
            Fortune.XIA_XIA -> null
        }
        if (fortune != Fortune.XIA_XIA && reward == null) {
            player.sendMessage("§c签运奖励配置缺失，请联系管理员处理。")
            return true
        }

        consumeMainHand(player)
        when (fortune) {
            Fortune.SHANG_SHANG -> {
                giveOrDrop(player, reward!!, player.location)
                player.sendMessage("§6鸿运当头！你从签运中获得了 §e金元宝 x3§6。")
            }
            Fortune.SHANG -> {
                giveOrDrop(player, reward!!, player.location)
                player.sendMessage("§d好运相伴，你从签运中获得了 §e金元宝 x1§d。")
            }
            Fortune.ZHONG -> {
                giveOrDrop(player, reward!!, player.location)
                player.sendMessage("§9平稳顺遂，你从签运中获得了 §f铜钱 x7§9。")
            }
            Fortune.XIA -> {
                giveOrDrop(player, reward!!, player.location)
                player.sendMessage("§f略有薄福，你从签运中获得了 §7铜钱 x3§f。")
            }
            Fortune.XIA_XIA -> player.sendMessage("§7今日气运低迷，诸事不宜，还是谨慎行事为妙。")
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, if (fortune == Fortune.XIA_XIA) 0.55f else 1.15f)
        return true
    }

    private fun createReward(resourceId: String, amount: Int): ItemStack? {
        return plugin.resourceManager.getItem(resourceId)?.clone()?.apply { this.amount = amount }
    }

    fun handleAdmin(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", true)) {
            sender.sendMessage("§6=== 卜算管理 ===")
            sender.sendMessage("§e/hjhadmin buqian info <玩家> §7- 查看今日求签和签运")
            sender.sendMessage("§e/hjhadmin buqian reset <玩家> §7- 刷新今日求签资格")
            return true
        }
        if (args.size < 2) return adminError(sender, "用法: /hjhadmin buqian <info|reset> <玩家>")
        val target = plugin.server.getOfflinePlayer(args[1])
        val targetId = target.uniqueId
        val targetName = target.name ?: args[1]
        return when (args[0].lowercase()) {
            "info", "view" -> {
                val queryDate = today()
                plugin.databaseManager.submitDatabaseOperation { repository.find(targetId) }.whenComplete { record, error ->
                    runOnMain {
                        if (error != null) {
                            sender.sendMessage("§c查询 $targetName 的求签记录失败：${error.cause?.message ?: error.message}")
                        } else if (record == null || record.requestDate != queryDate) {
                            sender.sendMessage("§f$targetName §a今日尚未求签。")
                        } else {
                            val fortune = Fortune.byResourceId(record.fortuneId)?.displayName ?: "§b待解签"
                            sender.sendMessage("§f$targetName §e今日已求签，结果：$fortune")
                        }
                    }
                }
                true
            }
            "reset", "refresh" -> {
                plugin.databaseManager.submitDatabaseOperation { repository.reset(targetId) }.whenComplete { _, error ->
                    runOnMain {
                        if (error != null) sender.sendMessage("§c刷新 $targetName 的求签资格失败：${error.cause?.message ?: error.message}")
                        else sender.sendMessage("§a已刷新 $targetName 的今日求签资格。")
                    }
                }
                true
            }
            else -> adminError(sender, "未知子指令，使用 /hjhadmin buqian help。")
        }
    }

    fun tabComplete(args: List<String>): List<String> {
        return when (args.size) {
            0, 1 -> listOf("help", "info", "reset").filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
            2 -> if (args[0].equals("info", true) || args[0].equals("reset", true)) {
                plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[1], true) }
            } else emptyList()
            else -> emptyList()
        }
    }

    fun shutdown() {
        seekTask?.cancel()
        potTask?.cancel()
        seekDisplay?.remove()
        if (seekingPlayer != null) lowerSeekingTable()
        seekingPlayer = null
        interpretingPlayer = null
    }

    fun resetPlayerData(playerId: UUID) {
        if (seekingPlayer == playerId) {
            seekTask?.cancel()
            seekTask = null
            seekDisplay?.remove()
            seekDisplay = null
            lowerSeekingTable()
            seekingPlayer = null
            plugin.server.scheduler.runTaskLater(plugin, Runnable(::normalizeSeekingStation), 2L)
        }
        if (interpretingPlayer == playerId) {
            potTask?.cancel()
            potTask = null
            interpretingPlayer = null
        }
    }

    private fun adminError(sender: CommandSender, message: String): Boolean {
        sender.sendMessage("§c$message")
        return true
    }

    private fun runOnMain(block: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun today(): LocalDate = LocalDate.now(zone)
    private fun stationLocation(): Location = Location(plugin.server.getWorld(WORLD_NAME), SEEK_X + 0.5, SEEK_Y + 0.5, SEEK_Z + 0.5)
    private fun potLocation(): Location = Location(plugin.server.getWorld(WORLD_NAME), POT_X + 0.5, POT_Y + 0.5, POT_Z + 0.5)

    companion object {
        const val WORLD_NAME = "world"
        const val EMPTY_SLIP_ID = "busuanqian"
        const val FILLED_SLIP_ID = "xiemanqianwendesuanqian"
        const val SEEK_X = 692
        const val SEEK_Y = 59
        const val SEEK_Z = -509
        const val POT_X = 687
        const val POT_Y = 58
        const val POT_Z = -500
    }
}
