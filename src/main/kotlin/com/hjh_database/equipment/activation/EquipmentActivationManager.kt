package com.hjh_database.equipment.activation

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.data.PlayerData
import com.hjh_database.weapon.WeaponManager
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

enum class WeaponOrigin {
    STANDARD,
    BAIHU
}

/**
 * 一次已经完成所有激活校验的武器解析结果。
 * 战斗事件只解析当前主手，不扫描背包。
 */
data class ActivatedWeapon(
    val id: String,
    val origin: WeaponOrigin,
    val stats: Map<String, Double>,
    val standardData: WeaponManager.WeaponData? = null,
    val baihuData: BaihuWeaponData? = null
) {
    val attackSpeed: Double?
        get() = stats["attack_speed"]
}

class EquipmentActivationManager(private val plugin: Hjh_database) {

    fun resolveWeapon(
        player: Player,
        playerData: PlayerData,
        item: ItemStack?,
        inventorySlot: Int
    ): ActivatedWeapon? {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null

        val standard = plugin.playerManager.weaponManager.getWeaponDataFromItem(item)
        if (standard != null &&
            standard.activationSpec.isActive(
                playerData = playerData,
                inventorySlot = inventorySlot,
                player = player,
                item = item
            )
        ) {
            return ActivatedWeapon(
                id = standard.id.lowercase(),
                origin = WeaponOrigin.STANDARD,
                stats = standard.stats,
                standardData = standard
            )
        }

        val baihu = plugin.baihuDzManager.getWeaponDataFromItem(item)
        if (baihu != null &&
            baihu.activationSpec.isActive(
                playerData = playerData,
                inventorySlot = inventorySlot,
                player = player,
                item = item
            ) &&
            plugin.baihuDzManager.canUse(player, item, baihu, false)
        ) {
            return ActivatedWeapon(
                id = baihu.id.lowercase(),
                origin = WeaponOrigin.BAIHU,
                stats = baihu.stats,
                baihuData = baihu
            )
        }

        return null
    }

    fun resolveHeldWeapon(player: Player, playerData: PlayerData): ActivatedWeapon? {
        val inventory = player.inventory
        return resolveWeapon(player, playerData, inventory.itemInMainHand, inventory.heldItemSlot)
    }

    fun isHoldingActiveWeapon(
        player: Player,
        expectedWeaponId: String? = null
    ): Boolean {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return false
        val active = resolveHeldWeapon(player, data) ?: return false
        return expectedWeaponId == null || active.id.equals(expectedWeaponId, ignoreCase = true)
    }

    fun heldAttackSpeed(
        player: Player,
        playerData: PlayerData,
        slot: Int = player.inventory.heldItemSlot
    ): Double {
        val item = player.inventory.getItem(slot)
        return resolveWeapon(player, playerData, item, slot)
            ?.attackSpeed
            ?.coerceAtLeast(0.0)
            ?: DEFAULT_ATTACK_SPEED
    }

    companion object {
        const val DEFAULT_ATTACK_SPEED = 4.0
    }
}
