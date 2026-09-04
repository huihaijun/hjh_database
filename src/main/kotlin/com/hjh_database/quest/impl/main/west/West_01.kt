package com.hjh_database.quest.impl.main.west

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.HashMap
import java.util.UUID

class West_01 : QuestBase("main_west_1", "[主线]虎爪旧事", QuestType.MAIN, 15) {

    override val raceLimit = null

    override val description = listOf(
        "§7你来到了西方区域的虎爪山脉。",
        "§7山脚下有位伤心的老人，",
        "§7似乎正在寻找什么人。"
    )

    private val talkProgress = HashMap<UUID, Int>()

    private val scriptGrandpa = listOf(
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你好啊旅行者，请问，你有在附近看到一个小孩子吗？",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f…没有啊…那也没关系，谢谢你啊。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你问我在找谁？唉…我在等我的儿子§e虎子§f。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f我原本和我老伴儿住在虎爪山里的小村落。村子不大，几十口人，靠着和来往的旅行者做些小买卖过日子。传说这虎爪山深处有个迷宫洞穴，里头藏了宝贝，这些年不少冒险者跑来寻宝，可没一个成功过。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f我家虎子，在八岁那年失踪了。村里人帮着找了很久，可怎么找都找不到他……我们一直没放弃，总想着哪天他会自己走回来。可我老伴儿去年走了，临了还在念叨虎子的名字。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f迷宫里头不知什么时候多了好些妖怪，村里人怕出事，只好搬离了祖祖辈辈住的地方。我现在寄住在皇城，但每天还是会走到这里来。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你若是路上碰见一个十来岁的孩子，帮我问一句——§e爹娘很想他，让他别怕，回家吧§f。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f不过，可这虎爪山不是寻常地方。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f看见洞口飘的那层§d若有若无的雾气§f没有？那是§c虎瘴§f——当年白虎大人离开后留下的煞气。人一沾上就跟背了块石头似的，越走越沉，护甲扛不住，身子骨也会被慢慢侵蚀。要是让瘴气积满了，五脏六腑都跟着受罪，止不住地掉血。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你要进去，千万多备些§e丹药§f。等你进到那个小镇，就能看见个商贩。进洞之前去找他一趟，别心疼那几个铜板，命比钱金贵。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f我这儿也有些自家做的§6干粮§f，你带上吧。虎子小时候最爱吃这个，每次他娘蒸好，他都等不及凉就往嘴里塞，烫得直跳脚。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往 §e虎爪山脉§c，寻找 §e伤心的老爷爷")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHANGXINDELAOYEYE.id || currentProgress != 0) return false

        playDialogue(player, scriptGrandpa) {
            giveFoodAndFinish(player)
        }
        return true
    }

    private fun giveFoodAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val food = rm.getItem("weihuzizhunbeideganliang") ?: ItemStack(Material.BAKED_POTATO).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e为虎子准备的干粮(配置缺失)")
                lore = listOf("§7缺少 weihuzizhunbeideganliang 配置", "§7请联系管理员")
            }
        }

        food.amount = 1
        val leftovers = player.inventory.addItem(food)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，干粮已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${food.itemMeta?.displayName}")
        }

        val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return
        Hjh_database.instance.questManager.completeQuest(player, data, this)
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f为虎子准备的干粮 x1")
        player.sendMessage("§8§m========================================")

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
