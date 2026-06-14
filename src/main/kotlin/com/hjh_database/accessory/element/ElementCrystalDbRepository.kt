package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import com.hjh_database.data.DatabaseManager
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ElementCrystalDbRepository(
    private val manager: DatabaseManager,
    private val plugin: Hjh_database
) {
    fun loadData(uuid: UUID, playerName: String): CompletableFuture<ElementCrystalData> {
        return CompletableFuture.supplyAsync {
            var data: ElementCrystalData? = null
            manager.dataSource?.connection?.use { conn ->
                conn.prepareStatement("SELECT * FROM player_element_crystal WHERE player_uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            data = ElementCrystalData(
                                uuid = uuid,
                                playerName = rs.getString("player_name"),
                                goldPoints = rs.getInt("gold_points"),
                                woodPoints = rs.getInt("wood_points"),
                                waterPoints = rs.getInt("water_points"),
                                firePoints = rs.getInt("fire_points"),
                                earthPoints = rs.getInt("earth_points")
                            )
                        }
                    }
                }
            }
            if (data == null) {
                data = ElementCrystalData(uuid, playerName)
            } else {
                if (data!!.playerName != playerName) {
                    data!!.playerName = playerName
                }
            }
            data!!
        }
    }

    fun saveData(data: ElementCrystalData) {
        manager.dataSource?.connection?.use { conn ->
            val sql = """
                INSERT INTO player_element_crystal (player_uuid, player_name, gold_points, wood_points, water_points, fire_points, earth_points)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    gold_points = excluded.gold_points,
                    wood_points = excluded.wood_points,
                    water_points = excluded.water_points,
                    fire_points = excluded.fire_points,
                    earth_points = excluded.earth_points
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, data.uuid.toString())
                stmt.setString(2, data.playerName)
                stmt.setInt(3, data.goldPoints)
                stmt.setInt(4, data.woodPoints)
                stmt.setInt(5, data.waterPoints)
                stmt.setInt(6, data.firePoints)
                stmt.setInt(7, data.earthPoints)
                stmt.executeUpdate()
            }
        }
    }
}
