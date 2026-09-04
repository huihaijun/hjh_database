package com.hjh_database.qixiazhen.farming.data

import org.bukkit.Material
import java.util.UUID

data class FarmBlockKey(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int
)
data class FarmPlot(
    val id: Long,
    val key: FarmBlockKey,
    val worldName: String
)

data class FarmController(
    val id: Long,
    val key: FarmBlockKey,
    val worldName: String
)

data class PlayerFarmState(
    val playerId: UUID,
    val plotId: Long,
    var cropId: String? = null,
    var seedResourceId: String? = null,
    var plantedAt: Long = 0L,
    var maturesAt: Long = 0L,
    var yieldMultiplier: Double = 1.0,
    var insectCheckedStage: Int = 0,
    var insectDisasterCount: Int = 0,
    var insectProtectedUntil: Long = 0L,
    var updatedAt: Long = System.currentTimeMillis()
) {
    val planted: Boolean get() = !cropId.isNullOrBlank()
    fun ready(now: Long = System.currentTimeMillis()): Boolean = planted && now >= maturesAt
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (maturesAt - now).coerceAtLeast(0L)

    fun clearCrop() {
        cropId = null
        seedResourceId = null
        plantedAt = 0L
        maturesAt = 0L
        yieldMultiplier = 1.0
        insectCheckedStage = 0
        insectDisasterCount = 0
        insectProtectedUntil = 0L
        updatedAt = System.currentTimeMillis()
    }
}

data class FarmSeedDef(
    val resourceId: String,
    val cropId: String,
    val matureSeconds: Long,
    val harvestMin: Int,
    val harvestMax: Int
)

data class FarmCropDef(
    val id: String,
    val resourceId: String,
    val name: String,
    val material: Material
)

data class FarmEventPresentation(
    val id: String,
    val name: String,
    val lore: List<String>,
    val message: String
)
