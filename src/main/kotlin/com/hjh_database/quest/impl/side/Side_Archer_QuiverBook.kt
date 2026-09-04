package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.random.Random

class Side_Archer_QuiverBook : QuestBase("side_archer_quiver_book", "[支线]老猎户的箭袋", QuestType.SIDE, 1), Listener {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val trials = HashMap<UUID, TrialSession>()
    private val pendingTrials = mutableSetOf<UUID>()
    private val introNightVariants = mutableMapOf<UUID, Boolean>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val requiredHits = 10
    private val trialDurationTicks = 1200
    private val targetLifetimeTicks = 160

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return data.job == 1 && data.lv >= 15
    }

    override val description = listOf(
        "§7退休的老猎户愿意传授粗布箭袋的制作法。",
        "§7但在那之前，他想看看你的眼力和箭法。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往龙须镇寻找 §e退休的老猎户")
            1 -> listOf("§c敲响桥顶的钟，开始红叶射击试炼")
            2 -> listOf("§c回去找 §e退休的老猎户 §c听取评价")
            3 -> listOf("§c回去找 §e退休的老猎户 §c领取奖励")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != "tuixiudelaoliehu") return false

        when (currentProgress) {
            0 -> {
                val useNightScript = introNightVariants.getOrPut(player.uniqueId) { isDuskOrNight(player) }
                val script = if (useNightScript) introScript + nightVisionScript + finalIntroLine else introScript + finalIntroLine
                playDialogue(player, "intro", script) {
                    introNightVariants.remove(player.uniqueId)
                    if (useNightScript) giveNightVision(player)
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            1 -> {
                player.sendMessage("§e[§a§l退休的老猎户§e] §f事不宜迟，麻溜地上去敲钟吧。一个弓箭手，最要不得的就是磨蹭，快去！")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                return true
            }
            2 -> {
                playDialogue(player, "fail", failScript) {
                    if (isDuskOrNight(player)) giveNightVision(player)
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            3 -> {
                playDialogue(player, "success", successScript) {
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return true
    }

    @EventHandler
    fun onBellInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!isTrialBell(block.location)) return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses[id] != QuestStatus.IN_PROGRESS || data.questProgress[id] != 1) return

        event.isCancelled = true
        if (trials.containsKey(player.uniqueId) || pendingTrials.contains(player.uniqueId)) {
            player.sendMessage("§c[试炼] §7试炼已经开始了，盯住红叶！")
            return
        }

        pendingTrials.add(player.uniqueId)
        block.world.playSound(block.location, Sound.BLOCK_BELL_USE, 1f, 1f)
        player.sendMessage("§a[试炼] §7钟声已响，3秒后开始。面朝北方，准备射击红叶！")
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingTrials.remove(player.uniqueId)
            val currentData = plugin.playerManager.getPlayerData(player)
            if (player.isOnline && currentData?.questStatuses?.get(id) == QuestStatus.IN_PROGRESS && currentData.questProgress[id] == 1) {
                startTrial(player)
            }
        }, 60L)
    }

    @EventHandler
    fun onArrowHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val player = arrow.shooter as? Player ?: return
        val session = trials[player.uniqueId] ?: return
        val hitEntity = event.hitEntity ?: return
        if (session.targetHitbox?.uniqueId != hitEntity.uniqueId) return

        event.isCancelled = true
        handleTargetHit(player, session, arrow)
    }

    private fun handleTargetHit(player: Player, session: TrialSession, arrow: AbstractArrow?) {
        if (trials[player.uniqueId] !== session || session.targetLoc == null) return
        arrow?.remove()
        session.hits++
        player.sendMessage("§a[试炼] §7命中红叶！当前命中 §e${session.hits}§7/$requiredHits")
        player.playSound(player.location, Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1.5f)
        clearTarget(session)
        scheduleNextTarget(player, session, 40L)
    }

    private fun detectNearbyArrowHit(player: Player, session: TrialSession) {
        val target = session.targetLoc ?: return
        val world = target.world ?: return
        val center = target.clone().add(0.5, 0.5, 0.5)
        val arrow = world.getNearbyEntities(center, 1.1, 1.1, 1.1)
            .filterIsInstance<AbstractArrow>()
            .firstOrNull { it.shooter == player && !it.isDead && !it.isOnGround }
            ?: return

        handleTargetHit(player, session, arrow)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingTrials.remove(event.player.uniqueId)
        introNightVariants.remove(event.player.uniqueId)
        endTrial(event.player.uniqueId, saveProgress = false)
    }

    private fun startTrial(player: Player) {
        val bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_20)
        val targetBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10)
        bar.addPlayer(player)
        targetBar.addPlayer(player)
        val session = TrialSession(player.uniqueId, bar, targetBar)
        trials[player.uniqueId] = session
        spawnTarget(player, session)

        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f)

        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || !trials.containsKey(player.uniqueId)) {
                    endTrial(player.uniqueId, saveProgress = false)
                    cancel()
                    return
                }

                session.elapsedTicks += 1
                session.targetTicks += 1

                val remaining = (trialDurationTicks - session.elapsedTicks).coerceAtLeast(0)
                bar.progress = (remaining / trialDurationTicks.toDouble()).coerceIn(0.0, 1.0)
                bar.setTitle("§e红叶试炼 §7| §f剩余 ${remaining / 20}s §7| §a命中 ${session.hits}/$requiredHits")
                updateTargetBar(session)

                session.targetLoc?.let { loc ->
                    loc.world.spawnParticle(Particle.DUST, loc.clone().add(0.5, 0.5, 0.5), 4, 0.25, 0.25, 0.25, org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.2f))
                }
                detectNearbyArrowHit(player, session)

                if (session.targetLoc != null && session.targetTicks >= targetLifetimeTicks) {
                    player.sendMessage("§c[试炼] §7红叶消失了，下一片马上出现。")
                    clearTarget(session)
                    scheduleNextTarget(player, session, 40L)
                }

                if (session.elapsedTicks >= trialDurationTicks) {
                    val passed = session.hits >= requiredHits
                    endTrial(player.uniqueId, saveProgress = true, passed = passed)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun updateTargetBar(session: TrialSession) {
        if (session.targetLoc == null) {
            session.targetBar.progress = 0.0
            session.targetBar.setTitle("§c红叶存在 §7| §f下一片红叶即将出现")
            return
        }

        val remaining = (targetLifetimeTicks - session.targetTicks).coerceAtLeast(0)
        session.targetBar.progress = (remaining / targetLifetimeTicks.toDouble()).coerceIn(0.0, 1.0)
        session.targetBar.setTitle("§c红叶存在 §7| §f剩余 ${"%.1f".format(remaining / 20.0)}s")
    }

    private fun scheduleNextTarget(player: Player, session: TrialSession, delayTicks: Long) {
        if (session.elapsedTicks + delayTicks >= trialDurationTicks) return
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (trials[player.uniqueId] === session && player.isOnline && session.targetLoc == null) {
                spawnTarget(player, session)
            }
        }, delayTicks)
    }

    private fun spawnTarget(player: Player, session: TrialSession) {
        val loc = targetLocations.random().clone()
        session.targetLoc = loc
        session.targetTicks = 0

        val display = loc.world.spawn(loc, BlockDisplay::class.java)
        display.setBlock(Material.NETHER_WART_BLOCK.createBlockData())

        val hitbox = loc.world.spawn(loc.clone().add(0.5, -0.25, 0.5), ArmorStand::class.java)
        hitbox.isVisible = false
        hitbox.setGravity(false)
        hitbox.isInvulnerable = true
        hitbox.isSilent = true
        hitbox.setBasePlate(false)
        hitbox.setArms(false)
        hitbox.isCollidable = true

        session.targetDisplay = display
        session.targetHitbox = hitbox

        loc.world.playSound(loc, Sound.BLOCK_GRASS_PLACE, 1f, 0.8f)
        loc.world.spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 20, 0.35, 0.35, 0.35, Material.NETHER_WART_BLOCK.createBlockData())
        player.sendMessage("§c[试炼] §7红叶出现了，8秒内射中它！")
    }

    private fun clearTarget(session: TrialSession) {
        val loc = session.targetLoc ?: return
        loc.world.spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25, Material.NETHER_WART_BLOCK.createBlockData())
        session.targetDisplay?.remove()
        session.targetHitbox?.remove()
        session.targetLoc = null
        session.targetDisplay = null
        session.targetHitbox = null
        session.targetTicks = 0
    }

    private fun endTrial(uuid: UUID, saveProgress: Boolean, passed: Boolean = false) {
        val session = trials.remove(uuid) ?: return
        session.task?.cancel()
        clearTarget(session)
        session.bar.removeAll()
        session.targetBar.removeAll()

        val player = Bukkit.getPlayer(uuid) ?: return
        if (!saveProgress) return

        if (passed) {
            player.sendMessage("§a[试炼] §7试炼结束，命中 §e${session.hits}§7 次。回去找老猎户吧。")
            plugin.questManager.updateProgress(player, id, 3)
        } else {
            player.sendMessage("§c[试炼] §7试炼结束，命中 §e${session.hits}§7 次，还差一点。回去找老猎户吧。")
            plugin.questManager.updateProgress(player, id, 2)
        }
    }

    private fun playDialogue(player: Player, dialogueId: String, scripts: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player.uniqueId, dialogueId)
        val index = talkProgress.getOrDefault(key, 0)
        if (index < scripts.size) {
            player.sendMessage("§e[§a§l退休的老猎户§e] ${scripts[index]}")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[key] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(key)
                onFinish()
            }
        }
    }

    private fun dialogueKey(uuid: UUID, dialogueId: String): UUID {
        return UUID.nameUUIDFromBytes((uuid.toString() + ":$dialogueId").toByteArray())
    }

    private fun isDuskOrNight(player: Player): Boolean {
        val time = player.world.time
        return time in 12000..23999
    }

    private fun giveNightVision(player: Player) {
        player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 60 * 5, 0, false, false, true))
        player.sendMessage("§7你咽下了老猎户的草药，在黑夜里看的更清晰了！")
        player.playSound(player.location, Sound.ENTITY_GENERIC_DRINK, 1f, 1.2f)
    }

    private fun giveResource(player: Player, id: String, amount: Int) {
        val item = plugin.resourceManager.getItem(id) ?: ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] $id") }
        }
        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§e获得 ${item.itemMeta?.displayName ?: id} §fx$amount")
    }

    private fun isTrialBell(loc: Location): Boolean {
        return loc.blockX == 552 && loc.blockY == 83 && loc.blockZ == 32
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f[粗布箭袋]制作书 x1")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 100)
        giveResource(player, "cubujiandaizhizuoshu", 1)
    }

    private data class TrialSession(
        val playerId: UUID,
        val bar: org.bukkit.boss.BossBar,
        val targetBar: org.bukkit.boss.BossBar,
        var hits: Int = 0,
        var elapsedTicks: Int = 0,
        var targetTicks: Int = 0,
        var targetLoc: Location? = null,
        var targetDisplay: BlockDisplay? = null,
        var targetHitbox: ArmorStand? = null,
        var task: BukkitTask? = null
    )

    private val targetLocations = listOf(
        Location(Bukkit.getWorld("world"), 537.0, 75.0, 18.0),
        Location(Bukkit.getWorld("world"), 531.0, 77.0, 11.0),
        Location(Bukkit.getWorld("world"), 543.0, 60.0, 6.0),
        Location(Bukkit.getWorld("world"), 547.0, 69.0, 4.0),
        Location(Bukkit.getWorld("world"), 550.0, 84.0, 3.0),
        Location(Bukkit.getWorld("world"), 552.0, 79.0, 9.0),
        Location(Bukkit.getWorld("world"), 558.0, 73.0, 20.0)
    )

    private val introScript = listOf(
        "§f哎，小兄弟，来来来，坐下喝杯茶。",
        "§f哦，你说墙上挂的这个啊……这是俺年轻时用的§6箭袋§f，老伙计了。想当年俺也是这方圆百里出了名的神射手，百步穿兔，箭无虚发。那个闹饥荒的年头饿死不少人，多亏俺这手本领，才没让家里断了荤腥。",
        "§f你可别小瞧这箭袋，瞧着就是几块破布和铁片子缝的，里头可大有玄机——足足能塞下§e上千支箭§f，背在身上还轻巧得很。",
        "§f怎么，想要？呵呵，那得先让俺看看你有没有这个本事！",
        "§f咱弓箭手，头一桩要紧的就是§b眼力§f。俺当年就凭这双招子，任那兔子狡兔三窟，也逃不出俺的掌心。这样，你从这座桥过去，一直爬到最顶上，那上头挂了口§c钟§f，敲一下，俺就知道你要开始了。",
        "§f敲完钟面朝§b北方§f，俺会在这头拉机关，你面前那些树上，会有一片叶子被染成§c红色§f。你就搭弓放箭，射中了俺再拉机关，红叶便会换个地儿冒出来。",
        "§f不过丑话说在前头——每片叶子最多等你§c8秒§f，射不中便算你失手一次，俺可没那闲工夫干等。一轮总共§c60秒§f，你要能保证打中个§e十§f次，这箭袋的打造法子，俺便传给你！"
    )

    private val nightVisionScript = listOf(
        "§f哦对了，这大晚上的，黑灯瞎火怕你看不清树梢。来，把这口§a草药§f嚼了咽下去，俺们猎户祖传的方子，吃了它，甭管多黑的天，看东西跟白昼似的，待会儿射不准可别拿天黑当借口！"
    )

    private val finalIntroLine = listOf(
        "§f事不宜迟，麻溜地上去敲钟吧。一个弓箭手，最要不得的就是磨蹭，快去！"
    )

    private val successScript = listOf(
        "§f好小子，没让俺失望！这手箭法，有俺当年的风范。",
        "§f拿去，好好琢磨，等你日后成了名震一方的神射手，别忘了回来请俺这老头子进城喝杯好茶！"
    )

    private val failScript = listOf(
        "§f怎么，蔫了？耷拉着个脸可不是弓箭手该有的模样。",
        "§f弦给你上紧了，再加把劲，去上头重新敲一次钟！俺瞧人一向很准，你小子没问题！"
    )
}
