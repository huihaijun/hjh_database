package com.hjh_database.qixiazhen.farming.event.impl

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState
import com.hjh_database.qixiazhen.farming.event.FarmDisasterEffect
import kotlin.random.Random

class FarmInsectDisasterEffect : FarmDisasterEffect {
    override val eventId: String = "farm_disaster_insects"

    override fun shouldTrigger(): Boolean = Random.nextDouble() < TRIGGER_CHANCE

    override fun apply(state: PlayerFarmState) {
        state.yieldMultiplier *= YIELD_MULTIPLIER
        state.insectDisasterCount++
    }

    companion object {
        const val GROWTH_CHECKPOINTS = 9
        const val MAX_OCCURRENCES = 3
        const val TRIGGER_CHANCE = 0.50
        const val YIELD_MULTIPLIER = 0.80
    }
}
