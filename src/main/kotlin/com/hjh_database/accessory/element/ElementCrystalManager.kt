package com.hjh_database.accessory.element

import com.hjh_database.Hjh_database
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ElementCrystalManager(private val plugin: Hjh_database) : Listener {
    private val cache = ConcurrentHashMap<UUID, ElementCrystalData>()
    private val dbRepo = ElementCrystalDbRepository(plugin.databaseManager, plugin)

    /** 战士精进技能管理器 */
    val warriorMastery = WarriorMasterySkills(plugin)

    /** 弓手精进技能管理器 */
    val archerMastery = ArcherMasterySkills(plugin)

    /** 术士精进技能管理器 */
    val warlockMastery = WarlockMasterySkills(plugin)

    /** 医师精进技能管理器 */
    val medicalMastery = MedicalMasterySkills(plugin)

    fun initBlock() {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val world = org.bukkit.Bukkit.getWorld("world")
            if (world != null) {
                val loc = org.bukkit.Location(world, 124.0, 60.0, -19.0)
                val block = loc.block
                if (block.type != org.bukkit.Material.RESPAWN_ANCHOR) {
                    block.type = org.bukkit.Material.RESPAWN_ANCHOR
                }
            }
        })
    }

    fun loadPlayer(player: Player) {
        dbRepo.loadData(player.uniqueId, player.name).thenAccept { data ->
            cache[player.uniqueId] = data
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.playerManager.updateStats(player)
                plugin.playerManager.crystalManager.refreshPlayerCrystals(player)
            })
        }
    }

    fun unloadPlayer(player: Player) {
        val data = cache.remove(player.uniqueId)
        if (data != null) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                dbRepo.saveData(data)
            })
        }
        warriorMastery.cleanup(player.uniqueId)
        archerMastery.cleanup(player.uniqueId)
        warlockMastery.cleanup(player.uniqueId)
        medicalMastery.cleanup(player.uniqueId)
    }

    fun savePlayerAsync(player: Player) {
        val data = cache[player.uniqueId]
        if (data != null) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                dbRepo.saveData(data)
            })
        }
    }

    fun saveAll() {
        for (data in cache.values) {
            dbRepo.saveData(data)
        }
    }

    fun getData(uuid: UUID): ElementCrystalData {
        return cache.getOrPut(uuid) {
            ElementCrystalData(uuid, "")
        }
    }

    fun getStats(data: ElementCrystalData): Map<String, Double> {
        val stats = mutableMapOf<String, Double>()
        if (data.goldPoints > 0) stats["power"] = data.goldPoints * 1.5
        if (data.woodPoints > 0) stats["max_health"] = data.woodPoints * 6.0
        if (data.waterPoints > 0) stats["cool_reduce"] = data.waterPoints * 0.02
        if (data.firePoints > 0) stats["crit_chance"] = data.firePoints * 0.04
        if (data.earthPoints > 0) stats["armor"] = data.earthPoints * 6.0
        return stats
    }

    fun onSkillTrigger(player: Player, element: String, level: Int) {
        // level = 1 (启示 2pts), 2 (精进 4pts), 3 (共鸣 6pts)
        // 预留技能接口
    }

    // 检测触发状态
    fun checkAndTriggerSkills(player: Player, data: ElementCrystalData) {
        val elements = mapOf(
            "gold" to data.goldPoints,
            "wood" to data.woodPoints,
            "water" to data.waterPoints,
            "fire" to data.firePoints,
            "earth" to data.earthPoints
        )
        for ((element, points) in elements) {
            if (points >= 6) {
                onSkillTrigger(player, element, 3)
            } else if (points >= 4) {
                onSkillTrigger(player, element, 2)
            } else if (points >= 2) {
                onSkillTrigger(player, element, 1)
            }
        }
    }

    // =================================================================
    // ⚡️ 启示状态技能数据结构与触发逻辑 (2点数时激活)
    // =================================================================

    // 各技能冷却时间戳 (UUID -> CooldownEndTimeMillis)
    private val woodCd = ConcurrentHashMap<UUID, Long>()
    private val waterCd = ConcurrentHashMap<UUID, Long>()
    private val fireCd = ConcurrentHashMap<UUID, Long>()
    private val earthCd = ConcurrentHashMap<UUID, Long>()

    // 金属性：锋芒状态 (UUID -> 层数) & (UUID -> 结束时间)
    private val goldStacks = ConcurrentHashMap<UUID, Int>()
    private val goldEndTime = ConcurrentHashMap<UUID, Long>()

    fun getFengMangStacks(player: Player): Int {
        val end = goldEndTime[player.uniqueId] ?: 0L
        if (System.currentTimeMillis() > end) {
            goldStacks.remove(player.uniqueId)
            goldEndTime.remove(player.uniqueId)
            return 0
        }
        return goldStacks.getOrDefault(player.uniqueId, 0)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun handleDamageDealt(event: ElementCrystalDamageDealtEvent) {
        val stacks = getFengMangStacks(event.player)
        if (stacks > 0) event.damage *= 1.0 + 0.05 * stacks
        onDamageDealt(
            event.player,
            event.victim,
            event.isNormalAttack,
            event.isArrowHit,
            event.arrow,
            event.damage
        )
    }

    @EventHandler
    fun handleDamageTaken(event: ElementCrystalDamageTakenEvent) {
        onDamageTaken(event.player, event.damageEvent)
    }

    // 1. 金/火 技能触发 (当玩家直接造成伤害时调用)
    fun onDamageDealt(
        player: Player,
        victim: org.bukkit.entity.LivingEntity,
        isNormalAttack: Boolean = false,
        isArrowHit: Boolean = false,
        arrow: org.bukkit.entity.AbstractArrow? = null,
        eventDamage: Double = 0.0
    ) {
        if (!isCrystalActive(player)) return
        val eData = getData(player.uniqueId)

        // 金元素·启示
        if (eData.goldPoints >= 2) {
            val now = System.currentTimeMillis()
            val end = goldEndTime[player.uniqueId] ?: 0L
            if (now > end) {
                // 开启新一轮叠层
                goldStacks[player.uniqueId] = 1
                goldEndTime[player.uniqueId] = now + 5000L
                player.sendMessage("§e[金·启示]已触发")
                player.world.spawnParticle(org.bukkit.Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 10, 0.2, 0.2, 0.2, 0.1)
            } else {
                val current = goldStacks.getOrDefault(player.uniqueId, 0)
                if (current < 3) {
                    goldStacks[player.uniqueId] = current + 1
                    player.world.spawnParticle(org.bukkit.Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 5, 0.2, 0.2, 0.2, 0.1)
                }
            }
        }

        // 火元素·启示
        if (eData.firePoints >= 2) {
            val now = System.currentTimeMillis()
            val cdEnd = fireCd.getOrDefault(player.uniqueId, 0L)
            if (now >= cdEnd) {
                fireCd[player.uniqueId] = now + 6000L // 6秒冷却
                player.sendMessage("§c[火·启示]已触发")
                player.world.spawnParticle(org.bukkit.Particle.LAVA, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.1)

                val pData = plugin.playerManager.getPlayerData(player)
                if (pData != null) {
                    // 获取当前职业的主进攻属性
                    val offStat = when (pData.job) {
                        1 -> pData.archerDamage
                        2, 3 -> pData.zfStr
                        else -> pData.attack
                    }
                    val totalDamage = offStat * 1.50
                    val tickDamage = totalDamage / 3.0

                    var ticksRun = 0
                    val task = object : org.bukkit.scheduler.BukkitRunnable() {
                        override fun run() {
                            if (victim.isDead) {
                                cancel()
                                return
                            }
                            victim.noDamageTicks = 0
                            val prevMax = victim.maximumNoDamageTicks
                            victim.maximumNoDamageTicks = 0
                            victim.setMetadata("HJH_MAGIC_DAMAGE", org.bukkit.metadata.FixedMetadataValue(plugin, tickDamage))
                            victim.damage(tickDamage, player)
                            victim.noDamageTicks = 0
                            victim.maximumNoDamageTicks = prevMax
                            victim.world.spawnParticle(org.bukkit.Particle.FLAME, victim.location.add(0.0, 0.5, 0.0), 5, 0.2, 0.2, 0.2, 0.05)
                            ticksRun++
                            if (ticksRun >= 3) {
                                cancel()
                            }
                        }
                    }
                    task.runTaskTimer(plugin, 20L, 20L) // 1秒 1 滴答，共 3 秒
                }
            }
        }

    }

    // 2. 木/土 技能触发 (当玩家受到伤害时调用)
    fun onDamageTaken(player: Player, event: org.bukkit.event.entity.EntityDamageEvent) {
        if (!isCrystalActive(player)) return
        val eData = getData(player.uniqueId)

        // 土元素·启示
        if (eData.earthPoints >= 2) {
            val now = System.currentTimeMillis()
            val cdEnd = earthCd.getOrDefault(player.uniqueId, 0L)
            if (now >= cdEnd) {
                earthCd[player.uniqueId] = now + 12000L // 12秒冷却
                player.sendMessage("§6[土·启示]已触发")
                
                try {
                    player.world.spawnParticle(
                        org.bukkit.Particle.BLOCK,
                        player.location.add(0.0, 1.0, 0.0),
                        15, 0.3, 0.5, 0.3,
                        org.bukkit.Material.DIRT.createBlockData()
                    )
                } catch (e: Exception) {
                    player.world.spawnParticle(org.bukkit.Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 10)
                }

                val pData = plugin.playerManager.getPlayerData(player)
                if (pData != null) {
                    pData.tempBonuses["armor"] = (pData.tempBonuses["armor"] ?: 0.0) + 10.0
                    plugin.playerManager.updateStats(player)

                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        if (player.isOnline) {
                            val currentArmor = pData.tempBonuses["armor"] ?: 0.0
                            pData.tempBonuses["armor"] = (currentArmor - 10.0).coerceAtLeast(0.0)
                            plugin.playerManager.updateStats(player)
                        }
                    }, 120L) // 6秒持续
                }
            }
        }

        // 木元素·启示
        if (eData.woodPoints >= 2) {
            val now = System.currentTimeMillis()
            val cdEnd = woodCd.getOrDefault(player.uniqueId, 0L)
            if (now >= cdEnd) {
                val pData = plugin.playerManager.getPlayerData(player)
                if (pData != null) {
                    val maxHp = pData.maxHealth
                    val currentHp = player.health - event.finalDamage
                    if (currentHp < maxHp * 0.5 && currentHp > 0.0) {
                        woodCd[player.uniqueId] = now + 30000L // 30秒冷却
                        player.sendMessage("§a[木·启示]已触发")
                        player.world.spawnParticle(org.bukkit.Particle.HEART, player.location.add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2, 0.1)

                        val healPerTick = maxHp * (0.20 / 3.0)
                        var ticksRun = 0
                        val task = object : org.bukkit.scheduler.BukkitRunnable() {
                            override fun run() {
                                if (!player.isOnline || player.isDead) {
                                    cancel()
                                    return
                                }
                                val newHp = (player.health + healPerTick).coerceAtMost(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)
                                player.health = newHp
                                pData.currentHealth = newHp
                                player.world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 8, 0.3, 0.5, 0.3, 0.1)
                                ticksRun++
                                if (ticksRun >= 3) {
                                    cancel()
                                }
                            }
                        }
                        task.runTaskTimer(plugin, 20L, 20L) // 1秒 1 滴答，共 3 秒
                    }
                }
            }
        }

    }

    // 3. 水元素技能触发 (释放各类主动技后)
    fun triggerWaterSkill(player: Player, skillType: String, skillId: String, totalCd: Double, material: org.bukkit.Material? = null) {
        if (!isCrystalActive(player)) return
        val eData = getData(player.uniqueId)

        if (skillType == "formation") {
            val pData = plugin.playerManager.getPlayerData(player)
            if (pData?.job == 2) {
                warlockMastery.onFormationCast(player, eData, pData)
            }
        }

        if (eData.waterPoints >= 2) {
            val now = System.currentTimeMillis()
            val cdEnd = waterCd.getOrDefault(player.uniqueId, 0L)
            if (now >= cdEnd) {
                waterCd[player.uniqueId] = now + 10000L // 10秒冷却
                player.sendMessage("§9[水·启示]已触发")
                player.world.spawnParticle(org.bukkit.Particle.SPLASH, player.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.1)

                val refundSeconds = totalCd * 0.15
                if (refundSeconds > 0.0) {
                    when (skillType) {
                        "weapon" -> {
                            plugin.weaponSkillManager.reduceCooldown(player, refundSeconds, material)
                        }
                        "medical" -> {
                            plugin.medicalSpellManager.reduceCooldown(player, skillId, refundSeconds)
                        }
                        "formation" -> {
                            plugin.elementZfManager.reduceCooldown(player, skillId, refundSeconds)
                        }
                    }
                }
            }
        }
    }

    // 校验：结晶是否在饰品栏第一格激活
    fun isCrystalActive(player: Player): Boolean {
        val savedBytes = player.persistentDataContainer.get(
            org.bukkit.NamespacedKey(plugin, "player_accessory_inv"),
            org.bukkit.persistence.PersistentDataType.BYTE_ARRAY
        ) ?: return false
        try {
            java.io.ByteArrayInputStream(savedBytes).use { bais ->
                org.bukkit.util.io.BukkitObjectInputStream(bais).use { ois ->
                    val size = ois.readInt()
                    if (size > 0) {
                        val firstItem = ois.readObject() as? org.bukkit.inventory.ItemStack
                        if (firstItem != null && firstItem.hasItemMeta()) {
                            val meta = firstItem.itemMeta
                            val crystalKey = org.bukkit.NamespacedKey(plugin, "crystal_id")
                            if (meta.persistentDataContainer.has(crystalKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                                val cid = meta.persistentDataContainer.get(crystalKey, org.bukkit.persistence.PersistentDataType.STRING)!!
                                return cid.startsWith("yuansujiejing")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return false
    }
}
