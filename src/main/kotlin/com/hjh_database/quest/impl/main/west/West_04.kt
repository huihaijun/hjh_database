package com.hjh_database.quest.impl.main.west

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.HashMap
import java.util.UUID

class West_04 : QuestBase("main_west_4", "[主线]白虎分魂", QuestType.MAIN, 18), Listener {

    override val raceLimit = null

    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()
    private val readingPlayers = mutableSetOf<UUID>()

    init {
        Bukkit.getPluginManager().registerEvents(this, Hjh_database.instance)
    }

    override val description = listOf(
        "§7你已替虎子向老爷爷传回音信。",
        "§7再次回到迷阵深处，",
        "§7听听虎子要告诉你的秘密。"
    )

    private val scriptHuzi = listOf(
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f你回来了。我爹他…还好吗？",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f那个笨老头，明明自己腿脚都不利索了，还说什么会照顾好自己。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f也好。他安心了，我也就没什么牵挂了。那么，作为报答，有些事也该告诉你了。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f其实，我一直在等你身上这股熟悉的气息——你身上有§4§n青龙和朱雀大人§f的祝福，对吧？你是为了搜集四方圣兽的祝福而来。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f既然如此，那我也该以真正的身份与你说话了。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f我并非什么迷路的孩子，而是掌管§e白虎祭坛§f的§4§n白虎分魂§f。多年以前，我因一场意外力竭倒在这迷宫洞口，被一对夫妇救回家中，做了他们的“儿子”。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f那几年，是我作为一缕分魂从未体会过的温暖。可我终究不属于那个家——§4§n白虎大人§f留在神庙的力量日渐衰弱，我若再不归位，祭坛将彻底崩塌。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f所以我离开了。即便知道这一走，“虎子”这个身份便不复存在，我也不得不走。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f如今你来了，我也该完成我的使命。这个§e竹简§f你拿着——上面是我写的诗，把迷阵的走法都藏在里头了。穿出这迷宫，你便能看到§e白虎祭坛§f。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f神庙的力量应该还足够给予你§4§n白虎§f的祝福。§4§n白虎大人§f的试炼考验的是§e速度与力量§f。我会在§e神庙§f等你，期待你的到来。"
    )

    private val poemLines = listOf(
        "§f迷阵迂回把路藏,贪嗔痴者必仿徨",
        "§f精怪流连乃寻常,流泉灵水潜下方",
        "§f信道半途将头仰,作壁上观走一趟",
        "§f扶摇直上二三敞,长廊尽头虎吉祥",
        "§e-虎爪山脉区域主线到此结束-"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c返回迷阵深处，寻找 §e孤伶伶的小男孩")
            1 -> listOf("§c主手持有 §e记载诗歌的竹简 §c右键阅读")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.GULINGLINGDEXIAONANHAI.id || currentProgress != 0) return false

        playDialogue(player, scriptHuzi) {
            giveBambooAndContinue(player)
        }
        return true
    }

    private fun giveBambooAndContinue(player: Player) {
        val bamboo = Hjh_database.instance.resourceManager.getItem("shigezhujian") ?: ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e记载诗歌的竹简(配置缺失)")
                lore = listOf("§7缺少 shigezhujian 配置", "§7请联系管理员")
            }
        }

        bamboo.amount = 1
        val leftovers = player.inventory.addItem(bamboo)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，竹简已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${bamboo.itemMeta?.displayName}")
        }

        Hjh_database.instance.questManager.updateProgress(player, id, 1)
        player.sendMessage("§a[任务] -> 请主手持有记载诗歌的竹简，右键阅读。")
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        if (getResourceId(item) != "shigezhujian") return

        val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return
        if (data.questStatuses[id] != QuestStatus.IN_PROGRESS || data.questProgress[id] != 1) return

        event.isCancelled = true
        if (readingPlayers.contains(player.uniqueId)) return
        readingPlayers.add(player.uniqueId)

        item.amount -= 1
        player.sendMessage("§7§o你缓缓展开了这卷竹简...")

        object : BukkitRunnable() {
            var lineIndex = 0

            override fun run() {
                if (!player.isOnline) {
                    readingPlayers.remove(player.uniqueId)
                    Hjh_database.instance.resourceManager.getItem("shigezhujian")?.let { returnedItem ->
                        returnedItem.amount = 1
                        player.inventory.addItem(returnedItem)
                    }
                    cancel()
                    return
                }

                if (lineIndex >= poemLines.size) {
                    readingPlayers.remove(player.uniqueId)
                    Hjh_database.instance.questManager.completeQuest(player, data, this@West_04)
                    cancel()
                    return
                }

                player.sendMessage(poemLines[lineIndex])
                player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f)
                lineIndex++
            }
        }.runTaskTimer(Hjh_database.instance, 40L, 60L)
    }

    private fun getResourceId(item: ItemStack): String? {
        if (!item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +500")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 500)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
