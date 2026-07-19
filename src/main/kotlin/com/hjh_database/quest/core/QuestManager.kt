package com.hjh_database.quest.core

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import java.util.concurrent.ConcurrentHashMap

class QuestManager(private val plugin: Hjh_database) : Listener {
    private val questMap = ConcurrentHashMap<String, QuestBase>()

    init {
        QuestRegistry.registerAll(this)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun register(quest: QuestBase) {
        questMap[quest.id] = quest
    }

    fun getQuest(id: String): QuestBase? = questMap[id]

    fun getAllQuests(): Collection<QuestBase> = questMap.values

    fun updateProgress(player: Player, questId: String, newProgress: Int) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val quest = getQuest(questId) ?: return

        data.questProgress[questId] = newProgress

        if (quest.checkComplete(newProgress)) {
            completeQuest(player, data, quest)
        } else {
            plugin.databaseManager.saveQuestData(player, questId, QuestStatus.IN_PROGRESS, newProgress)
        }
    }

    fun completeQuest(player: Player, data: PlayerData, quest: QuestBase) {
        data.questStatuses[quest.id] = QuestStatus.COMPLETED
        data.questProgress[quest.id] = 9999
        data.completedQuests.add(quest.id)

        plugin.databaseManager.saveQuestData(player, quest.id, QuestStatus.COMPLETED, 9999)
        quest.giveReward(player)

        player.sendMessage("§6§l[任务完成] §f${quest.title}")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        if (quest.type == QuestType.MAIN) {
            tryUnlockNextMainQuest(player, data, quest)
        }
    }

    private fun tryUnlockNextMainQuest(player: Player, data: PlayerData, currentQuest: QuestBase) {
        val nextQuest = questMap.values.find {
            it.type == QuestType.MAIN &&
                (
                    currentQuest.id in it.requiredCompletedQuestIds ||
                        (it.requiredCompletedQuestIds.isEmpty() &&
                            it.raceLimit == currentQuest.raceLimit &&
                            it.order == currentQuest.order + 1)
                )
        }

        if (nextQuest != null) {
            acceptQuest(player, nextQuest.id)
            player.sendMessage("§a[系统] 新任务已解锁: ${nextQuest.title}")
        }
    }

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
        if (quest.requiredCompletedQuestIds.isNotEmpty()) {
            return quest.requiredCompletedQuestIds.any { requiredQuestId ->
                data.questStatuses[requiredQuestId] == QuestStatus.COMPLETED ||
                    data.completedQuests.contains(requiredQuestId)
            }
        }

        if (quest.order <= 1) return true

        val prevQuest = quests.find {
            it.order == quest.order - 1 &&
                it.raceLimit == quest.raceLimit
        } ?: return false

        return data.questStatuses[prevQuest.id] == QuestStatus.COMPLETED || data.completedQuests.contains(prevQuest.id)
    }

    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val data = plugin.playerManager.getPlayerData(killer) ?: return

        data.questStatuses.forEach { (questId, status) ->
            if (status == QuestStatus.IN_PROGRESS) {
                val quest = getQuest(questId) ?: return@forEach
                val currentProgress = data.questProgress[questId] ?: 0
                val newProgress = quest.onKillMob(event, currentProgress)
                if (newProgress != null && newProgress != currentProgress) {
                    updateProgress(killer, questId, newProgress)
                }
            }
        }
    }

    fun handleNpcDialogue(player: Player, npcId: String): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false

        for ((questId, status) in data.questStatuses) {
            if (status == QuestStatus.IN_PROGRESS) {
                val quest = getQuest(questId) ?: continue
                val currentProgress = data.questProgress[questId] ?: 0
                if (quest.onNpcDialogue(player, npcId, currentProgress)) {
                    return true
                }
            }
        }
        return false
    }
}
