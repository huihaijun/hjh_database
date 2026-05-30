package com.hjh_database.quest.core

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import java.util.concurrent.ConcurrentHashMap

class QuestManager(private val plugin: Hjh_database) : Listener {

    // 存储所有任务对象
    private val questMap = ConcurrentHashMap<String, QuestBase>()

    init {
        // 初始化注册表
        QuestRegistry.registerAll(this)
        // 注册监听器
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun register(quest: QuestBase) {
        questMap[quest.id] = quest
        // plugin.logger.info("已加载任务: ${quest.id}")
    }

    fun getQuest(id: String): QuestBase? = questMap[id]

    fun getAllQuests(): Collection<QuestBase> = questMap.values

    // === 核心：更新任务进度 ===
    fun updateProgress(player: Player, questId: String, newProgress: Int) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val quest = getQuest(questId) ?: return

        // 更新内存
        data.questProgress[questId] = newProgress

        // 检查是否完成
        if (quest.checkComplete(newProgress)) {
            completeQuest(player, data, quest)
        } else {
            //仅保存进度到数据库
            plugin.databaseManager.saveQuestData(player, questId, QuestStatus.IN_PROGRESS, newProgress)
        }
    }

    // === 核心：完成任务 ===
    fun completeQuest(player: Player, data: PlayerData, quest: QuestBase) {
        // 更新内存状态
        data.questStatuses[quest.id] = QuestStatus.COMPLETED
        data.questProgress[quest.id] = 9999 // 标记满

        // 同步更新已完成任务的缓存 Set
        data.completedQuests.add(quest.id)

        // 数据库保存
        plugin.databaseManager.saveQuestData(player, quest.id, QuestStatus.COMPLETED, 9999)

        // 发奖励
        quest.giveReward(player)

        player.sendMessage("§6§l[任务完成] §f${quest.title}")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        // 【可选】自动接取下一个主线 (如果是主线任务)
        // 这里可以写一段逻辑，根据 order 自动解锁下一个 +1 的任务
        if (quest.type == QuestType.MAIN) {
            tryUnlockNextMainQuest(player, data, quest)
        }
    }

    private fun tryUnlockNextMainQuest(player: Player, data: PlayerData, currentQuest: QuestBase) {
        // 寻找同一个种族、order = current.order + 1 的任务
        val nextQuest = questMap.values.find {
            it.type == QuestType.MAIN &&
                    it.raceLimit == currentQuest.raceLimit &&
                    it.order == currentQuest.order + 1
        }

        if (nextQuest != null) {
            acceptQuest(player, nextQuest.id)
            player.sendMessage("§a[系统] 新任务已解锁: ${nextQuest.title}")
        }
    }

    // 强制接取/解锁任务
    fun acceptQuest(player: Player, questId: String) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        data.questStatuses[questId] = QuestStatus.IN_PROGRESS
        data.questProgress[questId] = 0
        plugin.databaseManager.saveQuestData(player, questId, QuestStatus.IN_PROGRESS, 0)
    }

    fun refreshAvailableQuests(player: Player, type: QuestType): Int {
        val data = plugin.playerManager.getPlayerData(player) ?: return 0
        val quests = getAllQuests()
            .filter { it.type == type }
            .sortedBy { it.order }

        var unlocked = 0
        for (quest in quests) {
            val status = data.questStatuses[quest.id] ?: QuestStatus.LOCKED
            if (status != QuestStatus.LOCKED) continue
            if (!quest.canAccept(player, data)) continue
            if (!hasRequiredPreviousQuest(data, quest, quests)) continue

            acceptQuest(player, quest.id)
            unlocked++
        }

        return unlocked
    }

    private fun hasRequiredPreviousQuest(data: PlayerData, quest: QuestBase, quests: List<QuestBase>): Boolean {
        if (quest.order <= 1) return true

        val prevQuest = quests.find { it.order == quest.order - 1 } ?: return false
        return data.questStatuses[prevQuest.id] == QuestStatus.COMPLETED || data.completedQuests.contains(prevQuest.id)
    }

    // === 事件监听分发 ===

    @EventHandler
    fun onMobDeath(e: EntityDeathEvent) {
        val killer = e.entity.killer ?: return
        val data = plugin.playerManager.getPlayerData(killer) ?: return

        // 遍历玩家所有 "进行中" 的任务
        data.questStatuses.forEach { (qId, status) ->
            if (status == QuestStatus.IN_PROGRESS) {
                val quest = getQuest(qId)
                if (quest != null) {
                    val currentProg = data.questProgress[qId] ?: 0
                    // 调用具体的任务逻辑
                    val newProg = quest.onKillMob(e, currentProg)
                    if (newProg != null && newProg != currentProg) {
                        updateProgress(killer, qId, newProg)
                    }
                }
            }
        }
    }

    /**
     * 【新增】尝试让任务系统接管NPC对话
     */
    fun handleNpcDialogue(player: Player, npcId: String): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false

        // 遍历玩家所有 "进行中" 的任务
        for ((qId, status) in data.questStatuses) {
            if (status == com.hjh_database.quest.core.QuestStatus.IN_PROGRESS) {
                val quest = getQuest(qId) ?: continue
                val currentProg = data.questProgress[qId] ?: 0
                // 询问任务是否要接管
                if (quest.onNpcDialogue(player, npcId, currentProg)) {
                    return true // 任务接管了，打断后续逻辑
                }
            }
        }
        return false
    }
}
