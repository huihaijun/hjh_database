package com.hjh_database.quest.core

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.title.QuestTitleRewards
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class QuestManager(private val plugin: Hjh_database) : Listener {
    private data class PendingNpcSelection(
        val token: String,
        val npcId: String,
        val questIds: Set<String>,
        val expiresAt: Long
    )

    private data class PreferredNpcQuest(val npcId: String, val questId: String)

    private val questMap = ConcurrentHashMap<String, QuestBase>()
    private val pendingNpcSelections = ConcurrentHashMap<UUID, PendingNpcSelection>()
    private val preferredNpcQuests = ConcurrentHashMap<UUID, PreferredNpcQuest>()
    private val legacySerializer = LegacyComponentSerializer.legacySection()

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
        QuestTitleRewards.byQuestId[quest.id]?.let { reward ->
            plugin.titleManager.grantQuestCompletionTitle(player, reward.titleId, reward.questId)
        }

        player.sendMessage("§6§l[任务完成] §f${quest.title}")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        if (quest.type == QuestType.MAIN) {
            tryUnlockNextMainQuest(player, data, quest)
        }
    }

    private fun tryUnlockNextMainQuest(player: Player, data: PlayerData, currentQuest: QuestBase) {
        val nextQuest = questMap.values.find {
            it.type == QuestType.MAIN &&
                (data.questStatuses[it.id] ?: QuestStatus.LOCKED) == QuestStatus.LOCKED &&
                it.canAccept(player, data) &&
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

        val selectable = data.questStatuses.entries.mapNotNull { (questId, status) ->
            if (status != QuestStatus.IN_PROGRESS) return@mapNotNull null
            val quest = getQuest(questId) ?: return@mapNotNull null
            val progress = data.questProgress[questId] ?: 0
            quest.takeIf { it.canHandleNpcDialogue(npcId, progress) }
        }.sortedWith(compareBy<QuestBase>({ it.type.ordinal }, { it.order }, { it.id }))

        preferredNpcQuests[player.uniqueId]?.let { preferred ->
            val quest = selectable.firstOrNull { it.id == preferred.questId && preferred.npcId == npcId }
            if (quest != null) {
                return quest.onNpcDialogue(player, npcId, data.questProgress[quest.id] ?: 0)
            }
            preferredNpcQuests.remove(player.uniqueId, preferred)
        }

        if (selectable.size > 1) {
            showNpcQuestSelection(player, npcId, selectable)
            return true
        }
        if (selectable.size == 1) {
            val quest = selectable.first()
            return quest.onNpcDialogue(player, npcId, data.questProgress[quest.id] ?: 0)
        }

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

    private fun showNpcQuestSelection(player: Player, npcId: String, quests: List<QuestBase>) {
        val token = UUID.randomUUID().toString()
        pendingNpcSelections[player.uniqueId] = PendingNpcSelection(
            token,
            npcId,
            quests.mapTo(LinkedHashSet()) { it.id },
            System.currentTimeMillis() + NPC_SELECTION_TIMEOUT_MILLIS
        )

        player.sendMessage("§e[系统] §f检测到此NPC有多个任务，请点击优先进行的任务。")
        var options = Component.empty()
        quests.forEach { quest ->
            val option = legacySerializer.deserialize("§e${quest.title}")
                .clickEvent(ClickEvent.runCommand("/$NPC_SELECTION_COMMAND $token ${quest.id}"))
                .hoverEvent(HoverEvent.showText(legacySerializer.deserialize("§a点击优先进行此任务")))
            options = options.append(option).append(Component.space())
        }
        player.sendMessage(options)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onNpcQuestSelectionCommand(event: PlayerCommandPreprocessEvent) {
        val parts = event.message.trim().split(Regex("\\s+"), limit = 3)
        if (parts.firstOrNull()?.equals("/$NPC_SELECTION_COMMAND", ignoreCase = true) != true) return
        event.isCancelled = true

        val player = event.player
        val pending = pendingNpcSelections[player.uniqueId]
        if (parts.size != 3 || pending == null || pending.token != parts[1]) {
            player.sendMessage("§c[系统] 该任务选择已经失效，请重新左键NPC。")
            return
        }
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingNpcSelections.remove(player.uniqueId, pending)
            player.sendMessage("§c[系统] 该任务选择已经超时，请重新左键NPC。")
            return
        }

        val questId = parts[2]
        if (questId !in pending.questIds) {
            player.sendMessage("§c[系统] 无效的任务选择。")
            return
        }

        val data = plugin.playerManager.getPlayerData(player)
        val quest = getQuest(questId)
        val progress = data?.questProgress?.get(questId) ?: 0
        if (data == null || quest == null || data.questStatuses[questId] != QuestStatus.IN_PROGRESS ||
            !quest.canHandleNpcDialogue(pending.npcId, progress)
        ) {
            pendingNpcSelections.remove(player.uniqueId, pending)
            player.sendMessage("§c[系统] 该任务当前已无法在此NPC处进行。")
            return
        }

        pendingNpcSelections.remove(player.uniqueId, pending)
        preferredNpcQuests[player.uniqueId] = PreferredNpcQuest(pending.npcId, questId)
        quest.onNpcDialogue(player, pending.npcId, progress)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        pendingNpcSelections.remove(event.player.uniqueId)
        preferredNpcQuests.remove(event.player.uniqueId)
    }

    private companion object {
        const val NPC_SELECTION_COMMAND = "hjhquestselect"
        const val NPC_SELECTION_TIMEOUT_MILLIS = 30_000L
    }
}
