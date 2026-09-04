package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.random.Random

class Side_Medical_TaolizhiBook : QuestBase("side_medical_taolizhi_book", "[支线]旦师傅的神奇树枝", QuestType.SIDE, 1), Listener {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val pendingTrials = mutableSetOf<UUID>()
    private val trials = HashMap<UUID, TrialSession>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val bucketId = "shoujicaoliaodetong"
    private val targetCatchCount = 10
    private val roundDurationTicks = 400
    private val spawnIntervalTicks = 6
    private val bucketCapacity = 16

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return data.job == 3 && data.lv >= 15
    }

    override val description = listOf(
        "§7皇城炼丹房的旦野师傅眼神不济，急需有人帮他分拣草药。",
        "§7若能分清三味草药，他愿意传授桃李枝的制作法。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往炼丹炉旁寻找 §e旦野师傅")
            1 -> listOf("§c手持 §e收集草药的桶 §c敲响炼丹炉上的钟")
            2 -> listOf("§c回去找 §e旦野师傅 §c重新准备试炼")
            3 -> listOf("§c回去找 §e旦野师傅 §c领取奖励")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != "danyeshifu") return false

        when (currentProgress) {
            0 -> {
                playDialogue(player, "intro", introScript) {
                    giveResource(player, bucketId, 1)
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            1 -> {
                player.sendMessage("§e[§a§l旦野师傅§e] §f桶拿稳了，准备好了就碰一下炼丹炉上的钟。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                return true
            }
            2 -> {
                playDialogue(player, "fail", failScript) {
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
            player.sendMessage("§c[草药试炼] §7试炼已经开始，先把这轮草药接完。")
            return
        }

        if (countResource(player, bucketId) <= 0) {
            player.sendMessage("§c[草药试炼] §7你需要带上旦野师傅给的收集草药的桶。")
            return
        }

        pendingTrials.add(player.uniqueId)
        block.world.playSound(block.location, Sound.BLOCK_BELL_USE, 1f, 1f)
        player.sendMessage("§a[草药试炼] §7钟声已响，2秒后开始分拣草药。")
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingTrials.remove(player.uniqueId)
            val currentData = plugin.playerManager.getPlayerData(player)
            if (player.isOnline && currentData?.questStatuses?.get(id) == QuestStatus.IN_PROGRESS && currentData.questProgress[id] == 1) {
                startTrial(player)
            }
        }, 40L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingTrials.remove(event.player.uniqueId)
        endTrial(event.player.uniqueId, saveProgress = false)
    }

    private fun startTrial(player: Player) {
        if (countResource(player, bucketId) <= 0) {
            player.sendMessage("§c[草药试炼] §7你需要带上旦野师傅给的收集草药的桶。")
            return
        }

        val bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10)
        bar.addPlayer(player)
        val targets = HerbType.entries.filter { it.isHerb }.shuffled()
        val session = TrialSession(player.uniqueId, bar, targets, bucketCapacity)
        trials[player.uniqueId] = session

        player.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f)
        announceRound(player, session)

        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || trials[player.uniqueId] !== session) {
                    endTrial(player.uniqueId, saveProgress = false)
                    cancel()
                    return
                }

                session.roundTicks += 1
                session.spawnTicks += 1

                if (session.spawnTicks >= spawnIntervalTicks) {
                    session.spawnTicks = 0
                    spawnFallingHerb(session)
                }

                updateFallingHerbs(player, session)
                updateTrialDisplay(player, session)

                if (session.roundTicks >= roundDurationTicks) {
                    finishRound(player, session)
                    if (session.currentRound >= session.targets.size) {
                        val passed = session.successfulRounds == session.targets.size
                        endTrial(player.uniqueId, saveProgress = true, passed = passed)
                        cancel()
                        return
                    }

                    announceRound(player, session)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun announceRound(player: Player, session: TrialSession) {
        session.roundTicks = 0
        session.spawnTicks = 0
        session.roundCaught = 0
        session.roundWrong = 0
        session.usedCapacity = 0
        session.capacityFullWarned = false
        session.falling.forEach { it.display.remove() }
        session.falling.clear()

        val target = session.currentTarget()
        player.sendMessage("§e[§a§l旦野师傅§e] §f这一轮接：${target.displayName}§f！手持桶，别把炉灰也收进去喽。")
        sendHerbTip(player, target)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f)
    }

    private fun spawnFallingHerb(session: TrialSession) {
        val type = rollDropType(session.currentTarget())
        val loc = randomDropLocation()
        val display = loc.world!!.spawn(loc, ItemDisplay::class.java)
        display.setItemStack(createDisplayItem(type))
        display.isInvulnerable = true
        display.isPersistent = false
        hideDisplayFromOtherPlayers(session, display)

        session.falling.add(FallingHerb(display, type))
        loc.world.spawnParticle(Particle.DUST, loc, 8, 0.12, 0.12, 0.12, Particle.DustOptions(type.color, 1.1f))
    }

    private fun hideDisplayFromOtherPlayers(session: TrialSession, display: ItemDisplay) {
        Bukkit.getOnlinePlayers().forEach { online ->
            if (online.uniqueId != session.playerId) {
                online.hideEntity(plugin, display)
            }
        }
    }

    private fun updateFallingHerbs(player: Player, session: TrialSession) {
        val iterator = session.falling.iterator()
        while (iterator.hasNext()) {
            val falling = iterator.next()
            val display = falling.display
            if (!display.isValid) {
                iterator.remove()
                continue
            }

            val nextLoc = display.location.add(0.0, -0.055, 0.0)
            display.teleport(nextLoc)
            display.world.spawnParticle(Particle.DUST, nextLoc, 1, 0.05, 0.05, 0.05, Particle.DustOptions(falling.type.color, 0.8f))

            if (nextLoc.y <= arenaMinY) {
                display.remove()
                iterator.remove()
                continue
            }

            if (!isHoldingBucket(player)) continue
            val catchLoc = player.location.add(0.0, 1.0, 0.0)
            if (catchLoc.world != nextLoc.world || catchLoc.distanceSquared(nextLoc) > 1.44) continue

            if (catchFallingHerb(player, session, falling)) {
                display.remove()
                iterator.remove()
            }
        }
    }

    private fun catchFallingHerb(player: Player, session: TrialSession, falling: FallingHerb): Boolean {
        if (session.usedCapacity >= session.bucketCapacity) {
            if (!session.capacityFullWarned) {
                session.capacityFullWarned = true
                player.sendMessage("§c[草药试炼] §7桶已经装满了，接不下更多东西。")
            }
            return false
        }

        session.usedCapacity++
        val target = session.currentTarget()
        if (falling.type == target) {
            session.roundCaught++
            player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.6f)
        } else {
            session.roundWrong++
            player.playSound(player.location, Sound.BLOCK_COMPOSTER_FILL, 0.6f, 0.8f)
        }
        return true
    }

    private fun finishRound(player: Player, session: TrialSession) {
        val target = session.currentTarget()
        val success = session.roundCaught >= targetCatchCount
        if (success) {
            session.successfulRounds++
            player.sendMessage("§a[草药试炼] §7第 ${session.currentRound + 1} 轮完成，${target.plainName} 分拣清楚了。")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f)
        } else {
            player.sendMessage("§c[草药试炼] §7第 ${session.currentRound + 1} 轮未完成，只接到 §e${session.roundCaught}§7/$targetCatchCount 个${target.plainName}。")
            player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1.0f)
        }

        session.currentRound++
    }

    private fun updateTrialDisplay(player: Player, session: TrialSession) {
        val remaining = (roundDurationTicks - session.roundTicks).coerceAtLeast(0)
        session.bar.progress = (remaining / roundDurationTicks.toDouble()).coerceIn(0.0, 1.0)
        session.bar.setTitle("§a草药分拣 §7| §f第 ${session.currentRound + 1}/3 轮 ${remaining / 20}s §7| ${session.currentTarget().displayName} §e${session.roundCaught}§7/$targetCatchCount")
        val actionbar = "§a[草药试炼] ${session.currentTarget().displayName} §e${session.roundCaught}§7/$targetCatchCount§f，目前容量：§b${session.usedCapacity}§7/${session.bucketCapacity}"
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(actionbar))
    }

    private fun endTrial(uuid: UUID, saveProgress: Boolean, passed: Boolean = false) {
        val session = trials.remove(uuid) ?: return
        session.task?.cancel()
        session.bar.removeAll()
        session.falling.forEach { it.display.remove() }
        session.falling.clear()

        val player = Bukkit.getPlayer(uuid) ?: return
        if (!saveProgress) return

        takeResource(player, bucketId, 1)
        if (passed) {
            player.sendMessage("§a[草药试炼] §7三味草药都分清了，回去找旦野师傅吧。")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
            plugin.questManager.updateProgress(player, id, 3)
        } else {
            player.sendMessage("§c[草药试炼] §7还是混进了不少杂物，回去找旦野师傅吧。")
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)
            plugin.questManager.updateProgress(player, id, 2)
            giveResource(player, bucketId, 1)
        }
    }

    private fun playDialogue(player: Player, dialogueId: String, scripts: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player.uniqueId, dialogueId)
        val index = talkProgress.getOrDefault(key, 0)
        if (index < scripts.size) {
            player.sendMessage("§e[§a§l旦野师傅§e] ${scripts[index]}")
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

    private fun sendHerbTip(player: Player, herb: HerbType) {
        player.sendMessage(herb.tip)
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

    private fun countResource(player: Player, id: String): Int {
        return player.inventory.contents
            .filter { getResourceId(it) == id }
            .sumOf { it?.amount ?: 0 }
    }

    private fun takeResource(player: Player, id: String, amount: Int): Boolean {
        var remaining = amount
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            if (getResourceId(item) != id) continue

            if (item.amount > remaining) {
                item.amount -= remaining
                return true
            }

            remaining -= item.amount
            player.inventory.setItem(slot, null)
            if (remaining <= 0) return true
        }
        return false
    }

    private fun isHoldingBucket(player: Player): Boolean {
        return getResourceId(player.inventory.itemInMainHand) == bucketId
    }

    private fun getResourceId(item: ItemStack?): String? {
        val meta = item?.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    private fun isTrialBell(loc: Location): Boolean {
        return loc.blockX == 253 && loc.blockY == 49 && loc.blockZ == 40
    }

    private fun randomDropLocation(): Location {
        val world = Bukkit.getWorld("world")!!
        val x = Random.nextDouble(arenaMinX + 0.3, arenaMaxX + 0.7)
        val z = Random.nextDouble(arenaMinZ + 0.3, arenaMaxZ + 0.7)
        return Location(world, x, arenaSpawnY, z)
    }

    private fun rollDropType(target: HerbType): HerbType {
        if (Random.nextDouble() < 0.55) return target
        return HerbType.entries.filter { it != target }.random()
    }

    private fun createDisplayItem(type: HerbType): ItemStack {
        return ItemStack(type.material).apply {
            itemMeta = itemMeta?.apply { setDisplayName(type.displayName) }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f[桃李枝]制作书 x1")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 100)
        giveResource(player, "taolizhizhizuoshu", 1)
    }

    private data class TrialSession(
        val playerId: UUID,
        val bar: org.bukkit.boss.BossBar,
        val targets: List<HerbType>,
        val bucketCapacity: Int,
        var currentRound: Int = 0,
        var successfulRounds: Int = 0,
        var roundTicks: Int = 0,
        var spawnTicks: Int = 0,
        var roundCaught: Int = 0,
        var roundWrong: Int = 0,
        var usedCapacity: Int = 0,
        var capacityFullWarned: Boolean = false,
        val falling: MutableList<FallingHerb> = mutableListOf(),
        var task: BukkitTask? = null
    ) {
        fun currentTarget(): HerbType = targets[currentRound.coerceIn(0, targets.lastIndex)]
    }

    private data class FallingHerb(
        val display: ItemDisplay,
        val type: HerbType
    )

    private enum class HerbType(
        val plainName: String,
        val displayName: String,
        val tip: String,
        val material: Material,
        val color: Color,
        val isHerb: Boolean = true
    ) {
        NINGXUE("凝血草", "§c凝血草", "§c凝血草§f，叶边带红，止血生肌的良品；", Material.POPPY, Color.RED),
        QINGXIN("清心叶", "§a清心叶", "§a清心叶§f，通体翠绿，专解火毒的上选；", Material.SMALL_DRIPLEAF, Color.LIME),
        KURONG("枯荣花", "§e枯荣花", "§e枯荣花§f，花色枯黄却含生机，调和诸药的引子。", Material.DANDELION, Color.YELLOW),
        ASH("炉灰", "§7炉灰", "§7炉灰§f，昨儿剩下的灰，可别收进桶里。", Material.GRAY_DYE, Color.GRAY, false)
    }

    private val introScript = listOf(
        "§7§o（叹气）又失败了，这人一老，眼睛就不中用喽……",
        "§f小兄弟，你来得正好！老夫这双眼如今连§a草药§f和杂草都分不清了，白白糟蹋了好几炉好丹。你来帮老夫搭把手，可好？",
        "§f甭担心，老夫不叫你白忙活。你看这个——这可是我私藏多年的一根§6灵枝§f，上头还结了果子呢。这可不是寻常树枝，它能把咱们医师行医时溢出的§d仁心§f攒起来，攒到一定程度便开花结果，召来§b灵鸟§f替你护身。了不起的宝贝吧？",
        "§f……哎哎别往嘴里送！这果子可不是拿来吃的，咬一口那就是暴殄天物了！",
        "§f老夫也不跟你兜圈子。这样，你拿好这个§6桶§f，待会儿我把这些混作一团的草药扬起来，我叫什么名字，你便接什么。里头还掺了昨儿剩的§7炉灰§f，当心别脏了手。你只要把三种草药分得清清楚楚，这§6灵枝§f的锻造之法，老夫便传给你！",
        "§f准备好了，就碰一下炼丹炉上的钟，咱们就开始。"
    )

    private val successScript = listOf(
        "§f太好了太好了！这下老夫可算能开炉炼丹了，总算不用把补药炼成泻药喽。",
        "§f差点忘了，给，这本制作书拿好。你帮了老夫大忙，这灵枝归你，不算糟蹋！"
    )

    private val failScript = listOf(
        "§f哎呀……还是没分清。无妨无妨，老夫这双眼虽花，但看人一向准得很，你再试一次，这灵枝迟早是你的！"
    )

    private companion object {
        const val arenaMinX = 256.0
        const val arenaMaxX = 264.0
        const val arenaMinZ = 37.0
        const val arenaMaxZ = 43.0
        const val arenaMinY = 47.0
        const val arenaSpawnY = 56.0
    }
}
