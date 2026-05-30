package com.hjh_database.data

import com.google.gson.Gson
import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzPlayerData
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.warehouse.data.WarehouseData
import com.hjh_database.warehouse.utils.ItemSerializer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

internal class DatabaseSubsystemRepository(
    private val manager: DatabaseManager,
    private val plugin: Hjh_database
) {

    // ==========================================

    // ----------------- Quest 任务 -----------------
    fun loadPlayerQuests(conn: Connection, data: PlayerData) {
        val sql = "SELECT quest_id, status, progress FROM player_quests WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val qId = rs.getString("quest_id")
                        val statusStr = rs.getString("status")
                        val progress = rs.getInt("progress")

                        val status = try {
                            QuestStatus.valueOf(statusStr)
                        } catch (e: Exception) { QuestStatus.LOCKED }

                        data.questStatuses[qId] = status
                        data.questProgress[qId] = progress
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载任务数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 保存单个任务状态 (异步 Upsert 操作)
     */
    fun saveQuestData(player: org.bukkit.entity.Player, questId: String, status: QuestStatus, progress: Int) {
        val sql = """
            INSERT INTO player_quests (uuid, player_name, quest_id, status, progress) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid, quest_id) DO UPDATE SET 
            player_name=?, status=?, progress=?
        """.trimIndent()

        java.util.concurrent.CompletableFuture.runAsync {
            try {
                manager.dataSource?.connection?.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, player.uniqueId.toString())
                        ps.setString(2, player.name)
                        ps.setString(3, questId)
                        ps.setString(4, status.name)
                        ps.setInt(5, progress)

                        ps.setString(6, player.name)
                        ps.setString(7, status.name)
                        ps.setInt(8, progress)

                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("保存任务 $questId 失败: ${e.message}")
            }
        }
    }


    // ----------------- Medical 医师 -----------------
    fun saveMedicalData(data: PlayerData) {
        try {
            manager.dataSource?.connection?.use { conn ->
                saveMedicalData(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("独立保存医术数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun saveMedicalData(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_medical (uuid, player_name, medical_skills) VALUES (?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, medical_skills=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                val uuidStr = data.uuid.toString()
                val name = data.playerName
                val skillsStr = data.getMedicalSkillsAsString()

                ps.setString(1, uuidStr)
                ps.setString(2, name)
                ps.setString(3, skillsStr)

                ps.setString(4, name)
                ps.setString(5, skillsStr)

                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存医术数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadMedicalData(data: PlayerData) {
        try {
            manager.dataSource?.connection?.use { conn ->
                loadMedicalData(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("独立加载医师数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadMedicalData(conn: Connection, data: PlayerData) {
        val sql = "SELECT medical_skills FROM player_medical WHERE uuid=?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        data.setMedicalSkillsFromString(rs.getString("medical_skills"))
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载医师数据失败: " + e.message)
            e.printStackTrace()
        }
    }


    // ----------------- Alchemy 丹药 -----------------
    fun saveAlchemyData(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_alchemy (uuid, player_name, alchemy_level, alchemy_exp, pill_sickness_end) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, alchemy_level=?, alchemy_exp=?, pill_sickness_end=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setInt(3, data.alchemyLevel)
                ps.setInt(4, data.alchemyExp)
                ps.setLong(5, data.pillSicknessEnd)

                ps.setString(6, data.playerName)
                ps.setInt(7, data.alchemyLevel)
                ps.setInt(8, data.alchemyExp)
                ps.setLong(9, data.pillSicknessEnd)

                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存丹药数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadAlchemyData(conn: Connection, data: PlayerData) {
        val sql = "SELECT alchemy_level, alchemy_exp, pill_sickness_end FROM player_alchemy WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        data.alchemyLevel = rs.getInt("alchemy_level")
                        data.alchemyExp = rs.getInt("alchemy_exp")
                        data.pillSicknessEnd = rs.getLong("pill_sickness_end")
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("加载丹药数据失败: " + e.message)
            e.printStackTrace()
        }
    }


    // ----------------- Status 玩家状态 -----------------
    fun savePlayerStatus(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_status (uuid, status, description) 
            VALUES (?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET status = ?, description = ?;
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setInt(2, data.status)
                ps.setString(3, data.statusDescription)
                ps.setInt(4, data.status)
                ps.setString(5, data.statusDescription)
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.warning("保存玩家状态失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadPlayerStatus(conn: Connection, data: PlayerData) {
        val sql = "SELECT status, description FROM player_status WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val s = rs.getInt("status")
                        data.updateStatus(s)
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("读取玩家状态失败: ${e.message}")
            e.printStackTrace()
        }
    }


    // ----------------- Golden Chest 金宝箱 -----------------
    fun savePlayerGoldenChest(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_goldenchest (uuid, player_name, dungeon_data) 
            VALUES (?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, dungeon_data=?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                val json = data.getDungeonRecordsAsJson()
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setString(3, json)

                ps.setString(4, data.playerName)
                ps.setString(5, json)

                ps.executeUpdate()
            }
        } catch (e: Exception) {
            plugin.logger.severe("保存玩家 ${data.playerName} 的金宝箱数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadPlayerGoldenChest(conn: Connection, data: PlayerData) {
        val sql = "SELECT dungeon_data FROM player_goldenchest WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val json = rs.getString("dungeon_data")
                        data.setDungeonRecordsFromJson(json)
                    } else {
                        data.setDungeonRecordsFromJson("{}")
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取玩家 ${data.playerName} 的金宝箱数据失败: ${e.message}")
            e.printStackTrace()
            data.setDungeonRecordsFromJson("{}")
        }
    }


    // ----------------- Chonghua 重华晶 -----------------
    fun saveChonghuaData(data: com.hjh_database.chonghua.ChonghuaData) {
        val sql = """
            INSERT INTO player_chonghua (uuid, player_name, unlocked_waypoints, waypoint_cooldowns) 
            VALUES (?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET 
            player_name=?, unlocked_waypoints=?, waypoint_cooldowns=?
        """.trimIndent()

        try {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                manager.dataSource?.connection?.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setString(3, data.getUnlockedAsJson())
                        ps.setString(4, data.getCooldownsAsJson())

                        ps.setString(5, data.playerName)
                        ps.setString(6, data.getUnlockedAsJson())
                        ps.setString(7, data.getCooldownsAsJson())

                        ps.executeUpdate()
                    }
                }
            })
        } catch (e: Exception) {
            plugin.logger.severe("保存重华晶数据失败: ${e.message}")
        }
    }

    fun loadChonghuaData(uuid: UUID, playerName: String): com.hjh_database.chonghua.ChonghuaData {
        val data = com.hjh_database.chonghua.ChonghuaData(uuid, playerName)
        val sql = "SELECT * FROM player_chonghua WHERE uuid = ?"
        try {
            manager.dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, uuid.toString())
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        data.playerName = rs.getString("player_name")
                        data.setUnlockedFromJson(rs.getString("unlocked_waypoints"))
                        data.setCooldownsFromJson(rs.getString("waypoint_cooldowns"))
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取重华晶数据失败: ${e.message}")
        }
        return data
    }


    // ----------------- DzPlayer 锻造 (独立保存) -----------------
    fun saveDzPlayerData(data: DzPlayerData) {
        val sql = """
            INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license) 
            VALUES (?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, forge_level=?, forge_exp=?, forge_license=?
        """.trimIndent()

        try {
            manager.dataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.forgeLevel)
                    ps.setInt(4, data.forgeExp)
                    ps.setInt(5, data.forgeLicense)

                    ps.setString(6, data.playerName)
                    ps.setInt(7, data.forgeLevel)
                    ps.setInt(8, data.forgeExp)
                    ps.setInt(9, data.forgeLicense)
                    ps.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存锻造数据失败 [" + data.playerName + "]: " + e.message)
            e.printStackTrace()
        }
    }

    // ----------------- WareHouse 个人仓库 -----------------
    fun saveWarehouse(conn: Connection, data: WarehouseData) {
        val sql = """
            INSERT INTO player_warehouse (uuid, player_name, category_names, items_data) 
            VALUES (?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, category_names=?, items_data=?
        """.trimIndent()

        try {
            // 直接使用传进来的 conn
            conn.prepareStatement(sql).use { ps ->
                val namesJson = Gson().toJson(data.categoryNames)
                val allItems = data.items.flatten().toTypedArray()
                val itemsBase64 = ItemSerializer.itemsToBase64(allItems)

                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setString(3, namesJson)
                ps.setString(4, itemsBase64)

                ps.setString(5, data.playerName)
                ps.setString(6, namesJson)
                ps.setString(7, itemsBase64)
                ps.executeUpdate()
            }
        } catch (e: Exception) {
            plugin.logger.severe("保存仓库数据失败: ${e.message}")
        }
    }

    fun loadWarehouse(conn: Connection, uuid: UUID, playerName: String): WarehouseData {
        val data = WarehouseData(uuid, playerName)
        val sql = "SELECT * FROM player_warehouse WHERE uuid = ?"
        try {
            // 直接使用传进来的 conn，删掉 manager.dataSource?.connection?.use
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val namesJson = rs.getString("category_names")
                        data.categoryNames = Gson().fromJson(namesJson, Array<String>::class.java)

                        val itemsBase64 = rs.getString("items_data")
                        val allItems = ItemSerializer.base64ToItems(itemsBase64)
                        // 恢复到二维数组
                        for (i in 0..7) {
                            System.arraycopy(allItems, i * 108, data.items[i], 0, 108)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取仓库数据失败: ${e.message}")
        }
        return data
    }

    // ----------------- MedicalTrials 医术试炼表 -----------------
    // 读取玩家已完成的医术试炼列表 (命名改为 load 以保持一致，传入 conn 避免死锁)
    fun loadCompletedMedicalTrials(conn: Connection, data: PlayerData) { // 注意：这里不需要 return set 了，直接修改 data
        val sql = "SELECT completed_trials FROM player_medicaltest WHERE uuid = ?"
        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val json = rs.getString("completed_trials")
                        if (!json.isNullOrEmpty() && json != "[]" && json != "null") {
                            val list = Gson().fromJson(json, Array<String>::class.java)
                            // 把解析出来的数据存入 PlayerData
                            data.completedMedicalTrials.addAll(list)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("读取医术试炼数据失败: ${e.message}")
        }
    }

    // 保存玩家的医术试炼完成状态 (命名改为 save 以保持一致，传入 conn 避免死锁)
    fun saveCompletedMedicalTrials(conn: Connection, data: PlayerData) {
        val sql = """
            INSERT INTO player_medicaltest (uuid, player_name, completed_trials) 
            VALUES (?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name = ?, completed_trials = ?
        """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                // 直接从 data 中获取需要存的数据
                val json = Gson().toJson(data.completedMedicalTrials)
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.playerName)
                ps.setString(3, json)

                ps.setString(4, data.playerName)
                ps.setString(5, json)
                ps.executeUpdate()
            }
        } catch (e: Exception) {
            plugin.logger.severe("保存医术试炼数据失败: ${e.message}")
        }
    }
}
