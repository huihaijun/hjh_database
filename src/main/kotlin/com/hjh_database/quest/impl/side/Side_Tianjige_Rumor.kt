package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class Side_Tianjige_Rumor : QuestBase("side_tianjige_rumor", "天机阁传闻", QuestType.SIDE, 1) {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val acceptedJianghuXindeIds = setOf(
        "jianghuxinde_longlinzhisen",
        "jianghuxinde_yanshadamo"
    )

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return data.lv >= 15
    }

    override val description = listOf(
        "§7皇城的天机阁今日开放了？里面到底有什么秘密？快去一探究竟！",
        "§7天机阁就在前往皇宫的路上，经过两个石狮子上台阶后，",
        "§7一直往西走就能看到了！先去找他们的总管聊一下？"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往皇城天机阁寻找 §e总管-孙元")
            1 -> listOf("§c主手持有一本 §d江湖心得 §c交给 §e孙元")
            2 -> listOf("§c继续听 §e孙元 §c介绍天机阁")
            3 -> listOf("§c前往楼上寻找 §e武凌河")
            4 -> listOf("§a已获得元素结晶", "§c继续听 §e武凌河 §c讲解")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.TIANJIGEZONGGUANSUNYUAN.id) {
            return handleSunYuanDialogue(player, currentProgress)
        }
        if (npcId == StoryNpcs.WULINGHE.id) {
            return handleWuLingheDialogue(player, currentProgress)
        }
        return false
    }

    private fun handleSunYuanDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            0 -> playDialogue(player, sunYuanIntro) {
                plugin.questManager.updateProgress(player, id, 1)
                player.sendMessage("§a[任务] -> 请主手持有一本江湖心得，再与孙元对话。")
            }
            1 -> {
                if (!isHoldingAcceptedJianghuXinde(player)) {
                    player.sendMessage("§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f想加入天机阁，须先交来一本§d江湖心得§f。记得拿在主手上。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                takeMainHandItem(player)
                talkProgress.remove(player.uniqueId)
                plugin.questManager.updateProgress(player, id, 2)
                player.sendMessage("§a[任务] -> 已交付江湖心得。")
                playDialogue(player, sunYuanJoined) {
                    plugin.questManager.updateProgress(player, id, 3)
                    player.sendMessage("§a[任务] -> 请从书台后方上楼寻找武凌河。")
                }
            }
            2 -> playDialogue(player, sunYuanJoined) {
                plugin.questManager.updateProgress(player, id, 3)
                player.sendMessage("§a[任务] -> 请从书台后方上楼寻找武凌河。")
            }
            else -> return false
        }
        return true
    }

    private fun handleWuLingheDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            3 -> playDialogue(player, wuLingheIntro) {
                if (!giveResource(player, "yuansujiejing1", 1, "元素结晶")) return@playDialogue
                plugin.questManager.updateProgress(player, id, 4)
            }
            4 -> playDialogue(player, wuLingheExplanation) {
                plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
            }
            else -> return false
        }
        return true
    }

    private fun isHoldingAcceptedJianghuXinde(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        val meta = item.itemMeta ?: return false
        val resourceId = meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING) ?: return false
        return resourceId in acceptedJianghuXindeIds
    }

    private fun takeMainHandItem(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= 1
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
    }

    private fun giveResource(player: Player, resourceId: String, amount: Int, displayName: String): Boolean {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            player.sendMessage("§c[错误] 无法获取物品配置：$resourceId，请联系管理员！")
            return false
        }

        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，$displayName 已掉落在脚下！")
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        } else {
            player.sendMessage("§a[系统] 获得 $displayName x$amount")
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
        return true
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= scripts.size) return

        val message = scripts[index].replace("%player%", player.name)
        player.sendMessage(message)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        talkProgress[player.uniqueId] = index + 1

        if (index == scripts.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +150")
        player.sendMessage("  §e[奖励] §f金元宝 x2")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 150)
        giveResource(player, "jinyuanbao", 2, "金元宝")
        plugin.playerManager.getPlayerData(player)?.let { plugin.databaseManager.savePlayerAsync(it) }
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean = false

    private val sunYuanIntro = listOf(
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f年轻人，怎么样，我们这天机阁够气派吧！",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f以往啊，这§6天机阁§f可是皇宫里的禁地，等闲之辈连门都摸不着。不是咱小气，实在是里头这些仪器金贵得很，磕了碰了，谁也担待不起。不过皇上隆恩浩荡，特许凡§e历练等级超过十五级§f的俊杰，便能入阁免费参观！",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f老夫瞧你步履沉稳、气度不凡，想必早已满足这个条件了。来，今日便由我替你引路讲解一番。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f天者，大道之经纬也；机者，万物之枢要也。而我们这座§6天机阁§f，便是皇城中一众英才汇聚于此，日夜钻研这天地运行至理的所在。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f你现在所站之处，名为§e迎才层§f。严格来说，这里连我们天机阁的第一层都算不上。整座天机阁共分§c六层§f，越是往上，所涉之学便越发深奥，所蕴之力也越发惊人。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f而这§e迎才层§f，便是根基中的根基。凡天下才俊，无论出身，皆可于此驻足。日后若有机缘，更可正式加入我们，成为天机阁的一员。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f怎么样，年轻人，可愿加入？不过，既是门槛，总得有个条件。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f你在近来的游历中，可有拾获一卷名为§d江湖心得§f的卷轴？只需交上§e一本§f，老夫便做主，纳你为天机阁的一员！"
    )

    private val sunYuanJoined = listOf(
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f不错不错，正是这本§d江湖心得§f！好，老夫现在宣布——",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f§e%player%§f，自此刻起，正式成为我§6天机阁§f的一员！",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f咳，别嫌这仪式寒酸。咱天机阁的经费全砸在研究上头了，哪还有闲钱给你们这帮毛头小子买烟花爆竹热闹一番。心意到了便是！",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f闲话少叙，这一层由老夫负责，专教你们如何善用手中的§d江湖心得§f。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f你瞧桌上那本悬浮的书台没有？此物名为§6江湖通鉴§f。只需伸手轻触，便能将你游历四方的种种历练尽数呈现——§b开物术§f、§d冶药法§f、§e锻造术§f……但凡你在这江湖中学到的本事，无所不包。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f这通鉴最精妙之处，在于它能将你手中那卷§d江湖心得§f，化作对应技能的经验，且§c毫无损耗§f。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f这于你修行而言，裨益之大不可估量。有人擅长冲锋陷阵，有人醉心锻造百器，而这§6江湖通鉴§f，却能让你在厮杀中悟出的心得，尽数化为提升其他技艺的养分。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f只需手持§d江湖心得§f，往这书台上轻轻一触，其中蕴藏的阅历便会化作经验，存入你的灵脉之中，永不消散。",
        "§e[${StoryNpcs.TIANJIGEZONGGUANSUNYUAN.displayName}§e] §f这便是江湖通鉴的奥秘所在。你大可放手一试，待摸清门路之后，便从这书台后方上楼去。那上头，才是咱们天机阁真正的§e第一层§f。那里有位师傅，名唤§e武凌河§f，他会教你些新的本事。"
    )

    private val wuLingheIntro = listOf(
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f哟，天机阁又来新面孔了！看来咱们门派日后必定是蒸蒸日上啊，哈哈！",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f底下孙元那老家伙跟你絮叨的那些，想必都记住了吧？那老夫便不费口舌了。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f咳咳……莫慌，这烟虽大了些，但对身子骨无害。你且看这烟、这火——这可是咱们淬炼§6元素结晶§f的独门秘法！",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f什么？你还没有§6元素结晶§f？也罢也罢，看在你初入天机阁的份上，老夫便送你一枚，接好了！"
    )

    private val wuLingheExplanation = listOf(
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f如何，看仔细了吧？是不是精妙绝伦？",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f§e元素§f，乃是这世间最为基础的组成。五行流转，生生不息。我等发现，将元素之力灌注于这结晶之中，便能令其焕发新生，让佩戴者借以掌控§e元素的力量§f。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f而这结晶的奥妙，与其§c等阶§f息息相关。等阶越高，所能承载的元素之力便越强。但这力量往往狂躁难驯，稍有不慎便会反噬己身。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f为此，咱们研制出了这台§6元素结晶台§f——喏，就你面前这紫疙瘩。将结晶置于中央，它便能感应其等阶，将其化作§b元素点数§f，每一阶对应§b1点§f。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f你可以在这台上，将点数任意分配于五行之中。记住老夫的话——\n§f当某一元素分配达到§b2点§f时，便会触发§d「启示」§f，初窥此元素的真谛；\n§f达到§b4点§f时，此元素技艺便获得§d「精进」§f，威力大增；\n§f而一旦攀至§b6点§f，元素之力将尽数迸发，与你产生§d「共鸣」§f！到那时，你获得的元素之力将极为恐怖……",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f日后你若想换条路子，只管点击§e重新分配§f，便可无损重来。元素的奥秘，就在于不断尝试。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f不过有一言，你须牢记在心。这§6元素结晶§f与你们手里那些破铜烂铁不同，它会与持有者心意相连。一枚崭新的结晶，只会认§c第一个将它放入结晶台的人§f为主。若非此人之手放入台面，结晶将丧失全部能力，后果不堪设想！",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f所以激活它之时，务必心无杂念。日后也别动歪心思去偷拿旁人的结晶——修炼这条路，终究得靠自己一步步走。",
        "§e[${StoryNpcs.WULINGHE.displayName}§e] §f好了，老夫要交代的便只有这些。待你日后修行精进，天机阁后面那几层，自有更深的奥秘等你一探究竟。去吧！"
    )
}
