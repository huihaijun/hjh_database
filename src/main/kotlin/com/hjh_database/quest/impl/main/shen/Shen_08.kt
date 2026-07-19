package com.hjh_database.quest.impl.main.shen

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class Shen_08 : QuestBase("main_shen_8", "[神族主线]圣兽祝福", QuestType.MAIN, 8) {

    override val raceLimit = 0

    override val description = listOf(
        "§7圣山拒绝了你的进入。",
        "§7返回神域，向长老请教圣兽祝福的线索。"
    )

    private val plugin get() = Hjh_database.instance
    private val talkProgress = HashMap<UUID, Int>()

    private val elderScript = listOf(
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f你说，你无法进入圣山？",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f……这便怪了。圣山理应一直在我族掌控之下。若连身为神族的你都无法进入，便意味着圣山与§4§n圣兽§f可能已脱离了我们。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f这样，你去各地搜集§e圣兽祝福§f。有了祝福之力，应当就能进入圣山一探究竟。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f祝福之力分列四方，务必按顺序逐一前往——先去§a东边森林的青龙祭坛§f，再往§c南边沙漠的朱雀祭坛§f，然后是§e西边山中的白虎祭坛§f，最后到§b北边湖中的玄武祭坛§f。一处比一处凶险，莫乱了次序。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f你此行表现尚可。这枚§6神族证明§f，现在正式授予你。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f我族乃世间秩序的维护者，其余种族为感念此恩，每隔一段时日便会前来进贡。贡品集中放在§e皇宫西侧§f那座§e冒黄光的楼§f前，每两时辰一次。届时那边的负责人自会传音告知你。你虽是新入族的，理应也有一份。但记住——§c半个时辰内§f便会被分光，晚了便没有留给你的份。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f此外，凭此证明可与一些商贩建立§e神识§f，无需当面交易便可直接完成买卖。不过你目前火候尚浅，最多建立§e三道神识§f，日后多加历练便是。",
        "§e[${StoryNpcs.SHEN_ZHANGLAO.displayName}§e] §f我已安排人族的皇接待你，去找他，让他给你安排一门§e职业§f。日后我很忙，修行之事，便看你自己的了。"
    )

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c返回神域，与 §e长老 §c对话")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHEN_ZHANGLAO.id || currentProgress != 0) return false

        playDialogue(player, elderScript) {
            giveItemsAndFinish(player)
        }
        return true
    }

    private fun giveItemsAndFinish(player: Player) {
        val proof = plugin.resourceManager.getItem("shen_zm_begin") ?: ItemStack(Material.LIGHT_BLUE_DYE).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§b神族证明") }
        }
        val yuanbao = plugin.resourceManager.getItem("jinyuanbao") ?: ItemStack(Material.SUNFLOWER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§6金元宝") }
        }
        val baozi = ItemStack(Material.BREAD, 15).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
        }

        proof.amount = 1
        yuanbao.amount = 1
        val leftovers = player.inventory.addItem(proof, yuanbao, baozi)
        leftovers.values.forEach { player.world.dropItem(player.location, it) }
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分物品已掉落在脚下！")
        }

        player.sendMessage("§e[系统] 获得 ${proof.itemMeta?.displayName ?: "神族证明"} x1")
        player.sendMessage("§e[系统] 获得 ${yuanbao.itemMeta?.displayName ?: "金元宝"} x1")
        player.sendMessage("§e[系统] 获得 §f包子 x15")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)

        plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 10)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("  §e[提示] §f前往人族皇城，寻找人皇-轩辕氏安排职业。")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
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
}
