package com.hjh_database.baihu_dz.skill

import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.data.PlayerData
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

interface BaihuWeaponSkill {
    fun bypassDurabilityCost(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): Boolean = false

    fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult

    fun deactivate(player: Player) {}
}

data class BaihuWeaponSkillResult(
    val success: Boolean,
    val consumeDurability: Boolean = true,
    val startCooldown: Boolean = true,
    val message: String? = null
) {
    companion object {
        val FAIL = BaihuWeaponSkillResult(false, consumeDurability = false, startCooldown = false)
        fun success(
            consumeDurability: Boolean = true,
            startCooldown: Boolean = true,
            message: String? = null
        ) = BaihuWeaponSkillResult(true, consumeDurability, startCooldown, message)
    }
}
