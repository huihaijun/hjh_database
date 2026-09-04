// 路径: com.hjh_database.accessory.skill.core.BaseAccessorySkill.kt
package com.hjh_database.accessory.skill.core

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

// 所有饰品技能的顶级抽象类
abstract class BaseAccessorySkill(protected val plugin: Hjh_database) {
    private data class TrackedCooldown(val endMillis: Long, val durationMillis: Long)

    private val trackedCooldowns = HashMap<UUID, TrackedCooldown>()

    // 默认提供 Shift+点击 的处理，子类按需重写（例如箭袋存取箭矢）
    open fun handleShiftClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        return false // 默认不拦截/不处理
    }

    open fun onMedicalHeal(event: MedicalHealEvent, item: ItemStack, slotKey: String, crystalData: CrystalData): Boolean {
        return false
    }

    protected fun getTrackedCooldownEnd(player: Player): Long =
        trackedCooldowns[player.uniqueId]?.endMillis ?: 0L

    protected fun startTrackedCooldown(player: Player, durationMillis: Long, now: Long = System.currentTimeMillis()) {
        trackedCooldowns[player.uniqueId] = TrackedCooldown(now + durationMillis, durationMillis)
    }

    protected fun clearTrackedCooldown(player: Player) {
        trackedCooldowns.remove(player.uniqueId)
    }

    /** 各职业基类会在这份通用冷却状态上继续组合箭量、回流、储血等能力。 */
    open fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState =
        trackedCooldowns[player.uniqueId]?.let {
            AccessorySkillHudState(
                cooldownEndMillis = it.endMillis,
                cooldownDurationMillis = it.durationMillis
            )
        } ?: AccessorySkillHudState()

    open fun cleanupHudState(player: Player) {
        trackedCooldowns.remove(player.uniqueId)
    }

    open fun shutdownHudState() {
        trackedCooldowns.clear()
    }
}
