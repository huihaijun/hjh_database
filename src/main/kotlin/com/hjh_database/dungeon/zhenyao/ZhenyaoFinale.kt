package com.hjh_database.dungeon.zhenyao

import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.combat.MonsterDamageClassification
import com.hjh_database.spawner.MobFactory
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.boss.*
import org.bukkit.entity.*
import org.bukkit.event.entity.*
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.*
import java.util.UUID

/** 所有阶段由本局单一tick驱动，结束后没有游离的技能定时任务。 */
internal class ZhenyaoFinale(val manager: ZhenyaoTowerManager, val session: ZhenyaoTowerManager.Session) {
    val plugin = manager.plugin
    val world = session.world
    val top = Location(world, -1001.5, 125.0, 3006.5)
    val middle = Location(world, -1001.5, 50.0, 3006.5)
    var boss: LivingEntity? = null
        private set
    var flightEnabled = false
        private set
    var transformed = false
        private set
    var halfPending = false
        private set
    var invulnerable = true
        private set
    var normalDamage = false
        private set
    var scriptedDamage = false
        private set
    var closed = false
        private set
    enum class Mode { INTRO, NORMAL, YIN_PREP, YIN, YIN_RETURN, YIN_REST, EYE_PREP, EYES, WEAK,
        COLLAPSE_MOVE, COLLAPSE_SPEECH, COLLAPSE_BREAK, COLLAPSE_CENTER, COLLAPSE_REST,
        RIFT_PREP, RIFT, ORB_PREP, ORBS, SWORD_PREP, SWORD_THROW, SWORD_DASH, SWORD_PICKUP, VICTORY }
    var mode = Mode.INTRO
        private set
    private var modeStart = session.tick
    val now get() = session.tick
    val elapsed get() = now - modeStart
    private val skillCooldown = TowerSkillCooldown()
    private var lastSkill = -1
    private val healthBar = Bukkit.createBossBar("§4妖族大长老-蚩尤", BarColor.BLUE, BarStyle.SOLID)
    private val mechanicBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID)
    private val windowBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID)
    private var window: YinYangWindow? = null
    private var yinStart = 0L
    private var yinActive = 0
    var yinAnimationSwap = -100L
        private set
    private var yinExposed = false
    private val twins = mutableListOf<LivingEntity>()
    val eyes = mutableListOf<LivingEntity>()
    private var collapseFlightApplied = false
    private var phaseTwoStart = -1L
    private val effects = ChiyouAttacks(this)
    private val flood = TowerFlood(this)
    private val animation = ChiyouAnimation(this)
    fun normalAnimation() = effects.animationFrame()
    fun swordsPrepared() = effects.swordsPrepared
    fun lastSword() = effects.lastSword
    fun animateAbsorption() = animation.absorb()
    fun yinLookTarget() = twins.getOrNull(yinActive)?.takeIf { it.isValid && !it.isDead }
        ?: twins.firstOrNull { it.isValid && !it.isDead }
    private var travelDetour: Location? = null
    private var introLift = 0.0

    fun players() = manager.active(session).filter { it.world == world }
    fun broadcast(text: String) = manager.active(session).forEach { it.sendMessage(text) }
    fun sound(sound: Sound, volume: Float = 1.4f, pitch: Float = .8f) = manager.active(session).forEach {
        it.playSound(it.location, sound, SoundCategory.PLAYERS, volume, pitch)
    }
    fun effect(origin: Location, shape: ClientParticleShape = ClientParticleShape.CLOUD, radius: Double = 2.0,
               color: Int = 0x702B91, count: Int = 35, end: Location = origin) {
        plugin.clientBridge.emitParticles(manager.active(session), origin, listOf(
            ClientParticleLayer("minecraft:dust", shape, count, color, 1.4f, radius, radius, .025),
            ClientParticleLayer("minecraft:reverse_portal", shape, (count / 3).coerceAtLeast(1), radius = radius, height = radius, speed = .04)
        ), end, ignoreDistance = true)
    }
    fun perform(cue: Sound, color: Int, shape: ClientParticleShape = ClientParticleShape.RING,
                radius: Double = 2.5, pitch: Float = .8f) {
        sound(cue, 1.6f, pitch)
        boss?.let { effect(it.location.clone().add(0.0, it.height / 2, 0.0), shape, radius, color, 36) }
    }
    fun spawn(id: String, at: Location): LivingEntity {
        val mob = checkNotNull(MobFactory.spawnMob(plugin, at, id)) { "无法生成 $id" }
        manager.own(session, mob)
        check(mob.isValid) { "$id 生成被取消" }
        return mob
    }
    fun own(entity: Entity) = manager.own(session, entity)

    fun tick() {
        try { tickBattle() } finally { animation.tick() }
    }
    private fun tickBattle() {
        if (closed) return
        if (mode != Mode.VICTORY) effects.tickTransient()
        if (mode != Mode.INTRO && mode != Mode.VICTORY && (boss?.isValid != true || boss?.isDead == true)) {
            manager.failAll(session, "§c蚩尤实体异常丢失，副本结束并恢复建筑。"); return
        }
        boss?.let { mob ->
            updateInvulnerability()
            mob.fireTicks = 0
            if (mode != Mode.VICTORY && now % 10 == 0L) {
                healthBar.progress = (mob.health / 10000.0).coerceIn(0.0, 1.0)
                players().forEach(healthBar::addPlayer)
            }
        }
        if (phaseTwoStart >= 0 && mode != Mode.VICTORY) flood.tick(now - phaseTwoStart)
        if (mode in setOf(Mode.YIN_PREP, Mode.EYE_PREP, Mode.RIFT_PREP, Mode.ORB_PREP, Mode.SWORD_PREP) && elapsed % 20 == 0L) {
            val (cue, color) = skillPresentation(lastSkill)
            sound(cue, .8f, 1.1f)
            boss?.let { effect(it.eyeLocation, ClientParticleShape.RING, 2.5, color, 18) }
        }
        when (mode) {
            Mode.INTRO -> intro()
            Mode.NORMAL -> {
                if (halfPending && effects.normalIdle) { beginCollapse(); return }
                if (effects.normalIdle && skillCooldown.isReady(now)) {
                    val eligible = if (transformed) 2..4 else 0..1
                    val next = eligible.firstOrNull { it > lastSkill } ?: eligible.firstOrNull()
                    if (next != null) { startSkill(next); return }
                }
                effects.tickNormal()
            }
            Mode.YIN_PREP -> {
                moveToward(top.clone().add(0.0, 4.0, 0.0), .18)
                flightPresentation(true)
                bar("黑白无常-准备中", 100)
                if (elapsed % 5 == 0L) listOf(3015.5, 2997.5).forEach { effect(Location(world, -1001.5, 125.5, it), ClientParticleShape.RING, 2.0) }
                if (elapsed >= 100) spawnTwins()
            }
            Mode.YIN -> tickTwins()
            Mode.YIN_RETURN -> {
                flightPresentation(false)
                if (moveToward(top, .18)) change(Mode.YIN_REST, false)
                else check(elapsed < 300) { "蚩尤无法返回塔顶" }
            }
            Mode.YIN_REST -> { freeze(); if (elapsed >= 20) finishSkill() }
            Mode.EYE_PREP -> {
                freeze(); bar("妖力侵染-准备中", 60)
                if (elapsed % 5 == 0L) manager.arena.markers(world, 125.0).forEach { effect(it, ClientParticleShape.RING, 2.0) }
                if (elapsed == 53L) {
                    perform(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0x9C44CB, radius = 4.0, pitch = .55f)
                    boss?.let { effect(it.location, ClientParticleShape.RING, 5.0, 0xA952D8, 40) }
                }
                if (elapsed >= 60) {
                    manager.arena.markers(world, 125.0).forEach { point ->
                        val eye = spawn("zhenyao_eye", point)
                        eye.setAI(false); eye.setGravity(false); eyes += eye
                    }
                    broadcast("§6蚩尤召唤了四座阵眼并强化了他的护甲，引诱他击杀自己的阵眼，让妖力反噬他！")
                    change(Mode.EYES, false)
                    effects.resetAfterEyes()
                }
            }
            Mode.EYES -> {
                bar("妖力侵染", TowerFinalRules.EYE_DURATION)
                if (eyes.all { it.isDead || !it.isValid }) {
                    effects.stopNormal(); removeEyes()
                    boss!!.health -= TowerFinalRules.cappedDamage(boss!!.health, 500.0, transformed)
                    if (!transformed && boss!!.health <= 5000) { boss!!.health = 5000.0; halfPending = true }
                    broadcast("§6四座阵眼尽毁，妖力反噬蚩尤！趁他虚弱发起攻击！")
                    change(Mode.WEAK, false)
                } else if (elapsed >= TowerFinalRules.EYE_DURATION) {
                    val penalty = TowerFinalRules.remainingEyesPenalty(eyes.count { it.isValid && !it.isDead })
                    effects.stopNormal(); removeEyes()
                    players().forEach { damage(it, penalty) }
                    finishSkill()
                } else effects.tickNormal()
            }
            Mode.WEAK -> { freeze(); bar("妖力反噬-弱点暴露", 100); if (elapsed >= 100) finishSkill() }
            Mode.COLLAPSE_MOVE -> {
                if (moveToward(top, .35)) {
                    change(Mode.COLLAPSE_SPEECH)
                    broadcast("§4蚩尤：区区一座破塔，也妄想镇我千年？")
                    plugin.clientBridge.cameraShake(players(), 360, .10f)
                } else check(elapsed < 400) { "蚩尤无法进入崩塔位置" }
            }
            Mode.COLLAPSE_SPEECH -> {
                freeze()
                boss!!.getAttribute(Attribute.SCALE)?.baseValue = ChiyouPresentationRules.collapseScale(elapsed)
                if (elapsed % 5 == 0L) effect(top.clone().add(0.0, 1.5, 0.0), ClientParticleShape.SPHERE, 5.0)
                if (elapsed == 60L) broadcast("§4蚩尤：今日，老夫便连这塔，带你们这群蝼蚁——")
                if (elapsed >= 120) {
                    broadcast("§4蚩尤：一并踏为齑粉，以慰我族的万千英魂！")
                    change(Mode.COLLAPSE_BREAK)
                    players().forEach {
                        manager.flight.capture(it)
                        it.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0))
                    }
                    plugin.clientBridge.cameraShake(players(), 200, .20f)
                }
            }
            Mode.COLLAPSE_BREAK -> collapseTick()
            Mode.COLLAPSE_CENTER -> {
                if (moveToward(middle, .65)) {
                    boss!!.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 100, 0))
                    boss!!.setGravity(false)
                    broadcast("§c蚩尤出现在了塔中央的位置！")
                    change(Mode.COLLAPSE_REST)
                } else check(elapsed < 500) { "蚩尤无法抵达塔中央" }
            }
            Mode.COLLAPSE_REST -> {
                freeze()
                if (elapsed >= 60) {
                    halfPending = false; transformed = true; phaseTwoStart = now
                    boss!!.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = .4 * TowerFinalRules.PHASE_TWO_SPEED_MULTIPLIER
                    val pdc = boss!!.persistentDataContainer
                    for (key in listOf(MobFactory.KEY_CUSTOM_ARMOR, MobFactory.KEY_CUSTOM_DAMAGE)) {
                        val base = checkNotNull(pdc.get(key, PersistentDataType.DOUBLE)) { "蚩尤缺少MobFactory战斗属性：$key" }
                        pdc.set(key, PersistentDataType.DOUBLE, base * TowerFinalRules.PHASE_TWO_ATTRIBUTE_MULTIPLIER)
                    }
                    skillCooldown.settle(now)
                    effects.resetNormal(); change(Mode.NORMAL, false)
                    broadcast("§6妖气每六十秒侵染一层，身处其中进攻和护甲降低10%；充盈全塔后蚩尤将吸收妖气恢复全部生命！")
                }
            }
            Mode.RIFT_PREP -> {
                freeze(); bar("妖力裂空-准备中", 60)
                if (elapsed >= 60) { change(Mode.RIFT, false); effects.tickRifts(0) }
            }
            Mode.RIFT -> {
                val wasReleased = effects.riftsReleased
                if (effects.tickRifts(elapsed)) finishSkill()
                else if (effects.riftsReleased) {
                    if (!wasReleased) { clearBar(); effects.resetNormal() }
                    effects.tickNormal()
                } else freeze()
            }
            Mode.ORB_PREP -> {
                freeze(); bar("九黎追魂-准备中", 40)
                effects.previewOrbs()
                if (elapsed >= 40) { change(Mode.ORBS, false); effects.releaseOrbs(); effects.resetNormal() }
            }
            Mode.ORBS -> {
                bar("九黎追魂", 240)
                if (elapsed < 240) effects.tickOrbs()
                if (elapsed >= 240 || effects.orbsResolved) { effects.clearOrbs(); effects.stopNormal(); finishSkill() }
                else effects.tickNormal()
            }
            Mode.SWORD_PREP -> {
                if (moveToward(middle, .5)) {
                    if (!effects.swordsPrepared) { effects.prepareSwords(); modeStart = now }
                    freeze(); bar("魔剑归宗-准备中", 100); effects.orbitSwords(elapsed)
                    if (elapsed >= 100) { change(Mode.SWORD_THROW, false); effects.beginSwordTrack() }
                } else check(elapsed < 400) { "蚩尤无法返回御剑位置" }
            }
            Mode.SWORD_THROW -> {
                freeze(); bar("魔剑归宗·追魂", TowerFinalRules.SWORD_TRACK_TICKS)
                if (effects.trackSword()) { change(Mode.SWORD_DASH, false); effects.beginSwordDash() }
            }
            Mode.SWORD_DASH -> {
                bar("魔剑归宗·收剑斩击", 240)
                if (effects.tickSwordDash() && !closed) {
                    if (effects.swordsPrepared) { change(Mode.SWORD_PICKUP, false); effects.beginSwordPickup() }
                    else advanceSword()
                }
            }
            Mode.SWORD_PICKUP -> {
                freeze(); bar("魔剑归宗·收剑斩击",12)
                if(effects.tickSwordPickup() && !closed) advanceSword()
            }
            Mode.VICTORY -> if (elapsed >= 60) manager.finishFinale(session)
        }
    }
    private fun advanceSword() {
        if(effects.swordsFinished) finishSkill()
        else { effects.prepareNextSword(); change(Mode.SWORD_THROW,false); effects.beginSwordTrack() }
    }

    private fun intro() {
        manager.playFloorFanfare(session, elapsed)
        if (elapsed == 40L) {
            players().forEach { it.sendTitle("§6镇妖塔", "§e第十层", 0, 50, 0) }
        }
        if (elapsed == 90L) players().forEach { it.sendTitle("§4§l塔顶", "", 0, 40, 0) }
        if (elapsed >= 130 && elapsed <= 490 && (elapsed - 130) % 60 == 0L) {
            val index = ((elapsed - 130) / 60).toInt()
            players().forEach { player ->
                val yao = plugin.playerManager.getData(player.uniqueId)?.race == 4
                player.sendMessage((if (yao) ChiyouDialogue.yao else ChiyouDialogue.other)[index])
            }
            effect(top.clone().add(0.0, 1.0, 0.0), ClientParticleShape.SPHERE, 2.8, count = 80)
            sound(if (index >= 3) Sound.ENTITY_WITHER_AMBIENT else Sound.BLOCK_PORTAL_AMBIENT, 1.2f, .7f)
            if (index == 3) {
                check(manager.arena.safe(top, .6, 2.0)) { "蚩尤出生点被遮挡" }
                // safe()要求脚下有实心支撑，只适用于落地点；空中入场检查整段扫掠空间。
                introLift = ChiyouPresentationRules.chooseIntroLift { lift ->
                    inAir(top.clone().add(0.0,lift,0.0), .6, 2.0) &&
                        manager.arena.clear(world, manager.arena.box(top, .6, 2.0+lift))
                }
                val entry=top.clone().add(0.0,introLift,0.0)
                boss = spawn("zhenyao_chiyou", entry).also { it.setAI(false); it.setGravity(false); it.isInvulnerable = true }
                nearest(top)?.let { face(it.location.toVector().subtract(top.toVector())) }
            }
        }
        if (boss != null && elapsed in 310L..409L) {
            if (elapsed < 350) {
                presentationMove(top.clone().add(0.0,ChiyouPresentationRules.introHeight(elapsed-309,introLift),0.0), .3)
                flightPresentation(false)
            } else {
                presentationMove(top, .2)
                if (elapsed==350L) {
                    sound(Sound.ENTITY_GENERIC_EXPLODE,1.6f,.7f)
                    effect(top,ClientParticleShape.RING,5.0,0xA25CB9,48)
                }
            }
        }
        if (elapsed >= 510) {
            skillCooldown.settle(now)
            change(Mode.NORMAL, false); effects.resetNormal()
        }
    }

    private fun startSkill(index: Int) {
        lastSkill = index; skillCooldown.start(); effects.stopNormal(); travelDetour = null
        when (index) {
            0 -> {
                change(Mode.YIN_PREP)
                broadcast("§4蚩尤：§f不堪一击，让他们会会你！")
                broadcast("§6蚩尤即将召唤黑白无常在场地中出现，他们二者每次只有一人可以行动，引诱他们靠近并同时杀死以破此阵！")
            }
            1 -> { change(Mode.EYE_PREP); broadcast("§4蚩尤：这座破塔，也配镇我？") }
            2 -> { change(Mode.RIFT_PREP, false); effects.startRifts(); broadcast("§6蚩尤正在凝聚妖力裂空，准备躲避三个切面！") }
            3 -> { change(Mode.ORB_PREP, false); effects.prepareOrbs(); broadcast("§6九黎追魂即将降临，远离周围的妖力球体！") }
            4 -> { change(Mode.SWORD_PREP, false); broadcast("§6魔剑归宗！飞剑将依次追魂，蚩尤会冲向停剑处，远离他的收剑斩击！") }
        }
        val (cue, color) = skillPresentation(index)
        perform(cue, color)
    }
    private fun skillPresentation(index: Int): Pair<Sound, Int> = when (index) {
        0 -> Sound.PARTICLE_SOUL_ESCAPE to 0xDBE6ED
        1 -> Sound.ENTITY_WARDEN_HEARTBEAT to 0x793EC4
        2 -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE to 0xE03CCD
        3 -> Sound.BLOCK_SCULK_SHRIEKER_SHRIEK to 0x26BDB5
        else -> Sound.ITEM_TRIDENT_RETURN to 0xFFDB8A
    }
    private fun finishSkill() {
        skillCooldown.settle(now)
        mechanicBar.removeAll(); windowBar.removeAll()
        effects.resetNormal(delay = 40)
        change(Mode.NORMAL, false)
    }
    private fun spawnTwins() {
        twins += spawn("zhenyao_black", Location(world, -1001.5, 125.0, 3015.5))
        twins += spawn("zhenyao_white", Location(world, -1001.5, 125.0, 2997.5))
        yinStart = now; window = YinYangWindow(now); yinExposed = false; yinActive = 0
        change(Mode.YIN)
        applyTwinState()
    }
    private fun tickTwins() {
        val angle=elapsed*.025
        presentationMove(top.clone().add(.6*sin(angle),4.0+.15*sin(angle*2),.6*cos(angle)), .08)
        yinLookTarget()?.let { face(it.eyeLocation.toVector().subtract(boss!!.eyeLocation.toVector())) }
        flightPresentation(true)
        val live = twins.filter { it.isValid && !it.isDead }
        val distance = if (live.size == 2) live[0].location.distance(live[1].location) else 0.0
        when (window!!.update(now, distance)) {
            YinYangResult.SUCCESS -> { resolveTwins(false); return }
            YinYangResult.PUNISH -> { resolveTwins(true); return }
            else -> Unit
        }
        val exposed = window!!.exposedAt != null
        if (exposed && !yinExposed) broadcast("§6黑白无常相互靠近，他们都被激活了！趁现在同时击杀他们！")
        if (!exposed && yinExposed) broadcast("§6黑白无常再次分离，重新引诱他们靠近！")
        yinExposed = exposed
        if (exposed) {
            val remaining = (200 - (now - window!!.exposedAt!!)).coerceAtLeast(0)
            windowBar.setTitle("黑白无常｜同时击杀窗口")
            windowBar.progress = remaining / 200.0; players().forEach(windowBar::addPlayer)
            if (now % 5 == 0L) live.forEach { effect(it.location, ClientParticleShape.SPHERE, 1.2, 0xFF3030, 12) }
            if (now % 5 == 0L && live.size == 2 && distance <= 5) {
                plugin.clientBridge.emitParticles(players(), live[0].eyeLocation, listOf(
                    ClientParticleLayer("minecraft:dust", ClientParticleShape.LINE, 14, 0xFFFFFF, 1.2f, .08, .08),
                    ClientParticleLayer("minecraft:dust", ClientParticleShape.LINE, 14, 0x171323, 1.2f, .08, .08)
                ), live[1].eyeLocation, ignoreDistance = true)
            }
        } else {
            windowBar.removeAll()
            val index = ((now - yinStart) / 100 % 2).toInt()
            if (index != yinActive) { yinActive = index; yinAnimationSwap=now; broadcast("§f阴阳逆转，§6黑白无常的行动交换了！"); sound(Sound.BLOCK_BEACON_POWER_SELECT) }
        }
        applyTwinState()
        val activeNames = twins.mapIndexedNotNull { index, mob ->
            if (mob.isValid && !mob.isDead && (yinExposed || index == yinActive)) {
                if (index == 0) "黑无常" else "白无常"
            } else null
        }.joinToString("、")
        bar("黑白无常｜当前可行动：${activeNames.ifEmpty { "无" }}", 600)
    }
    private fun applyTwinState() = twins.forEachIndexed { index, mob ->
        if (mob.isValid && !mob.isDead) {
            val enabled = yinExposed || index == yinActive
            mob.setAI(enabled); mob.isInvulnerable = !enabled
            if (!enabled) mob.velocity = Vector()
            else (mob as? Mob)?.target = nearest(mob.location)
        }
    }
    private fun resolveTwins(punish: Boolean) {
        if (mode != Mode.YIN) return
        twins.forEach { if (it.isValid) it.remove() }; twins.clear(); windowBar.removeAll(); mechanicBar.removeAll()
        if (punish) { broadcast("§c黑白无常引爆妖气！"); players().forEach { damage(it, 60.0) }; sound(Sound.ENTITY_GENERIC_EXPLODE, 2f) }
        else broadcast("§6黑白无常已被击败，阵法告破！")
        // 死亡/爆炸已经结算；返回与1秒的可受伤休息包含在这15秒冷却内。
        skillCooldown.settle(now)
        change(Mode.YIN_RETURN)
    }
    private fun removeEyes() { eyes.forEach { if (it.isValid) it.remove() }; eyes.clear() }
    private fun beginCollapse() {
        skillCooldown.start()
        effects.clear(); removeEyes(); twins.forEach(Entity::remove); twins.clear()
        manager.terrain.prepare(world)
        mechanicBar.removeAll(); windowBar.removeAll()
        change(Mode.COLLAPSE_MOVE)
    }
    private fun collapseTick() {
        val duration = TowerFinalRules.DEMOLITION_FLOORS.size * 20L
        presentationMove(top.clone().add(0.0,1.5*(elapsed/40.0).coerceIn(0.0,1.0),0.0), .12)
        flightPresentation(true)
        bar("崩塔", duration)
        // 在八层楼板逐层拆除的8秒内，从半血匀速恢复到80%；二阶段标志不再复位。
        boss!!.health = TowerFinalRules.collapseHealth(elapsed)
        if (elapsed >= 100 && !collapseFlightApplied) {
            collapseFlightApplied = true; flightEnabled = true
            players().forEach { manager.flight.capture(it); it.setGravity(false); it.allowFlight = true; it.fallDistance = 0f }
            broadcast("§6蚩尤翻涌着塔内的妖气，你的身体变得格外轻盈……")
            broadcast("§c(双击空格可开启飞行状态；身处妖气侵染的区域时，进攻和护甲降低10%)")
        }
        if (elapsed in 20L..duration && elapsed % 20 == 0L) {
            val floor = TowerFinalRules.DEMOLITION_FLOORS[(elapsed / 20).toInt() - 1]
            val y = 4.0 + (floor - 2) * 15
            manager.terrain.removeFloor(world, floor)
            manager.terrain.mask.asSequence().filter { it.floor == floor }.shuffled().take(32).forEach { cell ->
                plugin.clientBridge.emitParticles(players(), Location(world, cell.x + .5, cell.y + .5, cell.z + .5),
                    listOf(ClientParticleLayer("minecraft:explosion", ClientParticleShape.CLOUD, 1, radius = .2, height = .2)),
                    ignoreDistance = true)
            }
            effect(Location(world, -1001.5, y + .4, 3006.5), ClientParticleShape.RING, 23.0, 0x8A6A80, 160)
            // 再在各参与者附近渲染碎屑，避免远离当前被拆楼层时没有视听反馈。
            players().forEach { effect(it.location.clone().add(0.0, 1.0, 0.0), ClientParticleShape.CLOUD, 3.0, 0x847781, 35) }
            sound(Sound.ENTITY_GENERIC_EXPLODE, 2.0f, .65f)
            sound(Sound.BLOCK_DEEPSLATE_BREAK, 2.0f, .6f)
        }
        if (elapsed >= duration) { mechanicBar.removeAll(); change(Mode.COLLAPSE_CENTER) }
    }

    private fun change(next: Mode, immune: Boolean = true) {
        mode = next; modeStart = now; invulnerable = immune
        travelDetour = null
        boss?.let { it.setAI(false); it.velocity = Vector(); it.setGravity(false) }
        updateInvulnerability()
    }
    private fun updateInvulnerability() {
        val immune = invulnerable || halfPending
        boss?.isInvulnerable = immune
        healthBar.color = if (immune) BarColor.BLUE else BarColor.RED
    }
    fun freeze() { boss?.let { it.setAI(false); it.velocity = Vector(); it.setGravity(false) } }
    /** Small presentation travel retains normal collision checks and never changes combat clocks. */
    private fun presentationMove(target: Location, speed: Double) {
        val mob=boss?:return
        val delta=target.toVector().subtract(mob.location.toVector())
        if(delta.lengthSquared()<.0004) { freeze(); return }
        val step=delta.clone().normalize().multiply(min(speed,delta.length()))
        if(movementClear(mob.location,step,mob.width,mob.height)) {
            ChiyouMotion.controlled(mob); mob.velocity=step
        } else freeze()
    }
    private fun flightPresentation(ascending: Boolean) {
        val mob=boss?:return
        if(now%5!=0L) return
        val at=mob.location.clone().add(0.0,mob.height*.45,0.0)
        plugin.clientBridge.emitParticles(players(),at,listOf(
            ClientParticleLayer("minecraft:dust",ClientParticleShape.RING,16,
                if(ascending) 0xB585DC else 0xD6D0FF,1.1f,1.2,mob.height,.015),
            ClientParticleLayer("minecraft:enchant",ClientParticleShape.CLOUD,5,radius=.8,height=1.8,speed=.02)
        ),ignoreDistance=true)
        if(now%30==0L) sound(Sound.BLOCK_AMETHYST_BLOCK_CHIME,.65f,if(ascending) .8f else 1.2f)
    }
    fun idleMotion() {
        val mob = boss ?: return
        if (flightEnabled) nearest(mob.location)?.let { target ->
            if (target.location.distance(mob.location) > 4) moveToward(target.location, .18) else freeze()
        } else { ChiyouMotion.autonomous(mob); (mob as? Mob)?.target = nearest(mob.location) }
    }
    fun nearest(at: Location) = players().minByOrNull { it.location.distanceSquared(at) }
    fun farthest(at: Location) = players().maxByOrNull { it.location.distanceSquared(at) }
    fun bar(title: String, duration: Long) {
        val left = (duration - elapsed).coerceAtLeast(0)
        mechanicBar.setTitle(title)
        mechanicBar.progress = left.toDouble() / duration
        players().forEach(mechanicBar::addPlayer)
    }
    fun clearBar() = mechanicBar.removeAll()
    fun inAir(at: Location, width: Double = .7, height: Double = 2.0) =
        at.world == world && at.y >= 5 && at.y + height < 133 &&
            TowerFinalRules.insideOctagon(at.x, at.z, radiusAt(at.y) - width / 2) &&
            manager.arena.clear(world, manager.arena.box(at, width, height))
    fun radiusAt(y: Double): Double {
        val index = ((y - 5) / 15).toInt().coerceIn(0, 8)
        return doubleArrayOf(27.0, 26.0, 25.0, 23.0, 22.0, 21.0, 20.0, 18.0, 17.0)[index]
    }
    fun moveToward(target: Location, speed: Double, detour: Boolean = true): Boolean {
        val mob = boss ?: return false
        ChiyouMotion.controlled(mob)
        if (mob.location.distance(target) <= .65) { mob.velocity = Vector(); travelDetour = null; return true }
        if (travelDetour?.let { mob.location.distance(it) < .7 } == true) travelDetour = null
        val destination = travelDetour ?: target
        val direction = destination.toVector().subtract(mob.location.toVector()).normalize()
        val actualSpeed = TowerFinalRules.movementSpeed(speed, transformed)
        val step = direction.clone().multiply(min(actualSpeed, mob.location.distance(destination)))
        if (movementClear(mob.location, step, mob.width, mob.height)) {
            mob.velocity = step
            face(direction)
        } else {
            mob.velocity = Vector()
            if (detour) for (angle in listOf(Math.PI / 2, -Math.PI / 2, Math.PI / 4, -Math.PI / 4)) {
                val side = direction.clone().rotateAroundY(angle).multiply(3.0)
                val point = mob.location.clone().add(side)
                if (movementClear(mob.location, side, mob.width, mob.height)) { travelDetour = point; break }
            }
        }
        return false
    }
    fun movementClear(from: Location, delta: Vector, width: Double = .4, height: Double = .4): Boolean {
        val steps = ceil(delta.length() / .25).toInt().coerceAtLeast(1)
        return (1..steps).all { inAir(from.clone().add(delta.clone().multiply(it.toDouble() / steps)), width, height) }
    }
    fun face(direction: Vector) {
        val loc = top.clone().setDirection(direction)
        boss?.setRotation(loc.yaw, loc.pitch)
    }
    fun damage(target: LivingEntity, amount: Double, normal: Boolean = false, scaleWithBoss: Boolean = true) {
        if (closed || mode == Mode.VICTORY || !target.isValid || target.isDead) return
        val value = TowerFinalRules.attackDamage(amount, transformed && scaleWithBoss, normal && mode == Mode.EYES)
        scriptedDamage = true; normalDamage = normal
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        if (!normal) target.setMetadata(MonsterDamageClassification.SKILL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            if (normal) MonsterDamageClassification.withNormalAttack(plugin, target) { target.damage(value, boss) }
            else target.damage(value, boss)
        } finally {
            target.removeMetadata("hjh_physical_skill", plugin)
            if (!normal) target.removeMetadata(MonsterDamageClassification.SKILL_DAMAGE_METADATA, plugin)
            scriptedDamage = false; normalDamage = false
        }
    }
    private fun source(event: EntityDamageByEntityEvent): Entity? =
        (event.damager as? Projectile)?.shooter as? Entity ?: event.damager
    fun allowsEyeHit(event: EntityDamageByEntityEvent) =
        event.entity in eyes && source(event) == boss && scriptedDamage && normalDamage && mode == Mode.EYES
    fun blocksAttack(event: EntityDamageByEntityEvent): Boolean {
        if (source(event) == boss && !scriptedDamage) return true
        if (event.entity in eyes && !allowsEyeHit(event)) return true
        val index = twins.indexOf(event.entity)
        if (index >= 0 && !yinExposed && index != yinActive) return true
        if (event.entity == boss && (invulnerable || halfPending)) return true
        return false
    }
    fun onDamage(event: EntityDamageEvent) {
        // 统一盾牌系统处理已激活的RPG盾牌；同时移除普通原版盾牌对蚩尤技能的减伤。
        if (scriptedDamage && !normalDamage && event is EntityDamageByEntityEvent && source(event) == boss &&
            event.entity is Player && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0)
        }
        if (event.entity in eyes && (event !is EntityDamageByEntityEvent || !allowsEyeHit(event))) { event.isCancelled = true; return }
        if (event.entity != boss) return
        if (invulnerable || halfPending || mode == Mode.VICTORY) { event.isCancelled = true; return }
        if (mode == Mode.EYES) event.damage *= .1
        val mob = boss ?: return
        if (!transformed && mob.health - TowerFinalRules.cappedDamage(mob.health, event.finalDamage, false) <= TowerFinalRules.HALF_HEALTH) {
            event.isCancelled = true; mob.health = TowerFinalRules.HALF_HEALTH; halfPending = true
            updateInvulnerability()
        }
    }
    fun onDeath(event: EntityDeathEvent): Boolean {
        if (event.entity == boss) {
            if (!transformed) {
                event.isCancelled = true; event.reviveHealth = 5000.0; halfPending = true
                updateInvulnerability()
            } else win()
            return true
        }
        val index = twins.indexOf(event.entity)
        if (index >= 0) {
            when (window?.death(index, now)) {
                YinYangResult.PUNISH -> resolveTwins(true)
                YinYangResult.SUCCESS -> resolveTwins(false)
                else -> Unit
            }
            return true
        }
        return event.entity in eyes
    }
    private fun win() {
        effects.clear(); flood.close(); twins.forEach(Entity::remove); twins.clear(); removeEyes()
        mechanicBar.removeAll(); windowBar.removeAll(); healthBar.removeAll()
        plugin.clientBridge.cameraShake(manager.active(session), 0)
        manager.active(session).forEach {
            val yao = plugin.playerManager.getData(it.uniqueId)?.race == 4
            it.sendMessage(if (yao) "§4蚩尤：好！这身手，有几分老夫当年的影子，这趟没白来！"
                else "§4蚩尤：可恨……今日算你们命大。但这塔里的血仇，我妖族子弟自会记下——")
        }
        sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.4f, 1f)
        change(Mode.VICTORY)
        animation.tick()
    }
    fun removePlayer(player: Player) {
        flood.removePlayer(player)
        effects.removePlayer(player)
        healthBar.removePlayer(player); mechanicBar.removePlayer(player); windowBar.removePlayer(player)
        plugin.clientBridge.cameraShake(listOf(player), 0)
    }
    fun close() {
        if (closed) return
        animation.close()
        closed = true; effects.clear(); flood.close()
        healthBar.removeAll(); mechanicBar.removeAll(); windowBar.removeAll()
        plugin.clientBridge.cameraShake(manager.active(session), 0)
        manager.active(session).forEach(manager.flight::restore)
    }
}
