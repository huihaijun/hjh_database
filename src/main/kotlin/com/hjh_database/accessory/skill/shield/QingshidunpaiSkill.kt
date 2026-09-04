// 路径: com.hjh_database.accessory.skill.shield.QingshidunpaiSkill.kt
package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack

class QingshidunpaiSkill(plugin: Hjh_database) : BaseShieldSkill(plugin) {

    // 1. 声明轻石盾牌的冷却时间为 5000 毫秒 (5秒)
    override fun getBlockCooldownMillis(crystalData: CrystalData): Long {
        return 5000L
    }

    // 2. 声明轻石盾牌格挡成功后的专属行为
    override fun onBlockSuccess(player: Player, event: EntityDamageByEntityEvent, crystalData: CrystalData) {
        player.sendMessage("§b🛡盾牌抵挡了本次攻击！")
    }

    override fun handleShiftClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        // 如果未来轻石盾牌在饰品栏有 Shift+右键 的主动护盾技能，可以在这里编写
        return false
    }
}