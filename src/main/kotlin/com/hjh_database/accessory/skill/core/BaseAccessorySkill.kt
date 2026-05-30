// 路径: com.hjh_database.accessory.skill.core.BaseAccessorySkill.kt
package com.hjh_database.accessory.skill.core

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

// 所有饰品技能的顶级抽象类
abstract class BaseAccessorySkill(protected val plugin: Hjh_database) {

    // 默认提供 Shift+点击 的处理，子类按需重写（例如箭袋存取箭矢）
    open fun handleShiftClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        return false // 默认不拦截/不处理
    }

    open fun onMedicalHeal(event: MedicalHealEvent, item: ItemStack, slotKey: String, crystalData: CrystalData): Boolean {
        return false
    }
}
