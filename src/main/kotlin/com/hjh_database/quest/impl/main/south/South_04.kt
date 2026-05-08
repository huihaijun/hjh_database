package com.hjh_database.quest.impl.main.south

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

/**
 * 南方主线任务第四章 - 朱雀祭坛
 */
class South_04 : QuestBase("main_south_4", "[主线]朱雀祭坛", QuestType.MAIN, 13) {

    // 无种族限制
    override val raceLimit = null

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c进入信道，寻找 §e莲心")
            1 -> listOf("§c通过朱雀试炼后，向 §e莲心 §c回报")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你服用丹药后跳入了火山口，",
        "§7顺着信道来到了神秘的地下。",
        "§7莲心似乎已经在此等候，去问问她的发现吧。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // 防连点控制：记录正在异步查询数据库的玩家
    private val queryingPlayers = mutableSetOf<UUID>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptPart1 = listOf(
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f啊啊…果然是这样，岩浆下面竟然藏着§4§n朱雀§f的§2§o祭坛§f…",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f这座§2§o祭坛§f原本是一处运转地火的古老阵法，能将火山躁动的灵力转化为滋养南疆大地的生机",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f可自从§4§n朱雀§f离去之后，阵法失了主持，无人引导的灵力便成了这日益狂暴的祸根",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f换句话说，火山之所以蠢蠢欲动，并非什么神怒天罚，只是这股力量憋得太久，快要压不住罢了",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f不过，光凭我们两人，就算摸清了缘由，也没法子叫这火山安静下来",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f你既然能走到这里，想必不是泛泛之辈",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f这祭坛后方应当有一条旧时留下的§2§o通路§f，若能顺着它进入§2§o朱雀神庙§f，或许能寻到§4§n朱雀§f当年留下的试炼",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f§4§n圣兽§f虽已远去，神庙中的残力却从未断绝，只要你通过了试炼，获得§4§n朱雀§f的祝福，说不定便能暂时稳住这座祭坛",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f我留在这里继续记录阵法的运转，你且去神庙一试",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f等你带着祝福归来，我们再一起去见镇长，到那时，任她§4§n红鸾§f再怎么巧舌如簧，也抵不过你和这阵法亲口说出来的真相",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f千万小心，我在这儿等你回来"
    )

    private val scriptPassed = listOf(
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f…你回来了？这股温热的气息…你果然通过了§4§n朱雀§f的试炼",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f太好了，有了这份祝福和我的数据，我们就有了足够的分量和镇民们说明真相",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f至于§4§n红鸾§f…那些被当作祭品投进岩浆的人们，每一个都是她亲口下令害死的",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f这笔账，镇民们自然会找她算清楚",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f不管怎么说，真的很感谢你",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f等我一下，我写些东西…",
        "§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f好了，我还会在这里呆一会，就麻烦你将这封§9信§f交给镇长吧"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.JITAN_LIANXIN.id) {
            val plugin = Hjh_database.instance

            if (currentProgress == 0) {
                // 第一阶段：初次对话，引出朱雀试炼
                playDialogue(player, scriptPart1) {
                    player.sendMessage("§a[任务] -> 已了解到真相，请前往参与[朱雀试炼]。")
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            else if (currentProgress == 1) {
                // 第二阶段：检查是否完成朱雀试炼
                val index = talkProgress.getOrDefault(player.uniqueId, 0)

                // 只有在第一句话时才去查数据库，如果已经查过了正在播放后续对话就不再查
                if (index == 0) {
                    if (queryingPlayers.contains(player.uniqueId)) return true
                    queryingPlayers.add(player.uniqueId)

                    // 异步查询数据库
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        var isCompleted = false
                        try {
                            plugin.databaseManager.dataSource?.connection?.use { conn ->
                                val sql = "SELECT zhuque FROM player_test WHERE uuid = ?"
                                conn.prepareStatement(sql).use { ps ->
                                    ps.setString(1, player.uniqueId.toString())
                                    val rs = ps.executeQuery()
                                    if (rs.next() && rs.getInt("zhuque") == 1) {
                                        isCompleted = true
                                    }
                                }
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            player.sendMessage("§c[系统] 数据库查询异常，请联系管理员！")
                        }

                        // 回到主线程执行结果
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            queryingPlayers.remove(player.uniqueId)
                            if (!isCompleted) {
                                // 未通过试炼，直接发一句提示并拒绝后续对话
                                player.sendMessage("§e[${StoryNpcs.JITAN_LIANXIN.displayName}§e] §f勇者，你还好吗，朱雀大人的试炼是否通过了呢？")
                                player.sendMessage("§c提示：请先通过[朱雀试炼]，再来与§a§l莲心§c交谈吧")
                                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                            } else {
                                // 已通过，开始播放下一阶段剧情
                                playDialogue(player, scriptPassed) {
                                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this@South_04)
                                }
                            }
                        })
                    })
                } else {
                    // 后续对话直接播放
                    playDialogue(player, scriptPassed) {
                        plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                    }
                }
                return true
            }
        }
        return false
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
    // 任务奖励
    // ==========================================

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +300")
        player.sendMessage("  §e[奖励] §f交给镇长的信件 x1")
        player.sendMessage("§8§m========================================")

        // 1. 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 300
            Hjh_database.instance.databaseManager.savePlayer(data)
        }

        // 2. 发放任务物品
        val rm = Hjh_database.instance.resourceManager
        rm.getItem("jiaogeizhenzhangdexinjian")?.let { item ->
            item.amount = 1
            val leftovers = player.inventory.addItem(item)
            if (leftovers.isNotEmpty()) {
                player.sendMessage("§c[提示] 背包已满，信件已掉落在脚下！")
                for (drop in leftovers.values) {
                    player.world.dropItem(player.location, drop)
                }
            }
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}