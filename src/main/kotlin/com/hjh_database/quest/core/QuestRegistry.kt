package com.hjh_database.quest.core

import com.hjh_database.quest.impl.main.north.North_01
import com.hjh_database.quest.impl.main.north.North_02
import com.hjh_database.quest.impl.main.north.North_03
import com.hjh_database.quest.impl.main.north.North_04
import com.hjh_database.quest.impl.main.north.North_05
import com.hjh_database.quest.impl.main.ren.Ren_01
import com.hjh_database.quest.impl.main.ren.Ren_02
import com.hjh_database.quest.impl.main.ren.Ren_03
import com.hjh_database.quest.impl.main.ren.Ren_04
import com.hjh_database.quest.impl.main.ren.Ren_05
import com.hjh_database.quest.impl.main.ren.Ren_06
import com.hjh_database.quest.impl.main.ren.Ren_07
import com.hjh_database.quest.impl.main.ren.Ren_08
import com.hjh_database.quest.impl.main.ren.Ren_09
import com.hjh_database.quest.impl.main.ren.Ren_10
import com.hjh_database.quest.impl.main.ren.Ren_11
import com.hjh_database.quest.impl.main.ren.Ren_12
import com.hjh_database.quest.impl.main.shen.Shen_01
import com.hjh_database.quest.impl.main.shen.Shen_02
import com.hjh_database.quest.impl.main.shen.Shen_03
import com.hjh_database.quest.impl.main.shen.Shen_04
import com.hjh_database.quest.impl.main.shen.Shen_05
import com.hjh_database.quest.impl.main.shen.Shen_06
import com.hjh_database.quest.impl.main.shen.Shen_07
import com.hjh_database.quest.impl.main.shen.Shen_08
import com.hjh_database.quest.impl.main.shen.Shen_09
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
import com.hjh_database.quest.impl.main.west.West_01
import com.hjh_database.quest.impl.main.west.West_02
import com.hjh_database.quest.impl.main.west.West_03
import com.hjh_database.quest.impl.main.west.West_04
import com.hjh_database.quest.impl.main.xian.Xian_01
import com.hjh_database.quest.impl.main.xian.Xian_02
import com.hjh_database.quest.impl.main.xian.Xian_03
import com.hjh_database.quest.impl.main.xian.Xian_04
import com.hjh_database.quest.impl.main.xian.Xian_05
import com.hjh_database.quest.impl.main.xian.Xian_06
import com.hjh_database.quest.impl.main.xian.Xian_07
import com.hjh_database.quest.impl.main.xian.Xian_08
import com.hjh_database.quest.impl.main.xian.Xian_09
import com.hjh_database.quest.impl.main.zhan.Zhan_01
import com.hjh_database.quest.impl.main.zhan.Zhan_02
import com.hjh_database.quest.impl.main.zhan.Zhan_03
import com.hjh_database.quest.impl.main.zhan.Zhan_04
import com.hjh_database.quest.impl.main.zhan.Zhan_05
import com.hjh_database.quest.impl.main.zhan.Zhan_06
import com.hjh_database.quest.impl.main.zhan.Zhan_07
import com.hjh_database.quest.impl.main.zhan.Zhan_08
import com.hjh_database.quest.impl.main.zhan.Zhan_09
import com.hjh_database.quest.impl.side.Side_Archer_QuiverBook
import com.hjh_database.quest.impl.side.Side_Medical_TaolizhiBook
import com.hjh_database.quest.impl.side.Side_Qixi_StarWish
import com.hjh_database.quest.impl.side.Side_StrangeTree
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
        manager.register(Ren_10())
        manager.register(Ren_11())
        manager.register(Ren_12())

        // === 南方主线任务 ===
        manager.register(South_01())
        manager.register(South_02())
        manager.register(South_03())
        manager.register(South_04())
        manager.register(South_05())
//        manager.register(South_06())

        // === 西方主线任务 ===
        manager.register(West_01())
        manager.register(West_02())
        manager.register(West_03())
        manager.register(West_04())

        // === 北方主线任务 ===
        manager.register(North_01())
        manager.register(North_02())
        manager.register(North_03())
        manager.register(North_04())
        manager.register(North_05())

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

        // === 神族主线 ===
        manager.register(Shen_01())
        manager.register(Shen_02())
        manager.register(Shen_03())
        manager.register(Shen_04())
        manager.register(Shen_05())
        manager.register(Shen_06())
        manager.register(Shen_07())
        manager.register(Shen_08())
        manager.register(Shen_09())

        // === 战神族主线 ===
        manager.register(Zhan_01())
        manager.register(Zhan_02())
        manager.register(Zhan_03())
        manager.register(Zhan_04())
        manager.register(Zhan_05())
        manager.register(Zhan_06())
        manager.register(Zhan_07())
        manager.register(Zhan_08())
        manager.register(Zhan_09())

        // === 仙族主线 ===
        manager.register(Xian_01())
        manager.register(Xian_02())
        manager.register(Xian_03())
        manager.register(Xian_04())
        manager.register(Xian_05())
        manager.register(Xian_06())
        manager.register(Xian_07())
        manager.register(Xian_08())
        manager.register(Xian_09())

        // === 支线 ===
        manager.register(Side_Ren_01())
        manager.register(Side_Warrior_ShieldBook())
        manager.register(Side_Archer_QuiverBook())
        manager.register(Side_Warlock_BackflowBook())
        manager.register(Side_Medical_TaolizhiBook())
        manager.register(Side_Tianjige_Rumor())
        manager.register(Side_StrangeTree())
        manager.register(Side_Qixi_StarWish())
    }
}
