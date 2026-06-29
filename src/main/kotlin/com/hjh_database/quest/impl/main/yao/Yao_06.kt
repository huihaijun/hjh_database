package com.hjh_database.quest.impl.main.yao

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Yao_06 : QuestBase("main_yao_6", "[妖族主线]镇妖塔探秘", QuestType.MAIN, 6) {

    override val raceLimit = 4
    override val description = listOf(
        "§7谷主托付你前往镇妖塔打探大长老的下落。",
        "§7先去皇城西门外，寻找卧底探子 §e华夭§7。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptHuayao = listOf(
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f你没受伤吧？……看起来挺好的，不错不错。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f我两个时辰前就接到谷主的消息，说有只小妖会来找我。你这么晚才到，我还以为你出了什么事情。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f刚出谷感觉一定很新鲜，但是要小心——§c人类狡猾奸诈§f，§c仙族阴险诡谲§f，千万不能被他们抓到，否则就会被关进这座§4镇妖塔§f内。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f所以，你来是为了大长老的消息吧？",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f其实，我也不知道大长老究竟有没有被抓到塔内。我现在只是个守门的小卒而已，连查询登记在案的妖族名单都做不到。而且只有一个人，也没办法趁当职的时候偷偷进去看。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f希望他没被抓进来……",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f事实上，塔的正后方有一个直通塔顶的入口。镇妖塔会把越强的妖族关在越高的地方，如果大长老也被抓进来了，他肯定是被关在塔顶的。",
        "§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f等等我值班的时候，你就从后面溜进去。看看大长老到底有没有被关在上面，确定之后再回来跟我说。"
    )

    private val scriptElder = listOf(
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f小朋友，你好啊。什么？救我出去？别想了别想了。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f这里的§c结界和守卫§f，不是你眼下能对付的。真要救我，先想办法提升自己的实力再说。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f不过你来得正好，有件事倒是可以替我去办。这几天我感应到，§4神族四兽§f布下的结界减弱了许多。虽然不知缘由，但长久以来束缚我族的力量，似乎快要消散了。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f你替我去四方的§e圣兽祭坛§f走一趟，确认一下结界究竟为何而衰。先去§a东方的青龙祭坛§f，再往§c南方的朱雀祭坛§f，接着是§e西方的白虎祭坛§f，最后到§b北方的玄武祭坛§f。这四处历练一个比一个凶险，务必按此顺序逐一前往，切莫贪快乱了步子。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f把消息带回来告诉§e华夭§f那丫头，跟她说我在这儿好得很。虽然称不上舒坦，但就凭这破塔想弄死我，还早了几百年。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f对了，有件事得跟你说清楚。这座§4镇妖塔§f里关着的，不光有我们的族人，还有不少人仙二族从我们妖族手中§e掠夺来的宝贝§f——那些东西本该属于我族，日后你们免不了要来这里征伐，夺回属于我们自己的东西。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f塔中那些四处游荡的族人你也瞧见了。他们被§c阵法磨去了神智§f，只剩躯壳供人驱使。你若在征伐中遇上，不必手软——与其让他们行尸走肉般被奴役到魂飞魄散，你出手破阵，反倒是在替他们解脱。",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f说不定你一路打上来，最后还能撞见“我”。哈哈，别慌——那不过是老夫留在此处的§e一道幻象§f，并非本体。你就尽管使出浑身解数，狠狠揍那幻象一顿，正好让老夫看看，咱们妖族后生的拳头够不够硬！",
        "§e[${StoryNpcs.YAO_DAZHANGLAO.displayName}§e] §f快回去吧，§c万事小心§f。出去之后先去找§e华夭§f，把这里的情况告诉她。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往 §4镇妖塔 §c寻找 §e华夭")
            1 -> listOf("§a已与华夭接头", "§c从塔后入口潜入塔顶，寻找 §e妖族大长老")
            else -> listOf("§a任务已完成")
        }
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 2
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")

        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 10)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        if (npcId == StoryNpcs.YAO_HUAYAO.id) {
            when (currentProgress) {
                0 -> {
                    playDialogue(player, scriptHuayao) {
                        plugin.questManager.updateProgress(player, id, 1)
                        player.sendMessage("§a[任务] -> 请从镇妖塔后方入口潜入塔顶，寻找妖族大长老。")
                    }
                    return true
                }

                1 -> {
                    player.sendMessage("§e[${StoryNpcs.YAO_HUAYAO.displayName}§e] §f塔后方有直通塔顶的入口，趁我值班，快去看看大长老在不在上面。")
                    return true
                }
            }
        }

        if (npcId == StoryNpcs.YAO_DAZHANGLAO.id) {
            if (currentProgress != 1) return false

            playDialogue(player, scriptElder) {
                plugin.questManager.updateProgress(player, id, 2)
                player.sendMessage("§a[任务] -> 已确认大长老的下落。")
            }
            return true
        }

        return false
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index >= scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }
}
