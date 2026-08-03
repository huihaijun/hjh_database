package com.hjh_database.equipment.activation

import com.hjh_database.data.PlayerData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 装备激活失败的稳定原因。后续新增资格证、任务进度或地区条件时，
 * 只需增加对应的 [ActivationRequirement]，不需要改动战斗代码。
 */
enum class ActivationFailure {
    SLOT_MISMATCH,
    JOB_MISMATCH,
    LEVEL_MISMATCH,
    QUALIFICATION_MISSING,
    ENVIRONMENT_MISMATCH,
    DURABILITY_DEPLETED,
    PLAYER_DATA_UNAVAILABLE,
    CUSTOM_REQUIREMENT
}

data class ActivationResult(
    val active: Boolean,
    val failure: ActivationFailure? = null
) {
    companion object {
        val ACTIVE = ActivationResult(true)

        fun inactive(failure: ActivationFailure): ActivationResult =
            ActivationResult(false, failure)
    }
}

data class ActivationContext(
    val player: Player,
    val playerData: PlayerData,
    val item: ItemStack?,
    val inventorySlot: Int = ActivationSpec.UNSPECIFIED_SLOT,
    val slotKey: String? = null
)

fun interface ActivationRequirement {
    /**
     * 返回 null 表示满足要求；否则返回首个失败原因。
     * Requirement 实例应在配置加载时创建并复用，避免在高频事件里分配对象。
     */
    fun failure(context: ActivationContext): ActivationFailure?
}

interface ActivatableEquipment {
    val activationSpec: ActivationSpec
}

/**
 * 所有可激活装备共用的基础规则。
 *
 * 高频路径使用 [isActive]，成功时不创建 ActivationResult 或集合；
 * Lore、提示等低频路径可使用 [evaluate] 获取具体失败原因。
 */
class ActivationSpec(
    val requiredJob: Int = ANY_JOB,
    val requiredLevel: Int = 1,
    acceptedInventorySlots: IntArray? = null,
    acceptedSlotKeys: Set<String>? = null,
    private val bypassEligibilityWhen: ((PlayerData) -> Boolean)? = null,
    private val additionalRequirements: List<ActivationRequirement> = emptyList()
) {
    private val inventorySlots: IntArray? = acceptedInventorySlots?.copyOf()
    private val slotKeys: Set<String>? = acceptedSlotKeys?.toSet()

    fun isEligible(playerData: PlayerData): Boolean =
        eligibilityFailure(playerData) == null

    fun eligibilityFailure(playerData: PlayerData): ActivationFailure? {
        if (bypassEligibilityWhen?.invoke(playerData) == true) {
            return null
        }
        if (requiredJob != ANY_JOB && playerData.job != requiredJob) {
            return ActivationFailure.JOB_MISMATCH
        }
        if (playerData.lv < requiredLevel) {
            return ActivationFailure.LEVEL_MISMATCH
        }
        return null
    }

    fun isActive(
        playerData: PlayerData,
        inventorySlot: Int = UNSPECIFIED_SLOT,
        slotKey: String? = null,
        player: Player? = null,
        item: ItemStack? = null
    ): Boolean = firstFailure(playerData, inventorySlot, slotKey, player, item) == null

    fun evaluate(
        playerData: PlayerData,
        inventorySlot: Int = UNSPECIFIED_SLOT,
        slotKey: String? = null,
        player: Player? = null,
        item: ItemStack? = null
    ): ActivationResult {
        val failure = firstFailure(playerData, inventorySlot, slotKey, player, item)
        return if (failure == null) ActivationResult.ACTIVE else ActivationResult.inactive(failure)
    }

    fun firstFailure(
        playerData: PlayerData,
        inventorySlot: Int = UNSPECIFIED_SLOT,
        slotKey: String? = null,
        player: Player? = null,
        item: ItemStack? = null
    ): ActivationFailure? {
        if (inventorySlots != null &&
            (inventorySlot == UNSPECIFIED_SLOT || !inventorySlots.contains(inventorySlot))
        ) {
            return ActivationFailure.SLOT_MISMATCH
        }
        if (slotKeys != null && (slotKey == null || slotKey !in slotKeys)) {
            return ActivationFailure.SLOT_MISMATCH
        }

        eligibilityFailure(playerData)?.let { return it }
        if (additionalRequirements.isEmpty()) return null

        val actualPlayer = player ?: return ActivationFailure.CUSTOM_REQUIREMENT
        val context = ActivationContext(actualPlayer, playerData, item, inventorySlot, slotKey)
        for (requirement in additionalRequirements) {
            requirement.failure(context)?.let { return it }
        }
        return null
    }

    companion object {
        const val ANY_JOB = -1
        const val UNSPECIFIED_SLOT = Int.MIN_VALUE
    }
}
