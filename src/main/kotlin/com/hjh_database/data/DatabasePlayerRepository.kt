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
        val saveMain = """
            INSERT INTO player_data (
                uuid, player_name, lv, exp, exp_curve_version, job, race, attack, archer_damage, armor,
                speed, max_health, current_health, toughness, knock_back_res, attack_speed, crit_chance,
                zf_str, cool_reduce, lingli, money, total_rarity
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                lv=excluded.lv,
                exp=excluded.exp,
                exp_curve_version=excluded.exp_curve_version,
                job=excluded.job,
                race=excluded.race,
                attack=excluded.attack,
                archer_damage=excluded.archer_damage,
                armor=excluded.armor,
                speed=excluded.speed,
                max_health=excluded.max_health,
                current_health=excluded.current_health,
                toughness=excluded.toughness,
                knock_back_res=excluded.knock_back_res,
                attack_speed=excluded.attack_speed,
                crit_chance=excluded.crit_chance,
                zf_str=excluded.zf_str,
                cool_reduce=excluded.cool_reduce,
                lingli=excluded.lingli,
                money=excluded.money,
                total_rarity=excluded.total_rarity
        """.trimIndent()

        val saveBank = """
            INSERT INTO player_elementbank (uuid, player_name, metal, wood, water, fire, earth, relive_stone)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                metal=excluded.metal,
                wood=excluded.wood,
                water=excluded.water,
                fire=excluded.fire,
                earth=excluded.earth,
                relive_stone=excluded.relive_stone
        """.trimIndent()

        val saveSkills = """
            INSERT INTO player_element_zf_lvl (uuid, player_name, metal, wood, water, fire, earth)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                metal=excluded.metal,
                wood=excluded.wood,
                water=excluded.water,
                fire=excluded.fire,
                earth=excluded.earth
        """.trimIndent()

        val saveForge = """
            INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                forge_level=excluded.forge_level,
                forge_exp=excluded.forge_exp,
                forge_license=excluded.forge_license
        """.trimIndent()

        val saveKaiWu = """
            INSERT INTO player_kaiwu (uuid, player_name, kaiwu_level, kaiwu_exp, kaiwu_energy, node_data)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                kaiwu_level=excluded.kaiwu_level,
                kaiwu_exp=excluded.kaiwu_exp,
                kaiwu_energy=excluded.kaiwu_energy,
                node_data=excluded.node_data
        """.trimIndent()

        val saveJianghuXinde = """
            INSERT INTO player_jianghu_xinde (uuid, player_name, jianghu_xinde, xiushen_exp_gained, xiushen_last_level)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                jianghu_xinde=excluded.jianghu_xinde,
                xiushen_exp_gained=excluded.xiushen_exp_gained,
                xiushen_last_level=excluded.xiushen_last_level
        """.trimIndent()

        try {
            manager.dataSource?.connection?.use { conn ->
                val originalAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.prepareStatement(saveMain).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.lv)
                        ps.setInt(4, data.exp)
                        ps.setInt(5, data.expCurveVersion)
                        ps.setObject(6, data.job)
                        ps.setObject(7, data.race)
                        ps.setDouble(8, data.attack)
                        ps.setDouble(9, data.archerDamage)
                        ps.setDouble(10, data.armor)
                        ps.setDouble(11, data.speed)
                        ps.setDouble(12, data.maxHealth)
                        ps.setDouble(13, data.currentHealth)
                        ps.setDouble(14, data.toughness)
                        ps.setDouble(15, data.knockBackRes)
                        ps.setDouble(16, data.attackSpeed)
                        ps.setDouble(17, data.critChance)
                        ps.setDouble(18, data.zfStr)
                        ps.setDouble(19, data.coolReduce)
                        ps.setDouble(20, data.lingli)
                        ps.setDouble(21, data.money)
                        ps.setInt(22, data.totalRarity)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(saveBank).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.metal)
                        ps.setInt(4, data.wood)
                        ps.setInt(5, data.water)
                        ps.setInt(6, data.fire)
                        ps.setInt(7, data.earth)
                        ps.setInt(8, data.reliveStone)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(saveSkills).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.getElementLevel("METAL"))
                        ps.setInt(4, data.getElementLevel("WOOD"))
                        ps.setInt(5, data.getElementLevel("WATER"))
                        ps.setInt(6, data.getElementLevel("FIRE"))
                        ps.setInt(7, data.getElementLevel("EARTH"))
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(saveForge).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.forgeLevel)
                        ps.setInt(4, data.forgeExp)
                        ps.setInt(5, data.forgeLicense)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(saveKaiWu).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.kaiwuLevel)
                        ps.setInt(4, data.kaiwuExp)
                        ps.setDouble(5, data.kaiwuEnergy)
                        ps.setString(6, data.getNodeDataAsJsonString())
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(saveJianghuXinde).use { ps ->
                        ps.setString(1, data.uuid.toString())
                        ps.setString(2, data.playerName)
                        ps.setInt(3, data.jianghuXinde)
                        ps.setInt(4, data.xiushenExpGained)
                        ps.setInt(5, data.xiushenLastLevel)
                        ps.executeUpdate()
                    }

                    manager.saveMedicalData(conn, data)
                    manager.saveAlchemyData(conn, data)
                    manager.savePlayerStatus(conn, data)
                    manager.savePlayerGoldenChest(conn, data)
                    manager.saveCompletedMedicalTrials(conn, data)
                    conn.commit()
                } catch (ex: SQLException) {
                    try {
                        conn.rollback()
                    } catch (rollbackEx: SQLException) {
                        ex.addSuppressed(rollbackEx)
                    }
                    throw ex
                } finally {
                    conn.autoCommit = originalAutoCommit
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("保存玩家数据失败: " + e.message)
            e.printStackTrace()
        }
    }

    @Suppress("unused")
    private fun savePlayerLegacy(data: PlayerData) {
        val saveMain = """
            INSERT INTO player_data (
                uuid, player_name, lv, exp, exp_curve_version, job, race, attack, archer_damage, armor,
                speed, max_health, current_health, toughness, knock_back_res, attack_speed, crit_chance,
                zf_str, cool_reduce, lingli, total_rarity
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                lv=excluded.lv,
                exp=excluded.exp,
                exp_curve_version=excluded.exp_curve_version,
                job=excluded.job,
                race=excluded.race,
                attack=excluded.attack,
                archer_damage=excluded.archer_damage,
                armor=excluded.armor,
                speed=excluded.speed,
                max_health=excluded.max_health,
                current_health=excluded.current_health,
                toughness=excluded.toughness,
                knock_back_res=excluded.knock_back_res,
                attack_speed=excluded.attack_speed,
                crit_chance=excluded.crit_chance,
                zf_str=excluded.zf_str,
                cool_reduce=excluded.cool_reduce,
                lingli=excluded.lingli,
                total_rarity=excluded.total_rarity
        """.trimIndent()

        val saveBank = """
            INSERT INTO player_elementbank (uuid, player_name, metal, wood, water, fire, earth, relive_stone)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                metal=excluded.metal,
                wood=excluded.wood,
                water=excluded.water,
                fire=excluded.fire,
                earth=excluded.earth,
                relive_stone=excluded.relive_stone
        """.trimIndent()

        val saveSkills = """
            INSERT INTO player_element_zf_lvl (uuid, player_name, metal, wood, water, fire, earth)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                metal=excluded.metal,
                wood=excluded.wood,
                water=excluded.water,
                fire=excluded.fire,
                earth=excluded.earth
        """.trimIndent()

        val saveForge = """
            INSERT INTO player_dzlv (uuid, player_name, forge_level, forge_exp, forge_license)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                forge_level=excluded.forge_level,
                forge_exp=excluded.forge_exp,
                forge_license=excluded.forge_license
        """.trimIndent()

        val saveKaiWu = """
            INSERT INTO player_kaiwu (uuid, player_name, kaiwu_level, kaiwu_exp, kaiwu_energy, node_data) 
            VALUES (?, ?, ?, ?, ?, ?) 
            ON CONFLICT(uuid) DO UPDATE SET
                player_name=excluded.player_name,
                kaiwu_level=excluded.kaiwu_level,
                kaiwu_exp=excluded.kaiwu_exp,
                kaiwu_energy=excluded.kaiwu_energy,
                node_data=excluded.node_data
        """.trimIndent()

        val updateMain = saveMain
        val insertMain = saveMain
        val updateBank = saveBank
        val insertBank = saveBank
        val updateSkills = saveSkills
        val insertSkills = saveSkills
        val updateForge = saveForge
        val insertForge = saveForge

        try {
            // 【死锁修复】这里获取唯一连接，并一直持有到所有数据保存完毕
            manager.dataSource?.connection?.use { conn ->
                // 保存主数据
                conn.prepareStatement(updateMain).use { ps ->
                    ps.setString(1, data.playerName)
                    ps.setInt(2, data.lv)
                    ps.setInt(3, data.exp)
                    ps.setInt(4, data.expCurveVersion)
                    ps.setObject(5, data.job)
                    ps.setObject(6, data.race)
                    ps.setDouble(7, data.attack)
                    ps.setDouble(8, data.archerDamage)
                    ps.setDouble(9, data.armor)
                    ps.setDouble(10, data.speed)
                    ps.setDouble(11, data.maxHealth)
                    ps.setDouble(12, data.currentHealth)
                    ps.setDouble(13, data.toughness)
                    ps.setDouble(14, data.knockBackRes)
                    ps.setDouble(15, data.attackSpeed)
                    ps.setDouble(16, data.critChance)
                    ps.setDouble(17, data.zfStr)
                    ps.setDouble(18, data.coolReduce)
                    ps.setDouble(19, data.lingli)
                    ps.setInt(20, data.totalRarity)
                    ps.setString(21, data.uuid.toString())

                    if (ps.executeUpdate() == 0) {
                        conn.prepareStatement(insertMain).use { insertPs ->
                            insertPs.setString(1, data.playerName)
                            insertPs.setInt(2, data.lv)
                            insertPs.setInt(3, data.exp)
                            insertPs.setInt(4, data.expCurveVersion)
                            insertPs.setObject(5, data.job)
                            insertPs.setObject(6, data.race)
                            insertPs.setDouble(7, data.attack)
                            insertPs.setDouble(8, data.archerDamage)
                            insertPs.setDouble(9, data.armor)
                            insertPs.setDouble(10, data.speed)
                            insertPs.setDouble(11, data.maxHealth)
                            insertPs.setDouble(12, data.currentHealth)
                            insertPs.setDouble(13, data.toughness)
                            insertPs.setDouble(14, data.knockBackRes)
                            insertPs.setDouble(15, data.attackSpeed)
                            insertPs.setDouble(16, data.critChance)
                            insertPs.setDouble(17, data.zfStr)
                            insertPs.setDouble(18, data.coolReduce)
                            insertPs.setDouble(19, data.lingli)
                            insertPs.setInt(20, data.totalRarity)
                            insertPs.setString(21, data.uuid.toString())
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
            val sqlJianghuXinde = "SELECT jianghu_xinde, xiushen_exp_gained, xiushen_last_level FROM player_jianghu_xinde WHERE uuid = ?"
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
                                try {
                                    data.expCurveVersion = rs.getInt("exp_curve_version")
                                } catch (e: Exception) {}
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
                                    data.money = rs.getDouble("money")
                                } catch (e: Exception) {}
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
                    conn.prepareStatement(sqlJianghuXinde).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                data.jianghuXinde = rs.getInt("jianghu_xinde")
                                try { data.xiushenExpGained = rs.getInt("xiushen_exp_gained") } catch (e: Exception) {}
                                try { data.xiushenLastLevel = rs.getInt("xiushen_last_level") } catch (e: Exception) { data.xiushenLastLevel = 1 }
                            }
                        }
                    }

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
