// 路径: com.hjh_database.accessory.skill.warlock.HuiliuyiSkill.kt
package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData


// 路径: com.hjh_database.accessory.skill.warlock.HuiliuyiSkill.kt
class HuiliuyiSkill(plugin: Hjh_database) : BaseRefluxSkill(plugin) {
    override val accessoryId: String = "huiliuyi"
    override fun getThresholdPercent(crystalData: CrystalData): Double = 0.5
    override fun getCostPerLevel(crystalData: CrystalData): Double = 4.0
    override fun getTriggerProbability(crystalData: CrystalData): Double = 0.33
}
