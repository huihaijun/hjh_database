package com.hjh_database.qixiazhen.farming.event.impl

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.event.FarmDisasterEffect
import kotlin.random.Random

class FarmInsectDisasterEffect : FarmDisasterEffect {
    override val eventId: String = "farm_disaster_insects"

    // 事件概率和效果属于代码行为，不从 YAML 读取。
    override fun shouldTrigger(): Boolean = Random.nextDouble() < 0.10

    override fun apply(state: PlayerFarmState) {
        state.yieldMultiplier = (state.yieldMultiplier * 0.8).coerceAtLeast(0.1)
    }
}
