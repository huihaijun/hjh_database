package com.hjh_database.quest.impl.main.xian

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

class Xian_02 : QuestBase("main_xian_2", "[仙族主线]开物初识", QuestType.MAIN, 2) {

    override val raceLimit = 1

    override val description = listOf(
        "§7带着小飞的引荐信前往盟主大殿。",
        "§7拜见§e仙族盟主§7，并学习仙族的开物术。"
    )

    private val plugin get() = Hjh_database.instance
    private val resourceKey by lazy { NamespacedKey(plugin, "resource_id") }
    private val talkProgress = HashMap<UUID, Int>()

    private val beforeLetterScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f啊，道友，新飞升的吧？欢迎来到仙界。我是此地的盟主之一。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f小飞的信？递来我看看。"
    )

    private val afterLetterScript = listOf(
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f小飞这孩子看人的眼光，向来不差。信中既这般夸你，想来不会有错。好，我族又添一位可造之才。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f这阵子下界颇不平静，我族已许久未添新人了。既入此门，便把这里当作家吧。来，这几个§e包子§f你先拿着，飞升损耗不小，垫垫肚子，缓一缓气息。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f……吃好了？那便说些正事。近来下界魔物躁动，如失心智般见人便袭。我原疑心是§4§n镇妖塔§f中那些妖族孽障逃逸作乱——这帮妖物所到之处民生凋敝，实在令人忧心。也罢，此事容后再说。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f说了这许多，倒有些口干。道友，可否替我将那茶杯取来？",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f且慢，不必亲自起身。我族有一门§e隔空取物§f的本事，唤作§b开物术§f。你初来乍到，正好一学。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f施术时须心神专一——目光聚于目标，右手遥遥一点，便能引动四方灵气，将物件轻轻“请”到手心。切忌分神，灵气中断，白费心神。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f万物有灵，山石草木皆然。取用之前先看分明：若见§a绿光盈盈§f，便是灵气充盈的§a“富饶”§f之态，此时取用最为便当。若光华转§e暗黄§f，便是灵脉将竭，采之费时费力，往往不得其果。若只余§7灰蒙蒙一片§f，那是灵脉休眠未醒，不可再取。待它休养完毕，绿光自会重临。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f此外，施展此术会损耗§b精力§f，精力随时而生。开物术每精进一层，精力上限与§b取物速度§f皆会提升。",
        "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f听明白了？那便小试牛刀——茶壶就在你身旁的§e桌案§f上，为我取来吧。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c主手持有 §b小飞的引荐信 §c拜见仙族盟主")
        1 -> listOf("§c使用开物术取得 §b盟主的茶杯§c，主手持有后交给盟主")
        else -> listOf("§a已将茶杯交给盟主")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.XIAN_XIANZUMENGZHU.id) return false

        return when (currentProgress) {
            0 -> {
                handleIntroduction(player)
                true
            }
            1 -> {
                handleTeaCupDelivery(player)
                true
            }
            else -> false
        }
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 60)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +60")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
    }

    private fun handleIntroduction(player: Player) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index in beforeLetterScript.indices) {
            say(player, beforeLetterScript[index])
            talkProgress[player.uniqueId] = index + 1
            return
        }

        if (index == LETTER_CHECK_INDEX) {
            if (!isHoldingXiaofeiLetter(player.inventory.itemInMainHand)) {
                player.sendMessage("§c[任务] -> 请将§b小飞的引荐信§c拿在主手，再与盟主对话。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }

            consumeMainHand(player)
            player.sendMessage("§7（你呈上推荐函。盟主展信细阅，微微颔首）")
            player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.9f)
            talkProgress[player.uniqueId] = AFTER_LETTER_OFFSET
            return
        }

        val scriptIndex = index - AFTER_LETTER_OFFSET
        if (scriptIndex !in afterLetterScript.indices) return

        say(player, afterLetterScript[scriptIndex])
        if (scriptIndex == BUN_LINE_INDEX) giveBuns(player)

        if (scriptIndex == afterLetterScript.lastIndex) {
            talkProgress.remove(player.uniqueId)
            plugin.questManager.updateProgress(player, id, 1)
            player.sendMessage("§a[任务] -> 使用开物术取得盟主的茶杯，主手持有后交给盟主。")
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    private fun handleTeaCupDelivery(player: Player) {
        val hand = player.inventory.itemInMainHand
        if (getResourceId(hand) != TEA_CUP_ID || hand.amount < 1) {
            player.sendMessage("§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f茶杯还在桌案上么？用开物术取来，拿在主手交给我吧。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        consumeMainHand(player)
        say(player, "§e[${StoryNpcs.XIAN_XIANZUMENGZHU.displayName}§e] §f好，好！出手稳当，灵气相随，分毫不差。一杯清茶，却让我瞧见了我族后辈该有的模样。")
        val data = plugin.playerManager.getPlayerData(player) ?: return
        plugin.questManager.completeQuest(player, data, this)
    }

    private fun giveBuns(player: Player) {
        val buns = ItemStack(Material.BREAD, BUN_AMOUNT).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }
        player.inventory.addItem(buns).values.forEach { overflow ->
            player.world.dropItem(player.location, overflow)
            player.sendMessage("§c[提示] 背包已满，包子已掉落在脚下。")
        }
        player.sendMessage("§e[系统] 获得 §f包子 x$BUN_AMOUNT")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    private fun isHoldingXiaofeiLetter(item: ItemStack): Boolean {
        return item.type == Material.PAPER && item.itemMeta?.displayName == "§b小飞的引荐信"
    }

    private fun getResourceId(item: ItemStack): String? {
        return item.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)
    }

    private fun consumeMainHand(player: Player) {
        val hand = player.inventory.itemInMainHand
        if (hand.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            hand.amount -= 1
        }
    }

    private fun say(player: Player, line: String) {
        player.sendMessage(line)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
    }

    private companion object {
        const val TEA_CUP_ID = "mengzhudechabei"
        const val BUN_AMOUNT = 10
        const val BUN_LINE_INDEX = 1
        const val LETTER_CHECK_INDEX = 2
        const val AFTER_LETTER_OFFSET = 3
    }
}
