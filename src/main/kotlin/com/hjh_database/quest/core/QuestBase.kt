package com.hjh_database.quest.core

import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent

/**
 * 所有具体的任务文件都要继承这个类
 */
abstract class QuestBase(
    val id: String,
    val title: String,
    val type: QuestType,
    val order: Int = 0 // 排序用，尤其是主线
) {
    abstract val description: List<String>
    open val raceLimit: Int? = null // 限制种族ID

    // === 显示逻辑 ===
    fun getDisplayLore(progress: Int): List<String> {
        val list = ArrayList<String>()
        list.addAll(description)
        list.add("")
        list.add("§e§l当前进度:")
        list.addAll(getProgressText(progress))
        return list
    }

    // 子类实现：具体的进度文本
    abstract fun getProgressText(progress: Int): List<String>

    // === 核心逻辑接口 ===

    // 检查是否完成，返回 true 代表完成了
    abstract fun checkComplete(progress: Int): Boolean

    // 发放奖励
    abstract fun giveReward(player: Player)

    // === 事件回调 (由 QuestManager 调用) ===

    // 当杀怪时 (返回新的进度值，如果没变化返回 null)
    open fun onKillMob(event: EntityDeathEvent, currentProgress: Int): Int? { return null }

    // 当点击NPC/实体时
    open fun onInteractNpc(event: PlayerInteractEntityEvent, currentProgress: Int): Int? { return null }

    /**
     * 【新增】处理NPC对话 (左键)
     * @param npcId 点击的 NPC 的模版ID (例如 "ren_chief")
     * @param currentProgress 当前任务进度
     * @return 返回 true 表示任务系统接管了对话，不再播放NPC原本的闲聊；返回 false 表示无事发生
     */
    open fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return false
    }

}