package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.roundToInt
import kotlin.random.Random

class ZhuanYuanShangXianTrial(
    private val plugin: Hjh_database,
    override val player: org.bukkit.entity.Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "zhuanyuanshangxian"

    private enum class Phase {
        STORY,
        BRUSHING,
        ENDED
    }

    private var phase = Phase.STORY
    private var tick = 0
    private var timeTicksLeft = 180 * 20
    private var totalProgress = 0
    private var currentTarget: Location? = null
    private var activeBrushStartTick: Int? = null
    private var brushProgress = 0.0
    private var targetMin = 0
    private var targetMax = 0

    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val timeBar: BossBar = Bukkit.createBossBar("§d篆元上仙的医术试炼-倒计时", BarColor.PURPLE, BarStyle.SOLID)
    private val brushBar: BossBar = Bukkit.createBossBar("§b掸去灰尘进度：0%", BarColor.BLUE, BarStyle.SOLID)
    private val targetDust = Particle.DustOptions(Color.fromRGB(255, 218, 121), 1.2f)
    private val activeDust = Particle.DustOptions(Color.fromRGB(120, 220, 255), 1.0f)

    private val storyMessages = listOf(
        "§f小医仙，你来了。此地尚算安全，不知可否请阁下搭把手？",
        "§f实不相瞒，各职业之中，唯有§d行医之人§f最为心细如发，所以小仙斗胆，想请你帮个忙。",
        "§f前些时日为躲避那群魔物，不慎折了腿，可对这§e石碑§f的钻研却一日也不能停。你可愿替小仙拿起这把§6刷子§f，细细掸去碑面上的积尘？",
        "§f切记，此碑历经万古，脆如累卵。刷得§c太短§f，灰尘不去，徒劳无功；刷得§c太长§f，则恐伤及碑文，再难复原。下手务必轻重有度，分寸拿捏得当。",
        "§f若力道或时长出了偏差，小仙会§c立即叫停§f。绝非玩笑，此碑乃§4§n盘古开天§f之时便存于世间，上面所载，关乎整片大陆的存亡兴衰。",
        "§f准备好了，便握紧刷子，听我号令。若完成得漂亮，小仙愿将仙族珍藏的一卷§d医术§f相赠，绝不食言！"
    )

    override fun start() {
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
            Phase.BRUSHING -> runBrushTrial()
            Phase.ENDED -> return
        }

        tick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (event.hand != EquipmentSlot.HAND) return
        if (phase != Phase.BRUSHING) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return

        val target = findClickedTarget(event) ?: return
        val current = currentTarget ?: return
        if (!sameBlock(target, current)) return

        event.isCancelled = true
        if (!isShuazi(player.inventory.itemInMainHand)) {
            player.sendMessage("§c你需要主手握着篆元上仙给你的刷子！")
            return
        }

        if (activeBrushStartTick != null) {
            stopBrushing()
        } else {
            startBrushing()
        }
    }

    private fun runStory() {
        if (tick % 60 != 0) return

        val msgIndex = tick / 60
        if (msgIndex < storyMessages.size) {
            player.sendMessage("§a§l篆元上仙 §f: ${storyMessages[msgIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
        } else {
            phase = Phase.BRUSHING
            tick = 0
            timeTicksLeft = 180 * 20
            giveShuazi()
            timeBar.addPlayer(player)
            chooseNextTarget()
            updateTimeBar()
            player.sendMessage("§e[医术试炼] §f三分钟内将石碑清理进度推进到§a80%§f即可完成。")
            player.sendMessage("§7提示：看准带淡金粒子的石碑方块，主手持刷子右键开始，再次右键停下。")
        }
    }

    private fun runBrushTrial() {
        timeTicksLeft--
        if (timeTicksLeft <= 0) {
            player.sendMessage("§c三分钟已过，石碑尚未清理完成……")
            fail()
            return
        }

        updateTimeBar()
        highlightTarget()

        if (activeBrushStartTick != null) {
            updateBrushProgress()
            if (brushProgress > targetMax) {
                interruptCurrentBrush(tooLong = true)
            }
        }
    }

    private fun startBrushing() {
        activeBrushStartTick = tick
        brushProgress = 0.0
        val lower = Random.nextInt(35, 89)
        targetMin = lower
        targetMax = lower + 7
        brushBar.addPlayer(player)
        updateBrushBar()
        player.sendMessage("§e把掸灰进度控制在 §a$targetMin%§e - §a$targetMax%§e 之间停下。")
        player.playSound(player.location, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 1f, 1f)
    }

    private fun stopBrushing() {
        if (activeBrushStartTick == null) return
        updateBrushProgress()

        when {
            brushProgress < targetMin -> interruptCurrentBrush(tooLong = false)
            brushProgress > targetMax -> interruptCurrentBrush(tooLong = true)
            else -> completeCurrentTarget()
        }
    }

    private fun updateBrushProgress() {
        val started = activeBrushStartTick ?: return
        brushProgress = ((tick - started).coerceAtLeast(0) * 1.25).coerceIn(0.0, 100.0)
        updateBrushBar()
        if (tick % 5 == 0) {
            currentTarget?.let { loc ->
                player.world.spawnParticle(Particle.DUST, loc.clone().add(0.5, 0.5, 0.5), 8, 0.2, 0.25, 0.08, activeDust)
                player.playSound(player.location, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.35f, 1.2f)
            }
        }
    }

    private fun completeCurrentTarget() {
        activeBrushStartTick = null
        brushBar.removeAll()
        totalProgress = (totalProgress + 10).coerceAtMost(100)
        player.sendMessage("§a这处碑面清理得恰到好处！当前完成度：§e$totalProgress%")
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.3f)

        if (totalProgress >= 80) {
            win()
        } else {
            chooseNextTarget()
        }
    }

    private fun interruptCurrentBrush(tooLong: Boolean) {
        activeBrushStartTick = null
        brushBar.removeAll()
        totalProgress = (totalProgress - 5).coerceAtLeast(0)
        if (tooLong) {
            player.sendMessage("§a§l篆元上仙 §f: §c停手！你擦的太过了！")
        } else {
            player.sendMessage("§a§l篆元上仙 §f: §c停手！你擦的太少了！")
        }
        player.sendMessage("§7石碑清理完成度下降至：§e$totalProgress%")
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
        chooseNextTarget()
    }

    private fun chooseNextTarget() {
        val old = currentTarget
        val candidates = buildSteleLocations().filter { loc -> old == null || !sameBlock(loc, old) }
        currentTarget = candidates.random()
        player.sendMessage("§e石碑上有一处积尘显现了，快用刷子处理！")
        highlightTarget()
    }

    private fun updateTimeBar() {
        timeBar.progress = (timeTicksLeft / (180.0 * 20.0)).coerceIn(0.0, 1.0)
        timeBar.setTitle("§d篆元上仙的医术试炼-倒计时 §7| §f完成度：§a$totalProgress%§7/§a80%")
    }

    private fun updateBrushBar() {
        brushBar.progress = (brushProgress / 100.0).coerceIn(0.0, 1.0)
        brushBar.setTitle("§b掸去灰尘进度：${brushProgress.roundToInt()}% §7| §f合适：§a$targetMin%-$targetMax%")
    }

    private fun highlightTarget() {
        val loc = currentTarget ?: return
        if (tick % 5 != 0) return
        val faceZ = loc.blockZ - 0.04
        for (i in 0..4) {
            val t = i / 4.0
            player.world.spawnParticle(Particle.DUST, Location(player.world, loc.blockX + t, loc.blockY.toDouble(), faceZ), 1, 0.0, 0.0, 0.0, 0.0, targetDust)
            player.world.spawnParticle(Particle.DUST, Location(player.world, loc.blockX + t, loc.blockY + 1.0, faceZ), 1, 0.0, 0.0, 0.0, 0.0, targetDust)
            player.world.spawnParticle(Particle.DUST, Location(player.world, loc.blockX.toDouble(), loc.blockY + t, faceZ), 1, 0.0, 0.0, 0.0, 0.0, targetDust)
            player.world.spawnParticle(Particle.DUST, Location(player.world, loc.blockX + 1.0, loc.blockY + t, faceZ), 1, 0.0, 0.0, 0.0, 0.0, targetDust)
        }
        player.world.spawnParticle(Particle.END_ROD, Location(player.world, loc.blockX + 0.5, loc.blockY + 0.5, faceZ), 3, 0.25, 0.25, 0.0, 0.01)
    }

    private fun buildSteleLocations(): List<Location> {
        val world = player.world
        val set = linkedSetOf<Location>()
        addRect(set, world, 123..125, 48..54)
        addRect(set, world, 119..123, 54..56)
        addRect(set, world, 119..123, 51..54)
        return set.toList()
    }

    private fun addRect(set: MutableSet<Location>, world: org.bukkit.World, xs: IntRange, ys: IntRange) {
        for (x in xs) {
            for (y in ys) {
                set.add(Location(world, x.toDouble(), y.toDouble(), 810.0))
            }
        }
    }

    private fun findClickedTarget(event: PlayerInteractEvent): Location? {
        val clicked = event.clickedBlock?.location
        if (clicked != null && isSteleLocation(clicked)) return clicked

        val target = player.getTargetBlockExact(8)?.location
        if (target != null && isSteleLocation(target)) return target

        return null
    }

    private fun isSteleLocation(loc: Location): Boolean {
        if (loc.world != player.world || loc.blockZ != 810) return false
        return (loc.blockX in 123..125 && loc.blockY in 48..54) ||
                (loc.blockX in 119..123 && loc.blockY in 54..56) ||
                (loc.blockX in 119..123 && loc.blockY in 51..54)
    }

    private fun sameBlock(a: Location, b: Location): Boolean {
        return a.world == b.world && a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ
    }

    private fun giveShuazi() {
        val brush = plugin.resourceManager.getItem("shangxian_shuazi")?.clone()
            ?: ItemStack(Material.BRUSH)
        brush.amount = 1
        val leftovers = player.inventory.addItem(brush)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun isShuazi(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING) == "shangxian_shuazi"
    }

    private fun removeShuazi() {
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val item = inv.getItem(slot) ?: continue
            if (!isShuazi(item)) continue
            inv.setItem(slot, null)
        }
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        removeShuazi()

        player.sendMessage("§a感谢道友相助，这本【冥想】，正适合心细严谨的你！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val book = plugin.medicalManager.getSkillBook("mingxiang")?.clone()
        if (book != null) {
            book.amount = 1
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        } else {
            plugin.logger.warning("篆元上仙医术试炼奖励 mingxiang 未找到。")
        }

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 100
            data.completedMedicalTrials.add(trialId)

            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        plugin.databaseManager.saveCompletedMedicalTrials(conn, data)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("保存医术试炼完成记录失败: ${e.message}")
                }
            })
        }

        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        cleanUp()
        removeShuazi()

        if (shouldMessage) {
            player.sendMessage("§c篆元上仙的医术试炼失败！")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        timeBar.removeAll()
        brushBar.removeAll()
    }
}
