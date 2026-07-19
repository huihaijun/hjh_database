package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Shen_04 : QuestBase("main_shen_4", "[神族主线]天机令", QuestType.MAIN, 4) {

    override val raceLimit = 0

    override val description = listOf(
        "§7资源中心负责人准备了一批天机阁材料。",
        "§7去听听他对锻造天机令的安排。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val script = listOf(
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f是这样，长老日理万机，你的日常修行他顾不过来。我需在此地值守资源中心，也无法手把手教你下一步。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f这里有一些人族皇城§e天机阁§f留下的材料，足以锻造一件名为§6天机令§f的器物。此令可借助天地运转之理，助你洞悉前路方向、了解自身能力，另有一些奇妙功用，你日后自会体会。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f锻造可会？不会也无妨。听一遍：先站到锻造台前，伸手即触，界面自开。台上分§e武器§f、§e防具§f、§e法宝§f、§e杂项§f四类，想造何物先找准门类。每件器物皆有配方，点进去便能查看所需材料——缺什么，记下便是。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f锻造手艺越纯熟，能造之物便越多。每成一器，经验便涨一分，等级自然往上升。若有幸完成特殊委托或亲手锻出珍品，还能提升§e锻造资质§f——日后那些传说中的神兵利器，皆需此等资质方可触碰。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f材料拿好，锻造房沿这条路直走便是。打好之后把§6天机令§f拿来让我过目，看看是否合乎标准。",
        "§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f天机令在§e杂项§f类目，不要找错了，快去吧！"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e资源中心负责人 §c对话")
        1 -> listOf("§a已获得锻造材料", "§c锻造 §6天机令 §c并主手持有交给负责人")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.id) return false

        when (currentProgress) {
            0 -> playDialogue(player, script) {
                if (giveMaterials(player)) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 已获得材料，请在锻造台的杂项中制作天机令。")
                }
            }

            1 -> {
                if (!hasTianjiTokenInMainHand(player)) {
                    player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f天机令可铸好了？做好后主手持有它，再来找我。")
                    return true
                }

                player.sendMessage("§e[${StoryNpcs.SHEN_ZIYUANZHONGXINFUZEREN.displayName}§e] §f嗯，这枚§6天机令§f锻得不错，已经合乎标准了。")
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }

            else -> return false
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 10)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun giveMaterials(player: Player): Boolean {
        val core = plugin.resourceManager.getItem("tianjihe")
        val needle = plugin.resourceManager.getItem("tiezhen")
        if (core == null || needle == null) {
            player.sendMessage("§c[错误] 无法获取任务物品配置，请联系管理员！")
            return false
        }

        core.amount = 1
        needle.amount = 2
        val leftovers = player.inventory.addItem(core, needle)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
        }

        player.sendMessage("§e[系统] 获得 ${core.itemMeta?.displayName ?: "天机核"} x1")
        player.sendMessage("§e[系统] 获得 ${needle.itemMeta?.displayName ?: "铁针"} x2")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
        return true
    }

    private fun hasTianjiTokenInMainHand(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (plugin.menuManager.isTianjiToken(item)) return true

        val meta = item.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName.contains("天机令")
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= script.size) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        talkProgress[player.uniqueId] = index + 1

        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        }
    }
}
