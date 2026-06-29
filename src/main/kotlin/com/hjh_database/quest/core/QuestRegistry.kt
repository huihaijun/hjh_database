package com.hjh_database.quest.core

import com.hjh_database.quest.impl.main.ren.Ren_01
import com.hjh_database.quest.impl.main.ren.Ren_02
import com.hjh_database.quest.impl.main.ren.Ren_03
import com.hjh_database.quest.impl.main.ren.Ren_04
import com.hjh_database.quest.impl.main.ren.Ren_05
import com.hjh_database.quest.impl.main.ren.Ren_06
import com.hjh_database.quest.impl.main.ren.Ren_07
import com.hjh_database.quest.impl.main.ren.Ren_08
import com.hjh_database.quest.impl.main.ren.Ren_09
import com.hjh_database.quest.impl.main.south.South_01
import com.hjh_database.quest.impl.main.south.South_02
import com.hjh_database.quest.impl.main.south.South_03
import com.hjh_database.quest.impl.main.south.South_04
import com.hjh_database.quest.impl.main.south.South_05
import com.hjh_database.quest.impl.main.yao.Yao_01
import com.hjh_database.quest.impl.main.yao.Yao_02
import com.hjh_database.quest.impl.main.yao.Yao_03
import com.hjh_database.quest.impl.main.yao.Yao_04
import com.hjh_database.quest.impl.main.yao.Yao_05
import com.hjh_database.quest.impl.main.yao.Yao_06
import com.hjh_database.quest.impl.main.yao.Yao_07
import com.hjh_database.quest.impl.main.yao.Yao_08
import com.hjh_database.quest.impl.main.yao.Yao_09
import com.hjh_database.quest.impl.side.Side_Archer_QuiverBook
import com.hjh_database.quest.impl.side.Side_Medical_TaolizhiBook
import com.hjh_database.quest.impl.side.Side_Tianjige_Rumor
import com.hjh_database.quest.impl.side.Side_Warrior_ShieldBook
import com.hjh_database.quest.impl.side.Side_Warlock_BackflowBook
import com.hjh_database.quest.impl.side.ren.Side_Ren_01


object QuestRegistry {
    fun registerAll(manager: QuestManager) {
        // === 人族主线 ===
        manager.register(Ren_01())
        manager.register(Ren_02())
        manager.register(Ren_03())
        manager.register(Ren_04())
        manager.register(Ren_05())
        manager.register(Ren_06())
        manager.register(Ren_07())
        manager.register(Ren_08())
        manager.register(Ren_09())

        // === 南方主线任务 ===
        manager.register(South_01())
        manager.register(South_02())
        manager.register(South_03())
        manager.register(South_04())
        manager.register(South_05())
//        manager.register(South_06())


        // === 妖族主线 ===
        manager.register(Yao_01())
        manager.register(Yao_02())
        manager.register(Yao_03())
        manager.register(Yao_04())
        manager.register(Yao_05())
        manager.register(Yao_06())
        manager.register(Yao_07())
        manager.register(Yao_08())
        manager.register(Yao_09())

        // === 仙族主线 ===
        // manager.register(Xian_01_Begin())

        // === 支线 ===
        manager.register(Side_Ren_01())
        manager.register(Side_Warrior_ShieldBook())
        manager.register(Side_Archer_QuiverBook())
        manager.register(Side_Warlock_BackflowBook())
        manager.register(Side_Medical_TaolizhiBook())
        manager.register(Side_Tianjige_Rumor())
    }
}
