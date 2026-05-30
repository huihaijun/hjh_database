package com.hjh_database.data

import com.hjh_database.Hjh_database
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class DatabasePlayerRepository(
    private val manager: DatabaseManager,
    private val plugin: Hjh_database
) {

    // ==========================================

    @Throws(SQLException::class)
    private fun checkExists(conn: Connection, table: String, uuid: UUID): Boolean {
        val sql = "SELECT 1 FROM $table WHERE uuid = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    fun savePlayer(data: PlayerData) {
        val updateMain = "UPDATE player_data SET player_name=?, lv=?, exp=?, job=?, race=?, attack=?, archer_damage=?, armor=?, speed=?, max_health=?, current_health=?, toughness=?, knock_back_res=?, attack_speed=?, crit_chance=?, zf_str=?, cool_reduce=?, lingli=?, total_rarity=? WHERE uuid=?"
        val insertMain = "INSERT INTO player_data (player_name, lv, exp, job, race, attack, archer_damage, armor, speed, max_health, current_health, toughness, knock_back_res, attack_speed, crit_chance, zf_str, cool_reduce, lingli, total_rarity, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val updateBank = "UPDATE player_elementbank SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=?, relive_stone=? WHERE uuid=?"
        val insertBank = "INSERT INTO player_elementbank (player_name, metal, wood, water, fire, earth, relive_stone, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val updateSkills = "UPDATE player_element_zf_lvl SET player_name=?, metal=?, wood=?, water=?, fire=?, earth=? WHERE uuid=?"
        val insertSkills = "INSERT INTO player_element_zf_lvl (player_name, metal, wood, water, fire, earth, uuid) VALUES (?, ?, ?, ?, ?, ?, ?)"

        val updateForge = "UPDATE player_dzlv SET player_name=?, forge_level=?, forge_exp=?, forge_license=? WHERE uuid=?"
        val insertForge = "INSERT INTO player_dzlv (player_name, forge_level, forge_exp, forge_license, uuid) VALUES (?, ?, ?, ?, ?)"

        val saveKaiWu = """
            INSERT INTO player_kaiwu (uuid, player_name, kaiwu_level, kaiwu_exp, kaiwu_energy, node_data) 
            VALUES (?, ?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET player_name=?, kaiwu_level=?, kaiwu_exp=?, kaiwu_energy=?, node_data=?
        """.trimIndent()

        try {
            // 【死锁修复】这里获取唯一连接，并一直持有到所有数据保存完毕
            manager.dataSource?.connection?.use { conn ->
                // 保存主数据
                conn.prepareStatement(updateMain).use { ps ->
                    ps.setString(1, data.playerName)
                    ps.setInt(2, data.lv)
                    ps.setInt(3, data.exp)
                    ps.setObject(4, data.job)
                    ps.setObject(5, data.race)
                    ps.setDouble(6, data.attack)
                    ps.setDouble(7, data.archerDamage)
                    ps.setDouble(8, data.armor)
                    ps.setDouble(9, data.speed)
                    ps.setDouble(10, data.maxHealth)
                    ps.setDouble(11, data.currentHealth)
                    ps.setDouble(12, data.toughness)
                    ps.setDouble(13, data.knockBackRes)
                    ps.setDouble(14, data.attackSpeed)
                    ps.setDouble(15, data.critChance)
                    ps.setDouble(16, data.zfStr)
                    ps.setDouble(17, data.coolReduce)
                    ps.setDouble(18, data.lingli)
                    ps.setInt(19, data.totalRarity)
                    ps.setString(20, data.uuid.toString())

                    if (ps.executeUpdate() == 0) {
                        conn.prepareStatement(insertMain).use { insertPs ->
                            insertPs.setString(1, data.playerName)
                            insertPs.setInt(2, data.lv)
                            insertPs.setInt(3, data.exp)
                            insertPs.setObject(4, data.job)
                            insertPs.setObject(5, data.race)
                            insertPs.setDouble(6, data.attack)
                            insertPs.setDouble(7, data.archerDamage)
                            insertPs.setDouble(8, data.armor)
                            insertPs.setDouble(9, data.speed)
                            insertPs.setDouble(10, data.maxHealth)
                            insertPs.setDouble(11, data.currentHealth)
                            insertPs.setDouble(12, data.toughness)
                            insertPs.setDouble(13, data.knockBackRes)
                            insertPs.setDouble(14, data.attackSpeed)
                            insertPs.setDouble(15, data.critChance)
                            insertPs.setDouble(16, data.zfStr)
                            insertPs.setDouble(17, data.coolReduce)
                            insertPs.setDouble(18, data.lingli)
                            insertPs.setInt(19, data.totalRarity)
                            insertPs.setString(20, data.uuid.toString())
                            insertPs.executeUpdate()
                        }
                    }
                }

                // 保存仓库
                if (checkExists(conn, "player_elementbank", data.uuid)) {
                    conn.prepareStatement(updateBank).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.metal)
                        ps.setInt(3, data.wood)
                        ps.setInt(4, data.water)
                        ps.setInt(5, data.fire)
                        ps.setInt(6, data.earth)
                        ps.setInt(7, data.reliveStone)
                        ps.setString(8, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertBank).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.metal)
                        ps.setInt(3, data.wood)
                        ps.setInt(4, data.water)
                        ps.setInt(5, data.fire)
                        ps.setInt(6, data.earth)
                        ps.setInt(7, data.reliveStone)
                        ps.setString(8, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存技能表
                if (checkExists(conn, "player_element_zf_lvl", data.uuid)) {
                    conn.prepareStatement(updateSkills).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.getElementLevel("METAL"))
                        ps.setInt(3, data.getElementLevel("WOOD"))
                        ps.setInt(4, data.getElementLevel("WATER"))
                        ps.setInt(5, data.getElementLevel("FIRE"))
                        ps.setInt(6, data.getElementLevel("EARTH"))
                        ps.setString(7, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertSkills).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.getElementLevel("METAL"))
                        ps.setInt(3, data.getElementLevel("WOOD"))
                        ps.setInt(4, data.getElementLevel("WATER"))
                        ps.setInt(5, data.getElementLevel("FIRE"))
                        ps.setInt(6, data.getElementLevel("EARTH"))
                        ps.setString(7, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存锻造
                if (checkExists(conn, "player_dzlv", data.uuid)) {
                    conn.prepareStatement(updateForge).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.forgeLevel)
                        ps.setInt(3, data.forgeExp)
                        ps.setInt(4, data.forgeLicense)
                        ps.setString(5, data.uuid.toString())
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(insertForge).use { ps ->
                        ps.setString(1, data.playerName)
                        ps.setInt(2, data.forgeLevel)
                        ps.setInt(3, data.forgeExp)
                        ps.setInt(4, data.forgeLicense)
                        ps.setString(5, data.uuid.toString())
                        ps.executeUpdate()
                    }
                }

                // 保存开物术
                conn.prepareStatement(saveKaiWu).use { ps ->
                    ps.setString(1, data.uuid.toString())
                    ps.setString(2, data.playerName)
                    ps.setInt(3, data.kaiwuLevel)
                    ps.setInt(4, data.kaiwuExp)
                    ps.setDouble(5, data.kaiwuEnergy)
                    ps.setString(6, data.getNodeDataAsJsonString())
                    ps.setString(7, data.playerName)
                    ps.setInt(8, data.kaiwuLevel)
                    ps.setInt(9, data.kaiwuExp)
                    ps.setDouble(10, data.kaiwuEnergy)
                    ps.setString(11, data.getNodeDataAsJsonString())
                    ps.executeUpdate()
                }

                // 【死锁修复】保存子系统 (传入当前 conn)
                manager.saveMedicalData(conn, data)
                manager.saveAlchemyData(conn, data)
                manager.savePlayerStatus(conn, data)
                manager.savePlayerGoldenChest(conn, data)
                manager.saveCompletedMedicalTrials(conn, data)
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存玩家数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    fun loadPlayer(uuid: UUID, playerName: String): CompletableFuture<PlayerData> {
        return CompletableFuture.supplyAsync {
            val data = PlayerData(uuid, playerName)
            val sqlMain = "SELECT * FROM player_data WHERE uuid = ?"
            val sqlBank = "SELECT * FROM player_elementbank WHERE uuid = ?"
            val sqlSkills = "SELECT * FROM player_element_zf_lvl WHERE uuid = ?"
            val sqlForge = "SELECT * FROM player_dzlv WHERE uuid = ?"
            val sqlKaiWu = "SELECT * FROM player_kaiwu WHERE uuid = ?"
            val sqlCompletedQuests = "SELECT quest_id FROM player_quests WHERE uuid = ? AND status = 'COMPLETED'"

            try {
                // 【死锁修复】获取唯一连接
                manager.dataSource?.connection?.use { conn ->
                    // 1. 加载主数据
                    conn.prepareStatement(sqlMain).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                data.lv = rs.getInt("lv")
                                data.exp = rs.getInt("exp")
                                val job = rs.getObject("job") as? Int
                                if (job != null) data.job = job
                                val race = rs.getObject("race") as? Int
                                if (race != null) data.race = race
                                data.attack = rs.getDouble("attack")
                                data.archerDamage = rs.getDouble("archer_damage")
                                data.armor = rs.getDouble("armor")
                                data.speed = rs.getDouble("speed")
                                data.maxHealth = rs.getDouble("max_health")
                                data.currentHealth = rs.getDouble("current_health")
                                data.toughness = rs.getDouble("toughness")
                                data.knockBackRes = rs.getDouble("knock_back_res")
                                data.attackSpeed = rs.getDouble("attack_speed")
                                data.critChance = rs.getDouble("crit_chance")
                                data.zfStr = rs.getDouble("zf_str")
                                data.coolReduce = rs.getDouble("cool_reduce")
                                data.lingli = rs.getDouble("lingli")
                                try {
                                    data.totalRarity = rs.getInt("total_rarity")
                                } catch (e: Exception) {}
                            }
                        }
                    }

                    // 2. 加载仓库
                    conn.prepareStatement(sqlBank).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                data.metal = rs.getInt("metal")
                                data.wood = rs.getInt("wood")
                                data.water = rs.getInt("water")
                                data.fire = rs.getInt("fire")
                                data.earth = rs.getInt("earth")
                                data.reliveStone = rs.getInt("relive_stone")
                            }
                        }
                    }

                    // 3. 加载技能
                    conn.prepareStatement(sqlSkills).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.setElementLevel("METAL", rs.getInt("metal")) } catch (e: Exception) {}
                                try { data.setElementLevel("WOOD", rs.getInt("wood")) } catch (e: Exception) {}
                                try { data.setElementLevel("WATER", rs.getInt("water")) } catch (e: Exception) {}
                                try { data.setElementLevel("FIRE", rs.getInt("fire")) } catch (e: Exception) {}
                                try { data.setElementLevel("EARTH", rs.getInt("earth")) } catch (e: Exception) {}
                            }
                        }
                    }

                    // 4. 加载锻造
                    conn.prepareStatement(sqlForge).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.forgeLevel = rs.getInt("forge_level") } catch (e: Exception) {}
                                try { data.forgeExp = rs.getInt("forge_exp") } catch (e: Exception) {}
                                try { data.forgeLicense = rs.getInt("forge_license") } catch (e: Exception) {}
                            }
                        }
                    }

                    // 5. 加载开物术
                    conn.prepareStatement(sqlKaiWu).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                try { data.kaiwuLevel = rs.getInt("kaiwu_level") } catch (e: Exception) {}
                                try { data.kaiwuExp = rs.getInt("kaiwu_exp") } catch (e: Exception) {}
                                try { data.kaiwuEnergy = rs.getDouble("kaiwu_energy") } catch (e: Exception) {}
                                val jsonNode = rs.getString("node_data")
                                data.setNodeDataFromJsonString(jsonNode)
                            }
                        }
                    }

                    // 6. 加载各项子系统 (传入 conn 避免死锁)
                    manager.loadMedicalData(conn, data)
                    manager.loadPlayerQuests(conn, data)
                    manager.loadAlchemyData(conn, data)
                    manager.loadPlayerStatus(conn, data)
                    manager.loadPlayerGoldenChest(conn, data)
                    manager.loadCompletedMedicalTrials(conn, data)

                    // 7. 加载已完成的任务到缓存 (优化 RaceManager)
                    conn.prepareStatement(sqlCompletedQuests).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val qId = rs.getString("quest_id")
                                if (qId != null) {
                                    data.completedQuests.add(qId)
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("加载玩家数据失败: " + e.message)
                e.printStackTrace()
            }
            data
        }
    }

}
