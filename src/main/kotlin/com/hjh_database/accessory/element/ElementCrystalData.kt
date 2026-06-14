package com.hjh_database.accessory.element

import java.util.UUID

data class ElementCrystalData(
    val uuid: UUID,
    var playerName: String,
    var goldPoints: Int = 0,
    var woodPoints: Int = 0,
    var waterPoints: Int = 0,
    var firePoints: Int = 0,
    var earthPoints: Int = 0
) {
    fun getTotalPoints(): Int {
        return goldPoints + woodPoints + waterPoints + firePoints + earthPoints
    }

    fun resetPoints() {
        goldPoints = 0
        woodPoints = 0
        waterPoints = 0
        firePoints = 0
        earthPoints = 0
    }
}
