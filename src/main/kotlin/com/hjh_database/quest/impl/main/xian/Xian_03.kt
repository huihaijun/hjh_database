package com.hjh_database.quest.impl.main.xian

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Xian_03 : QuestBase("main_xian_3", "[仙族主线]天机令", QuestType.MAIN, 3) {

    override val raceLimit = 1

    override val description = listOf(
        "§7仙族盟主让你打造一枚天机令，方便日后下界查探。",
        "§7前往§e仙器铺§7，向掌柜学习锻造之法。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val leaderScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f……好茶。一杯饮尽，人也清爽多了。道友，方才说到下界之乱，如今尚无定论。族中近日议事频频，有人疑心是§4§n妖族§f作乱，也有人道神族那边行径可疑——毕竟四圣兽乃他们执掌，若说毫无干系，也说不过去。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f再过些时日，我自会派你下界彻查。只是仙凡往返一趟颇为周折，总叫你来回传信也不便。好在人族皇城的§e天机阁§f替我族造了一物，名为§6天机令§f。此令可知三界要闻，能指路明向，甚至可§e随身储物§f，虽小却五脏俱全。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f你且去我族的§e仙器铺§f，找那掌柜的。锻造之法，他自会倾囊相授。去吧，莫耽搁。"
    )

    private val shopkeeperScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f哦哦，新面孔！你好你好，快请进快请进！",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f道友来得好，小店的法宝那是出了名的物美价廉。看看这柄飞剑，用料扎实；这把长弓，做工华丽。放心，只收你一万两黄金就好……诶诶别走啊！五折，五折总行了吧？……三折？三折也成啊！",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f什么？你是来锻造§6天机令§f的？早说嘛，害我费这半天口舌……啊不是，是费心为你介绍。",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f盟主有令，天机令的材料对族人§e免费发放§f。拿好。接下来这锻造之法，可是咱们仙族立足的本事，听仔细了：",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f先站到锻造台前，伸手触碰，界面自开。台上分§e武器§f、§e防具§f、§e法宝§f、§e杂项§f四类，想造什么先选对门类。每件器物都有配方，点开即可查看所需材料，缺什么补什么。",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f锻造手艺越纯熟，能造之物越多。每成一器，经验便涨一分，等级水到渠成。若日后完成特殊委托或亲手锻出珍品，还能提升§e锻造资质§f——那些传说中的神兵法宝，没这资质可碰不得。",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f§6天机令§f在§e杂项§f栏里，自己去锻。锻好了拿来我瞧瞧。",
        "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f若实在学不会，我也可以开个小灶单独教，只收你五千两黄金……诶诶，玩笑，玩笑！别去盟主那儿告状啊！"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与 §e仙族盟主 §c对话")
        1 -> listOf("§c前往 §e仙器铺 §c寻找掌柜")
        2 -> listOf("§a已获得锻造材料", "§c锻造 §6天机令 §c并主手持有交给仙器铺掌柜检查")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.XIAN_XIANZUMENGZHU.id && currentProgress == 0) {
            playDialogue(player, leaderScript) {
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 前往仙器铺，向掌柜学习锻造天机令。")
            }
            return true
        }

        if (npcId != StoryNpcs.XIAN_XIANQIPUZHANGGUI.id) return false

        when (currentProgress) {
            1 -> playShopkeeperDialogue(player)
            2 -> inspectTianjiToken(player)
            else -> return false
        }
        return true
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 80)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +80")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun playShopkeeperDialogue(player: Player) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index !in shopkeeperScript.indices) return

        say(player, shopkeeperScript[index])
        if (index == MATERIAL_LINE_INDEX && !giveMaterials(player)) return

        if (index == shopkeeperScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 2)
            player.sendMessage("§a[任务] -> 已获得材料，请在锻造台的杂项中制作天机令。")
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun inspectTianjiToken(player: Player) {
        if (!hasTianjiTokenInMainHand(player)) {
            player.sendMessage("§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f天机令还没锻好？做好后拿在主手，再来让我瞧瞧。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        say(player, "§e[${StoryNpcs.XIAN_XIANQIPUZHANGGUI.displayName}§e] §f不错不错，锻造好了就去旁边的灵丹铺找那里的掌柜吧，他也会教你一些东西，别耽误我搞生意了嘿")
        val data = plugin.playerManager.getPlayerData(player) ?: return
        plugin.questManager.completeQuest(player, data, this)
    }

    private fun giveMaterials(player: Player): Boolean {
        val core = plugin.resourceManager.getItem("tianjihe")
        val needle = plugin.resourceManager.getItem("tiezhen")
        if (core == null || needle == null) {
            player.sendMessage("§c[错误] 无法获取任务物品配置，请联系管理员！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return false
        }

        core.amount = 1
        needle.amount = 2
        player.inventory.addItem(core, needle).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
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
        if (index !in script.indices) return

        say(player, script[index])
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private companion object {
        const val MATERIAL_LINE_INDEX = 3
    }
}
