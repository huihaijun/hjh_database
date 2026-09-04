package com.hjh_database.spawner.dungeon

import com.hjh_database.spawner.MobDefinition
import com.hjh_database.spawner.dungeon.impl.Qixi
import com.hjh_database.spawner.dungeon.impl.ShengShan

object DungeonMobRegistry {
    fun registerAll(register: (MobDefinition) -> Unit) {
        Qixi.definitions.forEach(register)
        ShengShan.definitions.forEach(register)
    }
}
