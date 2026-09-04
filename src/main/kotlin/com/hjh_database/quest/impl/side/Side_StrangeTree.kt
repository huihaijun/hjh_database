package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class Side_StrangeTree : QuestBase("side_strange_tree", "奇怪的树木", QuestType.SIDE, 1) {

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean = data.lv >= 20

    override val description = listOf(
        "§7临进皇城东墙的龙鳞森林边缘出现了一棵向地下生长的怪树。",
        "§7快去皇城东门外找龙须镇的居民了解下情况吧"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往龙鳞森林寻找 §e受惊的龙须镇居民")
        1 -> listOf("§c通过§e怪树树洞§c探索")
        2 -> listOf("§c前往栖霞镇寻找 §e镇长安民")
        3 -> listOf("§c前往栖霞镇坊市寻找 §e掌柜金大仁")
        4 -> listOf("§c前往 §e卜天居 §c与管理员采心交谈")
        5 -> listOf("§c前往灵田寻找 §e田主弦农")
        6 -> listOf("§c返回栖霞镇寻找 §e镇长安民")
        else -> listOf("§a任务进行中")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.id && currentProgress == 0 -> {
                playDialogue(player, startledResidentScript) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 前往怪树树洞。")
                }
                true
            }

            npcId == StoryNpcs.QIXIAZHENZHENZHANG.id && currentProgress == 2 -> {
                playDialogue(player, mayorScript) {
                    plugin.questManager.updateProgress(player, id, 3)
                    player.sendMessage("§a[任务] -> 前往坊市，与掌柜金大仁聊一聊。")
                }
                true
            }

            npcId == StoryNpcs.QIXIAZHENFANGSHILAOBAN.id && currentProgress == 3 -> {
                playDialogue(player, marketOwnerScript) {
                    plugin.questManager.updateProgress(player, id, 4)
                    player.sendMessage("§a[任务] -> 前往卜天居，与管理员采心交谈。")
                }
                true
            }

            npcId == StoryNpcs.BUTIANJUGUANLIYUAN.id && currentProgress == 4 -> {
                playDialogue(player, fortuneHouseManagerScript) {
                    plugin.questManager.updateProgress(player, id, 5)
                    player.sendMessage("§a[任务] -> 前往灵田，寻找田主弦农。")
                }
                true
            }

            npcId == StoryNpcs.LINGTIANZHANG.id && currentProgress == 5 -> {
                playDialogue(player, fieldOwnerScript) {
                    plugin.questManager.updateProgress(player, id, 6)
                    player.sendMessage("§a[任务] -> 返回栖霞镇，向镇长安民辞行。")
                }
                true
            }

            npcId == StoryNpcs.QIXIAZHENZHENZHANG.id && currentProgress == 6 -> {
                playDialogue(player, mayorFarewellScript) {
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                }
                true
            }

            else -> false
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +300")
        player.sendMessage("  §e[奖励] §f银票 x1")
        player.sendMessage("  §e[奖励] §f巨力丸（高级）x10")
        player.sendMessage("§8§m========================================")

        plugin.playerManager.giveExp(player, 300)
        giveResource(player, "yinpiao", 1, "银票")
        giveResource(player, "juliwan2", 10, "巨力丸（高级）")
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)
    }

    private fun giveResource(player: Player, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            player.sendMessage("§c[错误] 缺少任务奖励配置：$resourceId")
            return
        }

        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
            player.sendMessage("§e[提示] 背包已满，$displayName 已掉落在脚下。")
        }
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

    private val startledResidentScript = listOf(
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f欸呦喂，可吓死我了……小兄弟，你来得正好！",
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f你怕不是不知道，方才那事儿差点没把我魂给吓飞了！镇上的人都在传，说镇外靠着皇城那边，有棵长得§e贼奇怪的树§f。",
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f你瞧瞧这龙鳞森林，哪棵树不是拼命往上蹿，恨不得捅破天去。可就这棵怪树，不往上长，偏往§e地下钻§f，长着长着，还自己给自己盘出了个§e树洞§f来！你说邪门不邪门？",
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f还有这地上的玩意儿——你瞅瞅，这种§d暗紫色的木头§f，咱龙鳞森林的树哪有这样的？我看着新鲜，心想进去探探究竟……",
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f结果里头§c漆黑一片§f，啥也看不清，头顶好像还有些东西簌簌地往下飘。走着走着§c脑瓜子突然一晕§f，紧跟着耳边就响起了§e叮叮当当的怪声§f，跟阎王爷那儿敲锣打鼓似的！我以为自己这条小命要交代在里头了，吓得扭头就跑！",
        "§e[${StoryNpcs.SHOUJINGDELONGXUZHENJUMIN.displayName}§e] §f小兄弟，你年轻气盛，想必不怕这些吧？我可没怂恿你进去啊！§c出了事我可不负责§f，你自己掂量着办！"
    )

    private val mayorScript = listOf(
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f嗯？这树洞竟还能有人寻进来……小友，不知该如何称呼？",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f原来如此，你是顺着那树洞钻过来的。难怪——方才有人从那头一路跌进来，又喊又叫地跑了回去，把我们镇上的人也吓得不轻。你说的那人，八成就是他了。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f放轻松，此地名唤§e栖霞镇§f，不是什么阎罗殿。栖霞二字，说的是桃花烂漫之时，花瓣层层叠叠栖于枝头，远远望去宛若一片§d落于人间的霞光§f。你再往前走走便能瞧见那片桃花林了，来得正巧，眼下正是盛放的时节。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f看你这一身行头，也是从盘古大陆来的吧？如今外面的世道，我们也略知一二——圣兽异动，魔物横行，皇城四处征兵征役……乱得很。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f说来惭愧，我们这些人，便是当年不肯应那皇命逃出来的。那时皇城下令，但凡成丁，无论男女老幼，都要拉去充役——男的斩妖除魔，女的缝衣造饭，老弱则塞进丹药铺铁匠铺里做工，年纪轻些的便被分派到四面八方去探路，有些至今下落不明……",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f我们不愿过那样的日子，便一路躲到这世界尽头，搭起了这座小镇。当年§e第一任镇长§f在这里亲手种下了第一株桃树，后来年复一年，竟长成了这一大片桃花林。那天夕阳西沉，余晖穿透层层花瓣落在他肩上，他看了许久，说这般光景，便叫§e栖霞§f罢。名字就这么定下了。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f你既然来了，有一桩事盼你能应允——§e替我们守一守这地方§f，莫要对外宣扬。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f当初有位仙家途经此地，用奇术让皇城东门外的一棵老树§e逆向生长§f，盘根错节地拱出一个树洞来，便是你钻过来的那条路。这法子只有我们自己人知道底细，外人见了只当是妖怪洞穴，自然不敢靠近。你之前那位，口口声声说什么飘来飘去的白影——那不过是§e桃花的瓣儿§f，风一吹便簌簌往下落。那些暗紫色的§e桃花木§f，则是我们留给迷路人的记号，跟着走，便能找到这里。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f总而言之，我们不过是一群想安稳度日的普通百姓，不容易。你若愿意替我们守着这份宁静，镇上随意走走看看便是。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f沿着这条路往前，有一处§e坊市§f，找掌柜§e金大仁§f聊一聊，看看有没有什么合你心意的东西。来者是客，不必拘束。"
    )

    private val marketOwnerScript = listOf(
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f哎呀，陌生的面孔！看来那树洞没把你唬住啊——前些日子那位可是连滚带爬跑出去的，叫嚷声把整条街都惊动了，反倒是我们被他吓得不轻。",
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f既然是镇长点头让你进来的，那便是客了。欢迎来到§e栖霞镇§f，随便看，随便逛。",
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f我们这儿跟外头不一样，买卖不走寻常路。瞧见我这摊前那面§e旗子§f没有？右手碰一下，摊位的界面便出来了，简单得很。",
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f你在这儿瞧见的东西，全是镇上各家各户寄卖的。你若有闲置的物件想出手，也一样可以挂上来——§e买什么卖什么，全由你们自己拿主意§f。是不是比皇城那些讨价还价的铺子自在多了？",
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f结账就更是省心，直接从你钱庄的账户里扣，一枚铜板也不用掏出来数。喏，你旁边那位便是从皇城过来的§e钱庄掌柜§f，要存要取，找他就成。",
        "§e[${StoryNpcs.QIXIAZHENFANGSHILAOBAN.displayName}§e] §f还想打听些什么，就去我身后那座§e卜天阁§f看看吧。不过丑话说在前头——门口那位管事儿的脾性有些§c古怪§f，说话别太唐突，自己留点神。"
    )

    private val fortuneHouseManagerScript = listOf(
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f什么人！站住。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f我盯你许久了，打从你踏进这条巷子我就瞧着你眼生。镇上的人我都认得，你这一身打扮——是从§e盘古大陆§f来的吧？说，怎么进来的？",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f（一番解释后……）",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f原来如此，镇长已经见过你了。方才多有冒犯，还望见谅。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f你问我怎么一眼瞧出你不是本镇的人？呵，你还没发现吗——我们镇上的人，头顶的名字都是§b蓝色的§f，唯独你们这样的来客，顶的是§e黄色§f；那些别地来的商贩，看那钱庄掌柜，顶着§a绿色§f。这不明摆着的事嘛。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f说笑归说笑，方才的失礼并非无端。近来外面的世道愈发不太平，我守在这卜天居的门口，凡事都得留个心眼。既然是镇长点头的客人，那便是自己人，还望你多包涵。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f此地名为§e卜天居§f——卜算天意，事事随心。每日你都可以来这儿求上一签，费用不高，§6一枚元宝§f即可。至于求到什么，全看天意，莫要强求。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f拿到签文之后，投入我身旁这口§e瓦罐§f里，便会升起一缕烟云，告知你今日本运：\n§d上上签§f——诸事顺遂，吉星高照，今日运势大吉\n§a上签§f——顺水行舟，小有收获，宜稳步前行\n§e中签§f——平平淡淡，无惊无喜，守常即可\n§7下签§f——稍有波折，但无大碍，凡事三思\n§c下下签§f——凶星照临，诸事不宜，今日多加小心",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f签运不同，带来的造化也不一样。若得了下下签也别怨天尤人——这一切，都是命运的安排。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f若有兴致，现在便可求上一签。若想先转转，不妨去下面田里找§e弦农哥§f聊聊。他最近不知从哪弄来的法子，在折腾一块什么§e灵田§f，神神秘秘的。",
        "§e[${StoryNpcs.BUTIANJUGUANLIYUAN.displayName}§e] §f从我这儿左手边绕下去，瞧见田边那个小棚子便是了。去吧。"
    )

    private val fieldOwnerScript = listOf(
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f小友，这边这边！来瞧瞧我这块田——怎么样，在皇城可没见过这般景象吧？",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f啊，你说我怎么知道你要来？嗐，我哪有那未卜先知的能耐，是镇长派人来递的信儿。特殊时期，大家彼此都照应着些，你别往心里去。",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f说回正事。我最近鼓捣出了个新鲜东西，能让耕田也沾染上灵气，我管它叫§e灵田§f。这灵田最神奇的地方在于——它§e认主§f。一块田，好几个人同时种，互不干扰，向不同的人展示各自作物的状态。你种你的，他种他的，谁也不用排队抢地盘。",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f你只要右手轻轻碰一下灵田，就能瞧见每株作物的§e成熟时间§f，一目了然。若想看得更清楚，敲一下田边那口§e钟§f，所有灵田的状态便全部显现出来，哪块熟了、哪块还得等等，心里有个数。",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f不过丑话说在前头——眼下还在§e试验阶段§f，灵田总共只有§e九块§f。而且你得先有§e灵田契§f，才能和灵田绑定。没有契，任你喊破嗓子它也不理你！",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f好了，剩下的你自己慢慢摸索便是。等有空了，就来灵田这边多转转——就在最下面那块地，用§e栅栏围着的§f，好找。",
        "§e[${StoryNpcs.LINGTIANZHANG.displayName}§e] §f要是没什么别的问题……方才镇长派人来传话，说让你回去找他一趟。你先去忙，随时再来。"
    )

    private val mayorFarewellScript = listOf(
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f逛了一圈，感觉如何？栖霞虽小，倒也还算清静吧。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f你这般年纪，正是心怀宏图的时候，外面的天地广阔，有更重要的事等着你去做。窝在我们这偏安一隅的小镇子里，反倒委屈了你。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f既然如此，我也不强留。只盼你替我们守着这桩秘密，莫向外人提起此处的所在。日后这栖霞镇便认了你这朋友——什么时候累了乏了，随时回来歇歇脚，桃花年年都开，总有你一杯茶。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f逢年过节我们也会办些热闹事，到时候兴许还会修一方鱼塘，闲来垂钓，倒也是件美事。你若有缘赶上了，便一起热闹热闹。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f好了，这些是镇上攒的一些物资，我们自给自足，用得也不多。你我有缘一场，便赠与你了。",
        "§e[${StoryNpcs.QIXIAZHENZHENZHANG.displayName}§e] §f往后的路，§e一路顺风§f。"
    )
}
