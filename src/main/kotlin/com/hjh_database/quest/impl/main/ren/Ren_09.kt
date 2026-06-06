package com.hjh_database.quest.impl.main.ren

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

class Ren_09 : QuestBase("main_ren_9", "[人族主线]四方圣兽", QuestType.MAIN, 9) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c向皇宫的 §e大内总管-李公公 §c汇报情况")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你已成功通过了青龙试炼，",
        "§7并且获得了青龙的祝福。",
        "§7现在请回到皇宫，",
        "§7将一切告知大内总管李公公。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // 缓存玩家的职业信息（在第一次查询数据库后缓存，用于发放奖励）
    private val trialPassedCache = HashMap<UUID, Boolean>()

    // 防连点控制：记录正在异步查询数据库的玩家
    private val queryingPlayers = mutableSetOf<UUID>()

    // ==========================================
    // 剧本配置（根据是否为医师动态生成）
    // ==========================================

    private fun getScript(isHealer: Boolean): List<String> {
        val baseScript = listOf(
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f原来如此…所以你得到了青龙祝福之后,还是无法感知§4§n圣兽§f的位置吗…",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f看来就跟§4§n分魂§f说的一样,可能得到§4§n四圣兽§f的完整祝福之后,就能够感觉到§4§n圣兽§f们的位置吧",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f这样吧,你先去南边§2§o焱砂大漠§f中的§2§o朱雀祭坛§f看看,再去西边§2§o虎爪山脉§f的§2§o白虎祭坛§f,最后到北边§2§o玄水湖泊§f的§2§o玄武祭坛§f",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f这三处的魔物一处比一处凶险,你最好按着咱家的顺序依次前往,免得吃了亏",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f南方气候炎热干燥,若是途经一片§2§o绿洲§f,可在那儿歇歇脚,那边的§4§n镇长§f为人不错,兴许能帮你解决朱雀祭坛的麻烦",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f如果搜集到了全部的祝福,就到§2§o皇宫东侧§f找§4§n护国法师§f",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f我想有了祝福的力量,他应该有办法找到§4§n圣兽§f的位置",
            "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f之后的路,就得靠你一个人去走了,这些东西你收着,虽不是什么稀罕玩意儿,但盼着能对你的征途有些用处"
        )

        if (isHealer) {
            val healerScript = listOf(
                "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f唉呀，瞧咱家这记性，你们做§b医师§f的，平日里悬壶济世也就罢了，如今还不辞辛劳、跋山涉水来替咱解决这些麻烦，咱家这心里头，真是暖烘烘的",
                "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f来，这些算是咱家§b额外§f贴给你们的，虽不是什么珍贵东西，但多少能让你们在这条道上走得更稳当些",
                "§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f可记着，§b悄悄§f收好便是，别跟其他职业的家伙们张扬，省得说咱家§c偏心眼§f儿，呵呵～"
            )
            return baseScript + healerScript
        }

        return baseScript
    }

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.REN_LIGONGGONG.id) {
            if (currentProgress == 0) {
                val index = talkProgress.getOrDefault(player.uniqueId, 0)

                // 如果是第一句对话，执行异步检查
                if (index == 0) {
                    if (queryingPlayers.contains(player.uniqueId)) return true // 防止狂点NPC
                    queryingPlayers.add(player.uniqueId)

                    val plugin = Hjh_database.instance

                    // 异步检查数据库，防止卡顿主线程
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        var isCompleted = false
                        var isHealer = false

                        try {
                            plugin.databaseManager.dataSource?.connection?.use { conn ->
                                // 1. 查询是否完成青龙试炼
                                val sqlQinglong = "SELECT qinglong FROM player_test WHERE uuid = ?"
                                conn.prepareStatement(sqlQinglong).use { ps ->
                                    ps.setString(1, player.uniqueId.toString())
                                    val rs = ps.executeQuery()
                                    if (rs.next() && rs.getInt("qinglong") == 1) {
                                        isCompleted = true
                                    }
                                }

                                // 2. 查询玩家是否是医师 (job == 3)
                                val sqlJob = "SELECT job FROM player_data WHERE uuid = ?"
                                conn.prepareStatement(sqlJob).use { ps ->
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

                        // 回到主线程处理结果
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            queryingPlayers.remove(player.uniqueId)

                            if (!isCompleted) {
                                // 未完成试炼
                                player.sendMessage("§e[${StoryNpcs.REN_LIGONGGONG.displayName}§e] §f怎么样了年轻人，森林的事情解决妥善了没有")
                                player.sendMessage("§c（尚未通过青龙试炼，请完成后再来与李公公交谈吧）")
                                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                            } else {
                                // 验证通过，缓存职业状态并开始播放对话
                                trialPassedCache[player.uniqueId] = isHealer
                                playDialogue(player, isHealer) {
                                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this@Ren_09)
                                }
                            }
                        })
                    })
                } else {
                    // 后续对话直接从缓存读取职业状态并继续播放
                    val isHealer = trialPassedCache.getOrDefault(player.uniqueId, false)
                    playDialogue(player, isHealer) {
                        Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this@Ren_09)
                    }
                }
                return true
            }
        }
        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private fun playDialogue(player: Player, isHealer: Boolean, onFinish: () -> Unit) {
        val scripts = getScript(isHealer)
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
                // 发放完奖励后清理缓存
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

        // 1. 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 100)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        // 2. 发放物品
        val rm = Hjh_database.instance.resourceManager
        val itemsToGive = mutableListOf<ItemStack>()

        // 基础奖励
        rm.getItem("jinyuanbao")?.let { it.amount = 2; itemsToGive.add(it) }
        rm.getItem("yuansuduihuanquan")?.let { it.amount = 8; itemsToGive.add(it) }
        rm.getItem("yuhedan0")?.let { it.amount = 10; itemsToGive.add(it) }

        // 医师额外奖励
        if (isHealer) {
            rm.getItem("armor_core_3")?.let { it.amount = 1; itemsToGive.add(it) }
            rm.getItem("chitongding")?.let { it.amount = 1; itemsToGive.add(it) }
        }

        // 3. 塞入背包
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

        // 自动解锁南方主线第一章
        if (data != null) {
            // 将下一个任务设为进行中
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
