package com.hjh_database.medical

import org.bukkit.entity.Player

// 所有医术试炼的通用基础接口
interface MedicalTrial {
    val player: Player
    val trialId: String

    fun start()
    fun fail()
    fun cleanUp()
}