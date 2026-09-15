package com.hjh_database.spawner.dungeon

import com.hjh_database.spawner.MobDefinition
import com.hjh_database.spawner.dungeon.impl.Qixi
import com.hjh_database.spawner.dungeon.impl.ShengShan
import com.hjh_database.spawner.dungeon.impl.Zhenyao
import com.hjh_database.spawner.dungeon.impl.Huomo

object DungeonMobRegistry {
    fun registerAll(register: (MobDefinition) -> Unit) {
        Qixi.definitions.forEach(register)
        ShengShan.definitions.forEach(register)
        Zhenyao.definitions.forEach(register)
        Huomo.definitions.forEach(register)
    }
}
