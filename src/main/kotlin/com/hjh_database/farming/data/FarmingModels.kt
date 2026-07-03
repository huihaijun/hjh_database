package com.hjh_database.farming.data

data class FarmingPlayerData(
    val uuid: String,
    var playerName: String,
    var maxFields: Int = 1,
    var totalHarvests: Int = 0,
    val fields: MutableList<FarmingField> = mutableListOf()
) {
    fun field(index: Int): FarmingField {
        while (fields.size <= index) {
            fields.add(FarmingField(fields.size))
        }
        return fields[index]
    }

    fun allFields(maxSlots: Int): List<FarmingField> {
        repeat(maxSlots) { field(it) }
        return fields.take(maxSlots)
    }

    fun firstUnlocked(): FarmingField? = fields.firstOrNull { it.unlocked }
}

data class FarmingField(
    val index: Int,
    var unlocked: Boolean = false,
    var plantType: String? = null,
    var plantedAt: Long = 0L,
    var maturesAt: Long = 0L,
    var growthStage: Int = 0,
    var acceleratorId: String? = null,
    var boosterId: String? = null,
    var protectionId: String? = null,
    var protectionUntil: Long = 0L,
    var yieldPenalty: Double = 0.0,
    var delayPenaltyMs: Long = 0L
) {
    val planted: Boolean get() = !plantType.isNullOrBlank()
    val ready: Boolean get() = planted && System.currentTimeMillis() >= maturesAt

    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        return (maturesAt - now).coerceAtLeast(0L)
    }

    fun resetPlanting() {
        plantType = null
        plantedAt = 0L
        maturesAt = 0L
        growthStage = 0
        acceleratorId = null
        boosterId = null
        yieldPenalty = 0.0
        delayPenaltyMs = 0L
    }
}

data class PlantType(
    val id: String,
    val name: String,
    val description: String,
    val seedItemId: String,
    val harvestItemId: String,
    val growthTimeSeconds: Int,
    val growthStages: Int,
    val harvestMin: Int,
    val harvestMax: Int,
    val expReward: Int,
    val levelRequired: Int
) {
    val totalGrowthMs: Long get() = growthTimeSeconds * growthStages * 1000L
}

data class AcceleratorDef(
    val id: String,
    val itemId: String,
    val speedMultiplier: Double,
    val durationSeconds: Int
)

data class BoosterDef(
    val id: String,
    val itemId: String,
    val yieldMultiplier: Double
)

data class ProtectionDef(
    val id: String,
    val itemId: String,
    val price: Int,
    val against: List<String>,
    val durationSeconds: Int
)

data class FarmingEventDef(
    val id: String,
    val chance: Double,
    val damageType: String,
    val damagePercent: Double,
    val message: String,
    val affectedPlants: List<String>
)
