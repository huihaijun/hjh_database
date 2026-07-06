package com.hjh_database.dungeon.baihu.trial

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import com.hjh_database.spawner.MobFactory
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class BaihuTrialManager(private val plugin: Hjh_database) : Listener {

    companion object {
        const val PLAYER_TAG = "baihu_trial"
        private const val MOB_TAG = "baihu_trial_mob"
        private const val PAPER_TAG = "baihu_trial_paper_tiger"
        private const val GUARD_ARROW_TAG = "baihu_trial_guard_arrow"
        private const val STAGE_SECONDS = 8
    }

    var isDungeonActive = false
        private set
    var isStarting = false
        private set

    private var world: World? = null
    private var guard: Skeleton? = null
    private var mainTask: BukkitTask? = null
    private var dialogueTask: BukkitTask? = null
    private var failTask: BukkitTask? = null
    private var progressBar: BossBar? = null
    private var paperBar: BossBar? = null
    private var ending = false
    private var guardBowEmpowered = false

    private var nextPlatformIndex = 1
    private var stageCountdownTicks = STAGE_SECONDS * 20
    private val spawnedMobs = mutableSetOf<LivingEntity>()
    private val paperHits = mutableMapOf<UUID, Int>()
    private val hardenedBlocks = mutableSetOf<BlockPoint>()
    private val prisons = mutableMapOf<UUID, PrisonState>()

    private val triggerPoint = BlockPoint(2206, 93, -819)
    private val startLocation = TrialLocation(1828.61, 11.0, -811.91, 2367.61f, -11.85f)
    private val rewardLocation = TrialLocation(2234.45, 54.0, -970.32, 7650.18f, 0.30f)

    private val platforms = listOf(
        BlockPoint(1844, 16, -819),
        BlockPoint(1850, 25, -817),
        BlockPoint(1842, 22, -789),
        BlockPoint(1846, 26, -775),
        BlockPoint(1832, 41, -811),
        BlockPoint(1858, 52, -801),
        BlockPoint(1846, 66, -795),
        BlockPoint(1852, 71, -811)
    )

    private val finishPlates = setOf(
        BlockPoint(1857, 73, -806),
        BlockPoint(1857, 73, -805)
    )

    private val hardenedBlockPoints = setOf(
        BlockPoint(1848, 29, -766),
        BlockPoint(1836, 29, -771),
        BlockPoint(1835, 29, -776),
        BlockPoint(1834, 29, -781)
    )

    @EventHandler
    fun onTriggerClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.LANTERN && block.type != Material.SOUL_LANTERN) return
        if (BlockPoint.from(block.location) != triggerPoint) return

        event.isCancelled = true
        if (isDungeonActive || isStarting) {
            event.player.sendMessage("§c白虎试炼副本正在进行或准备中，无法重复开启！")
            return
        }

        val playersInArea = block.world.players.filter { player ->
            val loc = player.location
            loc.blockX in 2205..2207 && loc.blockY in 92..95 && loc.blockZ in -822..-820
        }
        if (playersInArea.isEmpty()) return

        isStarting = true
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            var completedPlayerName: String? = null
            val eligible = mutableListOf<Player>()
            try {
                plugin.databaseManager.dataSource?.connection?.use { conn ->
                    conn.prepareStatement("SELECT baihu FROM player_test WHERE uuid = ?").use { ps ->
                        for (player in playersInArea) {
                            ps.setString(1, player.uniqueId.toString())
                            ps.executeQuery().use { rs ->
                                if (rs.next() && rs.getInt("baihu") == 1) {
                                    completedPlayerName = player.name
                                } else {
                                    eligible.add(player)
                                }
                            }
                            if (completedPlayerName != null) break
                        }
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("检查白虎试炼完成状态失败: ${ex.message}")
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (completedPlayerName != null) {
                    event.player.sendMessage("§c玩家§e$completedPlayerName§c已完成白虎试炼！")
                    isStarting = false
                    return@Runnable
                }

                val selected = eligible.firstOrNull()
                if (selected == null || !selected.isOnline) {
                    isStarting = false
                    return@Runnable
                }

                isStarting = false
                startDungeon(selected)
            })
        })
    }

    private fun startDungeon(player: Player) {
        isDungeonActive = true
        ending = false
        world = player.world
        nextPlatformIndex = 1
        stageCountdownTicks = STAGE_SECONDS * 20
        guardBowEmpowered = false
        paperHits.clear()
        hardenedBlocks.clear()
        prisons.clear()

        player.addScoreboardTag(PLAYER_TAG)
        player.teleport(startLocation.toLocation(player.world))
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20 * 60, 255, false, false, false))
        player.playSound(player.location, "hjh:bgm_baihu", SoundCategory.RECORDS, 1.0f, 1.0f)

        val dialogues = listOf(
            "§f欢迎，能穿过白虎洞中层层§d瘴气§f来到此地，你的本事不差。",
            "§f废话就不多说了。§4§n白虎大人§f行事向来雷厉风行，它留下的试炼也只考一桩——§c速度与力量§f的极致。",
            "§f待会儿，白虎大人的侍卫会沿这条石路依次现身于各处§e站台§f。你们伤不了他，但他的速度未必追得上你们——只要在他之前§c抵达终点§f，便算通过。",
            "§f切记，这一路上侍卫会布下§c重重障碍§f阻挠你们前进。别想着硬碰，想办法绕开、摆脱，用最快的速度冲过去。",
            "§f看——白虎侍卫正在凝气聚形。试炼即刻开始，§e祝你好运§f！"
        )

        val dialogueTicks = listOf(0, 44, 92, 144, 184)
        var elapsedTicks = 0
        var step = 0
        dialogueTask = object : BukkitRunnable() {
            override fun run() {
                if (!isDungeonActive || !player.isOnline || !player.scoreboardTags.contains(PLAYER_TAG)) {
                    cancel()
                    return
                }
                if (step < dialogues.size && elapsedTicks >= dialogueTicks[step]) {
                    player.sendMessage("§a§l白虎分魂：")
                    player.sendMessage(dialogues[step])
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.15f)
                    if (step == dialogues.lastIndex) {
                        spawnGuardArrivalParticles()
                    }
                    step++
                }

                if (elapsedTicks >= 220) {
                    player.removePotionEffect(PotionEffectType.SLOWNESS)
                    spawnGuard()
                    cancel()
                    return
                }

                elapsedTicks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun spawnGuard() {
        val currentWorld = world ?: return
        val spawnLoc = platforms[0].toCenterLocation(currentWorld).add(0.0, 1.0, 0.0)
        currentWorld.spawnParticle(Particle.CLOUD, spawnLoc, 60, 0.7, 1.0, 0.7, 0.03)
        currentWorld.playSound(spawnLoc, Sound.ENTITY_SKELETON_AMBIENT, 1.2f, 0.7f)

        guard = currentWorld.spawn(spawnLoc, Skeleton::class.java) { skeleton ->
            skeleton.customName = "§c白虎侍卫"
            skeleton.isCustomNameVisible = true
            skeleton.addScoreboardTag(MOB_TAG)
            skeleton.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
            skeleton.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 1024.0
            skeleton.health = 1024.0
            skeleton.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
            skeleton.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
            skeleton.equipment?.helmet = ItemStack(Material.DIAMOND_HELMET)
            skeleton.equipment?.setItemInMainHand(ItemStack(Material.BOW))
            skeleton.removeWhenFarAway = false
        }
        guard?.let { spawnedMobs.add(it) }

        progressBar = Bukkit.createBossBar("§f白虎侍卫进度（1/${platforms.size}）", BarColor.WHITE, BarStyle.SEGMENTED_10)
        getTrialPlayers().forEach { progressBar?.addPlayer(it) }

        mainTask = object : BukkitRunnable() {
            private var tick = 0
            override fun run() {
                if (!isDungeonActive || ending) {
                    cancel()
                    return
                }
                tick++
                val players = getTrialPlayers()
                if (players.isEmpty()) {
                    endDungeon(false, killPlayers = false)
                    return
                }

                players.forEach { player ->
                    if (!isInsideArena(player.location)) {
                        failPlayer(player, "§c你离开了白虎试炼区域，试炼失败！")
                        return
                    }
                    if (isOnFinishPlate(player)) {
                        endDungeon(true, killPlayers = false)
                        return
                    }
                }

                guard?.let { skeleton ->
                    if (!skeleton.isDead && nextPlatformIndex <= 7) {
                        skeleton.target = players.firstOrNull()
                        keepGuardOnPlatform(skeleton)
                    }
                }

                updatePaperTigerState(players)
                updatePrisonState(players)
                drawHardenedBlocks()
                updateStageProgress()
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun updateStageProgress() {
        if (nextPlatformIndex > platforms.lastIndex) return
        stageCountdownTicks--
        progressBar?.setTitle("§f白虎侍卫进度（$nextPlatformIndex/${platforms.size}）")
        progressBar?.progress = (stageCountdownTicks / (STAGE_SECONDS * 20.0)).coerceIn(0.0, 1.0)

        val currentWorld = world ?: return
        if (stageCountdownTicks <= 60 && stageCountdownTicks % 10 == 0) {
            val currentIndex = (nextPlatformIndex - 1).coerceIn(0, platforms.lastIndex)
            spawnTeleportParticles(platforms[currentIndex].toCenterLocation(currentWorld).add(0.0, 1.0, 0.0))
            spawnTeleportParticles(platforms[nextPlatformIndex].toCenterLocation(currentWorld).add(0.0, 1.0, 0.0))
        }

        if (stageCountdownTicks > 0) return
        teleportGuardToStage(nextPlatformIndex)
        nextPlatformIndex++
        stageCountdownTicks = STAGE_SECONDS * 20
    }

    private fun teleportGuardToStage(stage: Int) {
        val currentWorld = world ?: return
        val target = platforms[stage].toCenterLocation(currentWorld).add(0.0, 1.0, 0.0)
        spawnTeleportParticles(target)
        currentWorld.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f)
        guard?.teleport(target)

        val platformNumber = stage + 1
        when (platformNumber) {
            2 -> spawnPaperTigers()
            4 -> markHardenedBlocks()
            5 -> empowerGuardBow()
            platforms.size -> {
                progressBar?.setTitle("§f白虎侍卫进度（${platforms.size}/${platforms.size}）")
                progressBar?.progress = 0.0
                failTask?.cancel()
                failTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (isDungeonActive && !ending) {
                        endDungeon(false, killPlayers = true)
                    }
                }, 60L)
            }
        }
    }

    private fun spawnPaperTigers() {
        val currentWorld = world ?: return
        val locs = listOf(
            Location(currentWorld, 1850.5, 27.0, -810.5),
            Location(currentWorld, 1848.5, 25.0, -807.5),
            Location(currentWorld, 1846.5, 24.0, -804.5)
        )

        locs.forEach { loc ->
            val zombie = currentWorld.spawn(loc, Zombie::class.java) { z ->
                z.customName = "§6纸老虎(?)"
                z.isCustomNameVisible = true
                z.addScoreboardTag(MOB_TAG)
                z.addScoreboardTag(PAPER_TAG)
                z.addScoreboardTag("panling")
                z.addScoreboardTag("monster")
                z.setAI(false)
                z.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 10000.0
                z.health = 10000.0
                z.equipment?.helmet = ItemStack(Material.LEATHER_HELMET)
                z.persistentDataContainer.set(MobFactory.KEY_NO_REWARD, PersistentDataType.BYTE, 1)
                z.removeWhenFarAway = false
            }
            spawnedMobs.add(zombie)
            paperHits[zombie.uniqueId] = 0
            currentWorld.spawnParticle(Particle.CLOUD, loc.add(0.0, 1.0, 0.0), 25, 0.4, 0.8, 0.4, 0.03)
        }

        getTrialPlayers().forEach { player ->
            player.sendMessage("§e白虎侍卫唤出了§6纸老虎(?)§e！它们不堪一击，轻碰两下即可击散。")
            player.playSound(player.location, Sound.ENTITY_ZOMBIE_AMBIENT, 0.8f, 1.4f)
        }

        paperBar?.removeAll()
        paperBar = Bukkit.createBossBar("§6纸老虎(?) 3/3", BarColor.YELLOW, BarStyle.SEGMENTED_6)
        getTrialPlayers().forEach { paperBar?.addPlayer(it) }
    }

    private fun updatePaperTigerState(players: List<Player>) {
        val aliveCount = spawnedMobs.count { it.scoreboardTags.contains(PAPER_TAG) && it.isValid && !it.isDead }
        if (aliveCount <= 0) {
            paperBar?.removeAll()
            paperBar = null
            return
        }

        players.forEach {
            it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 2, false, false, false))
        }
        paperBar?.setTitle("§6纸老虎(?) $aliveCount/3")
        paperBar?.progress = (aliveCount / 3.0).coerceIn(0.0, 1.0)
    }

    private fun markHardenedBlocks() {
        hardenedBlocks.clear()
        hardenedBlocks.addAll(hardenedBlockPoints)
        getTrialPlayers().forEach {
            it.sendMessage("§d白虎侍卫在道路上凝成了硬化方块，踩中后需要连续跳跃挣脱！")
        }
    }

    private fun drawHardenedBlocks() {
        val currentWorld = world ?: return
        if (hardenedBlocks.isEmpty()) return
        if ((Bukkit.getCurrentTick() % 5) != 0) return
        val white = Particle.DustOptions(Color.WHITE, 1.0f)
        val purple = Particle.DustOptions(Color.fromRGB(160, 80, 255), 1.0f)
        hardenedBlocks.forEach { point ->
            val base = point.toCenterLocation(currentWorld).add(0.0, 1.05, 0.0)
            for (i in 0 until 360 step 30) {
                val rad = Math.toRadians(i.toDouble())
                val loc = base.clone().add(cos(rad) * 0.72, 0.0, sin(rad) * 0.72)
                currentWorld.spawnParticle(Particle.DUST, loc, 1, if (i % 60 == 0) purple else white)
            }
        }
    }

    private fun updatePrisonState(players: List<Player>) {
        val iterator = prisons.iterator()
        while (iterator.hasNext()) {
            val (uuid, prison) = iterator.next()
            val player = players.firstOrNull { it.uniqueId == uuid }
            if (player == null || !player.isOnline || player.isDead) {
                iterator.remove()
                continue
            }
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 255, false, false, false))
            player.velocity = Vector(0.0, player.velocity.y.coerceAtMost(0.2), 0.0)
            val loc = prison.point.toCenterLocation(player.world).add(0.0, 1.2, 0.0)
            player.world.spawnParticle(Particle.DUST, loc, 4, 0.4, 0.5, 0.4, Particle.DustOptions(Color.fromRGB(170, 90, 255), 1.1f))
        }
    }

    private fun empowerGuardBow() {
        guardBowEmpowered = true
        guard?.equipment?.setItemInMainHand(ItemStack(Material.BOW))
        getTrialPlayers().forEach {
            it.sendMessage("§c白虎侍卫的弓弦泛起锐光，被射中会被击飞出道路！")
            it.playSound(it.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.55f)
        }
    }

    private fun keepGuardOnPlatform(skeleton: Skeleton) {
        val currentIndex = (nextPlatformIndex - 1).coerceIn(0, platforms.lastIndex)
        val target = platforms[currentIndex].toCenterLocation(skeleton.world).add(0.0, 1.0, 0.0)
        if (skeleton.location.distanceSquared(target) > 16.0) {
            skeleton.teleport(target)
        }
    }

    private fun spawnGuardArrivalParticles() {
        val currentWorld = world ?: return
        val center = platforms[0].toCenterLocation(currentWorld).add(0.0, 1.2, 0.0)
        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (!isDungeonActive || ticks > 60) {
                    cancel()
                    return
                }
                spawnTeleportParticles(center)
                ticks += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun spawnTeleportParticles(loc: Location) {
        val dust = Particle.DustOptions(Color.WHITE, 1.2f)
        loc.world?.spawnParticle(Particle.DUST, loc, 18, 0.55, 0.8, 0.55, dust)
        loc.world?.spawnParticle(Particle.CLOUD, loc, 8, 0.45, 0.7, 0.45, 0.02)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityDamage(event: EntityDamageEvent) {
        if (!isDungeonActive) return
        val entity = event.entity
        if (entity == guard) {
            event.isCancelled = true
            event.damage = 0.0
            return
        }

        if (entity is LivingEntity && entity.scoreboardTags.contains(PAPER_TAG)) {
            event.isCancelled = true
            val next = (paperHits[entity.uniqueId] ?: 0) + 1
            paperHits[entity.uniqueId] = next
            entity.world.spawnParticle(Particle.POOF, entity.location.add(0.0, 1.0, 0.0), 20, 0.4, 0.6, 0.4, 0.03)
            if (next >= 2) {
                entity.remove()
                spawnedMobs.remove(entity)
                paperHits.remove(entity.uniqueId)
                if (spawnedMobs.none { it.scoreboardTags.contains(PAPER_TAG) && it.isValid && !it.isDead }) {
                    onAllPaperTigersGone()
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGuardDamagePlayer(event: EntityDamageByEntityEvent) {
        if (!isDungeonActive) return
        val player = event.entity as? Player ?: return
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return
        val source = event.damager
        val shooter = if (source is Projectile) source.shooter as? LivingEntity else source as? LivingEntity
        if (shooter == null || shooter != guard) return

        event.damage = 1.0
        if (guardBowEmpowered) {
            val direction = player.location.toVector().subtract(shooter.location.toVector()).normalize()
            player.velocity = direction.multiply(2.7).setY(1.0)
            player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f)
        }
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        if (projectile.scoreboardTags.contains(GUARD_ARROW_TAG)) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { projectile.remove() }, 1L)
        }
    }

    @EventHandler
    fun onArrowLaunch(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        if (arrow.shooter == guard) {
            arrow.addScoreboardTag(GUARD_ARROW_TAG)
            if (guardBowEmpowered) {
                arrow.knockbackStrength = 8
            }
        }
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        if (!isDungeonActive) return
        val player = event.player
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return

        val prison = prisons[player.uniqueId]
        if (prison != null) {
            val from = event.from
            val to = event.to ?: return
            if (to.blockX != from.blockX || to.blockZ != from.blockZ) {
                event.to = from.clone().apply { y = to.y }
            }
            if (to.y > from.y + 0.08 && System.currentTimeMillis() - prison.lastJumpMs > 220L) {
                prison.jumpCount++
                prison.lastJumpMs = System.currentTimeMillis()
                player.sendMessage("§d硬化禁锢松动：${prison.jumpCount}/3")
                if (prison.jumpCount >= 3) {
                    releasePrison(player, prison.point)
                }
            }
            return
        }

        val blockBelow = BlockPoint.from(player.location.clone().subtract(0.0, 0.1, 0.0))
        if (hardenedBlocks.contains(blockBelow)) {
            prisons[player.uniqueId] = PrisonState(blockBelow)
            player.sendMessage("§5你踩中了硬化方块，连续跳跃3次挣脱！")
            player.playSound(player.location, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.5f)
        }
    }

    @EventHandler
    fun onPlayerDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
        val player = event.entity
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return
        player.stopSound("hjh:bgm_baihu", SoundCategory.RECORDS)
        player.stopSound("hjh:bgm_baihu")
        player.removeScoreboardTag(PLAYER_TAG)
        endDungeon(false, killPlayers = false)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (!player.scoreboardTags.contains(PLAYER_TAG)) return
        player.stopSound("hjh:bgm_baihu", SoundCategory.RECORDS)
        player.stopSound("hjh:bgm_baihu")
        player.removeScoreboardTag(PLAYER_TAG)
        endDungeon(false, killPlayers = false)
    }

    @EventHandler
    fun onTrialMobDeath(event: EntityDeathEvent) {
        if (!event.entity.scoreboardTags.contains(MOB_TAG)) return
        event.drops.clear()
        event.droppedExp = 0
    }

    private fun onAllPaperTigersGone() {
        paperBar?.removeAll()
        paperBar = null
        getTrialPlayers().forEach { player ->
            player.removePotionEffect(PotionEffectType.SLOWNESS)
            player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 100, 0, false, true, true))
            player.sendMessage("§a纸老虎尽数消散，你获得了短暂的速度加持！")
        }
    }

    private fun releasePrison(player: Player, point: BlockPoint) {
        prisons.remove(player.uniqueId)
        hardenedBlocks.remove(point)
        player.removePotionEffect(PotionEffectType.SLOWNESS)
        player.sendMessage("§a你挣脱了硬化方块！")
        player.world.spawnParticle(Particle.POOF, point.toCenterLocation(player.world).add(0.0, 1.0, 0.0), 28, 0.5, 0.5, 0.5, 0.03)
        player.playSound(player.location, Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.2f)
    }

    private fun failPlayer(player: Player, message: String) {
        if (ending) return
        player.sendMessage(message)
        endDungeon(false, killPlayers = true)
    }

    private fun endDungeon(win: Boolean, killPlayers: Boolean) {
        if (!isDungeonActive && !isStarting) return
        ending = true
        isDungeonActive = false
        isStarting = false

        dialogueTask?.cancel()
        mainTask?.cancel()
        failTask?.cancel()
        progressBar?.removeAll()
        paperBar?.removeAll()
        progressBar = null
        paperBar = null

        spawnedMobs.forEach { if (it.isValid && !it.isDead) it.remove() }
        spawnedMobs.clear()
        guard?.remove()
        guard = null
        guardBowEmpowered = false
        paperHits.clear()
        hardenedBlocks.clear()
        prisons.clear()

        val players = Bukkit.getOnlinePlayers().filter { it.scoreboardTags.contains(PLAYER_TAG) }
        players.forEach { player ->
            player.stopSound("hjh:bgm_baihu", SoundCategory.RECORDS)
            player.stopSound("hjh:bgm_baihu")
        }
        if (win) {
            val keyItem = plugin.resourceManager.getItem("mijingyaoshi")
            players.forEach { player ->
                player.sendMessage("§a§l白虎分魂：§f很好，速度不滞、力道不虚，此乃白虎所青睐之姿——试炼，通过了。")
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
                if (keyItem != null) {
                    val clonedKey = keyItem.clone()
                    clonedKey.amount = 1
                    player.inventory.addItem(clonedKey)
                    player.sendMessage("§e[奖励] §a获得 §f秘境钥匙 §ax1！")
                } else {
                    plugin.logger.warning("未能找到 ID 为 mijingyaoshi 的物品，无法发放白虎试炼秘境钥匙奖励。")
                }
                updateDungeonRecord(player)
                updateDbStateAsync(player.uniqueId, "baihu", 1)
                player.removeScoreboardTag(PLAYER_TAG)
                player.teleport(rewardLocation.toLocation(player.world))
            }
        } else {
            players.forEach { player ->
                player.removeScoreboardTag(PLAYER_TAG)
                player.removePotionEffect(PotionEffectType.SLOWNESS)
                if (killPlayers && !player.isDead && player.health > 0.0) {
                    player.health = 0.0
                }
            }
        }
    }

    fun shutdown() {
        endDungeon(false, killPlayers = false)
    }

    private fun updateDungeonRecord(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val record = data.dungeonRecords.getOrPut("baihu_test") { DungeonRecord() }
        record.clears += 1
        record.availableOpens += 1
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.savePlayer(data)
        })
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
                plugin.logger.warning("更新白虎试炼数据库状态失败: ${e.message}")
            }
        })
    }

    private fun getTrialPlayers(): List<Player> {
        val currentWorld = world ?: return emptyList()
        return currentWorld.players.filter { it.scoreboardTags.contains(PLAYER_TAG) }
    }

    private fun isInsideArena(loc: Location): Boolean {
        return loc.world == world &&
            loc.x in 1795.0..1885.0 &&
            loc.y in 0.0..95.0 &&
            loc.z in -850.0..-755.0
    }

    private fun isOnFinishPlate(player: Player): Boolean {
        val point = BlockPoint.from(player.location.clone().subtract(0.0, 0.05, 0.0))
        return finishPlates.contains(point)
    }

    private data class TrialLocation(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
        fun toLocation(world: World): Location = Location(world, x, y, z, yaw, pitch)
    }

    private data class BlockPoint(val x: Int, val y: Int, val z: Int) {
        fun toCenterLocation(world: World): Location = Location(world, x + 0.5, y.toDouble(), z + 0.5)

        companion object {
            fun from(location: Location): BlockPoint {
                return BlockPoint(floor(location.x).toInt(), floor(location.y).toInt(), floor(location.z).toInt())
            }
        }
    }

    private data class PrisonState(
        val point: BlockPoint,
        var jumpCount: Int = 0,
        var lastJumpMs: Long = 0L
    )
}
