package com.hjh_database.quest.impl.main.west

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.HashMap
import java.util.UUID

class West_03 : QuestBase("main_west_3", "[主线]父子音信", QuestType.MAIN, 17) {

    override val raceLimit = null

    override val description = listOf(
        "§7你从虎子那里拿到了他的肚兜。",
        "§7回到虎爪山外，",
        "§7将虎子的消息告诉 §e伤心的老爷爷§7。"
    )

    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()

    private val scriptGrandpa = listOf(
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f这是！？§e虎子的肚兜§f！你是在哪里发现的？",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f…你是说，他没事吗？太好了…平安就好，平安就好。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f这孩子从小就比别的娃娃懂事，两岁能吟诗，三岁能写字。我和他娘一直知道，他不是寻常人家的孩子。在一起那几年，他总偷偷帮我们——往包子里加稀罕的香料，趁我们不在把破衣裳和锅碗修得跟新的一样。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f我们心里都明白，他不可能永远待在我们身边。只是没想到，这一天来得这么早。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f他不愿见我，我不怪他。他有他的路要走，我一个半截身子入了土的老头子，有什么好见的。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你若是再遇到他，替我转告他：§e爹爹我安心了，会好好照顾自己。让他也别担心家里，照顾好他自己，比什么都重要§f。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f还是非常感谢你，这有点盘缠，请你一定要收下，我老了，拿钱也没用，你这样的好心人一定比我更需要它。",
        "§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f去吧，路上小心！"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c主手持有 §e虎子的肚兜 §c左键寻找 §e伤心的老爷爷")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHANGXINDELAOYEYE.id || currentProgress != 0) return false

        if (talkProgress.getOrDefault(player.uniqueId, 0) == 0 && !consumeDudou(player)) {
            player.sendMessage("§e[${StoryNpcs.SHANGXINDELAOYEYE.displayName}§e] §f你又来了啊。若是有虎子的消息，还请一定告诉我。")
            player.sendMessage("§c[提示] 请主手持有[虎子的肚兜]，再左键与老爷爷对话。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        playDialogue(player, scriptGrandpa) {
            val data = Hjh_database.instance.playerManager.getPlayerData(player) ?: return@playDialogue
            Hjh_database.instance.questManager.completeQuest(player, data, this)
        }
        return true
    }

    private fun consumeDudou(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (getResourceId(item) != "huzidedudou") return false

        item.amount -= 1
        return true
    }

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index])
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            if (index == 6) {
                giveYuanbao(player)
            }

            talkProgress[player.uniqueId] = index + 1

            if (index == scripts.size - 1) {
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    private fun giveYuanbao(player: Player) {
        val yuanbao = Hjh_database.instance.resourceManager.getItem("jinyuanbao") ?: return
        yuanbao.amount = 1

        val leftovers = player.inventory.addItem(yuanbao)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，金元宝已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${yuanbao.itemMeta?.displayName} §fx1")
        }
    }

    private fun getResourceId(item: ItemStack): String? {
        if (!item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
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
