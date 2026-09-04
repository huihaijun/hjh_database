package com.hjh_database.dungeon.qinglong

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.*
import kotlin.math.abs

data class TriviaQuestion(
    val question: String, val optionA: String, val optionB: String, val optionC: String, val optionD: String, val answer: String
)

class QingLongManager(private val plugin: Hjh_database) : Listener {

    var isDungeonActive = false
    var isStarting = false // 【新增】用于锁定异步查询期间的状态
    var boss: Zombie? = null
    var mainBossBar: BossBar? = null
    var skillBar: BossBar? = null
    val activeHolograms = mutableListOf<ArmorStand>()

    var loopTask: BukkitTask? = null
    var checkTask: BukkitTask? = null

    private val allQuestions = mutableListOf<TriviaQuestion>()
    private val remainingQuestions = mutableListOf<TriviaQuestion>()

    init {
        loadQuestions()
    }

    private fun loadQuestions() {
        // 1. 定义运行时的文件路径 (plugins/Hjh_database/dungeon/qinglong/questions.yml)
        val file = File(plugin.dataFolder, "dungeon/qinglong/questions.yml")
        // 2. 如果运行目录不存在该文件，则从 jar 包的 resources 目录下自动释放出来
        if (!file.exists()) {
            file.parentFile.mkdirs()
            try {
                // saveResource 会将 resources/dungeon/qinglong/questions.yml 复制到插件目录下
                plugin.saveResource("dungeon/qinglong/questions.yml", false)
                plugin.logger.info("已自动生成青龙题库配置文件: questions.yml")
            } catch (e: Exception) {
                plugin.logger.severe("无法释放 questions.yml！请检查 resources 目录下是否有该文件。")
                return
            }
        }

        // 3. 读取 YML 配置
        val config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        val questionSection = config.getConfigurationSection("questions")

        if (questionSection == null) {
            plugin.logger.warning("questions.yml 格式错误：找不到 'questions' 节点！")
            return
        }

        // 4. 解析题目存入列表
        allQuestions.clear()
        for (key in questionSection.getKeys(false)) {
            val qNode = questionSection.getConfigurationSection(key) ?: continue
            val questionStr = qNode.getString("question") ?: continue
            val a = qNode.getString("A") ?: ""
            val b = qNode.getString("B") ?: ""
            val c = qNode.getString("C") ?: ""
            val d = qNode.getString("D") ?: ""
            val ans = qNode.getString("answer") ?: "A"

            allQuestions.add(TriviaQuestion(questionStr, a, b, c, d, ans.uppercase()))
        }

        remainingQuestions.clear()
        remainingQuestions.addAll(allQuestions)
        plugin.logger.info("成功加载了 ${allQuestions.size} 道青龙试炼题目！")
    }

