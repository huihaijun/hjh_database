// 路径: com.hjh_database.accessory.skill.shield.QingshidunpaiSkill.kt
package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import kotlin.math.ceil

class QingshidunpaiSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {
    companion object {
        private val sharedCooldowns = HashMap<UUID, Long>()
        fun getCooldown(player: Player): Long = sharedCooldowns[player.uniqueId] ?: 0L
        fun setCooldown(player: Player, cooldownMillis: Long) {
            sharedCooldowns[player.uniqueId] = System.currentTimeMillis() + cooldownMillis
        }
    }

    override fun handleShiftClick(player: Player, quiverItem: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        return false
    }
}