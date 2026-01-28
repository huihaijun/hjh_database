package com.hjh_database.quest.impl.main.ren

import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.HashMap

class Ren_02 : QuestBase("main_ren_2", "[人族主线]坚韧试炼", QuestType.MAIN, 2) {

    // 限制人族，且需要在 main_ren_1 完成后才能接取（由 QuestManager 逻辑控制解锁，或者这里不做限制依靠顺序）
    override val raceLimit = 2
    override val description = listOf(
        "§7找到村长，与他对话吧",
        "§7村长就在你碰到的第一个分岔口的右侧"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // === 对话脚本 ===

    // 村长对话 Part 1：质疑与索要
    private val scriptChiefPart1 = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f啊……你也到了该出去见见世面的年纪了。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f看着你，就像看到年轻时的自己，满眼都是对山外世界的好奇。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f不过，就你现在这副模样，我实在很难相信你能担起我族的重任。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f江湖险恶，妖魔横行，我怕你这细胳膊嫩腿的，出门没两天就……",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f哦？你手上拿着的是……小仁的引荐信？",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f快，拿来给我瞧瞧。"
    )

    // 村长对话 Part 2：认可与发布任务
    private val scriptChiefPart2 = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f“此子心性纯良，勤勉好学……”嗯，小仁这孩子看人向来准。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f他既肯为你写这封信，说明你确有可取之处。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f只是……光有心性还不够。我们人族寿命虽短，却能在大陆上繁衍生息，靠的就是“坚韧”二字。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f可你看看自己，这双手连老茧都没几处。若连最基本的生存能力都没有，又怎能指望你为我族分忧？",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f这样吧……你若真想证明自己有那份坚韧，就去这人族峡谷最东头的山顶上打两桶山泉水回来。出门左转，看到上面那个最高的建筑了吗？那里流出的清泉，是我们人族峡谷最甘甜的水源。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f那里有个常年在打水的小伙子，叫“小礼”。你去找他，就说是我让你来的，他会教你如何取水。"
    )

    // 小礼的对话
    private val scriptXiaoli = listOf(
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f呼……呼……这水桶真沉啊。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f嗯？村长让你来的？看来你也要开始修行了啊。这山泉水就在这里了。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f诶诶诶？！你往哪跳呢！快上来！……谁跟你说采山泉水是要跳进去的呀？",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f你看——这水清澈见底，直接进去不是搅浑了吗？",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f在这片大陆上，人人都受天地灵气祝福，可以用自己的双手——",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f动用属于自身的那份“气”，来与万物共鸣，采其精华。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f村长说，这叫§b“开物术”§f。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f你只要把目光聚在你想采的东西上，轻轻一点——",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f就能引动周围的灵气，帮你把它“请”出来。？",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f不过记住，施展的时候要专注，别乱跑。要是中途分心，不仅会中断，还白白浪费你的精力和时间。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f万物有灵，不只咱们人，这些能采的东西也一样。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f开采前仔细看：要是它冒出淡淡的§a绿光§f，就是§a“富饶”§f状态——这时下手，又快又丰厚。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f要是光变得§e暗黄§f，就是“枯竭”啦。这时候也能采，但会费时费力，还可能……什么都采不到。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f要是连黄光都没了，只剩下§7灰蒙蒙§f一片——那就说明它正在休养，暂时不能采了。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f别急，等它休息好了，绿光自然会回来的。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f对了，每次施展开物术，都会消耗你的§b精力§f。不过精力会随着时间§b慢慢回复§f，而且你修为每提升一层，精力上限也会§b提高§f一些。",
        "§e[${StoryNpcs.REN_xiaoli.displayName}§e] §f……嗯，看来你都听明白了。来，试试看吧！就对着这汪泉水用开物术——山泉恢复得很快，不用担心采坏！"

    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c将信件交给村长 (0/1)")
            1 -> listOf("§c寻找小礼 (0/1)")
            2 -> listOf("§a已询问小礼", "§c采集山泉水并交给村长 (0/2)")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        // 这个任务最终完成是在提交物品后，由 completeQuest 设置为 9999 (或 COMPLETED)
        return progress >= 99
    }

    override fun giveReward(player: Player) {
        val plugin = com.hjh_database.Hjh_database.instance

        // 经验奖励
        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            plugin.databaseManager.savePlayer(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("  §7(村长似乎对你的表现很满意)")
        player.sendMessage("§8§m========================================")

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    // === 核心逻辑 ===
    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = com.hjh_database.Hjh_database.instance

        // -----------------------------------------------------------
        // 场景 1: 与村长对话 (提交信件 -> 接取打水任务 -> 提交水)
        // -----------------------------------------------------------
        if (npcId == StoryNpcs.REN_CHIEF.id) {

            // 阶段 0: 初次对话，需要提交信件
            if (currentProgress == 0) {
                val index = talkProgress.getOrDefault(player.uniqueId, 0)

                // 1. 播放 Part 1 (0 ~ size-1)
                if (index < scriptChiefPart1.size) {
                    player.sendMessage(scriptChiefPart1[index].replace("&", "§"))
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                    talkProgress[player.uniqueId] = index + 1
                    return true
                }

                // 2. Part 1 播完了，检查是否持有信件
                if (index == scriptChiefPart1.size) {
                    // 检查主手物品
                    val handItem = player.inventory.itemInMainHand

                    // 判断是不是“小仁的引荐信” (Paper + Name)
                    // 这里简单判断材质和名字，严格一点可以判断 Lore 或 PDC
                    if (handItem.type == Material.PAPER && handItem.itemMeta?.displayName?.contains("小仁的引荐信") == true) {
                        // 扣除信件
                        handItem.amount -= 1
                        player.sendMessage("§e[系统] 你将引荐信交给了村长。")
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)

                        // 进入下一阶段对话
                        talkProgress[player.uniqueId] = index + 1
                        // 稍微递归一下直接播放 Part2 第一句，或者让玩家再点一次，这里选择让玩家再点一次比较自然
                        player.sendMessage("§a[任务] -> (村长正在阅读信件，请点击左键继续)")
                        return true
                    } else {
                        player.sendMessage("§c[任务] -> 请将【小仁的引荐信】拿在主手，然后点击村长。")
                        return true
                    }
                }

                // 3. 播放 Part 2 (index > Part1.size)
                val part2Index = index - (scriptChiefPart1.size + 1) // +1 是因为扣除物品那个动作占了一个“点击”
                if (part2Index >= 0 && part2Index < scriptChiefPart2.size) {
                    player.sendMessage(scriptChiefPart2[part2Index].replace("&", "§"))
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                    talkProgress[player.uniqueId] = index + 1

                    // 如果是最后一句
                    if (part2Index >= scriptChiefPart2.size - 1) {
                        player.sendMessage("§a[任务] -> 对话结束，请去寻找小礼。")
                        // 更新进度到 1
                        plugin.questManager.updateProgress(player, id, 1)
                        talkProgress.remove(player.uniqueId)
                    }
                    return true
                }
            }

            // 阶段 1: 已经接了任务，但还没找小礼
            if (currentProgress == 1) {
                player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}] §f小礼就在峡谷东头，快去吧，别磨蹭。")
                return true
            }

            // 阶段 2: 找过小礼了，来交水
            if (currentProgress == 2) {
                // 检查背包是否有 2 桶山泉水 (湿海绵)
                if (hasQuestItem(player, "山泉水", 2)) {
                    // 扣除物品
                    removeQuestItem(player, "山泉水", 2)
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}] §f不错，虽然慢了点，但水没洒出来多少。")
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

                    // 完成任务
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                } else {
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}] §f水呢？我要的是两桶【山泉水】。")
                    player.sendMessage("§7(提示: 需要提交 2个 名为“山泉水”的湿海绵)")
                }
                return true
            }
        }

        // -----------------------------------------------------------
        // 场景 2: 与小礼对话 (指导打水)
        // -----------------------------------------------------------
        if (npcId == StoryNpcs.REN_xiaoli.id) {
            // 如果不在寻找小礼的阶段，就说普通闲聊
            if (currentProgress != 1) return false

            val index = talkProgress.getOrDefault(player.uniqueId, 0)

            if (index < scriptXiaoli.size) {
                player.sendMessage(scriptXiaoli[index].replace("&", "§"))
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
                talkProgress[player.uniqueId] = index + 1

                if (index >= scriptXiaoli.size - 1) {
                    player.sendMessage("§a[任务] -> 对话结束，请去采集山泉水。")
                    // 更新进度到 2 (开始打水)
                    plugin.questManager.updateProgress(player, id, 2)
                    talkProgress.remove(player.uniqueId)
                }
            }
            return true
        }

        return false
    }

    // === 辅助方法：检查并扣除任务物品 ===

    private fun hasQuestItem(player: Player, itemNamePart: String, amount: Int): Boolean {
        var count = 0
        for (item in player.inventory.contents) {
            if (item != null && item.type == Material.WET_SPONGE) { // 检查材质
                if (item.itemMeta?.displayName?.contains(itemNamePart) == true) {
                    count += item.amount
                }
            }
        }
        return count >= amount
    }

    private fun removeQuestItem(player: Player, itemNamePart: String, amount: Int) {
        var leftToRemove = amount
        for (item in player.inventory.contents) {
            if (item != null && item.type == Material.WET_SPONGE) {
                if (item.itemMeta?.displayName?.contains(itemNamePart) == true) {
                    val suck = Math.min(leftToRemove, item.amount)
                    item.amount -= suck
                    leftToRemove -= suck
                    if (leftToRemove <= 0) break
                }
            }
        }
    }
}