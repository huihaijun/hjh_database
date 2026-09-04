package com.hjh_database.qixiazhen.farming.event

import com.hjh_database.qixiazhen.farming.data.PlayerFarmState

interface FarmDisasterEffect {
    val eventId: String
    fun shouldTrigger(): Boolean
    fun apply(state: PlayerFarmState)
}
