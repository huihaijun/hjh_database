package com.hjh_database.quest.impl.main.north

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class North_03 : QuestBase("main_north_3", "[主线]玄水湾的珊瑚", QuestType.MAIN, 21) {

    override val raceLimit: Int? = null
    override val requiredCompletedQuestIds = setOf("main_north_2")

    override val description = listOf(
        "§7维持水族村庄稳定的珊瑚正在失去力量。",
        "§7前往玄水湾湖底，在三根石柱之间寻找珊瑚。"
    )

    private val plugin get() = Hjh_database.instance
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()

    private val scriptLuohe = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f哟，动作不慢嘛，比顾小汉那个半吊子强多了！",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f不过实话跟你说，方才那三条鱼只是开胃小菜。真正棘手的，是§e一块珊瑚§f。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f你没听错，珊瑚。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f千百年来，§4§n玄武大人§f一直守护着我们水族村庄的安宁。可近来天地异动，玄武大人踪迹全无。听上一任祭司说，玄武大人离去前曾留下§e一块珊瑚§f，就藏在§b玄水湾§f的湖底。这珊瑚蕴含着玄武大人的§e沉稳之力§f，正因有它在，我们这座村庄才能长居水下，不至于被水压碾得支离破碎。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f可自从玄武大人离开，珊瑚的力量便日渐不稳了……更要命的是，力量一乱，§c玄水湾底下竟滋生出了一群怪物§f。其中最瘆人的那个，村里人都叫它§c玄水魂§f——动不动就把人卷上天再狠狠摔下来，玄水湾被它们搅得七零八落，谁也不敢靠近。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f你身上已集齐了三位圣兽的祝福，想必不是等闲之辈。我能拜托你一件事吗——前往§b玄水湾§f的湖底，替我§e采一块珊瑚§f回来。我会用前人留下的典籍，尝试恢复这块珊瑚的力量。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f你到玄水湾后，会看见那些魔物在水底立了三根§c石柱§f，那便是他们的聚集之地。珊瑚就埋在§e三根柱子中间下方§f的那片水域里，通体泛着光，一眼便能认出来。只是周围那些魔物必定会来阻挠，千万小心。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f还有那个§c玄水魂§f——你若是有本事降服它，倒不妨一试。它身上有些§e稀罕的东西§f，日后打造护甲或许能派上大用场。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f对了，走之前把这个带上。这是村里人下海打鱼常备的§e洛水丹§f，服下后可在水下自由行走呼吸，和陆地上一般无二。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f这趟不仅仅是§e洄游祭§f的事，更是关乎我水族村庄往后的安危。拜托了。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与水族祭司 §e洛禾 §c对话")
        1 -> listOf(
            "§c前往 §b玄水湾 §c湖底",
            "§c在三根石柱中间下方采集 §e失活的珊瑚",
            "§c主手持有珊瑚，返回交给祭司洛禾"
        )
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHUIZUJISI.id) return false

        return when (currentProgress) {
            0 -> {
                playDialogue(player) {
                    if (giveLuoShuiDan(player)) {
                        plugin.questManager.updateProgress(player, id, 1)
                        player.sendMessage("§a[任务] 前往玄水湾湖底，在三根石柱中间下方寻找失活的珊瑚。")
                    }
                }
                true
            }

            1 -> {
                deliverCoral(player)
                true
            }

            else -> false
        }
    }

    private fun playDialogue(player: Player, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= scriptLuohe.size) return

        player.sendMessage(scriptLuohe[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == scriptLuohe.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun giveLuoShuiDan(player: Player): Boolean {
        val pills = plugin.resourceManager.getItem(LUOSHUI_DAN_ID)
        if (pills == null) {
            player.sendMessage("§c[错误] 未找到洛水丹配置，请联系管理员。")
            return false
        }

        pills.amount = LUOSHUI_DAN_AMOUNT
        val leftovers = player.inventory.addItem(pills)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }

        player.sendMessage("§a[获得物品] §f洛水丹 x$LUOSHUI_DAN_AMOUNT")
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§e[提示] 背包空间不足，部分洛水丹已掉落在脚下。")
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1.1f)
        return true
    }

    private fun deliverCoral(player: Player) {
        val held = player.inventory.itemInMainHand
        if (getResourceId(held) != CORAL_ID) {
            player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f珊瑚找到了吗？找到后拿在主手上交给我。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        consumeMainHand(player)
        player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f就是这块珊瑚！有了它，我便能着手尝试恢复其中的力量了。辛苦你了。")
        plugin.playerManager.getPlayerData(player)?.let { data ->
            plugin.questManager.completeQuest(player, data, this)
        }
    }

    private fun getResourceId(item: ItemStack): String? =
        item.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)

    private fun consumeMainHand(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.amount <= 1) {
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        } else {
            held.amount -= 1
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f)
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private companion object {
        const val LUOSHUI_DAN_ID = "luoshuidan"
        const val LUOSHUI_DAN_AMOUNT = 15
        const val CORAL_ID = "shihuodeshanhu"
    }
}
