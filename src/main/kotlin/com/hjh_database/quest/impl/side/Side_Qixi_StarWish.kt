package com.hjh_database.quest.impl.side

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.dungeon.qixi.QixiAccessPolicy
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class Side_Qixi_StarWish : QuestBase(ID, "[限时支线]鹊桥星愿", QuestType.SIDE, 1), Listener {

    private data class DialogueState(val key: String, val nextIndex: Int)

    private val plugin get() = Hjh_database.instance
    private val dialogueStates = HashMap<UUID, DialogueState>()
    private val eventConfig = loadEventConfig()
    private val minimumLevel = eventConfig.getInt("event-quest.min-level", DEFAULT_MINIMUM_LEVEL).coerceAtLeast(1)
    private val lastAcceptDate = parseLastAcceptDate()
    private val eventZone = parseEventZone()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override val raceLimit: Int? = null

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return QixiAccessPolicy.isAllowed(plugin, player) &&
            data.lv >= minimumLevel &&
            !LocalDate.now(eventZone).isAfter(lastAcceptDate)
    }

    override val description = listOf(
        "§7七夕将近，司天监发现天河星轨与鹊桥星光出现异常。",
        "§7前往皇城寻找监星官沈观，调查这场不同寻常的星象。",
        "§8限时接取：${lastAcceptDate}（含当日）前，等级达到${minimumLevel}级"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c前往皇城寻找 §e监星官-沈观")
        1 -> listOf("§c前往皇城东门外，乘鹊灵前往 §2§o鹊影桥")
        2 -> listOf("§c寻找鹊影桥的 §e守桥人-柳安")
        3 -> listOf("§c进入秘境§d【鹊桥星愿】§c一探究竟")
        4 -> listOf("§c返回鹊影桥，向 §e守桥人-柳安 §c报平安")
        else -> listOf("§a鹊桥星轨已经恢复平静")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.SHENGUAN.id && currentProgress == 0 -> {
                playDialogue(player, "shenguan_intro", shenGuanScript) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 前往皇城东门外，乘鹊灵前往鹊影桥。")
                }
                true
            }

            npcId == StoryNpcs.LIUAN.id && currentProgress == 2 -> {
                playDialogue(player, "liuan_intro", liuAnIntroScript) {
                    plugin.questManager.updateProgress(player, id, 3)
                    player.sendMessage("§d[任务] -> 进入秘境【鹊桥星愿】一探究竟。")
                }
                true
            }

            npcId == StoryNpcs.LIUAN.id && currentProgress == 4 -> {
                playDialogue(player, "liuan_return", liuAnReturnScript) {
                    val data = plugin.playerManager.getPlayerData(player) ?: return@playDialogue
                    plugin.questManager.completeQuest(player, data, this)
                }
                true
            }

            else -> false
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        dialogueStates.remove(event.player.uniqueId)
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 5000)
        giveResource(player, "yinpiao", 8, "银票")
        giveResource(player, "xingsha", 4, "星砂")
        giveResource(player, "lingyujian", 1, "灵玉简")
        giveResource(player, "fengmuhuichun2", 16, "逢木回春（高级）")
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +5000")
        player.sendMessage("  §e[奖励] §f银票 x8")
        player.sendMessage("  §e[奖励] §f星砂 x4")
        player.sendMessage("  §e[奖励] §f灵玉简 x1")
        player.sendMessage("  §e[奖励] §f逢木回春（高级）x16")
        player.sendMessage("  §e[奖励] §f奇遇称号：§d织星图")
        player.sendMessage("§8§m========================================")
        dialogueStates.remove(player.uniqueId)
    }

    private fun giveResource(player: Player, resourceId: String, amount: Int, displayName: String) {
        val item = plugin.resourceManager.getItem(resourceId)
        if (item == null) {
            player.sendMessage("§c[错误] 缺少任务奖励配置：$resourceId")
            plugin.logger.warning("七夕限时支线无法发放 $displayName：缺少资源 $resourceId")
            return
        }
        item.amount = amount
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
            player.sendMessage("§e[提示] 背包已满，$displayName 已掉落在脚下。")
        }
    }

    private fun playDialogue(player: Player, key: String, script: List<String>, onFinish: () -> Unit) {
        val state = dialogueStates[player.uniqueId]
        val index = if (state?.key == key) state.nextIndex else 0
        if (index !in script.indices) {
            dialogueStates.remove(player.uniqueId)
            return
        }

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            dialogueStates.remove(player.uniqueId)
            onFinish()
        } else {
            dialogueStates[player.uniqueId] = DialogueState(key, index + 1)
        }
    }

    private fun loadEventConfig(): YamlConfiguration {
        val file = File(plugin.dataFolder, "dungeon/qixi.yml")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            plugin.saveResource("dungeon/qixi.yml", false)
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun parseLastAcceptDate(): LocalDate {
        val raw = eventConfig.getString("event-quest.last-accept-date", DEFAULT_LAST_ACCEPT_DATE)
            ?: DEFAULT_LAST_ACCEPT_DATE
        return runCatching { LocalDate.parse(raw) }.getOrElse {
            plugin.logger.warning("dungeon/qixi.yml 的 event-quest.last-accept-date 无效：$raw，已使用默认日期 $DEFAULT_LAST_ACCEPT_DATE")
            LocalDate.parse(DEFAULT_LAST_ACCEPT_DATE)
        }
    }

    private fun parseEventZone(): ZoneId {
        val raw = eventConfig.getString("event-quest.time-zone", DEFAULT_TIME_ZONE) ?: DEFAULT_TIME_ZONE
        return runCatching { ZoneId.of(raw) }.getOrElse {
            plugin.logger.warning("dungeon/qixi.yml 的 event-quest.time-zone 无效：$raw，已使用 $DEFAULT_TIME_ZONE")
            ZoneId.of(DEFAULT_TIME_ZONE)
        }
    }

    private val shenGuanScript = listOf(
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f你也是来看七夕星象的？",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f我是皇城司天监的星官，沈观。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f七夕将近，按往年的星图，天河两岸的星光应该已经开始向中央汇聚了。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f可从昨夜开始，星轨便一直有些不对劲。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f几颗星的位置来回偏移，连代表鹊桥的星光也时明时暗。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f皇城上空有一座§2§o鹊影桥§f，是以前的人们为了庆祝七夕，仿照传说中的鹊桥建造的。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f每年到了这个时候，桥上的鹊灵总会比平日活跃许多，或许它们能察觉到什么。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f你若愿意的话，就替我上去看看吧。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f正好有皇城东门外鹊灵要返回§2§o鹊影桥§f，它可以载你一程。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f到了那里，去找守桥人§4§n柳安§f。",
        "§e[${StoryNpcs.SHENGUAN.displayName}§e] §f他看守那座桥很多年了，应该比我更清楚今年到底哪里不对。"
    )

    private val liuAnIntroScript = listOf(
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f你是从皇城来的？",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f……原来司天监那边也发现异常了。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f我是柳安，负责照看这座§2§o鹊影桥§f。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f这座桥已经建成很多年了，每逢七夕，桥上的星灯都会随着真正的鹊桥一同亮起。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f偶尔还会有天河的鹊灵飞到这里，久而久之，我也和§b§n牛郎§f、§b§n织女§f通过几次消息。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f他们每年都会从天河两岸出发，在鹊桥上完成一件很重要的事情。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f可今年直到现在，我都没有收到他们的消息。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f而且这几日回来的鹊灵也十分不安，有几只羽毛上还沾着奇怪的暗色星砂。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f看来真正的鹊桥上，恐怕出了什么事情。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f这座桥虽然只是凡人所建，却与真正的鹊桥相应了许多年。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f中央亭子里有一座小法阵，每逢七夕都能借来些许天河的星光。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f我可以让鹊灵替你引动法阵，试着送你前往真正的鹊桥。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f如果你在那里见到了牛郎和织女，就帮我看看究竟发生了什么吧。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f对了，若你从天河里带回了那种§9星砂§f，也可以拿来给我看看。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f这些年我收了不少和鹊桥有关的东西，若星砂确实有用，我可以拿些东西与你交换。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f准备好了，便到亭中的法阵上去。"
    )

    private val liuAnReturnScript = listOf(
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f你回来了，看来今晚的鹊桥总算平安了。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f这些§9星砂§f和小礼物你收下吧，就当是替大家谢谢你。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f以后若还从秘境里带回星砂，也可以拿来找我交换些东西。",
        "§e[${StoryNpcs.LIUAN.displayName}§e] §f七夕难得，有空就在桥上多看看今晚的星河吧。"
    )

    companion object {
        const val ID = "side_qixi_starwish"
        private const val DEFAULT_MINIMUM_LEVEL = 40
        private const val DEFAULT_LAST_ACCEPT_DATE = "2026-08-31"
        private const val DEFAULT_TIME_ZONE = "Asia/Shanghai"
    }
}
