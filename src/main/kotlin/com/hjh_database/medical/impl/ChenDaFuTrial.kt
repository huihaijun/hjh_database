package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.roundToInt
import kotlin.random.Random

class ChenDaFuTrial(
    private val plugin: Hjh_database,
    override val player: Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "chendafu"

    private enum class Phase {
        STORY,
        WAIT_BELL,
        RUNNING,
        ENDED
    }

    private data class Crystal(
        val block: Block,
        val originalData: BlockData,
        var ageTicks: Int = 0
    )

    private var phase = Phase.STORY
    private var tick = 0
    private var temperature = 80.0
    private var nextCrystalTicks = 30
    private var lastBellInteractMillis = 0L
    private val activeCrystals = mutableListOf<Crystal>()

    private val temperatureBar: BossBar = Bukkit.createBossBar(
        "§c丹炉温度：80%",
        BarColor.RED,
        BarStyle.SOLID
    )
    private val talismanKey = NamespacedKey(plugin, "resource_id")
    private val talismanProjectileTag = "hjh_chendafu_control_talisman"
    private val startLocation = Location(player.world, -126.69, 28.06, 141.33, 1981.37f, 0.15f)
    private val exitLocation = Location(player.world, -121.28, 46.00, 140.15, 2069.72f, 4.80f)
    private val bellLocation = Location(player.world, -124.0, 29.0, 144.0)

    private val storyMessages = listOf(
        "§f小友来得正好，老夫正愁没人搭手。救济苍生的事忙，还惦记着来帮老夫这一把，有心了。",
        "§f说来惭愧，这口炼丹炉随了老夫大半辈子，近来炉温忽高忽低，愈发不听使唤。老夫年轻时自创了一套控火的法子，治它绰绰有余，可惜如今这把老骨头气短手抖，实在折腾不动了，只能劳烦你跑一趟。",
        "§f拿着，这是老夫当年画的§e控火符§f。纸面泛黄了，效用不减——待会儿控温全靠它。",
        "§f待老夫将炉火推到最旺，你面前这口丹炉便会凝出几枚§d水晶块§f，半悬在炉身四周。它们会随时间推移渐渐变色，一旦§c通体转红§f，你便拿手上的§e控火符§f朝它砸过去，一枚符刚好压住一枚晶块，炉温便应声而降。",
        "§f听着简单，越往后越见真章。晶块浮现的§c速度会越来越快§f，到后面两三枚同时往外冒也不稀奇。有一点切切记住——任一枚晶块若在§c三息之内§f未被击碎，便会反噬丹炉，炉温猛蹿一截，前头的功夫就白费了。",
        "§f还有一事：试炼之时，§c万万不可靠近丹炉§f。这炉温常人沾上一下就得脱层皮，你只管站在这块§e地毯§f上，此处最是稳妥。",
        "§f若无旁的问题，便敲一声身后的§e钟§f。老夫即刻开火。"
    )

    override fun start() {
        player.teleport(startLocation)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        when (phase) {
            Phase.STORY -> runStory()
            Phase.RUNNING -> runTrial()
            else -> Unit
        }
        tick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!sameBlock(block.location, bellLocation) || block.type != Material.BELL) return

        event.isCancelled = true
        if (phase != Phase.WAIT_BELL) return
        val now = System.currentTimeMillis()
        if (now - lastBellInteractMillis < 500) return
        lastBellInteractMillis = now
        beginTrial()
    }

    @EventHandler
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val snowball = event.entity as? Snowball ?: return
        if (snowball.shooter != player || phase != Phase.RUNNING) return
        if (!isControlTalisman(snowball.item)) return

        snowball.addScoreboardTag(talismanProjectileTag)
        player.setCooldown(Material.SNOWBALL, 16)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (phase == Phase.RUNNING && player.isOnline) giveControlTalisman()
        }, 1L)
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val snowball = event.entity as? Snowball ?: return
        if (!snowball.scoreboardTags.contains(talismanProjectileTag) || phase != Phase.RUNNING) return
        val hitBlock = event.hitBlock ?: return
        val crystal = activeCrystals.firstOrNull { target -> sameBlock(target.block.location, hitBlock.location) } ?: return
        if (crystal.ageTicks < 60 || crystal.block.type != Material.RED_STAINED_GLASS) return

        event.isCancelled = true
        snowball.remove()
        removeCrystal(crystal)
        temperature = (temperature - 4.0).coerceAtLeast(0.0)
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.2f)
        player.sendMessage("§b红温晶块已击碎，丹炉温度下降！")
        updateTemperatureBar()
        if (temperature <= 10.0) win()
    }

    private fun runStory() {
        if (tick % 60 != 0) return
        val messageIndex = tick / 60
        if (messageIndex < storyMessages.size) {
            player.sendMessage("§a§l陈大夫 §f: ${storyMessages[messageIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            return
        }

        giveControlTalisman()
        phase = Phase.WAIT_BELL
        tick = 0
        player.sendMessage("§e[医术试炼] §f已获得§e陈大夫的控火符§f，敲响身后的钟开始控火。")
    }

    private fun beginTrial() {
        phase = Phase.RUNNING
        temperature = 80.0
        nextCrystalTicks = 30
        temperatureBar.addPlayer(player)
        updateTemperatureBar()
        player.playSound(bellLocation, Sound.BLOCK_BELL_USE, 1f, 1f)
        player.sendMessage("§e丹炉已开！红温晶块出现后，立刻用控火符将其击碎！")
    }

    private fun runTrial() {
        updateCrystals()
        if (phase != Phase.RUNNING) return
        nextCrystalTicks--
        if (nextCrystalTicks <= 0) {
            spawnCrystalWave()
            nextCrystalTicks = calculateSpawnInterval()
        }
        if (tick % 20 == 0) damageIfNearFurnace()
    }

    private fun updateCrystals() {
        val iterator = activeCrystals.iterator()
        while (iterator.hasNext()) {
            val crystal = iterator.next()
            crystal.ageTicks++
            when (crystal.ageTicks) {
                30 -> crystal.block.type = Material.YELLOW_STAINED_GLASS
                60 -> {
                    crystal.block.type = Material.RED_STAINED_GLASS
                    player.playSound(crystal.block.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.5f)
                }
                90 -> {
                    restoreCrystalBlock(crystal)
                    iterator.remove()
                    temperature = (temperature + 4.0).coerceAtMost(100.0)
                    player.sendMessage("§c红温晶块反噬，丹炉温度上升！")
                    updateTemperatureBar()
                    if (temperature >= 100.0) {
                        failByOverheat()
                        return
                    }
                }
            }
        }
    }

    private fun spawnCrystalWave() {
        spawnCrystal()
        val lowerTemperatureRatio = ((80.0 - temperature) / 70.0).coerceIn(0.0, 1.0)
        if (lowerTemperatureRatio > 0.25 && Random.nextDouble() < lowerTemperatureRatio * 0.4) {
            spawnCrystal()
        }
    }

    private fun spawnCrystal() {
        val occupied = activeCrystals.map { crystal -> crystal.block.location.blockX to crystal.block.location.blockY }.toSet()
        val candidates = buildCrystalLocations().filter { location ->
            (location.blockX to location.blockY) !in occupied
        }
        val location = candidates.randomOrNull() ?: return
        val block = location.block
        val crystal = Crystal(block, block.blockData.clone())
        activeCrystals += crystal
        block.type = Material.WHITE_STAINED_GLASS
        player.world.playSound(location.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.75f, 1.3f)
    }

    private fun calculateSpawnInterval(): Int {
        val lowerTemperatureRatio = ((80.0 - temperature) / 70.0).coerceIn(0.0, 1.0)
        return (30.0 - lowerTemperatureRatio * 14.0).roundToInt().coerceIn(16, 30)
    }

    private fun damageIfNearFurnace() {
        val location = player.location
        val nearestX = location.x.coerceIn(-132.0, -122.0)
        val nearestY = location.y.coerceIn(29.0, 33.0)
        val nearestZ = 134.0
        val dx = location.x - nearestX
        val dy = location.y - nearestY
        val dz = location.z - nearestZ
        if (dx * dx + dy * dy + dz * dz > 2.0 * 2.0) return

        player.sendMessage("§c温度太高了！快离开！你会被烫伤的！")
        player.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, 20.0))
        player.damage(20.0)
    }

    private fun updateTemperatureBar() {
        temperatureBar.progress = (temperature / 100.0).coerceIn(0.0, 1.0)
        temperatureBar.setTitle("§c丹炉温度：§e${temperature.roundToInt()}%")
        temperatureBar.color = when {
            temperature >= 85.0 -> BarColor.RED
            temperature >= 45.0 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }
    }

    private fun buildCrystalLocations(): List<Location> {
        val locations = mutableListOf<Location>()
        for (x in -132..-122) {
            for (y in 29..33) {
                locations += Location(player.world, x.toDouble(), y.toDouble(), 134.0)
            }
        }
        return locations
    }

    private fun removeCrystal(crystal: Crystal) {
        restoreCrystalBlock(crystal)
        activeCrystals.remove(crystal)
    }

    private fun restoreCrystalBlock(crystal: Crystal) {
        crystal.block.setBlockData(crystal.originalData, false)
    }

    private fun giveControlTalisman() {
        val talisman = plugin.resourceManager.getItem("chendafudekonghuofu")?.clone()
            ?: ItemStack(Material.SNOWBALL)
        talisman.amount = 1
        val leftovers = player.inventory.addItem(talisman)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun isControlTalisman(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.SNOWBALL || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.get(talismanKey, PersistentDataType.STRING) == "chendafudekonghuofu"
    }

    private fun removeControlTalismans() {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (isControlTalisman(item)) inventory.setItem(slot, null)
        }
    }

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world && first.blockX == second.blockX && first.blockY == second.blockY && first.blockZ == second.blockZ
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        player.teleport(exitLocation)
        player.sendMessage("§a陈大夫 §f: §a好手法！这卷杏花雨正适合你，收下吧。")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val book = plugin.medicalManager.getSkillBook("xinghuayu")?.clone()
        if (book != null) {
            book.amount = 1
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        } else {
            plugin.logger.warning("陈大夫医术试炼奖励 xinghuayu 未找到。")
        }

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 200)
            data.completedMedicalTrials.add(trialId)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { connection ->
                        plugin.databaseManager.saveCompletedMedicalTrials(connection, data)
                    }
                } catch (exception: Exception) {
                    plugin.logger.severe("保存陈大夫医术试炼完成记录失败: ${exception.message}")
                }
            })
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    private fun failByOverheat() {
        if (phase == Phase.ENDED) return
        player.sendMessage("§c丹炉温度失控，试炼失败！")
        fail()
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        cleanUp()
        if (player.isOnline && !player.isDead) player.teleport(exitLocation)
        if (shouldMessage) player.sendMessage("§c陈大夫的医术试炼失败！")
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        activeCrystals.forEach { crystal -> restoreCrystalBlock(crystal) }
        activeCrystals.clear()
        temperatureBar.removeAll()
        removeControlTalismans()
    }
}
