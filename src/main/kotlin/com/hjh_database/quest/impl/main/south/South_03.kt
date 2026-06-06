package com.hjh_database.quest.impl.main.south

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.HashMap

/**
 * 南方主线任务第三章 - 伪神之谜
 */
class South_03 : QuestBase("main_south_3", "[主线]伪神之谜", QuestType.MAIN, 12) {

    // 无种族限制
    override val raceLimit = null

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往旧村庄废墟寻找 §e莲心")
            1 -> listOf("§c收集 §e火元素x20 §c和 §e土元素x20 §c交给莲心")
            2 -> listOf("§a已交付元素", "§c继续听 §e莲心 §c说明丹药用法")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7红鸾让你去通知被选为祭品的莲心。",
        "§7前往旧废墟，",
        "§7看看这个不信神的女孩究竟有什么秘密。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<String, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptPart1 = listOf(
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f你好，请问你找我有什么事情吗？",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f…§4§n红鸾§f请你来找我的？",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f嗯，我大概知道了，是祭品的事情吧？",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f不是我能未卜先知，而是因为§4§n红鸾§f她会假借§4§n火山神§f的旨意指定祭品，将不相信她的人处以死刑",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我从来就没有相信过她，所以她迟早得将我除掉的…",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我之所以知道她在说谎，是因为§4§n火山神§f根本就不存在",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我从很早以前就搜集了许多关于这座§2§o「喷火神山」§f的数据",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f事实上，这座火山的不稳定是从§4§n圣兽朱雀§f在约百年前的离去有关",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f记载中的火山活动，近几年来有越来越频繁的趋势，这也才因此让§4§n巫者§f得以招摇撞骗",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f如果我的理论没错，火山下方应该有可以控制它的东西存在着…",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我翻了很多的数据，准备调配一种可以§9让人不畏惧岩浆的丹药§f",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f要是能有多一点的时间就好了…材料也完全不够…",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f你要帮我？那我就先谢过了",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f药引的事你不用担心，我早就备好了几份，待会儿直接给你便是",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f眼下最缺的是元素，尤其是§4§n火元素§f和§4§n土元素§f，火山四周本应遍地都是，奈何我一个人不敢走远",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f劳烦你替我跑一趟，各找来二十枚就好，够数了我便能开炉炼制",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f谢谢你了，时间不多，快去快回吧，我在这里等着"
    )

    private val scriptNotEnough = listOf(
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f收集齐材料了吗？",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我需要二十枚火元素和二十枚土元素，麻烦你了"
    )

    private val scriptPart2 = listOf(
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f好了，我这就把丹药给你，我能给到的就这么多，一定要省着点用",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f服下后应该就能暂时不受岩浆的伤害了，直接跳进§2§o岩浆口§f吧",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f火山洞内§2§o靠近平台那一侧§f应该有个可以进入火山内部的§2§o信道§f",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f从§2§o信道§f进去应该就能够了解火山的秘密了",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f另外，我调配的§9丹药§f持续时间不长，所以请小心使用",
        "§e[${StoryNpcs.LIANXIN.displayName}§e] §f我们到里面再见吧"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.LIANXIN.id) {
            val plugin = Hjh_database.instance

            if (currentProgress == 0) {
                // 第一阶段对话
                playDialogue(player, "part1", scriptPart1) {
                    player.sendMessage("§a[任务] -> 已接受收集委托，请收集火元素与土元素。")
                    plugin.questManager.updateProgress(player, id, 1)
                }
                return true
            }
            else if (currentProgress == 1) {
                // 第二阶段：检查物品
                if (hasResourceItem(player, "fire", 20) && hasResourceItem(player, "earth", 20)) {
                    if (Hjh_database.instance.resourceManager.getItem("lianxindan") == null) {
                        player.sendMessage("§c[错误] 无法获取莲心丹配置，请联系管理员！")
                        return true
                    }

                    if (!consumeElements(player)) {
                        playDialogue(player, "not_enough", scriptNotEnough) {}
                        return true
                    }

                    giveLianxinDan(player)
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] -> 已交付火元素与土元素。")
                    clearDialogueProgress(player)

                    playDialogue(player, "part2", scriptPart2) {
                        plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                    }
                } else {
                    // 材料不足
                    playDialogue(player, "not_enough", scriptNotEnough) {}
                }
                return true
            }
            else if (currentProgress == 2) {
                playDialogue(player, "part2", scriptPart2) {
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return false
    }

    private fun playDialogue(player: Player, dialogueId: String, scripts: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player, dialogueId)
        val index = talkProgress.getOrDefault(key, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            talkProgress[key] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(key)
                onFinish()
            }
        }
    }

    private fun dialogueKey(player: Player, dialogueId: String): String {
        return "${player.uniqueId}:$dialogueId"
    }

    private fun clearDialogueProgress(player: Player) {
        val prefix = "${player.uniqueId}:"
        talkProgress.keys.removeIf { it.startsWith(prefix) }
    }

    // ==========================================
    // 物品检测与扣除 (通过 ResourceManager 的 ID 检索 NBT)
    // ==========================================

    private fun hasResourceItem(player: Player, resId: String, amount: Int): Boolean {
        var count = 0
        val key = NamespacedKey(Hjh_database.instance, "resource_id")
        for (item in player.inventory.contents) {
            if (item != null && item.hasItemMeta()) {
                val meta = item.itemMeta
                if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) {
                    val id = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
                    if (id == resId) {
                        count += item.amount
                    }
                }
            }
        }
        return count >= amount
    }

    private fun consumeElements(player: Player): Boolean {
        if (!hasResourceItem(player, "fire", 20) || !hasResourceItem(player, "earth", 20)) {
            return false
        }

        val removedFire = removeResourceItem(player, "fire", 20)
        val removedEarth = removeResourceItem(player, "earth", 20)
        return removedFire && removedEarth
    }

    private fun removeResourceItem(player: Player, resId: String, amount: Int): Boolean {
        var leftToRemove = amount
        val key = NamespacedKey(Hjh_database.instance, "resource_id")
        for (item in player.inventory.contents) {
            if (item != null && item.hasItemMeta()) {
                val meta = item.itemMeta
                if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) {
                    val id = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
                    if (id == resId) {
                        val take = Math.min(leftToRemove, item.amount)
                        item.amount -= take
                        leftToRemove -= take
                        if (leftToRemove <= 0) break
                    }
                }
            }
        }
        return leftToRemove <= 0
    }

    private fun giveLianxinDan(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val item = rm.getItem("lianxindan")
        if (item == null) {
            player.sendMessage("§c[错误] 无法获取莲心丹配置，请联系管理员！")
            return
        }

        item.amount = 3
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，莲心丹已掉落在脚下！")
            for (drop in leftovers.values) {
                player.world.dropItem(player.location, drop)
            }
        } else {
            player.sendMessage("§a[系统] 获得了 莲心丹 x3")
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    // ==========================================
    // 任务奖励
    // ==========================================

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 100)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