    @EventHandler
    fun onTriggerClick(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return

        // 【新增 1】防止主副手重复触发！只处理主手的交互
        if (e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        val block = e.clickedBlock ?: return
        if (block.type != Material.SOUL_LANTERN) return

        // 仅在指定区域内右键触发
        val pLoc = e.player.location
        if (pLoc.x !in 1696.0..1698.0 || pLoc.z !in 172.0..715.0) return

        e.isCancelled = true

        // 【新增 2】同时拦截“正在进行”和“正在读取准备”的状态
        if (isDungeonActive || isStarting) {
            e.player.sendMessage("§c青龙试炼副本正在进行或准备中，无法重复开启！")
            return
        }

        val world = block.world
        val minX = 1696.0; val maxX = 1698.0
        val minY = 115.0; val maxY = 118.0
        val minZ = 172.0; val maxZ = 715.0

        val playersInArea = world.players.filter { p ->
            val loc = p.location
            loc.x in minX..maxX && loc.y in minY..maxY && loc.z in minZ..maxZ
        }

        if (playersInArea.isEmpty()) return

        // 【新增 3】立即上锁！在异步查询前就把门关死，防止并发点击
        isStarting = true

        // 异步检查数据库，防止卡顿主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val eligiblePlayers = mutableListOf<Player>()
            var blocked = false
            try {
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    val sql = "SELECT qinglong FROM player_test WHERE uuid = ?"
                    conn.prepareStatement(sql).use { ps ->
                        for (p in playersInArea) {
                            ps.setString(1, p.uniqueId.toString())
                            val rs = ps.executeQuery()
                            if (rs.next() && rs.getInt("qinglong") == 1) {
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

            // 回到主线程处理结果
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (blocked) {
                    e.player.sendMessage("§c区域内存在已经完成过青龙试炼的玩家，无法开启！")
                    isStarting = false // 【解锁】条件不满足，重新开放点击
                    return@Runnable
                }

                val selected = eligiblePlayers.shuffled().take(2)
                if (selected.isEmpty()) {
                    isStarting = false // 【解锁】没有合适的玩家，重新开放点击
                    return@Runnable
                }

                // 成功通过所有校验，解除准备锁，并由 startDungeon 接管 isDungeonActive 状态
                isStarting = false
                startDungeon(selected)
            })
        })
    }

    private fun startDungeon(players: List<Player>) {
        isDungeonActive = true
        val world = players[0].world
        val spawnLoc = Location(world, 1142.5, 86.0, 1727.5)

        players.forEach { p ->
            p.addScoreboardTag("qinglong_trial")
            p.teleport(spawnLoc)
        }

        val dialogues = listOf(
            "§f嗯……这个地方好像和你们进入祭坛的地方极为类似",
            "§f青龙大人曾凭借自身的圣力开辟出了这样一块空间以供他调养生息",
            "§f估计他也感受到了结界的减弱，还派出了自己的侍卫保护他",
            "§f青龙代表着§b智慧§f与§b敏锐§f，希望你们可以通过青龙大人的考验，探明结界减弱的真实原因……",
            "§c小心，青龙大人的侍卫来了……"
        )

        var step = 0
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                if (step < dialogues.size) {
                    players.forEach { p ->
                        p.sendMessage("§a§l青龙分魂：")
                        p.sendMessage("- ${dialogues[step]}")
                    }
                    step++
                } else {
                    spawnBoss(world)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 60L, 60L)
    }

    private fun spawnBoss(world: org.bukkit.World) {
        val loc = Location(world, 1155.5, 85.0, 1727.5, 90f, 0f)
        // 【优化2】Boss 登场特效：附魔台神秘字符 + 传送门粒子 + 星光点点，取代大爆炸
        val effectLoc = loc.clone().add(0.0, 1.0, 0.0)
        world.spawnParticle(org.bukkit.Particle.ENCHANT, effectLoc, 100, 0.5, 1.0, 0.5, 1.0)
        world.spawnParticle(org.bukkit.Particle.PORTAL, effectLoc, 80, 0.5, 1.0, 0.5, 0.5)
        world.spawnParticle(org.bukkit.Particle.END_ROD, effectLoc, 40, 0.5, 1.0, 0.5, 0.05)

        world.playSound(loc, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1f) // 龙吼音效
        world.playSound(loc, org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.5f, 0.8f) // 附魔翻书的神秘音效
        // 【修改点】调用你自己的 MobFactory 统一生成怪物
        val spawnedEntity = com.hjh_database.spawner.MobFactory.spawnMob(plugin, loc, "qinglongshiwei")

        if (spawnedEntity !is Zombie) {
            plugin.logger.warning("青龙侍卫生成失败或类型错误，请检查 MobRegistry 配置！")
            return
        }

        boss = spawnedEntity

        // 保证是成年僵尸 (通常默认就是成年的，安全起见加一句)
        boss!!.setAdult() // 直接调用方法，将其设置为成年

        // 创建顶部 BossBar
        mainBossBar = Bukkit.createBossBar("§a§l青龙侍卫", BarColor.GREEN, BarStyle.SOLID)
        val players = getDungeonPlayers()
        players.forEach { p ->
            mainBossBar?.addPlayer(p)
            // 【优化4】开始对副本内玩家播放 BGM
            p.playSound(p.location, "hjh:bgm_dragon", org.bukkit.SoundCategory.RECORDS, 4f, 1f)
        }

        // 启动胜负判定与血条刷新器
        checkTask = object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive || boss == null) return
                // 更新 Boss 血条
                val currentHp = boss!!.health
                val maxHp = boss!!.getAttribute(Attribute.MAX_HEALTH)!!.value
                mainBossBar?.progress = (currentHp / maxHp).coerceIn(0.0, 1.0)

                // 检查全灭失败条件
                if (getDungeonPlayers().isEmpty()) {
                    endDungeon(false)
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)

        // 10秒后释放第一次答题技能
        scheduleNextSkill(10 * 20L)
    }

