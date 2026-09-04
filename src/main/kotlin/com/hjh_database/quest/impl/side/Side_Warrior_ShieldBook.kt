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
import kotlin.math.sin
import kotlin.random.Random

class Side_Warrior_ShieldBook : QuestBase("side_warrior_shield_book", "[支线]老战士的盾牌", QuestType.SIDE, 1), Listener {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val cookingSessions = HashMap<UUID, CookingSession>()
    private val successDialoguePlayers = mutableSetOf<UUID>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return data.job == 0 && data.lv >= 15
    }

    override val description = listOf(
        "§7听闻老战士邵斌手中有一册盾牌制作书。",
        "§7他不愿再谈杀伐，只想尝一口家乡的烤牛排。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往世外桃源寻找 §e老战士邵斌")
            1 -> listOf("§c手持 §e桃源生牛肉 §c右键邵斌旁的熔炉烤肉")
            2 -> listOf("§c手持 §e不完美的牛排 §c回去找 §e老战士邵斌")
            3 -> listOf("§c寻找 §e老战士的煤炭 §c并交给 §e老战士邵斌")
            4 -> listOf("§c手持 §e完美的牛排 §c回去找 §e老战士邵斌")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != "shaobin") return false

        when (currentProgress) {
            0 -> {
                playDialogue(player, introScript) {
                    giveResource(player, "taoyuanshengniurou", 1)
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            1 -> {
                player.sendMessage("§e[§a§l邵斌§e] §f旁边炉灶火候正好，手持桃源生牛肉右键熔炉试试。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                return true
            }
            2 -> {
                if (!isHoldingResource(player, "buwanmeideniupai")) {
                    player.sendMessage("§e[§a§l邵斌§e] §f烤坏了也别藏着，拿来让我瞧瞧。")
                    return true
                }
                takeMainHandResource(player, "buwanmeideniupai", 1)
                playDialogue(player, badSteakScript) {
                    plugin.questManager.updateProgress(player, id, 3)
                }
                return true
            }
            3 -> {
                if (!isHoldingResource(player, "laozhanshidemeitan")) {
                    player.sendMessage("§e[§a§l邵斌§e] §f去旁边那堆石头缝里找一块煤炭来，火不旺，肉可烤不香。")
                    return true
                }
                takeMainHandResource(player, "laozhanshidemeitan", 1)
                playDialogue(player, retryScript) {
                    giveResource(player, "taoyuanshengniurou", 1)
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            4 -> {
                if (!successDialoguePlayers.contains(player.uniqueId)) {
                    if (!isHoldingResource(player, "wanmeideniupai")) {
                        player.sendMessage("§e[§a§l邵斌§e] §f闻着味儿了，快把那块烤好的牛排拿来。")
                        return true
                    }
                    takeMainHandResource(player, "wanmeideniupai", 1)
                    successDialoguePlayers.add(player.uniqueId)
                }
                playDialogue(player, successScript) {
                    successDialoguePlayers.remove(player.uniqueId)
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return true
    }

    @EventHandler
    fun onFurnaceInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!isCookingFurnace(block.location)) return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses[id] != QuestStatus.IN_PROGRESS) return

        val progress = data.questProgress[id] ?: 0
        if (progress != 1) return

        event.isCancelled = true

        val existing = cookingSessions[player.uniqueId]
        if (existing != null) {
            judgeRound(player, existing)
            return
        }

        if (!isHoldingResource(player, "taoyuanshengniurou")) {
            player.sendMessage("§c[烤肉] §7你需要主手持有桃源生牛肉。")
            return
        }

        takeMainHandResource(player, "taoyuanshengniurou", 1)
        startCooking(player, block.location)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        successDialoguePlayers.remove(event.player.uniqueId)
        val session = cookingSessions.remove(event.player.uniqueId) ?: return
        session.task?.cancel()
        session.bar.removeAll()
        session.display.remove()
    }

    private fun startCooking(player: Player, furnaceLoc: Location) {
        val bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_20)
        bar.addPlayer(player)

        val displayLoc = furnaceLoc.clone().add(0.5, 1.25, 0.5)
        val display = furnaceLoc.world!!.spawn(displayLoc, ItemDisplay::class.java)
        display.setItemStack(plugin.resourceManager.getItem("taoyuanshengniurou") ?: ItemStack(Material.BEEF))

        val session = CookingSession(player.uniqueId, bar, display)
        cookingSessions[player.uniqueId] = session
        rollWindow(session)

        player.sendMessage("§a[烤肉] §7牛排架上炉灶了，最佳火候时再次右键熔炉！")
        player.playSound(player.location, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1f, 1.2f)

        session.task = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || !cookingSessions.containsKey(player.uniqueId)) {
                    finishCooking(player.uniqueId, false, "§c[烤肉] §7烤肉中断了。")
                    cancel()
                    return
                }

                session.progress += 0.0125
                if (session.progress >= 1.0) {
                    finishCooking(player.uniqueId, false, "§c[烤肉] §7火候过了，牛排烤得不太妙。")
                    cancel()
                    return
                }

                bar.progress = session.progress.coerceIn(0.0, 1.0)
                bar.setTitle("§e烤肉 ${session.round}/3 §7| §f当前 ${percent(session.progress)} §7| §a最佳 ${percent(session.windowStart)}-${percent(session.windowEnd)}")

                val loc = display.location
                val floatY = sin(session.progress * Math.PI * 10.0) * 0.08
                display.teleport(displayLoc.clone().add(0.0, floatY, 0.0))
                loc.world.spawnParticle(Particle.SMOKE, displayLoc, 2, 0.18, 0.08, 0.18, 0.01)
                loc.world.spawnParticle(Particle.FLAME, displayLoc.clone().add(0.0, -0.25, 0.0), 1, 0.12, 0.05, 0.12, 0.0)
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun judgeRound(player: Player, session: CookingSession) {
        if (session.progress < session.windowStart || session.progress > session.windowEnd) {
            finishCooking(player.uniqueId, false, "§c[烤肉] §7火候差了一点，这块牛排不够完美。")
            return
        }

        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f)
        player.sendMessage("§a[烤肉] §7第 ${session.round} 轮火候正好！")

        if (session.round >= 3) {
            finishCooking(player.uniqueId, true, "§a[烤肉] §7香气扑鼻，这块牛排烤得正完美！")
            return
        }

        session.round++
        session.progress = 0.0
        rollWindow(session)
    }

    private fun finishCooking(uuid: UUID, success: Boolean, message: String) {
        val session = cookingSessions.remove(uuid) ?: return
        session.task?.cancel()
        session.bar.removeAll()
        session.display.remove()

        val player = Bukkit.getPlayer(uuid) ?: return
        player.sendMessage(message)
        player.playSound(player.location, if (success) Sound.ENTITY_PLAYER_LEVELUP else Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f)

        if (success) {
            giveResource(player, "wanmeideniupai", 1)
            plugin.questManager.updateProgress(player, id, 4)
        } else {
            giveResource(player, "buwanmeideniupai", 1)
            plugin.questManager.updateProgress(player, id, 2)
        }
    }

    private fun rollWindow(session: CookingSession) {
        session.windowStart = Random.nextDouble(0.60, 0.66)
        session.windowEnd = session.windowStart + 0.05
    }

    private fun isCookingFurnace(loc: Location): Boolean {
        return loc.blockX == -840 && loc.blockY == 83 && loc.blockZ == 441
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player.uniqueId, scripts)
        val index = talkProgress.getOrDefault(key, 0)
        if (index < scripts.size) {
            player.sendMessage("§e[§a§l邵斌§e] ${scripts[index]}")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[key] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(key)
                onFinish()
            }
        }
    }

    private fun dialogueKey(uuid: UUID, scripts: List<String>): UUID {
        return UUID.nameUUIDFromBytes((uuid.toString() + ":" + System.identityHashCode(scripts)).toByteArray())
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

    private fun isHoldingResource(player: Player, id: String): Boolean {
        return getResourceId(player.inventory.itemInMainHand) == id && player.inventory.itemInMainHand.amount > 0
    }

    private fun takeMainHandResource(player: Player, id: String, amount: Int): Boolean {
        val item = player.inventory.itemInMainHand
        if (getResourceId(item) != id || item.amount < amount) return false
        if (item.amount == amount) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= amount
        }
        return true
    }

    private fun getResourceId(item: ItemStack?): String? {
        val meta = item?.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f[轻石盾牌]制作书 x1")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 100)
        giveResource(player, "qingshidunpaizhizuoshu", 1)
    }

    private data class CookingSession(
        val playerId: UUID,
        val bar: org.bukkit.boss.BossBar,
        val display: ItemDisplay,
        var round: Int = 1,
        var progress: Double = 0.0,
        var windowStart: Double = 0.60,
        var windowEnd: Double = 0.65,
        var task: BukkitTask? = null
    )

    private val introScript = listOf(
        "§f小朋友，你怎么摸到这地方来的？看你年纪轻轻，不去外头闯荡，反倒躲来这种地方跟我这老头子学养生？",
        "§f哦，想找我要盾牌的打造之法啊……呵呵，瞒不住瞒不住。当年老夫纵横沙场，不光靠手中一柄利剑，更仗着这块坚不可摧的盾牌，不知多少次从鬼门关前捡回这条命呐。",
        "§f锻造的技艺我早些年就写成书了，想要便看你有没有这份心。你放心，老夫不会叫你去杀敌，打了这么多年仗，早就悟透了一个理——战争，说穿了不过是最无聊的事。",
        "§f在这山谷里住久了，反倒馋起家里那口吃的来了。",
        "§f这样吧，旁边那炉灶看见了没？替我把这块牛排烤了，正巧腹中有些饥饿。要是烤得出我家里的味儿，这本制作书，你拿走便是！",
        "§f给，拿着，这可是桃源里养的牛，壮实得很，你可得给我仔细烤好了！"
    )

    private val badSteakScript = listOf(
        "§f嗯……火候还差点意思。炭火快烧没了，也罢，你若诚心想要这盾牌，去旁边那堆石头缝里给我找一块煤炭来，老夫再给你一次机会。"
    )

    private val retryScript = listOf(
        "§f拿去，这块肉重新烤，这回可得上心了！"
    )

    private val successScript = listOf(
        "§f天啊……这味道……是家乡的味儿……",
        "§f征战杀伐半辈子，到头来最惦念的，还是家里那口热乎饭。",
        "§f这本制作书，拿去吧。临别只有一句相赠：武器终究是拿来护身的，不是拿来伤人的，记住。"
    )
}
