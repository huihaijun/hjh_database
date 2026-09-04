package com.hjh_database.quest.impl.main.south

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.HashMap
import java.util.UUID

/**
 * 南方主线任务第五章 - 莲心的信 (南域终章)
 */
class South_05 : QuestBase("main_south_5", "[主线]莲心的信", QuestType.MAIN, 14), Listener {

    override val raceLimit = null

    // 记录玩家对话索引
    private val talkProgress = HashMap<UUID, Int>()
    // 标记玩家是否已经成功交信并进入了成功剧情分支
    private val inSuccessBranch = mutableSetOf<UUID>()

    // 记录正在读信的玩家
    private val readingPlayers = mutableSetOf<UUID>()

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c将莲心的信交给 §e绿洲小镇镇长")
            1 -> listOf("§c阅读莲心留给你的 §e信件")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你带着莲心给的信件和朱雀的祝福，",
        "§7回到了绿洲小镇。",
        "§7将真相告知镇长吧。"
    )

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptMayorFail = listOf(
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f旅行者你回来了啊",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f有什么东西需要给我吗？"
    )

    private val scriptMayorSuccess = listOf(
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f这封§9信§f是…？",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f…原来如此,火山下面的构造是这个样子吗…",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f等等,这封§9信§f的背面…是祭坛设施的改建图？",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f有了这个,就可以利用火山的能量改善我们的生活环境了",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f§4§n莲心§f她真的是个天才…是我们辜负了她…",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f她的§4§n弟弟§f是去年献祭的祭品…她大概很想报仇吧",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f我这就带巡守队去把§4§n红鸾§f抓起来,有这些证据,她休想再煽动信徒胡作非为",
        "§e[${StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.displayName}§e] §f等等…这里面夹的§9信§f是给你的,拿去吧"
    )

    private val letterLines = listOf(
        "§9致无以回报的陌生人",
        "§f当你看到这封§9信§f的时候，我已经不在了。",
        "§f感谢你对我的帮助，而我甚至不知道你的名字…",
        "§f我其实有一个§4§n弟弟§f。早年父母双亡，我和他相依为命。",
        "§f他是个好孩子，既懂事又聪明，每天都会采一朵小花放在我的书桌上…",
        "§f但是，他在去年的献祭中，被当作祭品，活生生地丢入了§2§o火山口§f。",
        "§f那天夜里，他回头看了我一眼，我却被人群挤在后面，连他的手都没能抓住。",
        "§f我真是个不合格的姐姐。",
        "§f那段时间，我恨着镇上的每一个人…",
        "§f你知道吗？这火山下面的阵法，我早就摸透了。",
        "§f我本来想在祭坛上做些手脚，让它喷发，让这座城镇给弟弟陪葬。",
        "§f对，我差一点就这么做了。",
        "§f可你出现了。",
        "§f你让我明白，这世上还有人愿意对素不相识的陌生人伸出援手。",
        "§f所以，我想，做到这里就够了。",
        "§f§4§n红鸾§f会受到她该有的惩罚…",
        "§f而我，要去找我的§4§n弟弟§f了。",
        "§f他从小就依赖我，胆子又小，一个人在那边，该害怕了吧。",
        "§f真的很谢谢你。",
        "§f我相信无论你的目标是什么，一定都能实现的。",
        "§f因为我在你的眼中，看到了温暖和希望。",
        "§4§n莲心§f绝笔",
        "§e-焱砂大漠区域主线到此结束-"
    )

    // ==========================================
    // NPC 对话逻辑
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.LVZHOUXIAOZHENDEZHENZHANG.id && currentProgress == 0) {
            val uuid = player.uniqueId
            val index = talkProgress.getOrDefault(uuid, 0)

            if (inSuccessBranch.contains(uuid)) {
                playDialogue(player, scriptMayorSuccess) {
                    finishMayorDialogue(player)
                }
                return true
            }

            if (index == 0) {
                val itemInHand = player.inventory.itemInMainHand
                val key = NamespacedKey(Hjh_database.instance, "resource_id")
                var hasLetter = false

                if (itemInHand.hasItemMeta() && itemInHand.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.STRING) == true) {
                    val resId = itemInHand.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING)
                    if (resId == "jiaogeizhenzhangdexinjian") {
                        hasLetter = true
                    }
                }

                if (hasLetter) {
                    itemInHand.amount -= 1
                    inSuccessBranch.add(uuid)
                    playDialogue(player, scriptMayorSuccess) {
                        finishMayorDialogue(player)
                    }
                } else {
                    playDialogue(player, scriptMayorFail) {
                        player.sendMessage("§c[提示] 请主手持有[交给镇长的信]，再和镇长对话吧")
                    }
                }
            } else {
                playDialogue(player, scriptMayorFail) {
                    player.sendMessage("§c[提示] 请主手持有[交给镇长的信]，再和镇长对话吧")
                }
            }
            return true
        }
        return false
    }

    private fun finishMayorDialogue(player: Player) {
        inSuccessBranch.remove(player.uniqueId)

        val rm = Hjh_database.instance.resourceManager
        rm.getItem("geimoshengrendexin")?.let { item ->
            item.amount = 1
            val leftovers = player.inventory.addItem(item)
            if (leftovers.isNotEmpty()) {
                player.sendMessage("§c[提示] 背包已满，信件已掉落在脚下！")
                for (drop in leftovers.values) {
                    player.world.dropItem(player.location, drop)
                }
            } else {
                player.sendMessage("§a[系统] 获得了 给陌生人的信 x1")
            }
        }

        player.sendMessage("§a提示：请阅读这封信，才能完成任务哦~")
        Hjh_database.instance.questManager.updateProgress(player, id, 1)
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    // ==========================================
    // 监听右键读信
    // ==========================================

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        if (!item.hasItemMeta()) return

        val key = NamespacedKey(Hjh_database.instance, "resource_id")
        val meta = item.itemMeta!!
        if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) {
            val resId = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
            if (resId == "geimoshengrendexin") {

                val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return
                if (data.questStatuses[id] == QuestStatus.IN_PROGRESS && data.questProgress[id] == 1) {

                    if (readingPlayers.contains(player.uniqueId)) return
                    readingPlayers.add(player.uniqueId)

                    // 【新增：右键立刻消耗信件】
                    item.amount -= 1

                    player.sendMessage("§7§o你缓缓打开了这封信件...")

                    object : BukkitRunnable() {
                        var lineIndex = 0

                        override fun run() {
                            // 防死档拦截：如果读信期间玩家掉线，把信退给玩家并终止
                            if (!player.isOnline) {
                                readingPlayers.remove(player.uniqueId)
                                val rm = Hjh_database.instance.resourceManager
                                rm.getItem("geimoshengrendexin")?.let { returnedItem ->
                                    returnedItem.amount = 1
                                    // 玩家下线，将物品塞回其离线存档或等下次上线发放（这里简单利用原版离线背包特性或掉落）
                                    player.inventory.addItem(returnedItem)
                                }
                                cancel()
                                return
                            }

                            if (lineIndex >= letterLines.size) {
                                readingPlayers.remove(player.uniqueId)
                                Hjh_database.instance.questManager.completeQuest(player, data, this@South_05)
                                cancel()
                                return
                            }

                            player.sendMessage(letterLines[lineIndex])
                            player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f)
                            lineIndex++
                        }
                    }.runTaskTimer(Hjh_database.instance, 40L, 60L)
                }
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +300")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 300)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
