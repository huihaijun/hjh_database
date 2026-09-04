package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.scheduler.BukkitRunnable

class ShanShenMiaoTrial(
    private val plugin: Hjh_database,
    override val player: Player
) : BukkitRunnable(), MedicalTrial {

    override val trialId = "shanshenmiao"

    private var tick = 0
    private var phase = 0 // 0: 剧情, 1: 战斗
    private var tributes = 20
    private var lastStealTime = 0L
    private val activeMobs = mutableListOf<Mob>()
    private var miaoGong: Villager? = null
    // 【新增】用于控制第一波的音效，以及随机刷怪的下一个目标 tick
    private var isFirstWave = true
    private var nextSpawnTick = 0

    private val timeBar: BossBar = Bukkit.createBossBar("§e上供仪式倒计时", BarColor.YELLOW, BarStyle.SOLID)
    private val tributeBar: BossBar = Bukkit.createBossBar("§c剩余贡品数量: 20/20", BarColor.RED, BarStyle.SEGMENTED_20)

    private val spawnLoc = Location(player.world, 852.5, 44.0, 99.5)
    private val miaoGongLoc = Location(player.world, 841.5, 41.0, 104.5, 90.90f, 9.30f)
    private val winLoc = Location(player.world, 834.31, 40.00, 109.47, 301.80f, -0.3f)

    private val storyMessages = listOf(
        "§f难得有个医师愿意来帮衬这上供仪式，老夫甚是欣慰啊……",
        "§f这庙虽破，老夫守着不走，图的不就是个风调雨顺、国泰民安么？",
        "§f可这些魔物三天两头来搅扰，害得老夫难以诚心供奉……",
        "§f好在你来了。待会儿老夫便开始上供，那些魔物§b不会冲你§f来，他们是奔着§b贡品§f来的。",
        "§f你得§b守住§f，莫让他们§b靠近§f。每被抢走一个贡品，山神便会§c降下神罚§f，你我难以承受。若咱俩提前倒下，这仪式也就断了。",
        "§f撑过§b半炷香§f的功夫，只要还剩§b五个§f以上贡品，山神一高兴，咱俩都有§b重赏§f。",
        "§f老夫也把私藏的§b医术§f传授于你。",
        "§f好了，准备妥当。老夫这便开始了——",
        "§e“谨奉清香，上达穹苍，山神鉴之，保境安康……”"
    )

    override fun start() {
        player.teleport(Location(player.world, 842.44, 41.00, 105.06, 268.95f, 0.4f))

        miaoGong = player.world.spawn(miaoGongLoc, Villager::class.java) { v ->
            v.customName = "§a§l上供中的庙公"
            v.isCustomNameVisible = true
            v.setAI(false)
            v.isInvulnerable = true
            v.setGravity(false)
        }
        isolateEntity(miaoGong!!)

        timeBar.addPlayer(player)
        tributeBar.addPlayer(player)

        this.runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        if (phase == 0) {
            if (tick % 60 == 0) {
                val msgIndex = tick / 60
                if (msgIndex < storyMessages.size) {
                    player.sendMessage("§a§l山神庙庙公 §f: ${storyMessages[msgIndex]}")
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                } else {
                    phase = 1
                    tick = 0
                    // 剧情结束瞬间刷第一波，并设定下一次刷怪在 5~8 秒后
                    spawnWave()
                    nextSpawnTick = kotlin.random.Random.nextInt(100, 161)
                }
            }
        } else if (phase == 1) {
            val secondsPassed = tick / 20
            val secondsLeft = 30 - secondsPassed

            timeBar.progress = (secondsLeft / 30.0).coerceIn(0.0, 1.0)
            timeBar.setTitle("§e上供仪式倒计时: ${secondsLeft}秒")
            tributeBar.progress = (tributes / 20.0).coerceIn(0.0, 1.0)
            tributeBar.setTitle("§c剩余贡品数量: $tributes/20")

            // 【修改点】当到达随机设定的 tick 时刷怪，并重新抽取下一个 5~8 秒
            if (tick >= nextSpawnTick && secondsPassed < 30) {
                spawnWave()
                nextSpawnTick = tick + kotlin.random.Random.nextInt(100, 161)
            }

            val iterator = activeMobs.iterator()
            while (iterator.hasNext()) {
                val mob = iterator.next()
                if (mob.isDead) {
                    iterator.remove()
                    continue
                }

                if (tick % 10 == 0) {
                    // 【修改点】将速度倍率从 1.2 降为 1.0，恢复原版标准移速
                    mob.pathfinder.moveTo(miaoGongLoc, 1.0)
                    mob.target = null
                }

                if (mob.location.distanceSquared(miaoGongLoc) < 1.5 * 1.5) {
                    mob.remove()
                    iterator.remove()

                    if (System.currentTimeMillis() - lastStealTime >= 3000) {
                        tributes--
                        lastStealTime = System.currentTimeMillis()
                        player.sendMessage("§c怪物偷走了1个贡品！")
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)

                        player.damage(8.0)
                    }
                }
            }

            if (secondsLeft <= 0) {
                // 【修改点】在发奖前一刻，再严格查验一遍玩家是不是在同 tick 刚被打死
                if (player.isDead || !player.isOnline) {
                    fail()
                } else if (tributes >= 5) {
                    win()
                } else {
                    player.sendMessage("§c贡品剩余不足5个，山神大怒，试炼失败！")
                    fail()
                }
            }
        }
        tick++
    }

    private fun spawnWave() {
        // 【新增】如果是第一波，播放一声诡异的音效（可自行更改为其他喜欢的音效）
        if (isFirstWave) {
            player.playSound(player.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.5f)
            isFirstWave = false
        }

        val mobIds = listOf("yssl_jiangshi", "yssl_kulou", "yssl_zhizhu")
        for (id in mobIds) {
            val entity = MobFactory.spawnMob(plugin, spawnLoc, id)
            if (entity is Mob) {
                activeMobs.add(entity)
                isolateEntity(entity)
            }
        }
    }

    private fun isolateEntity(entity: Entity) {
        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            if (onlinePlayer != player) {
                onlinePlayer.hideEntity(plugin, entity)
            }
        }
    }

    private fun win() {
        cleanUp()
        player.teleport(winLoc)
        player.sendMessage("§a恭喜你完成了“山神庙庙公的医术试炼”！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 100)
            data.learnMedicalSkill("dusuzhen")
            data.completedMedicalTrials.add(trialId)
            player.sendMessage("§a[奖励] 你已将三阶医术 §9毒素针 §a存入灵智！")

            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { conn ->
                        plugin.databaseManager.saveMedicalData(conn, data)
                        plugin.databaseManager.saveCompletedMedicalTrials(conn, data)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("保存医术试炼完成记录失败: ${e.message}")
                }
            })
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun fail() {
        cleanUp()
        if (player.isOnline && !player.isDead) {
            player.sendMessage("§c医术试炼失败！")
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        this.cancel()
        timeBar.removeAll()
        tributeBar.removeAll()
        miaoGong?.remove()
        activeMobs.forEach { it.remove() }
    }
}
