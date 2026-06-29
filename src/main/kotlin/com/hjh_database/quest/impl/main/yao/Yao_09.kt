package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.HashMap
import java.util.UUID

class Yao_09 : QuestBase("main_yao_9", "[妖族主线]四方结界", QuestType.MAIN, 9) {

    override val raceLimit = 4

    override val description = listOf(
        "§7你已通过青龙试炼，获得了青龙的祝福。",
        "§7回到 §4镇妖塔§7，向 §e华夭§7 汇报结界的情况。"
    )

    private val talkProgress = HashMap<UUID, Int>()
    private val trialPassedCache = HashMap<UUID, Boolean>()
    private val queryingPlayers = mutableSetOf<UUID>()

    private fun getScript(isHealer: Boolean): List<String> {
        val baseScript = listOf(
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f原来如此……所以你得到青龙祝福之后，确实感觉到了§e龙之力§f在消退吗？",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f如果镇守四方的结界阵眼全部消失，长久以来束缚我族的力量便会彻底瓦解。见到人仙二族就要东躲西藏的日子，说不定就快到头了！",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f不过现在还不是高兴的时候，咱们手头的情报还远远不够。你继续往下一处走吧——§c南边的朱雀祭坛§f。之前那位前辈提过，南方气候炎热得很，要是撑不住了，可以先在沙漠里的§e客栈§f歇一歇脚。",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f再往前走走，有一片§e绿洲§f。前辈说那儿的镇长对你是人是妖不太在意，说不定能告诉你一些关于朱雀祭坛的事。",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f把四方祝福都搜集齐全，看看这结界到底是不是真的大不如前了。等全部到手之后，就回塔顶找§e大长老§f，让他来做最后的决定。",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f之后的路，就得靠你一个人去走了。我这儿有些东西，虽不算珍贵，但也是我在镇妖塔当差攒下的§e俸禄§f——哼，可恶的§c人仙二族§f，抢了咱们的东西拿去给自己人发俸，想想就来气。我相信你，一定能带咱们妖族走向复兴，替大伙报这个仇！",
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f大长老在塔顶也撑不了太久，速战速决，§e加油§f！"
        )

        val healerScript = listOf(
            "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f等一下，差点忘了——你们§d医师§f平日里救死扶伤本就辛苦，还要东奔西跑，着实为难你们了。我从镇妖塔仓库里偷偷顺了些比较§e珍贵的东西§f出来，优先给你们医师拿着。藏好了别声张，免得其他职业说我偏心眼儿"
        )

        return if (isHealer) baseScript + healerScript else baseScript
    }

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c返回 §4镇妖塔 §c寻找 §e华夭")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.YAO_HUAYAO.id || currentProgress != 0) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index == 0) {
            if (queryingPlayers.contains(player.uniqueId)) return true
            queryingPlayers.add(player.uniqueId)

            val plugin = Hjh_database.instance
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                var isCompleted = false
                var isHealer = false

                try {
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        conn.prepareStatement("SELECT qinglong FROM player_test WHERE uuid = ?").use { ps ->
                            ps.setString(1, player.uniqueId.toString())
                            val rs = ps.executeQuery()
                            if (rs.next() && rs.getInt("qinglong") == 1) {
                                isCompleted = true
                            }
                        }

                        conn.prepareStatement("SELECT job FROM player_data WHERE uuid = ?").use { ps ->
                            ps.setString(1, player.uniqueId.toString())
                            val rs = ps.executeQuery()
                            if (rs.next() && rs.getInt("job") == 3) {
                                isHealer = true
                            }
                        }
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    player.sendMessage("§c[系统] 数据库查询异常，请联系管理员！")
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    queryingPlayers.remove(player.uniqueId)

                    if (!isCompleted) {
                        player.sendMessage("§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f青龙神庙那边怎么样了？你先通过试炼，确认结界的情况再来找我。")
                        player.sendMessage("§c（尚未通过青龙试炼，请完成后再来与华夭交谈吧）")
                        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    } else {
                        trialPassedCache[player.uniqueId] = isHealer
                        playDialogue(player, isHealer) {
                            plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this@Yao_09)
                        }
                    }
                })
            })
        } else {
            val isHealer = trialPassedCache.getOrDefault(player.uniqueId, false)
            playDialogue(player, isHealer) {
                Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this@Yao_09)
            }
        }

        return true
    }

    private fun playDialogue(player: Player, isHealer: Boolean, onFinish: () -> Unit) {
        val scripts = getScript(isHealer)
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
                trialPassedCache.remove(player.uniqueId)
            }
        }
    }

    override fun giveReward(player: Player) {
        val isHealer = trialPassedCache.getOrDefault(player.uniqueId, false)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f金元宝 x2")
        player.sendMessage("  §e[奖励] §f元素兑换券 x8")
        player.sendMessage("  §e[奖励] §f愈合丹 x10")
        if (isHealer) {
            player.sendMessage("  §d[额外] §f三阶防具核心 x1")
            player.sendMessage("  §d[额外] §f赤铜锭 x1")
        }
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 100)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        val rm = Hjh_database.instance.resourceManager
        val itemsToGive = mutableListOf<ItemStack>()

        rm.getItem("jinyuanbao")?.let { it.amount = 2; itemsToGive.add(it) }
        rm.getItem("yuansuduihuanquan")?.let { it.amount = 8; itemsToGive.add(it) }
        rm.getItem("yuhedan0")?.let { it.amount = 10; itemsToGive.add(it) }

        if (isHealer) {
            rm.getItem("armor_core_3")?.let { it.amount = 1; itemsToGive.add(it) }
            rm.getItem("chitongding")?.let { it.amount = 1; itemsToGive.add(it) }
        }

        if (itemsToGive.isNotEmpty()) {
            val leftovers = player.inventory.addItem(*itemsToGive.toTypedArray())
            if (leftovers.isNotEmpty()) {
                player.sendMessage("§c[提示] 背包已满，部分物品已掉落在脚下！")
                for (item in leftovers.values) {
                    player.world.dropItem(player.location, item)
                }
            }
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        if (data != null) {
            data.questStatuses["main_south_1"] = com.hjh_database.quest.core.QuestStatus.IN_PROGRESS
            data.questProgress["main_south_1"] = 0

            player.sendMessage("")
            player.sendMessage("§b§l[系统] §f你已解锁新的主线章节：§e[南方区域]绿洲传闻")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
