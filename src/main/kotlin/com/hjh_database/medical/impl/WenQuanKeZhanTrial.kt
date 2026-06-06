package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
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

class WenQuanKeZhanTrial(
    private val plugin: Hjh_database,
    override val player: org.bukkit.entity.Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "wenquankezhan"

    private enum class Phase {
        STORY,
        WAIT_BELL,
        ROUND_COUNTDOWN,
        REVEAL,
        INPUT,
        ENDED
    }

    private var phase = Phase.STORY
    private var tick = 0
    private var completedRounds = 0
    private var roundCountdownTicks = 0
    private var revealIndex = 0
    private var smokeTicks = 0
    private var expectedClickIndex = 0
    private var inputTicksLeft = 60 * 20
    private var hasRetriedCurrentRound = false
    private var sequence = emptyList<Location>()

    private val completionBar: BossBar = Bukkit.createBossBar("§d温泉客栈的医术试炼-完成度", BarColor.PURPLE, BarStyle.SEGMENTED_10)
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val bellLoc = Location(player.world, -474.0, 97.0, 344.0)

    private val roundSizes = listOf(3, 3, 3, 3, 4)
    private val smokeOptions = Particle.DustOptions(Color.fromRGB(164, 73, 255), 1.5f)

    private val storyMessages = listOf(
        "§f哟，治病的！快来快来，这边请！",
        "§f这干得冒烟的破地方，可算把您这位§d医师§f给盼来了。一路上累坏了吧？要不要先去后头那口§b温泉§f里泡一泡解解乏？咱这儿不光给您更衣，还给您搓搓背、按按摩，包您浑身舒坦……",
        "§f嘿嘿，实不相瞒，小的确实有事相求。您想啊，那些个玩刀舞弓放火的，哪比得上咱§d悬壶济世的仁者§f来得稳当？这事儿啊，非得您出手不可。",
        "§f这不最近生意冷清嘛，我们几个人一合计，琢磨着往温泉里添几味§a草药§f，弄成个§6药浴§f，好歹招揽些客官回来。可我们几个大老粗哪懂这些？四处求人愣是找不到，正发愁呢，您就来了！",
        "§f待会儿您拿上这个§6罐子§f，站到温泉边上去。我在下头控制§c火候§f，火一大，水里有些石头便会冒出§d紫烟§f。您瞧仔细了，哪块石头先冒烟，就往里头撒药，§c顺序千万不能错§f，错了一次，这锅药浴可就全毁了。",
        "§f总共来上§c五趟§f，趟趟不差，这本§d医术宝典§f，俺便双手奉上！",
        "§f准备好了的话，去后头温泉旁边敲一下那口§c钟§f，咱们马上开干！"
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
            Phase.WAIT_BELL -> updateCompletionBar()
            Phase.ROUND_COUNTDOWN -> runRoundCountdown()
            Phase.REVEAL -> runReveal()
            Phase.INPUT -> runInputCountdown()
            Phase.ENDED -> return
        }

        tick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (event.hand != EquipmentSlot.HAND) return

        if (phase == Phase.WAIT_BELL && isBellClick(event)) {
            event.isCancelled = true
            player.world.playSound(bellLoc.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_BELL_USE, 1f, 1f)
            beginRoundCountdown()
            return
        }

        if (phase != Phase.INPUT) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val clickLoc = findSpringClickLocation(event) ?: return

        event.isCancelled = true
        if (!isCaoyaoGuan(player.inventory.itemInMainHand)) {
            player.sendMessage("§c你需要主手拿着温泉客栈老板给的草药罐！")
            return
        }

        val expected = sequence.getOrNull(expectedClickIndex) ?: return
        if (clickLoc.blockX != expected.blockX || clickLoc.blockZ != expected.blockZ) {
            player.sendMessage("§c投药顺序错了，这锅药浴毁了！")
            handleRoundFailure()
            return
        }

        expectedClickIndex++
        spawnSuccessParticles(expected)
        player.playSound(player.location, Sound.ITEM_BOTTLE_FILL, 1f, 1.2f)

        if (expectedClickIndex >= sequence.size) {
            completeRound()
        } else {
            player.sendMessage("§a顺序正确！继续投下一处草药。")
        }
    }

    private fun runStory() {
        if (tick % 60 != 0) return

        val msgIndex = tick / 60
        if (msgIndex < storyMessages.size) {
            player.sendMessage("§a§l温泉客栈老板 §f: ${storyMessages[msgIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
        } else {
            giveCaoyaoGuan()
            completionBar.addPlayer(player)
            phase = Phase.WAIT_BELL
            tick = 0
            updateCompletionBar()
            player.sendMessage("§e[医术试炼] §f已获得§6草药罐§f，去后头温泉旁敲响§c钟§f开始试炼。")
        }
    }

    private fun beginRoundCountdown() {
        val nextRoundSize = roundSizes.getOrNull(completedRounds) ?: return
        roundCountdownTicks = 10 * 20
        phase = Phase.ROUND_COUNTDOWN
        tick = 0
        updateCompletionBar()
        player.sendMessage("§e第 ${completedRounds + 1} 轮将在 §c10§e 秒后开始，准备记住紫烟顺序！")
        if (nextRoundSize >= 4) {
            player.sendMessage("§e本轮将出现§c${nextRoundSize}§e次紫烟，请注意！")
        }
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.0f)
    }

    private fun runRoundCountdown() {
        if (roundCountdownTicks <= 0) {
            startRound()
            return
        }

        updateCompletionBar()
        if (roundCountdownTicks % 20 == 0) {
            val seconds = roundCountdownTicks / 20
            player.sendMessage("§e第 ${completedRounds + 1} 轮倒计时：§c$seconds")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.0f + (10 - seconds) * 0.05f)
        }
        roundCountdownTicks--
    }

    private fun startRound() {
        val size = roundSizes.getOrNull(completedRounds) ?: return
        sequence = buildSpringLocations().shuffled().take(size)
        revealIndex = 0
        smokeTicks = 0
        expectedClickIndex = 0
        inputTicksLeft = 60 * 20
        phase = Phase.REVEAL
        tick = 0
        updateCompletionBar()
        player.sendMessage("§e第 ${completedRounds + 1} 轮开始，仔细记住紫烟出现的顺序！")
    }

    private fun runReveal() {
        if (revealIndex >= sequence.size) {
            phase = Phase.INPUT
            tick = 0
            inputTicksLeft = 60 * 20
            player.sendMessage("§e请按刚才紫烟出现的顺序，主手持草药罐右键对应位置！")
            updateCompletionBar()
            return
        }

        val loc = sequence[revealIndex]
        if (smokeTicks == 0) {
            player.playSound(loc.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 1.4f)
        }
        spawnPurpleSmoke(loc)
        smokeTicks++

        if (smokeTicks >= 40) {
            revealIndex++
            smokeTicks = 0
        }
    }

    private fun runInputCountdown() {
        inputTicksLeft--
        if (inputTicksLeft <= 0) {
            player.sendMessage("§c一炷香都快烧完了，药浴没配成……")
            handleRoundFailure()
            return
        }

        if (tick % 10 == 0) {
            updateCompletionBar()
        }
    }

    private fun completeRound() {
        completedRounds++
        hasRetriedCurrentRound = false
        updateCompletionBar()
        player.sendMessage("§a第 $completedRounds 轮药浴调配成功！")
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

        if (completedRounds >= roundSizes.size) {
            win()
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (phase != Phase.ENDED && player.isOnline) {
                    beginRoundCountdown()
                }
            }, 30L)
        }
    }

    private fun handleRoundFailure() {
        if (!hasRetriedCurrentRound) {
            hasRetriedCurrentRound = true
            player.sendMessage("§a§l温泉客栈老板 §f: §f手抖了？小问题，这一轮重新来过！")
            player.sendMessage("§a§l温泉客栈老板 §f: §f但要再失误，这药浴就算是完了！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            beginRoundCountdown()
            return
        }

        fail()
    }

    private fun updateCompletionBar() {
        completionBar.progress = (completedRounds / 5.0).coerceIn(0.0, 1.0)
        val title = if (phase == Phase.INPUT) {
            "§d温泉客栈的医术试炼-完成度：$completedRounds/5 §7| §f本轮剩余 ${inputTicksLeft / 20} 秒"
        } else if (phase == Phase.ROUND_COUNTDOWN) {
            "§d温泉客栈的医术试炼-完成度：$completedRounds/5 §7| §f第 ${completedRounds + 1} 轮倒计时 ${((roundCountdownTicks + 19) / 20).coerceAtLeast(0)} 秒"
        } else {
            "§d温泉客栈的医术试炼-完成度：$completedRounds/5"
        }
        completionBar.setTitle(title)
    }

    private fun isBellClick(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return false
        val block = event.clickedBlock ?: return false
        return block.type == Material.BELL &&
                block.location.blockX == bellLoc.blockX &&
                block.location.blockY == bellLoc.blockY &&
                block.location.blockZ == bellLoc.blockZ
    }

    private fun findSpringClickLocation(event: PlayerInteractEvent): Location? {
        val clicked = event.clickedBlock?.location
        if (clicked != null && isSpringArea(clicked)) return clicked

        val target = player.getTargetBlockExact(6, FluidCollisionMode.ALWAYS)?.location
        if (target != null && isSpringArea(target)) return target

        return null
    }

    private fun buildSpringLocations(): List<Location> {
        val world = player.world
        val list = mutableListOf<Location>()
        for (x in -485..-475) {
            for (z in 344..351) {
                list.add(Location(world, x.toDouble(), 96.0, z.toDouble()))
            }
        }
        return list
    }

    private fun isSpringArea(loc: Location): Boolean {
        return loc.world == player.world &&
                loc.blockX in -485..-475 &&
                loc.blockZ in 344..351 &&
                loc.blockY in 95..97
    }

    private fun spawnPurpleSmoke(loc: Location) {
        val particleLoc = loc.clone().add(0.5, 0.6, 0.5)
        player.world.spawnParticle(Particle.DUST, particleLoc, 14, 0.25, 0.35, 0.25, smokeOptions)
        player.world.spawnParticle(Particle.WITCH, particleLoc, 3, 0.18, 0.2, 0.18, 0.0)
    }

    private fun spawnSuccessParticles(loc: Location) {
        val particleLoc = loc.clone().add(0.5, 0.6, 0.5)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, particleLoc, 16, 0.3, 0.25, 0.3, 0.05)
    }

    private fun giveCaoyaoGuan() {
        val jar = plugin.resourceManager.getItem("wenquan_caoyaoguan")?.clone()
            ?: ItemStack(Material.FLOWER_POT)
        jar.amount = 1
        val leftovers = player.inventory.addItem(jar)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun isCaoyaoGuan(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING) == "wenquan_caoyaoguan"
    }

    private fun removeCaoyaoGuan() {
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val item = inv.getItem(slot) ?: continue
            if (!isCaoyaoGuan(item)) continue
            inv.setItem(slot, null)
        }
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        removeCaoyaoGuan()

        player.sendMessage("§a温泉客栈老板 §f: §a好！五趟药浴全成了！这本医术宝典归您了！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val book = plugin.medicalManager.getSkillBook("huichunyu")?.clone()
        if (book != null) {
            book.amount = 1
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        } else {
            plugin.logger.warning("温泉客栈医术试炼奖励 huichunyu 未找到。")
        }

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 100)
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
        removeCaoyaoGuan()

        if (shouldMessage) {
            player.sendMessage("§c温泉客栈的医术试炼失败！")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        completionBar.removeAll()
    }
}
