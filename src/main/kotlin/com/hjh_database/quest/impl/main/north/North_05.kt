package com.hjh_database.quest.impl.main.north

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
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

class North_05 : QuestBase("main_north_5", "[主线]玄武的认可", QuestType.MAIN, 23) {

    override val raceLimit: Int? = null
    override val requiredCompletedQuestIds = setOf("main_north_4")

    override val description = listOf(
        "§7你已经通过玄武试炼，取得最后一份圣兽祝福。",
        "§7回到水族村庄，把好消息告诉祭司洛禾。"
    )

    private val plugin get() = Hjh_database.instance
    private val resourceKey = NamespacedKey(Hjh_database.instance, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()

    private val scriptBeforeSilver = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f这股气息……没错，正是§4§n玄武大人§f！",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f你果然是我们水族的有缘人。珊瑚重焕生机，玄武大人的庇护之力便不会断绝，我们这座村庄也算是保住了。太感谢了，这是村里的一点心意，请你务必收下！"
    )

    private val scriptBeforePills = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f除此之外，关于这片§b北方湖泊§f，还有些事你应当知晓。方才你在玄水湾与那些魔物缠斗时，可曾觉得手中的武器§e忽然不听使唤§f了？",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f那并非错觉。这些魔物常年栖于湖底，早已学会将§b湿气§f打入你的兵刃与双手之间。当你聚精会神想要发动武器中蕴藏的技能力量时，这股湿气便会趁机逸散出来，硬生生§c阻断你的招式§f。湿气越重，被阻的概率便越高。虽说被阻断后不会损耗灵力，但若被魔物团团围住却死活动不了招式，那滋味想必你也尝过了。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f所幸并非无解。有一种丹药名为§e清灵丹§f，不仅能驱散自身的异常状态，连这股缠人的湿气也能一并化解。村里就有§e炼丹房§f，你稍后可自行炼制，我也会给你备上一些。"
    )

    private val scriptClosing = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f此外，一些医师似乎掌握一种涤荡天地气息的医术，也能让这些湿气魂飞魄散。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f另外，玄水湾附近有些魔物抢走了我们供奉给玄武大人的§e祭品§f。你若能在征伐中夺回一些，拿来给我，必有更多报酬奉上。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f如今四圣兽的祝福已尽数汇聚于你一身，往后的路，怕是比这玄水湾的风浪更加艰险。我不过一介祭司，没有通晓天地之能，但从你身上，我看到了无限的潜力。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f长风破浪，一日千里。我们§e有缘再见§f。"
    )

    override fun canAccept(player: Player, data: PlayerData): Boolean {
        return super.canAccept(player, data) && hasPassedXuanwuTrial(data)
    }

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0, 1, 2 -> listOf("§c通过玄武试炼后，与水族祭司 §e洛禾 §c对话")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHUIZUJISI.id) return false

        val data = plugin.playerManager.getPlayerData(player) ?: return true
        if (!hasPassedXuanwuTrial(data)) {
            player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f玄武试炼还没有结束。通过试炼之后，再尽快回来找我吧。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return true
        }

        return when (currentProgress) {
            0 -> {
                playDialogue(player, scriptBeforeSilver) {
                    giveResource(player, SILVER_NOTE_ID, 1, Material.GOLD_NUGGET, "银票")
                    plugin.questManager.updateProgress(player, id, 1)
                }
                true
            }

            1 -> {
                playDialogue(player, scriptBeforePills) {
                    giveResource(player, QINGLING_DAN_ID, 10, Material.POTION, "清灵丹")
                    plugin.questManager.updateProgress(player, id, 2)
                }
                true
            }

            2 -> {
                playDialogue(player, scriptClosing) {
                    plugin.questManager.completeQuest(player, data, this)
                }
                true
            }

            else -> false
        }
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val key = dialogueKey(player, plugin.playerManager.getPlayerData(player)?.questProgress?.get(id) ?: 0)
        val index = talkProgress.getOrDefault(key, 0)
        if (index >= script.size) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(key)
            onFinish()
        } else {
            talkProgress[key] = index + 1
        }
    }

    private fun hasPassedXuanwuTrial(data: PlayerData): Boolean =
        (data.dungeonRecords[XUANWU_RECORD_ID]?.clears ?: 0) > 0

    private fun giveResource(
        player: Player,
        resourceId: String,
        amount: Int,
        fallbackMaterial: Material,
        displayName: String
    ) {
        val item = plugin.resourceManager.getItem(resourceId) ?: ItemStack(fallbackMaterial).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§f$displayName")
                lore = listOf("§c配置缺失，请联系管理员")
                persistentDataContainer.set(resourceKey, PersistentDataType.STRING, resourceId)
            }
        }
        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[获得物品] §f$displayName x$amount")
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§e[提示] 背包空间不足，部分物品已掉落在脚下。")
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1.1f)
    }

    private fun dialogueKey(player: Player, stage: Int): UUID =
        UUID.nameUUIDFromBytes("${player.uniqueId}:$stage".toByteArray(Charsets.UTF_8))

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 850)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +850")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        clearDialogueProgress(player)
    }

    private fun clearDialogueProgress(player: Player) {
        (0..2).forEach { stage -> talkProgress.remove(dialogueKey(player, stage)) }
    }

    private companion object {
        const val XUANWU_RECORD_ID = "xuanwu_test"
        const val SILVER_NOTE_ID = "yinpiao"
        const val QINGLING_DAN_ID = "qinglingdan"
    }
}
