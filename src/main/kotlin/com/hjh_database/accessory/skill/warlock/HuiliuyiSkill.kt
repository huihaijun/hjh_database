// 路径: com.hjh_database.accessory.skill.warlock.HuiliuyiSkill.kt
package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData


// 路径: com.hjh_database.accessory.skill.warlock.HuiliuyiSkill.kt
class HuiliuyiSkill(plugin: Hjh_database) : BaseRefluxSkill(plugin) {
    override fun getThresholdPercent(crystalData: CrystalData): Double = 0.5
    override fun getCostPerLevel(crystalData: CrystalData): Double = 10.0
    // 【新增】当前等级的回流仪只有 35% 概率触发
    override fun getTriggerProbability(crystalData: CrystalData): Double = 0.35
}