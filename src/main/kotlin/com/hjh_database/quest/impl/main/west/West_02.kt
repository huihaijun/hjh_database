package com.hjh_database.quest.impl.main.west

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
import java.util.HashMap
import java.util.UUID

class West_02 : QuestBase("main_west_2", "[主线]迷阵中的虎子", QuestType.MAIN, 16) {

    override val raceLimit = null

    override val description = listOf(
        "§7你带着老人准备的干粮进入虎爪山脉。",
        "§7若能找到名叫 §e虎子§7 的孩子，",
        "§7便将这份干粮交给他吧。"
    )

    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()

    private val scriptHuzi = listOf(
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f你好，请问你是…？",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f你，你怎么知道我的名字的？好久没有人这么叫我了…",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f什么！？你是说，§e爹爹§f他直到现在还在找我吗？那个笨老头，为什么要这么固执呢…",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f娘过世了！？孩儿不孝，没能见上最后一面…",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f这件肚兜，是娘给我缝的最后一件衣裳。我一直带在身边，想了的时候就拿出来看看。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f我可以求你一件事吗？替我把这个§e肚兜§f带给我爹。告诉他，虎子在这儿过得很好，让他别再等了。",
        "§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f至于怎么走出这迷阵……你帮我把肚兜送到，回来我就告诉你出口在哪。放心，我不会让你白跑一趟的。"
    )

    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c主手持有 §e为虎子准备的干粮 §c左键寻找 §e虎子")
            else -> listOf("§a任务已完成")
        }
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.GULINGLINGDEXIAONANHAI.id || currentProgress != 0) return false

        if (talkProgress.getOrDefault(player.uniqueId, 0) == 0 && !consumeFood(player)) {
            player.sendMessage("§e[${StoryNpcs.GULINGLINGDEXIAONANHAI.displayName}§e] §f你是迷路了吗？这里可不好出去。")
            player.sendMessage("§c[提示] 请主手持有[为虎子准备的干粮]，再左键与虎子对话。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        playDialogue(player, scriptHuzi) {
            giveDudouAndFinish(player)
        }
        return true
    }

    private fun consumeFood(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (getResourceId(item) != "weihuzizhunbeideganliang") return false

        item.amount -= 1
        return true
    }

    private fun giveDudouAndFinish(player: Player) {
        val rm = Hjh_database.instance.resourceManager
        val dudou = rm.getItem("huzidedudou") ?: ItemStack(Material.NETHER_WART).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e虎子的肚兜(配置缺失)")
                lore = listOf("§7缺少 huzidedudou 配置", "§7请联系管理员")
            }
        }

        dudou.amount = 1
        val leftovers = player.inventory.addItem(dudou)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，虎子的肚兜已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        } else {
            player.sendMessage("§e[系统] 获得 ${dudou.itemMeta?.displayName}")
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

    private fun getResourceId(item: ItemStack): String? {
        if (!item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +100")
        player.sendMessage("  §e[奖励] §f虎子的肚兜 x1")
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
