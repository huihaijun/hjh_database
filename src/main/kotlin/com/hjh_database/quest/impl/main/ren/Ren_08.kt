package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

class Ren_08 : QuestBase("main_ren_8", "[人族主线]青龙神庙", QuestType.MAIN, 8) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c前往龙鳞森林中心寻找 §e青龙分魂")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7你来到了龙鳞森林中心的青龙神庙。",
        "§7寻找青龙大人的分魂，",
        "§7询问关于结界减弱与祭坛的问题。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    private val scriptQingLong = listOf(
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f你好,这里是§2§o青龙神庙§f,我是§4§n青龙大人的分魂§f",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f我知道你的来意,我得很遗憾地告诉你,结界的力量确实在减弱中",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f而§4§n青龙大人§f不知道什么时候才会回来",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f再这样下去,结界最终将会完全消失,世界的秩序会崩溃的…",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f虽然很急,但是我没法离开神庙,也没办法做什么,因为我只是§4§n青龙大人§f的一缕意识罢了",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f不过所幸§2§o祭坛§f目前还剩下一些能量,足够给通过试炼者青龙的祝福",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f不过你得知道,§4§n青龙大人§f司掌智慧与灵识,它的试炼考验的是你对这片天地的了解",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f或许等得到了祝福,你能感应到§4§n青龙大人§f现在的位置,告诉他结界减弱的危机…",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f如果还是不行的话,可能就需要其他三§2§o圣兽祭坛§f的帮助了",
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f青龙试炼可以从§2§o神庙的二楼§f雕像进行,希望你能成功通过试炼",
        // === 新增对话与提示 ===
        "§e[${StoryNpcs.QINGLONGFENHUN.displayName}§e] §f等你通过青龙大人的试炼后，我会在试炼出口等你的消息……",
        "§c[注意]：§f请通关青龙试炼后，回到皇宫找§b李公公§f汇报情况"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId == StoryNpcs.QINGLONGFENHUN.id) {
            if (currentProgress == 0) {
                playDialogue(player, scriptQingLong) {
                    // 对话结束，直接完成任务
                    Hjh_database.instance.questManager.completeQuest(player, Hjh_database.instance.playerManager.getPlayerData(player)!!, this)
                }
                return true
            }
        }
        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            // 神明分魂的对话可以换个空灵一点的音效，这里暂时使用原版的交易音效保持一致
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
        player.sendMessage("  §e[奖励] §f经验 +320")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            Hjh_database.instance.playerManager.giveExp(player, 320)
            Hjh_database.instance.databaseManager.savePlayerAsync(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    override fun checkComplete(progress: Int): Boolean {
        return false
    }
}