    private fun scheduleNextSkill(delayTicks: Long) {
        loopTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            castTriviaSkill()
        }, delayTicks)
    }

    private fun castTriviaSkill() {
        if (!isDungeonActive) return
        // 抽取题目时的安全判断
        val q = if (allQuestions.isEmpty()) {
            // 如果题库无论如何都是空的，给一个默认的送分题，防止报错崩溃
            TriviaQuestion(
                "【系统警告】题库文件读取失败或为空！请联系管理员。送分题：1+1=?",
                "A. 2", "B. 3", "C. 4", "D. 5", "A"
            )
        } else {
            // 正常抽取逻辑
            if (remainingQuestions.isEmpty()) {
                remainingQuestions.addAll(allQuestions) // 抽完重置
            }
            val qIndex = remainingQuestions.indices.random()
            remainingQuestions.removeAt(qIndex)
        }

        val players = getDungeonPlayers()
        players.forEach { p ->
            p.sendMessage("§c青龙即将对你们的智慧发起考验……")
            // 【优化6】额外弹出技能提示
            p.sendMessage("§6在他出题后,请踩在对应答案的发光块上！")
            p.sendMessage("§6答案的位置与题目所给位置一一对应！在答题区域上空也会悬浮答案！")
        }

        skillBar = Bukkit.createBossBar("§c青龙之试...", BarColor.RED, BarStyle.SOLID)
        players.forEach { skillBar?.addPlayer(it) }

        var ticks = 60 // 3秒前摇
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                ticks -= 2
                skillBar?.progress = (ticks / 60.0).coerceAtLeast(0.0)
                if (ticks <= 0) {
                    skillBar?.removeAll()
                    skillBar = null
                    startAnswerPhase(q, players)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun startAnswerPhase(q: TriviaQuestion, players: List<Player>) {
        players.forEach { p ->
            p.sendMessage("§b--题目：${q.question}")
            p.sendMessage("§b--                ${q.optionA}")
            p.sendMessage("§b--    ${q.optionB}              ${q.optionC}")
            p.sendMessage("§b--                ${q.optionD}")
        }

        val world = boss!!.world
        // 【优化5】将所有选项的 y 坐标从 85.0 抬高到 87.5
        val aLoc = Location(world, 1168.5, 87.5, 1727.5)
        val bLoc = Location(world, 1155.5, 87.5, 1713.5)
        val cLoc = Location(world, 1155.5, 87.5, 1741.5)
        val dLoc = Location(world, 1142.5, 87.5, 1727.5)

        // 【优化5】给悬浮文字增加加粗符号 §l
        activeHolograms.add(spawnHologram(aLoc, "§a§l" + q.optionA))
        activeHolograms.add(spawnHologram(bLoc, "§b§l" + q.optionB))
        activeHolograms.add(spawnHologram(cLoc, "§c§l" + q.optionC))
        activeHolograms.add(spawnHologram(dLoc, "§d§l" + q.optionD))

        skillBar = Bukkit.createBossBar("§c青龙之试 - 倒计时", BarColor.BLUE, BarStyle.SEGMENTED_10)
        players.forEach { skillBar?.addPlayer(it) }

        var ticks = 200 // 10秒答题
        object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive) { cancel(); return }
                ticks -= 2
                skillBar?.progress = (ticks / 200.0).coerceAtLeast(0.0)
                if (ticks <= 0) {
                    skillBar?.removeAll()
                    skillBar = null
                    activeHolograms.forEach { it.remove() }
                    activeHolograms.clear()

                    resolveAnswers(q)
                    cancel()

                    if (isDungeonActive) scheduleNextSkill(15 * 20L) // 15秒循环
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun resolveAnswers(q: TriviaQuestion) {
        if (boss == null || boss!!.isDead) return
        val players = getDungeonPlayers()
        val world = boss!!.world
        val aLoc = Location(world, 1168.5, 85.0, 1727.5)
        val bLoc = Location(world, 1155.5, 85.0, 1713.5)
        val cLoc = Location(world, 1155.5, 85.0, 1741.5)
        val dLoc = Location(world, 1142.5, 85.0, 1727.5)

        var correctCount = 0
        players.forEach { p ->
            val inA = isInSquare(p.location, aLoc)
            val inB = isInSquare(p.location, bLoc)
            val inC = isInSquare(p.location, cLoc)
            val inD = isInSquare(p.location, dLoc)

            val ans = when {
                inA -> "A"; inB -> "B"; inC -> "C"; inD -> "D"
                else -> "None"
            }

            if (ans == q.answer) {
                correctCount++
                p.sendMessage("§a回答正确！青龙侍卫受到了伤害！")
            } else {
                p.damage(15.0) // 15点伤害
                p.sendMessage("§c回答错误或未答题！你受到了伤害！")
            }
        }

        if (correctCount > 0) {
            val dmg = 50.0 * correctCount
            val newHp = boss!!.health - dmg
            // 【优化1】在Boss受击时播放魔法暴击和受击红心粒子，以及受击音效
            val centerLoc = boss!!.location.add(0.0, 1.0, 0.0)
            world.spawnParticle(org.bukkit.Particle.DAMAGE_INDICATOR, centerLoc, 15, 0.5, 0.8, 0.5, 0.1)
            world.spawnParticle(org.bukkit.Particle.CRIT, centerLoc, 30, 0.5, 0.8, 0.5, 0.1)
            world.playSound(boss!!.location, org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f, 1.2f) // 沉闷的打击声
            if (newHp <= 0) {
                boss!!.health = 0.0
                // 会自然触发死亡事件，通过 onBossDeath 进行最终结算
            } else {
                boss!!.health = newHp
                boss!!.damage(0.0001) // 播一下红光受击动画
            }
        }
    }

    @EventHandler
    fun onBossDeath(e: EntityDeathEvent) {
        if (isDungeonActive && e.entity == boss) {
            endDungeon(true)
        }
    }

    private fun endDungeon(win: Boolean) {
        if (!isDungeonActive) return
        isDungeonActive = false

        loopTask?.cancel()
        checkTask?.cancel()
        mainBossBar?.removeAll()
        skillBar?.removeAll()
        activeHolograms.forEach { it.remove() }
        activeHolograms.clear()

        // 获取所有持有 tag 的玩家（哪怕他们死回城了），切断 BGM 并播放过渡音效
        val taggedPlayers = Bukkit.getOnlinePlayers().filter { it.scoreboardTags.contains("qinglong_trial") }
        taggedPlayers.forEach { p ->
            // 切断 BGM
            p.stopSound("hjh:bgm_dragon", org.bukkit.SoundCategory.RECORDS)
            p.stopSound("hjh:bgm_dragon")

            // 【优化3】根据胜负播放不同的落幕音效，完美掩盖 BGM 戛然而止的突兀感
            if (win) {
                // 原版完成进度/挑战时的激昂号角声，非常有胜利的仪式感
                p.playSound(p.location, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            } else {
                // 失败时的落幕：一声悲鸣的低沉龙死声音
                p.playSound(p.location, org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 0.5f, 0.5f)
            }
        }

        val players = getDungeonPlayers()

        if (win) {
            boss?.remove()
            players.forEach {
                it.sendMessage("§c青龙侍卫：§f呃啊……")
                it.sendMessage("§a§l青龙分魂：§f侍卫化作青烟消失了！你们通过了§b青龙大人§f的考验！")
            }
            // 【新增】提前通过 ResourceManager 获取“秘境钥匙”物品模板
            val keyItem = plugin.resourceManager.getItem("mijingyaoshi")
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val rewardLoc = Location(players.firstOrNull()?.world, 2120.5, 59.0, 40.5)
                players.forEach { p ->
                    // --- 1. 发放实体物品奖励 ---
                    if (keyItem != null) {
                        val clonedKey = keyItem.clone()
                        clonedKey.amount = 1
                        p.inventory.addItem(clonedKey)
                        p.sendMessage("§a§l青龙分魂：§f这把钥匙你且收下，作为青龙大人给你的奖赏！")
                    } else {
                        plugin.logger.warning("未能找到 ID 为 mijingyaoshi 的物品，无法发放奖励！请检查 Resource 配置。")
                    }

                    // --- 2. 增加副本通关数据与可开箱次数 (dragon_test) ---
                    val data = plugin.playerManager.getData(p.uniqueId)
                    if (data != null) {
                        // 如果你的变量名不同（比如叫 dungeonData 等），请自行修改下面的变量名
                        val record = data.dungeonRecords.getOrPut("dragon_test") { DungeonRecord() }
                        record.clears += 1
                        record.availableOpens += 1
                        // 异步保存玩家整体数据 (或者调用你专门的 saveDungeonData 方法)
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                            plugin.databaseManager.savePlayer(data)
                        })
                    }

                    p.teleport(rewardLoc)
                    p.removeScoreboardTag("qinglong_trial")
                    updateDbStateAsync(p.uniqueId, "qinglong", 1)
                }
            }, 60L) // 3秒延迟
        } else {
            boss?.remove()
            players.forEach { it.removeScoreboardTag("qinglong_trial") }
        }
    }

    private fun getDungeonPlayers(): List<Player> {
        val center = Location(Bukkit.getWorlds()[0], 1155.5, 85.0, 1727.5) // 使用通用检测范围
        boss?.let { center.world = it.world }
        return center.world!!.getNearbyEntities(center, 50.0, 50.0, 50.0)
            .filterIsInstance<Player>()
            .filter { it.scoreboardTags.contains("qinglong_trial") }
    }

    private fun spawnHologram(loc: Location, text: String): ArmorStand {
        return loc.world!!.spawn(loc, ArmorStand::class.java) { ast ->
            ast.isMarker = true
            ast.isVisible = false
            ast.customName = text
            ast.isCustomNameVisible = true
            ast.setGravity(false)
        }
    }

    private fun isInSquare(pLoc: Location, center: Location): Boolean {
        // 边长3格正方形即 x、z 到中心距离均<=1.5
        return abs(pLoc.x - center.x) <= 1.5 && abs(pLoc.z - center.z) <= 1.5 && abs(pLoc.y - center.y) <= 4.0 // 允许一定的跳跃高度
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

    // 【优化4】玩家如果战死，立刻停止播放 BGM，并剥夺 Tag 踢出副本队列
    @EventHandler
    fun onDungeonPlayerDeath(e: org.bukkit.event.entity.PlayerDeathEvent) {
        val p = e.entity
        if (p.scoreboardTags.contains("qinglong_trial")) {
            p.stopSound("hjh:bgm_dragon", org.bukkit.SoundCategory.RECORDS)
            p.stopSound("hjh:bgm_dragon")
            p.removeScoreboardTag("qinglong_trial")
            // 剥夺 tag 后，checkTask 会发现副本内玩家变少，如果是0个则会自动触发失败结束副本
        }
    }
}