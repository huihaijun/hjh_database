package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.random.Random

class HuZhenShangRenTrial(
    private val plugin: Hjh_database,
    override val player: org.bukkit.entity.Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "huzhenshangren"

    private enum class Phase {
        STORY,
        WAIT_BELL,
        PREPARE,
        HEATING,
        POST_FIRE,
        ENDED
    }

    private data class Herb(val id: String, val name: String, val category: String)

    private data class Recipe(
        val name: String,
        val category: String,
        val beforeFire: List<Herb>,
        val earlyFire: List<Herb>,
        val midFire: Herb,
        val afterFire: Herb
    )

    private data class BlockKey(val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(location: Location) = BlockKey(location.blockX, location.blockY, location.blockZ)
        }
    }

    private var phase = Phase.STORY
    private var tick = 0
    private var completedRecipes = 0
    private var recipeQueue = emptyList<Recipe>()
    private var currentRecipe: Recipe? = null
    private var beforeFireIndex = 0
    private var earlyFireIndex = 0
    private var midFireAdded = false
    private var midFirePrompted = false
    private var fireProgress = 0.0
    private var midFireThreshold = 0
    private var overheatedTicksLeft: Int? = null
    private var prepareTicksLeft = 0
    private var lastBellInteractMillis = 0L

    private val totalBar: BossBar = Bukkit.createBossBar(
        "§d虎镇商人的医术试炼 §7| §f总进度：0/3",
        BarColor.PURPLE,
        BarStyle.SEGMENTED_6
    )
    private val fireBar: BossBar = Bukkit.createBossBar(
        "§6炼药火候：0%",
        BarColor.YELLOW,
        BarStyle.SOLID
    )
    private val shelfHerbs = mutableMapOf<BlockKey, Herb>()
    private val shelfDisplays = mutableListOf<TextDisplay>()
    private val droppedHerbs = mutableListOf<org.bukkit.entity.Item>()

    private val startLocation = Location(player.world, -421.47, 122.00, 131.49, 4590.77f, 1.65f)
    private val exitLocation = Location(player.world, -399.98, 111.00, 145.73, 352.53f, 1.05f)
    private val bellLocation = Location(player.world, -417.0, 122.0, 128.0)
    private val vesselLocation = Location(player.world, -417.0, 122.0, 127.0)
    private val fireLocation = Location(player.world, -417.0, 121.0, 127.0)
    private val herbKey = NamespacedKey(plugin, "huzhen_trial_herb")

    private val warmJiang = Herb("warm_jiang", "§e暖姜", "§b抗寒系列")
    private val yanYangCao = Herb("yan_yang_cao", "§e炎阳草", "§b抗寒系列")
    private val wenMaiYe = Herb("wen_mai_ye", "§e温脉叶", "§b抗寒系列")
    private val quHanTeng = Herb("qu_han_teng", "§e驱寒藤", "§b抗寒系列")
    private val rongShuangHua = Herb("rong_shuang_hua", "§e融霜花", "§b抗寒系列")
    private val chiShiTai = Herb("chi_shi_tai", "§e炽石苔", "§b抗寒系列")
    private val xueJie = Herb("xue_jie", "§e血竭", "§c止血系列")
    private val ningXueCao = Herb("ning_xue_cao", "§e凝血草", "§c止血系列")
    private val yuShangGen = Herb("yu_shang_gen", "§e愈伤根", "§c止血系列")
    private val duanHongTeng = Herb("duan_hong_teng", "§e断红藤", "§c止血系列")
    private val shouHenYe = Herb("shou_hen_ye", "§e收痕叶", "§c止血系列")
    private val zhiXueLan = Herb("zhi_xue_lan", "§e止血兰", "§c止血系列")
    private val tieGuTeng = Herb("tie_gu_teng", "§e铁骨藤", "§a强身系列")
    private val zhuangJinCao = Herb("zhuang_jin_cao", "§e壮筋草", "§a强身系列")
    private val liShen = Herb("li_shen", "§e力参", "§a强身系列")
    private val huPoGuo = Herb("hu_po_guo", "§e虎魄果", "§a强身系列")
    private val qiangYuanGen = Herb("qiang_yuan_gen", "§e强元根", "§a强身系列")
    private val panShiHua = Herb("pan_shi_hua", "§e磐石花", "§a强身系列")

    private val allHerbs by lazy {
        listOf(
            warmJiang, yanYangCao, wenMaiYe, quHanTeng, rongShuangHua, chiShiTai,
            xueJie, ningXueCao, yuShangGen, duanHongTeng, shouHenYe, zhiXueLan,
            tieGuTeng, zhuangJinCao, liShen, huPoGuo, qiangYuanGen, panShiHua
        )
    }

    private val recipes by lazy {
        listOf(
            Recipe("暖脉丹", "§b抗寒", listOf(warmJiang, yanYangCao), emptyList(), wenMaiYe, chiShiTai),
            Recipe("融霜丸", "§b抗寒", listOf(quHanTeng), listOf(rongShuangHua), warmJiang, yanYangCao),
            Recipe("愈伤散", "§c止血", listOf(xueJie, ningXueCao), emptyList(), duanHongTeng, shouHenYe),
            Recipe("生肌丹", "§c止血", listOf(yuShangGen), emptyList(), zhiXueLan, shouHenYe),
            Recipe("壮骨丸", "§a强身", listOf(tieGuTeng, liShen), emptyList(), zhuangJinCao, panShiHua),
            Recipe("强元丹", "§a强身", listOf(qiangYuanGen), emptyList(), huPoGuo, liShen),
            Recipe("虎力丸", "§a强身", listOf(zhuangJinCao, huPoGuo), emptyList(), tieGuTeng, qiangYuanGen)
        )
    }

    private val storyMessages = listOf(
        "§f小医师，你来了。多谢你愿意出手相助，我便直说了。",
        "§f你眼前这间坍塌过半的旧屋，是当年虎金镇遗留下来的医庐。别的值钱物件早已不存，唯独这几排§e医柜§f保存尚好。柜中仍封存着当年居民留下的§a草药§f，夹层中还有几张§d丹方§f，只是年代久远，字迹多有残缺。",
        "§f我粗人一个，不通医术，这些药方搁在我手里便是废纸。所以想请你出手——依照这§d丹方§f上所载，尝试炼出几炉丹药来。",
        "§f这屋梁不稳，容不下两人同时在内。我在楼下替你§c生火§f，你在上面往§e炼药锅§f中下药，我们各司其职。",
        "§f稍后我会唤出丹药之名，你按§d丹方§f上记载的顺序，将对应草药依次投入那口§6桶§f中。药材便夹在你前后两排书架之间，右键轻触即可取下。唯有一点——§c顺序不可有丝毫差错§f，错一味则前功尽弃。",
        "§f投药完毕，敲一声§e钟§f，我便开始生火。但生火期间你不可松懈——部分丹方在炼制中途尚需§c添入额外药材§f，因方而异，届时我会提醒你。",
        "§f待火候到位，再敲一次§e钟§f，我便停火。务必时刻留意——火候若过，锅中丹药§c顷刻化为灰烬§f，再无挽回余地。",
        "§f五张丹方全部炼成，你的任务便算完成。事成之后，我这有一卷幼时从一位游方郎中处习得的§d医术§f，赠予你便是。有它傍身，穿行这§c白虎迷阵§f也能多几分底气。",
        "§f若已准备妥当，便敲钟提醒我开始吧。"
    )

    override fun start() {
        player.teleport(startLocation)
        setCampfireLit(false)
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
            Phase.PREPARE -> runPrepareCountdown()
            Phase.HEATING -> runHeating()
            Phase.POST_FIRE -> runPostFireDeadline()
            else -> Unit
        }
        tick++
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return

        if (isBell(block) && (event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_BLOCK)) {
            event.isCancelled = true
            val now = System.currentTimeMillis()
            if (now - lastBellInteractMillis < 500) return
            lastBellInteractMillis = now
            handleBell()
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (phase != Phase.PREPARE && phase != Phase.HEATING && phase != Phase.POST_FIRE) return

        val herb = shelfHerbs[BlockKey.from(block.location)]
        if (herb != null) {
            event.isCancelled = true
            giveHerb(herb)
            return
        }

        if (isAlchemyVessel(block)) {
            event.isCancelled = true
            player.sendMessage("§e请将从医柜取下的草药丢入堆肥桶中。")
        }
    }

    @EventHandler
    fun onDropHerb(event: PlayerDropItemEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (phase != Phase.PREPARE && phase != Phase.HEATING && phase != Phase.POST_FIRE) return

        val dropped = event.itemDrop
        val herb = getTrialHerb(dropped.itemStack) ?: return
        if (player.location.world != vesselLocation.world || player.location.distanceSquared(vesselLocation) > 4.0 * 4.0) {
            player.sendMessage("§c靠近堆肥桶后，再把草药丢进去。")
            return
        }

        droppedHerbs += dropped
        dropped.teleport(vesselLocation.clone().add(0.5, 0.85, 0.5))
        dropped.velocity = Vector(0.0, 0.0, 0.0)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (phase == Phase.ENDED || !dropped.isValid) return@Runnable
            submitDroppedHerb(dropped, herb)
        }, 2L)
    }

    private fun runStory() {
        if (tick % 60 != 0) return
        val messageIndex = tick / 60
        if (messageIndex < storyMessages.size) {
            player.sendMessage("§a§l虎镇商人 §f: ${storyMessages[messageIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            return
        }

        phase = Phase.WAIT_BELL
        tick = 0
        player.sendMessage("§e[医术试炼] §f请敲响屋内的§e钟§f，领取第一张丹方。")
    }

    private fun handleBell() {
        when (phase) {
            Phase.WAIT_BELL -> beginNextRecipe()
            Phase.PREPARE -> {
                val recipe = currentRecipe ?: return
                if (beforeFireIndex < recipe.beforeFire.size) {
                    player.sendMessage("§c药材尚未按丹方投完，先投药再敲钟开火。")
                } else {
                    beginHeating()
                }
            }
            Phase.HEATING -> {
                val recipe = currentRecipe ?: return
                if (fireProgress < 100.0) {
                    failWithMessage("§c火候尚未到位便停火，丹药未能炼成！")
                } else if (earlyFireIndex < recipe.earlyFire.size || !midFireAdded) {
                    failWithMessage("§c丹方所需的火中药材尚未投入，贸然停火，整炉药都毁了！")
                } else {
                    phase = Phase.POST_FIRE
                    setCampfireLit(false)
                    player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f)
                    player.sendMessage("§e火已停！请立刻投入丹方最后一味草药！")
                }
            }
            else -> Unit
        }
    }

    private fun beginNextRecipe() {
        if (recipeQueue.isEmpty()) {
            recipeQueue = recipes.shuffled().take(3)
        }
        currentRecipe = recipeQueue[completedRecipes]
        beforeFireIndex = 0
        earlyFireIndex = 0
        midFireAdded = false
        midFirePrompted = false
        fireProgress = 0.0
        midFireThreshold = Random.nextInt(40, 71)
        overheatedTicksLeft = null
        prepareTicksLeft = 12 * 20
        phase = Phase.PREPARE
        setCampfireLit(false)
        removeTrialHerbs()

        totalBar.addPlayer(player)
        fireBar.addPlayer(player)
        populateShelves(currentRecipe!!)
        updateBars()
        player.sendMessage("§d[残缺丹方] §f本炉炼制：${formatRecipeName(currentRecipe!!)}")
        sendRecipe(currentRecipe!!)
        player.sendMessage("§c本炉只有 12 秒准备时间，请尽快投药并敲钟开火！")
    }

    private fun beginHeating() {
        phase = Phase.HEATING
        setCampfireLit(true)
        player.playSound(player.location, Sound.BLOCK_FIRE_AMBIENT, 1f, 1f)
        player.sendMessage("§6虎镇商人 §f: 火已生起，留意火候；到时我会提醒你添药。")
        currentRecipe?.earlyFire?.firstOrNull()?.let { herb ->
            player.sendMessage("§6虎镇商人 §f: 先投入${herb.name}§f，莫要耽搁！")
        }
    }

    private fun runPrepareCountdown() {
        prepareTicksLeft--
        if (prepareTicksLeft <= 0) {
            failWithMessage("§c十二秒内未能完成投药并敲钟开火，丹方药性已经散尽！")
            return
        }
        if (prepareTicksLeft % 20 == 0) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.65f, 1.2f)
        }
        updateBars()
    }

    private fun formatRecipeName(recipe: Recipe): String = "§e${recipe.name}§f【${recipe.category}§f】"

    private fun sendRecipe(recipe: Recipe) {
        val beforeFireSteps = recipe.beforeFire.mapIndexed { index, herb ->
            if (index == 0) "先取${herb.name}§f放入锅中" else "再取${herb.name}§f放入锅中"
        }
        val earlyFireSteps = recipe.earlyFire.map { herb -> "开火后取${herb.name}§f放入锅中" }
        player.sendMessage("§d丹方残页 §f${formatRecipeName(recipe)}§f：")
        player.sendMessage("§f${(beforeFireSteps + "随后敲钟开火").joinToString("，")}。")
        if (earlyFireSteps.isNotEmpty()) {
            player.sendMessage("§f${earlyFireSteps.joinToString("，")}。")
        }
        player.sendMessage("§f火候升至§e$midFireThreshold%§f时，再取${recipe.midFire.name}§f放入锅中。")
        player.sendMessage("§f火候至§c100%§f时敲钟关火，§c4 秒内§f再取${recipe.afterFire.name}§f放入锅中。")
    }

    private fun giveHerb(herb: Herb) {
        val item = ItemStack(Material.NETHER_WART)
        item.itemMeta = item.itemMeta?.apply {
            setDisplayName(herb.name)
            lore = listOf(herb.category, "§7虎镇医庐试炼药材")
            persistentDataContainer.set(herbKey, PersistentDataType.STRING, herb.id)
        }
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§e已取下 ${herb.name}§e，靠近堆肥桶后将它丢入其中。")
        player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_PICKUP, 0.8f, 1.2f)
    }

    private fun getTrialHerb(item: ItemStack?): Herb? {
        if (item == null || item.type != Material.NETHER_WART || !item.hasItemMeta()) return null
        val id = item.itemMeta.persistentDataContainer.get(herbKey, PersistentDataType.STRING) ?: return null
        return allHerbs.firstOrNull { it.id == id }
    }

    private fun removeTrialHerbs() {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (getTrialHerb(item) != null) inventory.setItem(slot, null)
        }
    }

    private fun submitDroppedHerb(dropped: org.bukkit.entity.Item, herb: Herb) {
        val recipe = currentRecipe ?: return
        val expected = when (phase) {
            Phase.PREPARE -> recipe.beforeFire.getOrNull(beforeFireIndex)
            Phase.HEATING -> when {
                earlyFireIndex < recipe.earlyFire.size -> recipe.earlyFire[earlyFireIndex]
                !midFireAdded && fireProgress >= midFireThreshold -> recipe.midFire
                else -> null
            }
            Phase.POST_FIRE -> recipe.afterFire
            else -> null
        }

        if (expected == null) {
            dropped.remove()
            failWithMessage("§c添药时机不对，丹方药性相冲，整炉药毁了！")
            return
        }
        if (herb != expected) {
            dropped.remove()
            failWithMessage("§c投入草药的顺序错了，整炉丹药化为了灰烬！")
            return
        }

        consumeDroppedHerb(dropped)
        player.playSound(vesselLocation, Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.15f)
        vesselLocation.world.spawnParticle(Particle.HAPPY_VILLAGER, vesselLocation.clone().add(0.5, 1.0, 0.5), 12, 0.25, 0.25, 0.25, 0.03)

        when (phase) {
            Phase.PREPARE -> {
                beforeFireIndex++
                if (beforeFireIndex >= recipe.beforeFire.size) {
                    player.sendMessage("§a药材已按顺序投完，敲钟开火！")
                } else {
                    player.sendMessage("§a投药正确，继续投入下一味草药。")
                }
            }
            Phase.HEATING -> {
                if (earlyFireIndex < recipe.earlyFire.size) {
                    earlyFireIndex++
                    player.sendMessage("§a添药正确，继续掌控火候。")
                } else {
                    midFireAdded = true
                    player.sendMessage("§a过半添药正确，继续等待火候到位。")
                }
            }
            Phase.POST_FIRE -> completeRecipe()
            else -> Unit
        }
    }

    private fun consumeDroppedHerb(dropped: org.bukkit.entity.Item) {
        val stack = dropped.itemStack
        stack.amount -= 1
        if (stack.amount <= 0) {
            dropped.remove()
        } else {
            dropped.itemStack = stack
        }
    }

    private fun runHeating() {
        fireProgress = (fireProgress + 100.0 / (12 * 20)).coerceAtMost(100.0)
        val vessel = vesselLocation
        if (tick % 5 == 0) {
            vessel.world.spawnParticle(Particle.FLAME, vessel.clone().add(0.5, 1.0, 0.5), 5, 0.2, 0.15, 0.2, 0.01)
            vessel.world.spawnParticle(Particle.SMOKE, vessel.clone().add(0.5, 1.15, 0.5), 2, 0.15, 0.15, 0.15, 0.01)
        }

        val recipe = currentRecipe ?: return
        if (!midFireAdded && earlyFireIndex >= recipe.earlyFire.size && !midFirePrompted && fireProgress >= midFireThreshold) {
            player.sendMessage("§6虎镇商人 §f: 火候过半了！快投入${recipe.midFire.name}§f！")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f)
            midFirePrompted = true
        }

        if (fireProgress >= 100.0) {
            if (overheatedTicksLeft == null) {
                overheatedTicksLeft = 4 * 20
                player.sendMessage("§c火候已到极限！4 秒内敲钟停火，并投入最后一味${recipe.afterFire.name}§c！")
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.6f)
            }
            overheatedTicksLeft = overheatedTicksLeft!! - 1
            if (overheatedTicksLeft!! <= 0) {
                failWithMessage("§c火候过了，锅中丹药顷刻化为灰烬！")
                return
            }
        }
        updateBars()
    }

    private fun runPostFireDeadline() {
        val deadline = (overheatedTicksLeft ?: 0) - 1
        overheatedTicksLeft = deadline
        if (deadline <= 0) {
            failWithMessage("§c停火后未能及时添药，丹药化为了灰烬！")
            return
        }
        updateBars()
    }

    private fun completeRecipe() {
        completedRecipes++
        currentRecipe?.let { recipe ->
            player.sendMessage("§a${formatRecipeName(recipe)}§a炼制成功！当前总进度：§e$completedRecipes/3")
        }
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f)
        clearShelfDisplays()

        if (completedRecipes >= 3) {
            win()
            return
        }

        phase = Phase.WAIT_BELL
        currentRecipe = null
        fireProgress = 0.0
        overheatedTicksLeft = null
        updateBars()
        player.sendMessage("§e敲钟领取下一张丹方。")
    }

    private fun updateBars() {
        totalBar.progress = (completedRecipes / 3.0).coerceIn(0.0, 1.0)
        totalBar.setTitle("§d虎镇商人的医术试炼 §7| §f总进度：§e$completedRecipes/3" +
                (currentRecipe?.let { " §7| §f当前：${formatRecipeName(it)}" } ?: ""))
        fireBar.progress = when (phase) {
            Phase.PREPARE -> (prepareTicksLeft / (12.0 * 20.0)).coerceIn(0.0, 1.0)
            else -> (fireProgress / 100.0).coerceIn(0.0, 1.0)
        }
        fireBar.setTitle(
            when (phase) {
                Phase.PREPARE -> "§6准备开火：§e${((prepareTicksLeft + 19) / 20).coerceAtLeast(0)} 秒"
                Phase.POST_FIRE -> "§6炼药火候：§e${fireProgress.toInt()}% §c| §f立刻添药！"
                else -> "§6炼药火候：§e${fireProgress.toInt()}%"
            }
        )
        fireBar.color = when {
            fireProgress >= 100.0 -> BarColor.RED
            fireProgress >= 70.0 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }
    }

    private fun populateShelves(recipe: Recipe) {
        clearShelfDisplays()
        val shelves = findBookshelves()
        if (shelves.isEmpty()) {
            failWithMessage("§c医庐中的医柜未找到，试炼无法继续。")
            return
        }

        val required = recipe.beforeFire + recipe.earlyFire + recipe.midFire + recipe.afterFire
        val entries = required.toMutableList()
        val distractors = allHerbs.filter { it !in required }.shuffled()
        entries += distractors.take((shelves.size - entries.size).coerceAtLeast(0))
        while (entries.size < shelves.size) entries += allHerbs.random()

        shelves.shuffled().zip(entries.shuffled()).forEach { (shelf, herb) ->
            shelfHerbs[BlockKey.from(shelf.location)] = herb
            spawnShelfDisplay(shelf.location, herb)
        }
    }

    private fun findBookshelves(): List<Block> {
        val found = mutableListOf<Block>()
        for (y in 122..124) {
            for (z in 129..133) addBookshelfIfPresent(found, player.world.getBlockAt(-417, y, z))
            for (z in 130..133) addBookshelfIfPresent(found, player.world.getBlockAt(-424, y, z))
        }
        return found
    }

    private fun addBookshelfIfPresent(target: MutableList<Block>, block: Block) {
        if (block.type == Material.BOOKSHELF || block.type == Material.CHISELED_BOOKSHELF) target += block
    }

    private fun spawnShelfDisplay(location: Location, herb: Herb) {
        val xOffset = if (location.blockX == -417) -0.06 else 1.06
        val textLocation = Location(location.world, location.blockX + xOffset, location.blockY + 0.42, location.blockZ + 0.5)
        val display = location.world.spawn(textLocation, TextDisplay::class.java) { entity ->
            entity.text(LegacyComponentSerializer.legacySection().deserialize("${herb.name}\n${herb.category}"))
            entity.billboard = Display.Billboard.FIXED
            entity.setRotation(if (location.blockX == -417) 90f else -90f, 0f)
            entity.isSeeThrough = false
            entity.isShadowed = true
            entity.addScoreboardTag("hjh_huzhen_medical_trial")
        }
        shelfDisplays += display
    }

    private fun isBell(block: Block): Boolean {
        return block.type == Material.BELL && sameBlock(block.location, bellLocation)
    }

    private fun isAlchemyVessel(block: Block): Boolean {
        return block.type == Material.COMPOSTER && sameBlock(block.location, vesselLocation)
    }

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world && first.blockX == second.blockX && first.blockY == second.blockY && first.blockZ == second.blockZ
    }

    private fun setCampfireLit(lit: Boolean) {
        val block = fireLocation.block
        if (block.type != Material.CAMPFIRE) {
            plugin.logger.warning("虎镇商人医术试炼营火坐标不是 CAMPFIRE：${block.type}")
            return
        }
        val data = block.blockData as? Lightable ?: return
        data.isLit = lit
        block.blockData = data
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        player.teleport(exitLocation)
        player.sendMessage("§a虎镇商人 §f: §a五炉丹药皆已炼成，这卷念气劲便赠予你！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        val book = plugin.medicalManager.getSkillBook("nianqijin")?.clone()
        if (book != null) {
            book.amount = 1
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        } else {
            plugin.logger.warning("虎镇商人医术试炼奖励 nianqijin 未找到。")
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
                    plugin.logger.severe("保存虎镇商人医术试炼完成记录失败: ${exception.message}")
                }
            })
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    private fun failWithMessage(message: String) {
        if (phase == Phase.ENDED) return
        player.sendMessage(message)
        fail()
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        cleanUp()
        if (player.isOnline && !player.isDead) player.teleport(exitLocation)
        if (shouldMessage) player.sendMessage("§c虎镇商人的医术试炼失败！")
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        setCampfireLit(false)
        totalBar.removeAll()
        fireBar.removeAll()
        clearShelfDisplays()
        removeTrialHerbs()
        droppedHerbs.forEach { dropped -> if (dropped.isValid) dropped.remove() }
        droppedHerbs.clear()
    }

    private fun clearShelfDisplays() {
        shelfDisplays.forEach { display ->
            if (display.isValid) display.remove()
        }
        shelfDisplays.clear()
        shelfHerbs.clear()
    }
}
