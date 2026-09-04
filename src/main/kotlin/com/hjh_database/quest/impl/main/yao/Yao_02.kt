package com.hjh_database.quest.impl.main.yao

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

class Yao_02 : QuestBase("main_yao_2", "[妖族主线]启灵之果", QuestType.MAIN, 2) {

    override val raceLimit = 4
    override val description = listOf(
        "§7将小花的推荐函交给谷主。",
        "§7谷主就在古树下的水池边。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")

    private val scriptGuzhuPart1 = listOf(
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f哦哦……终于又有新生的§2§o妖族§f同伴了么……",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f老夫在这谷里守了不知多少年月，眼看着族人日渐凋零，心都凉了大半。看来上苍还未抛弃我们，老夫有生之年，说不定真能看到§2§o我族§f的复兴啊……",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f孩子，走近些，让老夫好好瞧瞧你——嗯，灵息纯净，妖脉初生，是个好苗子。就是这身子骨尚还稚嫩，刚来到这世上，怕是连站都还没站稳吧？",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f咦，你手上拿的是……§b小花的推荐函§f？快，递过来给老夫看看。"
    )

    private val scriptGuzhuPart2 = listOf(
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f“……初生灵智，眼神清亮，心意赤诚，恳请谷主多加照拂……”呵呵，小花这丫头真有意思。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f孩子，你既是我妖族新生的一脉，老夫便有责任教导你。如今这片大陆早已不似太古那般万物平等——§c人族§f与§c仙族§f视我们为眼中钉，稍有不慎便有性命之危。想在乱世中活下去，光有一颗赤子心可不够。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f看到那棵参天古树了吗？那是咱们叶灵谷的§e根基§f，千年开花，百年一果。树梢上那些沉甸甸的果子，名为§e启灵果§f——新生的妖族吃过之后，灵智初开，能更快适应这天地间的灵气流转。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f这样吧，你若想证明自己有这份心意，就去替老夫摘两颗§e启灵果§f回来。那树高得很，可别想着爬上去——树旁有个叫§e小蔓§f的藤妖丫头，去寻她帮忙，就说老夫让你来的。她日日守着那棵古树，自有巧法能助你取果。",
        "§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f去之前先歇歇脚，吃点东西，别急着跑。刚醒来的小妖腿脚还软着呢，慢慢走，不急。"
    )

    private val scriptXiaoman = listOf(
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f诶，族人！我在这儿呢！",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f嘻嘻，你还没开口我就猜到你要找我啦。每一个新生的族人，谷主都会让他来这儿，从咱们§e古树§f上摘两颗§e启灵果§f回去。这果子可甜了，吃过的都说好！",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f喏，看见树梢上那些红彤彤的果子没？是不是特别诱人？这启灵果呀，可是咱们叶灵谷的宝贝，百年才结一次，寻常人想闻都闻不着呢——",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f诶诶诶！你干嘛呢！快停下，别往树上爬！",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f哎呀，你这性子也太急了，话都没听完就往树上蹿，树上可没梯子接着你。再说了，咱们§2§o妖族§f可是受天地灵气滋养，与自然和谐共生的种族，你这副手忙脚乱的模样，传出去岂不叫旁人笑话。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f来，我教你。轻闭双眼，静下心神，用指尖去触碰眼前的花草树木、天地灵物。等你心念合一，与它们共鸣之际——§d砰§f！那东西就自己跑到你手里了，神奇吧？",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f谷主说，这门本事叫做§b开物术§f。咱们妖族天生与自然亲近，和这些天地精华凝成的资源打交道，那可是拿手好戏。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f不过你可得记住了——万物有灵，这些资源也是活的呢。你靠近的时候仔细看：要是它身上冒出§a绿莹莹的光§f，那就是它心情正好，处于§a“富饶”§f状态，这时候采集，事半功倍。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f等采过之后，它的光就会变成§e暗黄色§f，这就叫§e“枯竭”§f了。这时候你要是硬来，不仅费时费力，说不定还什么都捞不着，伤己又伤物，可不划算。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f要是你实在缺资源，枯竭了也照采不误——那它就会蒙上一层§7灰扑扑§f的光，像是被抽干了力气，开始“休养”了。没关系，让它好好睡一觉，等它恢复精神，绿光还会再亮起来的。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f哦对了，施展§b开物术§f可是要消耗§b精力§f的。不过不必担心，每过一阵子精力就会自行恢复。开物术每提升一级，精力上限都会提高，§b开采速度也会增加10%§f，最多增加§b50%§f。",
        "§e[${StoryNpcs.YAO_XIAOMAN.displayName}§e] §f好啦，说了这么多，快去试试吧！采上§e两颗启灵果§f，就回去找谷主。记住——用指尖去感受，别用脚爬，摔下来我可不接着你！"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c主手持有 §b小花的推荐函 §c交给谷主")
            1 -> listOf("§c寻找古树旁的 §e小蔓")
            2 -> listOf("§a已询问小蔓", "§c采集启灵果并交给谷主 (0/2)")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 99
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.getPlayerData(player)?.let { data ->
            plugin.playerManager.giveExp(player, 60)
            plugin.databaseManager.savePlayerAsync(data)
        }

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.YAO_GUZHU.id) {
            return handleGuzhuDialogue(player, currentProgress)
        }

