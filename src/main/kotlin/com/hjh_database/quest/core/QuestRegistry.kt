package com.hjh_database.quest.core

import com.hjh_database.quest.impl.main.ren.Ren_01
import com.hjh_database.quest.impl.main.ren.Ren_02
import com.hjh_database.quest.impl.main.ren.Ren_03
import com.hjh_database.quest.impl.main.ren.Ren_04
import com.hjh_database.quest.impl.main.ren.Ren_05
//import com.hjh_database.quest.impl.main.ren.Ren_06

object QuestRegistry {
    fun registerAll(manager: QuestManager) {
        // === 人族主线 ===
        manager.register(Ren_01())
        manager.register(Ren_02())
        manager.register(Ren_03())
        manager.register(Ren_04())
        manager.register(Ren_05())
//        manager.register(Ren_06())


        // === 仙族主线 ===
        // manager.register(Xian_01_Begin())

        // === 支线 ===
        // manager.register(Side_Blacksmith())
    }
}