package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
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

class WangYuanWaiTrial(
    private val plugin: Hjh_database,
    override val player: org.bukkit.entity.Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "wangyuanwai"

    private enum class Phase {
        STORY,
        SEARCH,
        CARRY,
        ENDED
    }

    private var phase = Phase.STORY
    private var tick = 0
    private var trialTicksLeft = 210 * 20
    private var searchProgress = 0.0
    private var lastSearchClickMillis = 0L
    private var lastAcceptedSearchClickMillis = 0L
    private var lingzhiTemperature = 20.0
    private var highTempTicks = 0
    private var hasShownSearchHint = false
    private var lastCarryLocation: Location? = null

    private val countdownBar: BossBar = Bukkit.createBossBar("§e王员外的医术试炼-倒计时", BarColor.YELLOW, BarStyle.SOLID)
    private var searchBar: BossBar? = null
    private var temperatureBar: BossBar? = null

    private val startLoc = Location(player.world, -336.50, 71.50, 418.50)
    private val hopperLoc = Location(player.world, -177.0, 53.0, 515.0)
    private val wangYuanWaiLoc = Location(player.world, -283.0, 55.0, 395.0)
    private val resourceKey = NamespacedKey(plugin, "resource_id")

    private val storyMessages = listOf(
        "§f大侠留步！实不相瞒，我一眼便瞧出您是位§d德高望重的医师§f，这事儿旁的人干不了，我只能把您拉到一旁悄悄说了……",
        "§f我娘卧病在床许久了，就指着那一株§e千年灵芝§f续命。原想着送完这趟货便带着灵芝回去孝敬她，哪知半路杀出那帮天杀的§c马贼团§f，连人带车抢了个干净……",
        "§f那株灵芝多半被他们头头带回§c老巢§f去了。可恨漫山遍野都是些野菇杂草，只有医师的这双慧眼，才能一眼辨出哪株才是真正的§e千年灵芝§f。大侠，小的只能仰仗您了！",
        "§f您从此地出发，一路§b向东§f，绕过山丘便能瞧见他们的§c大本营§f。这帮贼寇喜欢把抢来的好东西藏在营地深处的§6漏斗§f里，那儿戒备最严。您得手之后只管撒腿跑，不必跟他们多纠缠。",
        "§f不过还有桩要紧事——这§e千年灵芝§f娇贵得很，§b喜寒厌热§f。南方这鬼天气跟蒸笼似的，您走两步就得停一停，想法子给它§9降降温§f。而且它离了我这§6土盆§f便撑不了太久，§c三分半§f之内若还没取回来，灵芝便会枯死，药效尽失……",
        "§f大侠，只要您能替我办成这件事，小的这儿有一卷§d失传已久的医术§f，愿拱手奉上，绝不食言！",
        "§f我娘的性命，就全系在大侠您手上了！"
    )

    override fun start() {
        player.teleport(startLoc)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        when (phase) {
            Phase.STORY -> runStoryPhase()
            Phase.SEARCH -> runTimedPhase {
                decaySearchProgress()
            }
            Phase.CARRY -> runTimedPhase {
                updateTemperature()
            }
            Phase.ENDED -> return
        }

        tick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (event.hand == EquipmentSlot.OFF_HAND) return

        if (phase == Phase.SEARCH && event.action == Action.RIGHT_CLICK_BLOCK) {
            val block = event.clickedBlock ?: return
            if (block.location.blockX == hopperLoc.blockX &&
                block.location.blockY == hopperLoc.blockY &&
                block.location.blockZ == hopperLoc.blockZ &&
                block.type == Material.HOPPER
            ) {
                event.isCancelled = true
                handleHopperSearchClick()
            }
        } else if (phase == Phase.CARRY &&
            (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) &&
            isNearWangYuanWai() &&
            isLingzhi(player.inventory.itemInMainHand)
        ) {
            event.isCancelled = true
            win()
        }
    }

    private fun runStoryPhase() {
        if (tick % 60 != 0) return

        val msgIndex = tick / 60
        if (msgIndex < storyMessages.size) {
            player.sendMessage("§a§l被抢劫的富商-王员外 §f: ${storyMessages[msgIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
        } else {
            phase = Phase.SEARCH
            tick = 0
            countdownBar.addPlayer(player)
            updateCountdownBar()
            player.sendMessage("§e[医术试炼] §f三分半倒计时已开始，尽快找回§e千年灵芝§f！")
        }
    }

    private fun runTimedPhase(action: () -> Unit) {
        trialTicksLeft--
        if (trialTicksLeft <= 0) {
            player.sendMessage("§c三分半已过，千年灵芝药效尽失……")
            fail()
            return
        }

        updateCountdownBar()
        action()
    }

    private fun handleHopperSearchClick() {
        val now = System.currentTimeMillis()
        if (now - lastAcceptedSearchClickMillis < 500) return

        lastAcceptedSearchClickMillis = now
        lastSearchClickMillis = now

        val bar = searchBar ?: Bukkit.createBossBar(
            "§b正在漏斗寻找灵芝——完成度：0%",
            BarColor.BLUE,
            BarStyle.SEGMENTED_10
        ).also {
            searchBar = it
            it.addPlayer(player)
        }

        if (!hasShownSearchHint) {
            hasShownSearchHint = true
            player.sendMessage("§c请尽快右键漏斗来翻找灵芝，长时间不点击会导致完成度下降")
        }

        searchProgress = (searchProgress + Random.nextDouble(4.0, 8.0)).coerceAtMost(100.0)
        updateSearchBar(bar)
        player.playSound(player.location, Sound.BLOCK_BARREL_OPEN, 0.8f, 1.2f)

        if (searchProgress >= 100.0) {
            giveLingzhi()
            searchBar?.removeAll()
            searchBar = null

            phase = Phase.CARRY
            tick = 0
            lastCarryLocation = player.location.clone()
            temperatureBar = Bukkit.createBossBar("§b灵芝温度：20度", BarColor.GREEN, BarStyle.SOLID).also {
                it.addPlayer(player)
            }
            player.sendMessage("§a你在漏斗深处找到了千年灵芝！快带回王员外身边右键提交！")
            player.sendMessage("§c灵芝喜寒厌热，长时间移动会增加灵芝的温度，适当停下来！")
            player.sendMessage("§c不要让他处于80度高温超过3秒，否则灵芝将消失！")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        }
    }

    private fun decaySearchProgress() {
        val bar = searchBar ?: return
        if (tick % 10 != 0) return
        if (System.currentTimeMillis() - lastSearchClickMillis <= 500) return
        if (searchProgress <= 0.0) return

        searchProgress = (searchProgress - 3.0).coerceAtLeast(0.0)
        updateSearchBar(bar)
    }

    private fun updateTemperature() {
        if (tick % 10 != 0) return

        val current = player.location.clone()
        val previous = lastCarryLocation
        val moved = previous != null && previous.world == current.world &&
                current.distanceSquared(previous) > 0.01

        lingzhiTemperature = if (moved) {
            (lingzhiTemperature + 1.8).coerceAtMost(100.0)
        } else {
            (lingzhiTemperature - 1.4).coerceAtLeast(0.0)
        }
        lastCarryLocation = current

        val tempBar = temperatureBar ?: return
        tempBar.progress = (lingzhiTemperature / 100.0).coerceIn(0.0, 1.0)
        tempBar.setTitle("§b灵芝温度：${lingzhiTemperature.roundToInt()}度")
        tempBar.color = when {
            lingzhiTemperature >= 80.0 -> BarColor.RED
            lingzhiTemperature >= 65.0 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }

        if (lingzhiTemperature > 80.0) {
            highTempTicks += 10
            if (highTempTicks >= 60) {
                failByHighTemperature()
            }
        } else {
            highTempTicks = 0
        }
    }

    private fun updateCountdownBar() {
        countdownBar.progress = (trialTicksLeft / (210.0 * 20.0)).coerceIn(0.0, 1.0)
        countdownBar.setTitle("§e王员外的医术试炼-倒计时")
    }

    private fun updateSearchBar(bar: BossBar) {
        val percent = searchProgress.roundToInt().coerceIn(0, 100)
        bar.progress = (searchProgress / 100.0).coerceIn(0.0, 1.0)
        bar.setTitle("§b正在漏斗寻找灵芝——完成度：$percent%")
    }

    private fun giveLingzhi() {
        val lingzhi = plugin.resourceManager.getItem("wangyuanwai_qiannianlingzhi")?.clone()
            ?: ItemStack(Material.POTATO)
        lingzhi.amount = 1
        val leftovers = player.inventory.addItem(lingzhi)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        consumeOneFromMainHand()
        cleanUp()

        player.sendMessage("§a大恩不言谢，大侠！这门医术你且收下，我这就回去救我老娘！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val book = plugin.medicalManager.getSkillBook("bingqingyu")?.clone()
        if (book != null) {
            book.amount = 1
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        } else {
            plugin.logger.warning("王员外医术试炼奖励 bingqingyu 未找到。")
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

    private fun failByHighTemperature() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        removeOneLingzhiFromInventory()
        cleanUp()
        if (player.isOnline) {
            player.sendMessage("§c千年灵芝因高温死去，已化作灰烬消失了……")
            player.sendMessage("§7医术试炼失败……")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        removeOneLingzhiFromInventory()
        cleanUp()
        if (shouldMessage) {
            player.sendMessage("§c医术试炼失败！")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        countdownBar.removeAll()
        searchBar?.removeAll()
        temperatureBar?.removeAll()
    }

    private fun isNearWangYuanWai(): Boolean {
        return player.location.world == wangYuanWaiLoc.world &&
                player.location.distanceSquared(wangYuanWaiLoc) <= 3 * 3
    }

    private fun isLingzhi(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING) ==
                "wangyuanwai_qiannianlingzhi"
    }

    private fun consumeOneFromMainHand() {
        val item = player.inventory.itemInMainHand
        if (!isLingzhi(item)) return
        item.amount -= 1
    }

    private fun removeOneLingzhiFromInventory() {
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val item = inv.getItem(slot) ?: continue
            if (!isLingzhi(item)) continue
            item.amount -= 1
            return
        }
    }
}
