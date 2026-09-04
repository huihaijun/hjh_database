package com.hjh_database.accessory.skill.medical

import com.hjh_database.Hjh_database

class TaolizhiSkill(plugin: Hjh_database) : BaseMedicalOverflowSkill(plugin) {
    override fun getMaxDamageRetargets(): Int = 1

    override fun getOverflowRetargetRange(): Double = 8.0
}