        if (npcId == StoryNpcs.YAO_XIAOMAN.id) {
            return handleXiaomanDialogue(player, currentProgress)
        }

        return false
    }

    private fun handleGuzhuDialogue(player: Player, currentProgress: Int): Boolean {
        when (currentProgress) {
            0 -> {
                val index = talkProgress.getOrDefault(player.uniqueId, 0)
                if (index < scriptGuzhuPart1.size) {
                    say(player, scriptGuzhuPart1[index])
                    talkProgress[player.uniqueId] = index + 1
                    return true
                }

                if (index == scriptGuzhuPart1.size) {
                    val handItem = player.inventory.itemInMainHand
                    if (isXiaohuaLetter(handItem)) {
                        handItem.amount -= 1
                        player.sendMessage("§e[系统] 你将小花的推荐函交给了谷主。")
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
                        talkProgress[player.uniqueId] = index + 1
                        player.sendMessage("§a[任务] -> 谷主正在阅读推荐函，请左键继续对话。")
                    } else {
                        player.sendMessage("§c[任务] -> 请将【小花的推荐函】拿在主手，然后左键谷主。")
                    }
                    return true
                }

                val part2Index = index - (scriptGuzhuPart1.size + 1)
                if (part2Index in scriptGuzhuPart2.indices) {
                    say(player, scriptGuzhuPart2[part2Index])
                    talkProgress[player.uniqueId] = index + 1

                    if (part2Index >= scriptGuzhuPart2.size - 1) {
                        giveBaozi(player)
                        player.sendMessage("§a[任务] -> 对话结束，请去寻找小蔓。")
                        plugin.questManager.updateProgress(player, id, 1)
                        talkProgress.remove(player.uniqueId)
                    }
                    return true
                }
            }

            1 -> {
                player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f小蔓就在古树旁守着藤蔓，去寻她帮忙吧。")
                return true
            }

            2 -> {
                if (countResource(player, "qilingguo") >= 2) {
                    removeResource(player, "qilingguo", 2)
                    player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f好，好！两颗启灵果都带回来了。你这孩子，果然有灵性。")
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                } else {
                    player.sendMessage("§e[${StoryNpcs.YAO_GUZHU.displayName}§e] §f启灵果还不够，老夫要的是两颗§e启灵果§f。")
                    player.sendMessage("§7(提示: 需要提交 2个 resource_id 为 qilingguo 的启灵果)")
                }
                return true
            }
        }

        return false
    }

    private fun handleXiaomanDialogue(player: Player, currentProgress: Int): Boolean {
        if (currentProgress != 1) return false

        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index < scriptXiaoman.size) {
            say(player, scriptXiaoman[index])
            talkProgress[player.uniqueId] = index + 1

            if (index >= scriptXiaoman.size - 1) {
                player.sendMessage("§a[任务] -> 对话结束，请去采集启灵果。")
                plugin.questManager.updateProgress(player, id, 2)
                talkProgress.remove(player.uniqueId)
            }
        }

        return true
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private fun giveBaozi(player: Player) {
        val baozi = ItemStack(Material.BREAD, 10)
        val meta = baozi.itemMeta
        meta?.setDisplayName("§f包子")
        baozi.itemMeta = meta

        val leftovers = player.inventory.addItem(baozi)
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        player.sendMessage("§e[系统] 获得 §f包子 x10")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun isXiaohuaLetter(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.PAPER) return false
        return item.itemMeta?.displayName?.contains("小花的推荐函") == true
    }

    private fun countResource(player: Player, resourceId: String): Int {
        return player.inventory.contents
            .filter { getResourceId(it) == resourceId }
            .sumOf { it?.amount ?: 0 }
    }

    private fun removeResource(player: Player, resourceId: String, amount: Int) {
        var remaining = amount
        for (item in player.inventory.contents) {
            if (remaining <= 0) break
            if (getResourceId(item) != resourceId) continue

            val removed = minOf(remaining, item!!.amount)
            item.amount -= removed
            remaining -= removed
        }
    }

    private fun getResourceId(item: ItemStack?): String? {
        val meta = item?.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }
}
