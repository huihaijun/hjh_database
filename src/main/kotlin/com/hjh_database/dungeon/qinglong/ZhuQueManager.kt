package com.hjh_database.dungeon.zhuque

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class ZhuQueManager(private val plugin: Hjh_database) : Listener {

    var isDungeonActive = false
    var isStarting = false
    var boss: Phantom? = null
    var mainBossBar: BossBar? = null
    var timeBossBar: BossBar? = null
    var skillBar: BossBar? = null
    var skill2Bar: BossBar? = null // 新增：炎兵助阵倒计时Bar

    // 副本流程控制
    var mainTask: BukkitTask? = null
    var bossAiTask: BukkitTask? = null
    var dungeonTimeLeft = 300 // 副本总时间：默认5分钟，你可以在这里修改
    val arenaCenter = Location(Bukkit.getWorlds()[0], 2471.5, 34.0, 38.5)

    // 技能状态
    var isSkill1Active = false
    var isSkill2Active = false // 新增：技能2进行中状态
    var yanbingList = mutableListOf<LivingEntity>()
    var skill1CdTicks = 240 // 开局12秒后放技能1 (12*20)
    var skill2CdTicks = 100 // 开局5秒后放技能2 (5*20)

    @EventHandler
    fun onTriggerClick(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        if (e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        val block = e.clickedBlock ?: return
        if (block.type != Material.SOUL_LANTERN) return

        val pLoc = e.player.location
        if (pLoc.x !in 2470.0..2472.0 || pLoc.z !in 58.0..59.0) return

        e.isCancelled = true

        if (isDungeonActive || isStarting) {
            e.player.sendMessage("§c朱雀试炼副本正在进行或准备中，无法重复开启！")
            return
        }

        val world = block.world
        val playersInArea = world.players.filter { p ->
            val loc = p.location
            loc.x in 2470.0..2472.0 && loc.y in 31.0..34.0 && loc.z in 58.0..59.0
        }

        if (playersInArea.isEmpty()) return

        isStarting = true

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val eligiblePlayers = mutableListOf<Player>()
            var blocked = false
            try {
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    val sql = "SELECT zhuque FROM player_test WHERE uuid = ?"
                    conn.prepareStatement(sql).use { ps ->
                        for (p in playersInArea) {
                            ps.setString(1, p.uniqueId.toString())
                            val rs = ps.executeQuery()
                            if (rs.next() && rs.getInt("zhuque") == 1) {
                                blocked = true
                                break
                            }
                            eligiblePlayers.add(p)
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (blocked) {
                    e.player.sendMessage("§c区域内存在已经完成过朱雀试炼的玩家，无法开启！")
                    isStarting = false
                    return@Runnable
                }

                val selected = eligiblePlayers.shuffled().take(2)
                if (selected.isEmpty()) {
                    isStarting = false
                    return@Runnable
                }

                isStarting = false
                startDungeon(selected)
            })
        })
    }

    private fun startDungeon(players: List<Player>) {
        isDungeonActive = true
        dungeonTimeLeft = 300
        yanbingList.clear()
        isSkill1Active = false
        isSkill2Active = false
        skill1CdTicks = 240
        skill2CdTicks = 100
        arenaCenter.world = players[0].world

        val spawnLoc = Location(arenaCenter.world, 2471.5, 34.0, 38.5)

        players.forEach { p ->
            p.addScoreboardTag("zhuque_trial")
            p.teleport(spawnLoc)
        }

        val dialogues = listOf(
            "§f你们来了……",
            "§f这里是很热，朱雀大人司掌南方沙漠，酷热的环境也让他赋予了这片大地生机",
            "§f朱雀喜欢旺盛的§b生命§f，向朱雀大人展示你们顽强的生命，就可以通过他的考验",
            "§f听！那是经常伴飞与朱雀大人的侍卫的声音！",
            "§f朱雀大人的侍卫生命力同样旺盛，你们估计打不败他，坚持一定时间向他证明你们的生命力，也一样可以过关！",
            "§c抬头！他来了……祝你们好运"
        )

        var step = 0
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                if (step < dialogues.size) {
                    players.forEach { p ->
                        p.sendMessage("§a§l朱雀分魂：")
                        p.sendMessage("- ${dialogues[step]}")
                        if (step == 3) {
                            p.playSound(p.location, Sound.ENTITY_PHANTOM_SWOOP, 1.5f, 1.2f) // 凤凰(幻翼)的鸣叫
                        } else {
                            p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f) // 叮
                        }
                    }
                    step++
                } else {
                    spawnBoss(arenaCenter.world!!)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 60L, 60L) // 3秒一句
    }

    private fun spawnBoss(world: World) {
        val bossSpawnLoc = Location(world, 2471.5, 45.0, 38.5)

        // 登场特效
        world.spawnParticle(Particle.FLAME, bossSpawnLoc, 150, 2.0, 2.0, 2.0, 0.1)
        world.playSound(bossSpawnLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1.5f) // 高音龙吼模拟凤凰

        val spawnedEntity = com.hjh_database.spawner.MobFactory.spawnMob(plugin, bossSpawnLoc, "zhuqueshiwei")
        if (spawnedEntity !is Phantom) {
            plugin.logger.warning("朱雀侍卫生成失败，请检查 MobRegistry 是否配置为 PHANTOM！")
            return
        }
        boss = spawnedEntity

        // 1.21.3 放大体型、防阳光燃烧、设置攻击力和血量
        boss!!.getAttribute(Attribute.SCALE)?.baseValue = 4.0
        boss!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 30000.0
        boss!!.health = 30000.0
        boss!!.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 15.0
        boss!!.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, Int.MAX_VALUE, 0, false, false))

        mainBossBar = Bukkit.createBossBar("§c§l朱雀侍卫", BarColor.RED, BarStyle.SOLID)
        timeBossBar = Bukkit.createBossBar("§e副本剩余时间: $dungeonTimeLeft 秒", BarColor.YELLOW, BarStyle.SEGMENTED_12)
        val players = getDungeonPlayers()
        players.forEach { p ->
            mainBossBar?.addPlayer(p)
            timeBossBar?.addPlayer(p)
            p.playSound(p.location, "hjh:bgm_zhuque", SoundCategory.RECORDS, 1f, 1f) // 播放BGM
        }

        // --- Boss 飞行控制 AI (每2Tick检测) ---
        bossAiTask = object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive || boss == null || boss!!.isDead) return
                val loc = boss!!.location
                // 1. 限制高度
                if (loc.y > 45.0) {
                    boss!!.velocity = boss!!.velocity.setY(-0.3)
                }
                // 2. 限制超出副本范围 (半径17格)
                val flatLoc = loc.clone().apply { y = arenaCenter.y }
                if (flatLoc.distance(arenaCenter) > 17.0) {
                    val dir = arenaCenter.toVector().subtract(loc.toVector()).setY(0).normalize().multiply(0.4)
                    boss!!.velocity = boss!!.velocity.add(dir)
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)

        // --- 主控计时器 (每 Tick 运行) ---
        mainTask = object : BukkitRunnable() {
            var tickCount = 0
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                tickCount++

                // 血条更新
                val currentHp = boss!!.health
                val maxHp = boss!!.getAttribute(Attribute.MAX_HEALTH)!!.value
                mainBossBar?.progress = (currentHp / maxHp).coerceIn(0.0, 1.0)

                // 死亡判定
                val activePlayers = getDungeonPlayers()
                if (activePlayers.isEmpty()) {
                    endDungeon(false)
                    return
                }

                // 技能 1 冷却与触发
                if (!isSkill1Active) {
                    skill1CdTicks--
                    if (skill1CdTicks <= 0) {
                        castSkill1(activePlayers)
                    }
                }

                // 技能 2 冷却与触发 (优化)
                if (!isSkill2Active) {
                    skill2CdTicks--
                    if (skill2CdTicks <= 0) {
                        castSkill2(activePlayers)
                    }
                }

                // 每秒倒计时扣除
                if (tickCount % 20 == 0) {
                    dungeonTimeLeft--
                    timeBossBar?.setTitle("§e副本剩余时间: $dungeonTimeLeft 秒")
                    timeBossBar?.progress = (dungeonTimeLeft / 300.0).coerceIn(0.0, 1.0)

                    if (dungeonTimeLeft <= 0) {
                        endDungeon(true)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // ======================================
    // 技能 1：大漠炎海
    // ======================================
    private fun castSkill1(players: List<Player>) {
        isSkill1Active = true
        players.forEach { p ->
            p.sendMessage("§c朱雀侍卫：炎海翻涌！")
            p.sendMessage("§6朱雀侍卫即将在场地内随机召唤炎海，请尽快逃离圆圈范围！")
            p.playSound(p.location, Sound.ENTITY_GHAST_WARN, 1f, 1f)
        }

        skillBar = Bukkit.createBossBar("§c大漠炎海 - 准备发动", BarColor.RED, BarStyle.SOLID)
        players.forEach { skillBar?.addPlayer(it) }

        // 3秒前摇
        var chantTicks = 60
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                chantTicks -= 2
                skillBar?.progress = (chantTicks / 60.0).coerceIn(0.0, 1.0)
                if (chantTicks <= 0) {
                    executeFlameSeaRound(1, players)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun executeFlameSeaRound(round: Int, players: List<Player>) {
        if (!isDungeonActive) return
        if (round > 3) {
            // 三轮结束，结算奖励
            var bonusGiven = false
            players.forEach { p ->
                val hpPercent = p.health / (p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
                if (hpPercent > 0.8) {
                    bonusGiven = true
                }
            }
            if (bonusGiven) {
                dungeonTimeLeft = (dungeonTimeLeft - 30).coerceAtLeast(0)
                players.forEach { it.sendMessage("§a你们的生命力十分充沛！副本剩余时间减少 30 秒！") }
            }

            skillBar?.removeAll()
            skillBar = null
            isSkill1Active = false
            skill1CdTicks = 260 // 结束后进入固定 13 秒 CD
            return
        }

        skillBar?.setTitle("§c大漠炎海 - 第 $round 轮迸发准备")

        val circles = mutableListOf<Location>()
        val activePlayers = getDungeonPlayers()

        if (activePlayers.isNotEmpty()) {
            activePlayers.forEach { p ->
                circles.add(p.location.clone().apply { y = arenaCenter.y + 0.5 })
            }
        }

        val remainingCount = 5 - circles.size
        for (i in 0 until remainingCount) {
            val r = kotlin.math.sqrt(Random().nextDouble()) * 16.0
            val angle = Random().nextDouble() * 2 * Math.PI
            val randomLoc = arenaCenter.clone().add(r * cos(angle), 0.5, r * sin(angle))
            circles.add(randomLoc)
        }

        // 5秒生成圈，然后爆炸
        var delayTicks = 100
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                delayTicks -= 2
                skillBar?.progress = (delayTicks / 100.0).coerceIn(0.0, 1.0)

                val world = arenaCenter.world!!
                circles.forEach { center ->
                    for (i in 0 until 360 step 15) {
                        val rad = Math.toRadians(i.toDouble())
                        val x = cos(rad) * 5.0
                        val z = sin(rad) * 5.0
                        world.spawnParticle(Particle.FLAME, center.clone().add(x, 0.0, z), 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }

                if (delayTicks <= 0) {
                    circles.forEach { center ->
                        world.spawnParticle(Particle.LAVA, center, 100, 4.0, 1.0, 4.0, 0.1)
                        world.playSound(center, Sound.ENTITY_GENERIC_BURN, 1f, 1f)
                    }
                    getDungeonPlayers().forEach { p ->
                        for (center in circles) {
                            if (p.location.apply{y = center.y}.distance(center) <= 5.0) {
                                p.damage(20.0) // 真实伤害
                                p.sendMessage("§4你受到了大漠炎海的灼烧！")
                                break
                            }
                        }
                    }
                    executeFlameSeaRound(round + 1, players)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // ======================================
    // 技能 2：炎兵助阵 (全新机制重做)
    // ======================================
    private fun castSkill2(players: List<Player>) {
        isSkill2Active = true
        skill2Bar = Bukkit.createBossBar("§c炎兵助阵 - 倒计时", BarColor.RED, BarStyle.SEGMENTED_20)

        players.forEach { p ->
            skill2Bar?.addPlayer(p)
            p.sendMessage("§c朱雀侍卫：炎兵助我！")
            p.sendMessage("§6朱雀侍卫召唤了炎兵协助作战，30秒内未击杀将受到时间惩罚！杀死他们能加快通关速度！")
            p.playSound(p.location, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.5f)
        }

        val spawnCoords = listOf(
            Location(arenaCenter.world, 2480.5, 33.0, 29.5),
            Location(arenaCenter.world, 2483.5, 33.0, 38.5),
            Location(arenaCenter.world, 2480.5, 33.0, 37.5),
            Location(arenaCenter.world, 2471.5, 33.0, 49.5),
            Location(arenaCenter.world, 2462.5, 33.0, 47.5),
            Location(arenaCenter.world, 2459.5, 33.0, 38.5),
            Location(arenaCenter.world, 2462.5, 33.0, 29.5),
            Location(arenaCenter.world, 2471.5, 33.0, 26.5)
        )

        var typeToggle = 0
        spawnCoords.forEach { loc ->
            val mobId = if (typeToggle == 0) "zhuque_yanbing_zombie" else "zhuque_yanbing_kulou"
            loc.world?.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 30, 0.5, 1.0, 0.5, 0.05)
            val mob = com.hjh_database.spawner.MobFactory.spawnMob(plugin, loc, mobId)
            if (mob != null) {
                mob.scoreboardTags.add("zhuque_mob")
                yanbingList.add(mob)
            }
            typeToggle = 1 - typeToggle
        }

        // 30秒倒计时任务
        var timerTicks = 600
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) {
                    skill2Bar?.removeAll()
                    cancel()
                    return
                }

                // 随时清理意外死亡的炎兵实体以防卡死机制
                yanbingList.removeAll { it.isDead || !it.isValid }

                // 情况1：玩家在30秒内全部杀光了炎兵
                if (yanbingList.isEmpty()) {
                    isSkill2Active = false
                    skill2CdTicks = 200 // 重置10秒CD
                    skill2Bar?.removeAll()
                    cancel()
                    return
                }

                timerTicks -= 2
                skill2Bar?.progress = (timerTicks / 600.0).coerceIn(0.0, 1.0)

                // 情况2：30秒倒计时结束
                if (timerTicks <= 0) {
                    val remainCount = yanbingList.size
                    val addedTime = remainCount * 10
                    dungeonTimeLeft += addedTime // 每个炎兵增加剩余时间（延长通关难度）

                    getDungeonPlayers().forEach { p ->
                        p.sendMessage("§c朱雀侍卫吸收了剩余的 $remainCount 名炎兵！副本通关时间增加了 $addedTime 秒！")
                        p.playSound(p.location, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 1.2f)
                    }

                    // 献祭特效清空场上剩余炎兵
                    yanbingList.forEach { mob ->
                        val loc = mob.location.add(0.0, 1.0, 0.0)
                        mob.world.spawnParticle(Particle.FLAME, loc, 50, 0.5, 1.0, 0.5, 0.1)
                        mob.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 30, 0.5, 1.0, 0.5, 0.1)
                        mob.world.playSound(loc, Sound.ENTITY_BLAZE_DEATH, 1f, 1f)
                        mob.remove()
                    }
                    yanbingList.clear()

                    isSkill2Active = false
                    skill2CdTicks = 200 // 献祭结束后10秒CD
                    skill2Bar?.removeAll()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // ======================================
    // 事件监听与结算
    // ======================================

    @EventHandler
    fun onMobDeath(e: EntityDeathEvent) {
        if (!isDungeonActive) return
        val entity = e.entity

        if (entity == boss) {
            endDungeon(true)
            return
        }

        if (entity.scoreboardTags.contains("zhuque_mob") && yanbingList.contains(entity)) {
            yanbingList.remove(entity)
            dungeonTimeLeft = (dungeonTimeLeft - 5).coerceAtLeast(0)
            getDungeonPlayers().forEach { p ->
                p.sendMessage("§a你击杀了一名炎兵！剩余时间减少 5 秒！")
            }
            if (dungeonTimeLeft <= 0) {
                endDungeon(true)
            }
        }
    }

    @EventHandler
    fun onPlayerDeath(e: PlayerDeathEvent) {
        val p = e.entity
        if (p.scoreboardTags.contains("zhuque_trial")) {
            p.removeScoreboardTag("zhuque_trial") // 死亡时立刻清Tag
            p.stopSound("hjh:bgm_zhuque", SoundCategory.RECORDS) // 死亡时立刻切断BGM
            p.sendMessage("§7你在秘境中死亡或逃跑，已将你移入奈何桥……")
        }
    }

    @EventHandler
    fun onPlayerQuit(e: PlayerQuitEvent) {
        val p = e.player
        if (p.scoreboardTags.contains("zhuque_trial")) {
            p.health = 0.0 // 强制处死
            p.removeScoreboardTag("zhuque_trial") // 退服时立刻清Tag
        }
    }

    private fun endDungeon(win: Boolean) {
        if (!isDungeonActive) return
        isDungeonActive = false

        mainTask?.cancel()
        bossAiTask?.cancel()
        mainBossBar?.removeAll()
        timeBossBar?.removeAll()
        skillBar?.removeAll()
        skill2Bar?.removeAll() // 新增：清理技能2BossBar

        yanbingList.forEach { if (!it.isDead) it.remove() }
        yanbingList.clear()

        // 提取所有副本内玩家
        val taggedPlayers = Bukkit.getOnlinePlayers().filter { it.scoreboardTags.contains("zhuque_trial") }

        // ====== 优化: 无论成败，立刻切断BGM ======
        taggedPlayers.forEach { p ->
            p.stopSound("hjh:bgm_zhuque", SoundCategory.RECORDS)
        }

        if (win) {
            boss?.remove()
            taggedPlayers.forEach { p ->
                p.sendMessage("§a§l朱雀分魂：太好了！你们的生命力很顽强！你们过关了！")
                p.playSound(p.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                p.removeScoreboardTag("zhuque_trial") // ====== 优化: 结算立刻移除Tag ======
            }

            val keyItem = plugin.resourceManager.getItem("mijingyaoshi")

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val rewardLoc = Location(arenaCenter.world, 2494.5, 33.0, 39.5)

                taggedPlayers.forEach { p ->
                    if (!p.isOnline) return@forEach // 防止离线玩家报错

                    if (keyItem != null) {
                        val clonedKey = keyItem.clone()
                        clonedKey.amount = 1
                        p.inventory.addItem(clonedKey)
                        p.sendMessage("§e[奖励] §a恭喜通关！获得了 §f秘境钥匙 §ax1！")
                    }

                    val data = plugin.playerManager.getData(p.uniqueId)
                    if (data != null) {
                        val record = data.dungeonRecords.getOrPut("zhuque_test") { DungeonRecord() }
                        record.clears += 1
                        record.availableOpens += 1

                        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                            plugin.databaseManager.savePlayer(data)
                        })
                        p.sendMessage("§e[秘境] §a朱雀试炼通关记录 +1，金宝箱可开箱次数 +1！")
                    }

                    p.teleport(rewardLoc)
                    // Tag已经在上方移除，确保不会因延时任务被跳过
                    updateDbStateAsync(p.uniqueId, "zhuque", 1)
                }
            }, 60L) // 3秒后传送
        } else {
            // 失败结局
            boss?.remove()
            taggedPlayers.forEach { p ->
                p.playSound(p.location, Sound.ENTITY_ENDER_DRAGON_DEATH, 0.5f, 0.5f)
                p.removeScoreboardTag("zhuque_trial") // ====== 优化: 结算立刻移除Tag ======
            }
        }
    }

    private fun getDungeonPlayers(): List<Player> {
        return arenaCenter.world!!.getNearbyEntities(arenaCenter, 25.0, 25.0, 25.0)
            .filterIsInstance<Player>()
            .filter { it.scoreboardTags.contains("zhuque_trial") }
    }

    private fun updateDbStateAsync(uuid: UUID, column: String, value: Int) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    val sql = """
                        INSERT INTO player_test (uuid, player_name, $column) 
                        VALUES (?, ?, ?) 
                        ON CONFLICT(uuid) DO UPDATE SET $column = ?
                    """.trimIndent()
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.setString(2, Bukkit.getOfflinePlayer(uuid).name ?: "Unknown")
                        ps.setInt(3, value)
                        ps.setInt(4, value)
                        ps.executeUpdate()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }
}