package com.hjh_database.dungeon.huomo

import com.hjh_database.client.ClientParticleShape
import com.hjh_database.client.ClientParticleLayer
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.*
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal class HuomoCombat(private val m: HuomoDungeonManager) {
    private val arena get() = m.arena
    private fun point(s: HuomoSession, x: Double, y: Double, z: Double) = Location(s.world, x, y, z)
    private fun alive(s: HuomoSession) = m.session === s && s.phase == HuomoPhase.FIGHT
    fun tick(s: HuomoSession) {
        if (s.core.isDead) { m.victory(s); return }
        check(s.core.isValid) { "火魔核心被异常移除" }
        val crossings = s.milestones.observe(s.core.health)
        s.pendingStages.addAll(crossings.stages); s.pendingShifts += crossings.shifts
        if (s.pendingHeat != 0) { val change = s.pendingHeat; s.pendingHeat = 0; heat(s, change) }
        if ((s.tick - s.combatStart) % 20 == 0L) heat(s, 1)
        shift(s)
        if (!alive(s)) return
        if (s.pendingStages.isNotEmpty() && s.cast?.skill !in setOf(HuomoSkill.COMPRESS, HuomoSkill.WORLD_BURN) && s.shiftStart == null) {
            val next = s.pendingStages.removeFirst()
            cancel(s)
            begin(s, if (next == 3) HuomoSkill.WORLD_BURN else HuomoSkill.COMPRESS, next)
        }
        s.core.isInvulnerable = s.shiftStart != null || s.pendingShifts > 0 || s.pendingStages.isNotEmpty() ||
            s.cast?.skill in setOf(HuomoSkill.COMPRESS, HuomoSkill.WORLD_BURN)
        s.healthBar?.color = if (s.core.isInvulnerable) BarColor.BLUE else BarColor.RED
        s.core.velocity = Vector()
        if (s.tick % 20 == 0L) {
            if (s.core.location.distanceSquared(m.coreLocation(s)) > .01) s.core.teleport(m.coreLocation(s))
            maintain(s)
        }
        val n = m.active(s).size
        if (n == 0) return
        val waitingVeins = s.cast?.let { it.skill == HuomoSkill.VEINS && !it.activated } == true
        if (!waitingVeins && s.tick - s.lastSpawn >= s.furnace.spawnTicks(n)) {
            wave(s, s.cast?.skill == HuomoSkill.VEINS)
            s.lastSpawn = s.tick
        }
        fires(s); pillars(s)
        if (!alive(s)) return
        val cast = s.cast
        if (cast != null) tickCast(s, cast)
        else if (s.pendingStages.isEmpty() && s.tick - s.cooldownStart >= s.furnace.skillTicks(s.stage)) {
            val cycle = when (s.stage) {
                0 -> listOf(HuomoSkill.BREATH, HuomoSkill.MARK)
                1 -> listOf(HuomoSkill.CHAIN, HuomoSkill.MARK, HuomoSkill.ABSORB)
                2 -> listOf(HuomoSkill.MARK, HuomoSkill.VEINS)
                else -> listOf(HuomoSkill.BREATH, HuomoSkill.MARK, HuomoSkill.ABSORB)
            }
            begin(s, if (s.stage == 2) {
                if (s.veinCycle.nextIsVein) HuomoSkill.VEINS else HuomoSkill.MARK
            } else cycle[s.cycle++ % cycle.size])
        }
        if (s.tick % 4 == 0L) {
            s.healthBar?.setTitle("§c火焰魔王" + if (s.coreThermal.relieving(s.tick)) " §b[泄压]" else "")
            s.healthBar?.progress = (s.core.health / 15000.0).coerceIn(0.0, 1.0)
            s.heatBar?.setTitle("§c炉温：${s.furnace.heat.roundToInt()}%")
            s.heatBar?.progress = s.furnace.heat / 100.0
            s.heatBar?.color = if (s.furnace.full) BarColor.RED else BarColor.YELLOW
        }
    }
    private fun heat(s: HuomoSession, thirds: Int) {
        if (s.furnace.addThirds(thirds)) {
            val recovered = s.recovery.claim(maxOf(s.stage, s.milestones.reachedStage))
            if (recovered) s.core.health = (s.core.health + 3000.0).coerceAtMost(15000.0)
            s.nextPillars = s.tick
            m.say(s, if (recovered) "§4炉温已满！火焰魔王恢复生命，躲开岩浆喷柱！" else "§4炉温已满！躲开岩浆喷柱！")
            m.sound(s, Sound.ENTITY_ENDER_DRAGON_GROWL, .6f)
        }
        if (!s.furnace.full) s.pillars.clear()
        m.bonuses(s)
    }
    private fun shift(s: HuomoSession) {
        if (s.shiftStart == null && s.pendingShifts > 0 && s.pendingStages.isEmpty() &&
            s.cast?.skill !in setOf(HuomoSkill.COMPRESS, HuomoSkill.WORLD_BURN)) {
            s.pendingShifts--; s.shiftStart = s.tick
            s.core.isInvulnerable = true
            m.sound(s, Sound.ENTITY_TNT_PRIMED)
        }
        val start = s.shiftStart ?: return
        val elapsed = s.tick - start
        val at = m.coreLocation(s).add(0.0, 1.0, 0.0)
        if (elapsed < 20) {
            if (elapsed % 2 == 0L) m.fx(s, at, ClientParticleShape.SPHERE, 70, 3.5 * (1 - elapsed / 20.0) + .3, red = true)
            return
        }
        m.fx(s, at, ClientParticleShape.BURST, 120, 6.0)
        m.sound(s, Sound.ENTITY_GENERIC_EXPLODE)
        m.plugin.clientBridge.cameraShake(m.active(s), 10, .14f)
        m.active(s).filter { it.location.distanceSquared(at) <= 36.0 }.forEach { p ->
            m.damage(s, p, 15.0)
            if (!p.isDead) p.velocity = Vector(((p.location.x - at.x) * .14).coerceIn(-.6, .6), .8, -1.3)
        }
        s.coreIndex = arena.coreCells.indices.filter { it != s.coreIndex }.random()
        check(s.core.teleport(m.coreLocation(s))) { "火魔核心换位被取消" }
        s.core.velocity = Vector(); s.shiftStart = null
        heat(s, -15)
    }
    private fun maintain(s: HuomoSession) {
        val players = m.active(s)
        s.mobs.toList().forEach { id ->
            val e = Bukkit.getEntity(id) as? Mob
            if (e == null || !e.isValid || e.isDead) { s.mobs.remove(id); return@forEach }
            e.fireTicks = 0
            if (!arena.inside(e.location) || e.location.y < 14) {
                val home = arena.spawnCells.map { it.location(s.world, 1.0) }.firstOrNull { arena.safe(s.world, it, e.width, e.height) }
                if (home != null) { e.teleport(home); e.velocity = Vector() }
            }
            e.target = players.minByOrNull { it.location.distanceSquared(e.location) }
        }
        // 被动射出的箭和火球最多保留一分钟，不跨局、不无限积累。
        s.entities.toList().forEach { id ->
            val e = Bukkit.getEntity(id)
            if (e == null || !e.isValid || e.isDead) { s.entities.remove(id); s.born.remove(id) }
            else if (e is Projectile && e.uniqueId != s.cast?.projectile?.uniqueId && s.tick - (s.born[id] ?: s.tick) >= 1200) m.remove(s, e)
        }
    }
    private fun wave(s: HuomoSession, zombies: Boolean) {
        m.say(s, "§7火焰魔王的手下自地底的熔岩处爬了上来……")
        val wide = arena.spawnCells.indices.filter { arena.safe(s.world, arena.spawnCells[it].location(s.world, 1.0), 2.05, 2.05) }
        val players = m.active(s).size
        if (players == 0) return
        val types = HuomoWaves.ids(wide, zombies, players)
        arena.spawnCells.forEachIndexed { i, c ->
            val type = types[i] ?: return@forEachIndexed
            val at = c.location(s.world, 1.0)
            // 地形变更也按当时实际碰撞空间检查，不能把大型史莱姆塞进障碍。
            val width = if (types[i] == "huomo_pet") 2.05 else .7
            val height = if (types[i] == "huomo_pet") 2.05 else 2.0
            if (arena.safe(s.world, at, width, height)) {
                val e = m.spawn(s, type, at)
                (e as? Mob)?.target = m.active(s).minByOrNull { it.location.distanceSquared(at) }
                m.fx(s, at, ClientParticleShape.RING, 35, 1.3)
            }
        }
    }
    private fun fires(s: HuomoSession) {
        s.fires.removeIf { s.tick >= it.expires }
        if (s.tick % 10 == 0L) s.fires.forEach { f ->
            m.fx(s, f.point, ClientParticleShape.RING, 70, HuomoTiming.POLLUTION_RADIUS, .3)
            m.plugin.clientBridge.emitParticles(m.active(s), f.point, listOf(
                ClientParticleLayer("minecraft:campfire_cosy_smoke", ClientParticleShape.CLOUD, 10,
                    radius = 1.5, height = .5, speed = .01)))
        }
        if (s.tick % 20 == 0L) m.active(s).forEach { p ->
            if (s.fires.any { horizontal(p.location, it.point) <= HuomoTiming.POLLUTION_RADIUS * HuomoTiming.POLLUTION_RADIUS && p.location.y < it.point.y + 1.8 }) m.damage(s, p, 10.0)
        }
    }
    private fun pillars(s: HuomoSession) {
        if (!s.furnace.full) { s.pillars.clear(); return }
        if (s.tick >= s.nextPillars) {
            val points = arena.floorCells.shuffled().asSequence().map { it.location(s.world, 1.0) }
                .filter { arena.safe(s.world, it) }.take(10).toList()
            s.pillars += HuomoPillars(points, s.tick)
            s.nextPillars = s.tick + 60
        }
        s.pillars.toList().forEach { volley ->
            val elapsed = s.tick - volley.start
            if (elapsed >= 40) { s.pillars.remove(volley); return@forEach }
            if (elapsed % 5 == 0L) volley.points.forEach { at ->
                m.fx(s, if (elapsed < 20) at else at.clone().add(0.0, 4.0, 0.0),
                    if (elapsed < 20) ClientParticleShape.RING else ClientParticleShape.CLOUD,
                    100, 5.0, if (elapsed < 20) .1 else 4.0, red = elapsed < 20)
            }
            if (elapsed >= 20) m.active(s).forEach { p ->
                if (p.uniqueId !in volley.hit && volley.points.any { horizontal(p.location, it) <= 25 &&
                        p.boundingBox.minY < it.y + 8 && p.boundingBox.maxY >= it.y }) {
                    volley.hit += p.uniqueId; m.damage(s, p, 30.0)
                }
            }
        }
    }
    private fun begin(s: HuomoSession, skill: HuomoSkill, stage: Int? = null) {
        val c = HuomoCast(skill, s.tick, ++s.serial)
        c.nextStage = stage; s.cast = c
        val title = when (skill) {
            HuomoSkill.BREATH -> "焚息 准备中"
            HuomoSkill.MARK -> "火印"
            HuomoSkill.CHAIN -> "炽焰锁 准备中"
            HuomoSkill.ABSORB -> "吞火 准备中"
            HuomoSkill.VEINS -> "熔岩血脉 准备中"
            HuomoSkill.COMPRESS -> "焚炉压缩"
            HuomoSkill.WORLD_BURN -> "焚世万物"
        }
        s.skillBar?.removeAll()
        s.skillBar = Bukkit.createBossBar("§c$title", BarColor.RED, BarStyle.SOLID)
        m.active(s).forEach { s.skillBar?.addPlayer(it) }
        m.sound(s, Sound.ENTITY_BLAZE_AMBIENT, .7f)
        when (skill) {
            HuomoSkill.BREATH -> {
                s.breathHit.clear()
                m.say(s, "§c火焰魔王：§f站得这么整齐？那就一起烤熟吧！")
                m.say(s, "§6火焰魔王正在积蓄§c焚息§e,避开它面前的区域！退到南侧躲进或角落的残破木架！")
            }
            HuomoSkill.MARK -> nextMark(s, c)
            HuomoSkill.CHAIN -> {
                val target = m.active(s).randomOrNull()
                c.target = target?.uniqueId
                if (target != null) {
                    m.say(s, "§c火焰魔王：§e${target.name}§c想去哪？给我回来！")
                    m.say(s, "§6火焰魔王的炽焰锁已链接§e${target.name}§f身上，距离火焰魔王越远灼烧越强！快靠近火魔身体降低伤害！")
                }
            }
            HuomoSkill.ABSORB -> {
                s.fires.filter { it.expires > s.tick }.shuffled().take(HuomoWaves.seedCap(m.active(s).size)).forEach { f ->
                    val e = m.spawn(s, "huomo_ember", f.point.clone(), true)
                    c.seeds[e.uniqueId] = e.location.clone()
                }
                s.fires.clear()
                m.say(s, "§c火焰魔王：§f这些火苗，是我熊熊燃烧的动力！")
                m.say(s, "§6场上的残焰正在回到火焰魔王体内！击碎正在回流的火种,阻止火焰魔王恢复力量！")
            }
            HuomoSkill.VEINS -> {
                // 清场使用 remove，不触发死亡奖励和尸体堵塞。
                s.mobs.mapNotNull(Bukkit::getEntity).forEach { m.remove(s, it) }
                s.entities.mapNotNull(Bukkit::getEntity).filter { it != s.core && it is LivingEntity }.forEach { m.remove(s, it) }
                arena.veins.flatten().forEach { arena.set(s.world, it, Material.NETHER_WART_BLOCK) }
                c.veins += arena.veins.map { VeinProgress(it.size) }
                m.say(s, "§c火焰魔王：§f我听到了……是火焰生命涌动的声音！给予我新的力量吧！")
                m.say(s, "§6场上出现了火焰魔王的七条“血管”，用自己身体或§b附近§f怪物的§b尸体§f去堵住血液流动！")
            }
            HuomoSkill.COMPRESS, HuomoSkill.WORLD_BURN -> {
                s.furnace.vent(); m.bonuses(s)
                if (!s.furnace.full) s.pillars.clear()
                if (skill == HuomoSkill.COMPRESS) {
                    m.say(s, "§c火焰魔王：§c焚炉，合！")
                    m.say(s, "§6看好时机，起跳躲过这次气浪！也可以躲进角落的残骸避免此次伤害！")
                } else {
                    m.say(s, "§c火焰魔王：§4焚世万物！")
                    m.say(s, "§6热浪太强，快躲进角落的残骸！")
                }
            }
            else -> Unit
        }
    }
    private fun warning(s: HuomoSession, elapsed: Long, duration: Int) {
        s.skillBar?.progress = (1.0 - elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
    }
    private fun tickCast(s: HuomoSession, c: HuomoCast) {
        when (c.skill) {
            HuomoSkill.BREATH -> breath(s, c)
            HuomoSkill.MARK -> mark(s, c)
            HuomoSkill.CHAIN -> chain(s, c)
            HuomoSkill.ABSORB -> absorb(s, c)
            HuomoSkill.VEINS -> veins(s, c)
            HuomoSkill.COMPRESS, HuomoSkill.WORLD_BURN -> compression(s, c)
        }
    }
    private fun protect(s: HuomoSession, c: HuomoCast, p: Player, attack: Long = c.id): Boolean {
        val index = arena.shelter(p) ?: return false
        val before = s.shelters.used[index]
        val protected = s.shelters.protect(index, attack)
        if (protected) c.protectedAttacks += attack
        if (before != s.shelters.used[index]) {
            heat(s, -15)
            arena.damageShelter(s.world, index, s.shelters.used[index])
            m.say(s, "§6${if (index == 0) "西北" else "东北"}避难所承受了冲击，剩余抵挡次数：${3 - s.shelters.used[index]}")
        }
        return protected
    }
    private fun breath(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.start
        if (elapsed < HuomoTiming.BREATH_WARNING) {
            warning(s, elapsed, HuomoTiming.BREATH_WARNING)
            if (elapsed % 4 == 0L) m.fx(s, point(s, 1059.0, 15.1, 907.0), ClientParticleShape.LINE,
                140, .1, .1, point(s, 1119.0, 15.1, 907.0), red = true)
            return
        }
        val progress = HuomoTiming.breathProgress(elapsed)
        if (progress == null) { finish(s, c); return }
        val z = 907.0 - progress * 35.0
        s.skillBar?.setTitle("§c焚息")
        s.skillBar?.progress = (1 - progress).coerceIn(0.0, 1.0)
        if (elapsed % 3 == 0L) for (y in 15..19) {
            m.fx(s, point(s, 1059.0, y + .5, z), ClientParticleShape.LINE, 120, .4,
                end = point(s, 1119.0, y + .5, z))
        }
        if (elapsed % 20 == 0L) m.sound(s, Sound.ITEM_FIRECHARGE_USE, .6f)
        m.active(s).filter { p ->
            val b = p.boundingBox
            b.minX <= 1119 && b.maxX >= 1059 && b.minZ <= z + .6 && b.maxZ >= z - .6 && b.minY < 20 && b.maxY > 15
        }.forEach { p ->
            if (!protect(s, c, p) && s.tick - (s.breathHit[p.uniqueId] ?: -40L) >= 40) {
                s.breathHit[p.uniqueId] = s.tick; m.damage(s, p, 50.0)
            }
        }
    }
    private fun nextMark(s: HuomoSession, c: HuomoCast) {
        val players = m.active(s)
        if (players.isEmpty()) return
        if (players.none { it.uniqueId !in c.chosen }) c.chosen.clear()
        val p = players.filter { it.uniqueId !in c.chosen }.random()
        c.chosen += p.uniqueId; c.target = p.uniqueId
        if (c.shot == 0) {
            m.say(s, "§c火焰魔王：§e${p.name}§f别跑啊,我已经盯上你了！")
            m.say(s, "§6火焰魔王的火印正悬于§e${p.name}§f头上，降落后会造成伤害并留下长时间的火焰领域！")
        }
        c.point = point(s, p.location.x, 30.0, p.location.z)
        c.shotStart = s.tick; c.falling = false; c.fallTicks = 0
        c.projectile = s.world.spawn(c.point!!, LargeFireball::class.java).apply {
            yield = 0f; setIsIncendiary(false); setGravity(false)
            direction = Vector(0.0, -.001, 0.0); velocity = Vector()
            shooter = s.core
            m.track(s, this)
        }
        s.skillBar?.setTitle("§c火印：${c.shot + 1}/${if (s.stage == 0) 3 else 5}")
        s.skillBar?.progress = 1.0
    }
    private fun mark(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.shotStart
        val duration = HuomoTiming.markTrackingTicks(s.stage > 0)
        // 实体被外部清理仍保留逻辑火印，判定不依赖视觉实体存活。
        val projectile = c.projectile
        if (elapsed < duration) {
            val p = c.target?.let(Bukkit::getPlayer)
            if (p != null && m.participant(s, p) && !p.isDead && p.world == s.world) {
                c.point = point(s, p.location.x, 30.0, p.location.z)
            }
            warning(s, elapsed, duration)
        } else {
            c.falling = true
            s.skillBar?.setTitle("§c火印 坠落中")
            c.point?.subtract(0.0, HuomoTiming.fallSpeed(c.fallTicks++), 0.0)
        }
        val at = c.point ?: return
        if (projectile?.isValid == true) { projectile.teleport(at); projectile.velocity = Vector() }
        if (s.tick % 3 == 0L) {
            m.fx(s, at, ClientParticleShape.SPHERE, 40, .8)
            m.fx(s, point(s, at.x, 15.05, at.z), ClientParticleShape.RING, 65, 6.0, .1, red = true)
        }
        if (c.falling && at.y <= 15.0) {
            val landing = point(s, at.x, 15.05, at.z)
            projectile?.let { m.remove(s, it) }; c.projectile = null
            m.fx(s, landing, ClientParticleShape.BURST, 160, 6.0)
            m.sound(s, Sound.ENTITY_GENERIC_EXPLODE)
            val attack = ++s.serial
            m.active(s).filter { horizontal(it.location, landing) <= 36.0 }.forEach { p ->
                if (!protect(s, c, p, attack)) m.damage(s, p, 45.0)
            }
            s.fires += HuomoFire(landing, s.tick + 600)
            c.shot++
            if (c.shot >= if (s.stage == 0) 3 else 5) finish(s, c) else nextMark(s, c)
        }
    }
    private fun chain(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.start
        if (elapsed < 60) { warning(s, elapsed, 60); return }
        if (!c.activated) {
            c.activated = true
            s.skillBar?.setTitle("§c炽焰锁")
        }
        if (elapsed >= 160) { finish(s, c); return }
        val p = c.target?.let(Bukkit::getPlayer)
        if (p == null || !m.participant(s, p) || p.isDead || p.world != s.world) { finish(s, c); return }
        s.skillBar?.progress = (1.0 - (elapsed - 60) / 100.0).coerceIn(0.0, 1.0)
        if (elapsed % 3 == 0L) m.fx(s, point(s, 1089.45, 31.26, 912.70), ClientParticleShape.LINE,
            100, .12, end = p.location.clone().add(0.0, 1.0, 0.0))
        if ((elapsed - 60) % 20 == 0L) m.damage(s, p, HuomoTiming.chainBaseDamage(p.location.z))
    }
    private fun absorb(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.start
        val players = m.active(s).size
        if (players == 0) return
        val cap = HuomoWaves.seedCap(players)
        c.seeds.keys.toList().drop(cap).forEach { id ->
            Bukkit.getEntity(id)?.let { m.remove(s, it) }
            c.seeds.remove(id)
        }
        val end = point(s, 1089.45, 31.26, 912.70)
        if (elapsed < HuomoTiming.ABSORB_WARNING) {
            warning(s, elapsed, HuomoTiming.ABSORB_WARNING)
            if (elapsed % 8 == 0L) m.fx(s, end, ClientParticleShape.SPHERE, 70, 3.0, red = true)
        } else {
            s.skillBar?.setTitle("§c吞火")
            s.skillBar?.progress = (1.0 - (elapsed - HuomoTiming.ABSORB_WARNING) / HuomoTiming.ABSORB_WAIT.toDouble()).coerceIn(0.0, 1.0)
        }
        c.seeds.toMap().forEach { (id, start) ->
            val e = Bukkit.getEntity(id) as? LivingEntity
            if (e == null || e.isDead || !e.isValid) { c.seeds.remove(id); return@forEach }
            if (e.location.distanceSquared(start) > .01) e.teleport(start)
            e.velocity = Vector()
            if (elapsed >= HuomoTiming.ABSORB_WARNING) {
                if (elapsed % 4 == 0L) m.plugin.clientBridge.emitParticles(m.active(s), start, listOf(
                    ClientParticleLayer("minecraft:end_rod", ClientParticleShape.SPHERE, 12, radius = .8, height = 1.0, speed = .02)))
                if (elapsed >= HuomoTiming.ABSORB_WARNING + HuomoTiming.ABSORB_WAIT) {
                    m.remove(s, e); c.seeds.remove(id)
                    heat(s, 15)
                    m.active(s).forEach { m.damage(s, it, 15.0) }
                    m.sound(s, Sound.ENTITY_BLAZE_SHOOT)
                }
            }
        }
        if (elapsed >= HuomoTiming.ABSORB_WARNING && c.seeds.isEmpty()) finish(s, c)
    }
    fun seedKilled(s: HuomoSession, id: UUID) {
        val c = s.cast ?: return
        if (alive(s) && c.skill == HuomoSkill.ABSORB && c.seeds.remove(id) != null) heat(s, -9)
    }
    private fun compression(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.start
        val ultimate = c.skill == HuomoSkill.WORLD_BURN
        val warn = if (ultimate) HuomoTiming.WORLD_BURN_WARNING else HuomoTiming.COMPRESSION_WARNING
        if (elapsed < warn) warning(s, elapsed, warn)
        if (!ultimate && HuomoTiming.compressionJumpAllowed(elapsed)) m.active(s).forEach { p ->
            val jump = s.lastJump[p.uniqueId]
            if (jump != null && HuomoTiming.compressionJumpAllowed(jump - c.start)) c.jumps += p.uniqueId
        }
        if (elapsed == warn.toLong()) {
            arena.floorCells.filterIndexed { i, _ -> i % 12 == 0 }.forEach {
                m.fx(s, it.location(s.world, 1.05), ClientParticleShape.BURST, 15, 2.0, .3)
            }
            m.sound(s, Sound.ENTITY_GENERIC_EXPLODE, .5f)
            m.plugin.clientBridge.cameraShake(m.active(s), 10, .18f)
        }
        // 普通火浪多保留10 tick输入容差，实现预警结束前后0.5秒起跳均有效。
        if (elapsed >= warn + if (ultimate) 0 else HuomoTiming.JUMP_TOLERANCE) {
            m.active(s).forEach { p ->
                if (!ultimate && p.uniqueId in c.jumps) return@forEach
                if (!protect(s, c, p)) m.damage(s, p, if (ultimate) 80.0 else 50.0, .5)
            }
            c.nextStage?.let { s.stage = it; s.cycle = 0 }
            finish(s, c)
        }
    }
    private fun veins(s: HuomoSession, c: HuomoCast) {
        val elapsed = s.tick - c.start
        if (elapsed < HuomoTiming.VEIN_WARNING) { warning(s, elapsed, HuomoTiming.VEIN_WARNING); return }
        if (!c.activated) {
            c.activated = true
            arena.veins.forEach { arena.set(s.world, it.first(), Material.MAGMA_BLOCK) }
            wave(s, true); s.lastSpawn = s.tick
        }
        c.veins.forEachIndexed { index, vein ->
            if (vein.resolved) return@forEachIndexed
            val path = arena.veins[index]
            m.active(s).forEach { p ->
                val key = p.uniqueId to index
                val head = path[vein.head]
                val onBlood = p.location.y in 14.9..15.15 &&
                    head.x == p.location.blockX && head.z == p.location.blockZ &&
                    s.world.getBlockAt(head.x, head.y, head.z).type == Material.MAGMA_BLOCK
                if (!onBlood) c.standing.remove(key)
                else {
                    val since = c.standing.getOrPut(key) { s.tick }
                    if (s.tick - since >= 20 && vein.block()) {
                        arena.restoreVein(s.world, index)
                        // 先反馈成功，再结算代价；即使这次伤害致死，堵塞者也能收到提示。
                        blockedFeedback(s, "§6玩家§e${p.name}§6用身体堵住了第${index + 1}条血脉！")
                        m.damage(s, p, 30.0, furnaceScaled = false)
                        m.fx(s, p.location, ClientParticleShape.BURST, 40, 1.5, red = true)
                    }
                }
            }
            if (HuomoTiming.veinAdvances(elapsed) && !vein.resolved) {
                val arrived = vein.advance()
                c.standing.keys.removeIf { it.second == index }
                arena.set(s.world, path[vein.head], Material.MAGMA_BLOCK)
                m.fx(s, path[vein.head].location(s.world, 1.05), ClientParticleShape.BURST, 20, .5)
                if (arrived) {
                    heat(s, 15)
                    m.active(s).forEach { m.damage(s, it, 15.0) }
                    m.say(s, "§c第${index + 1}条血脉抵达终点，炉温上升！")
                }
            }
            if (!vein.resolved && s.tick % 4 == 0L) {
                val front = path[vein.head].location(s.world, 1.1)
                m.fx(s, front, ClientParticleShape.RING, 35, .65, .15, red = true)
                m.plugin.clientBridge.emitParticles(m.active(s), front, listOf(
                    ClientParticleLayer("minecraft:end_rod", ClientParticleShape.CLOUD, 10,
                        radius = .3, height = .7, speed = .02)))
            }
        }
        val blocked = c.veins.count { it.blocked }
        // 原生连续 BossBar，每堵住一条增加 1/7。
        s.skillBar?.setTitle("§c熔岩血脉：$blocked/7")
        s.skillBar?.style = BarStyle.SOLID
        s.skillBar?.progress = blocked / 7.0
        if (c.veins.all { it.resolved }) finish(s, c)
    }
    fun corpse(s: HuomoSession, at: Location) {
        val c = s.cast ?: return
        if (c.skill != HuomoSkill.VEINS || !c.activated) return
        val closest = c.veins.indices.filter { !c.veins[it].resolved }.mapNotNull { i ->
            arena.veins[i].take(c.veins[i].head + 1).minOfOrNull { it.location(s.world, 1.0).distanceSquared(at) }
                ?.let { i to it }
        }.filter { it.second <= 25.0 }.minByOrNull { it.second }?.first ?: return
        if (c.veins[closest].block()) {
            arena.restoreVein(s.world, closest)
            m.fx(s, at, ClientParticleShape.BURST, 45, 2.0, red = true)
            blockedFeedback(s, "§6近卫的尸体堵住了第${closest + 1}条血脉！")
        }
    }
    private fun blockedFeedback(s: HuomoSession, message: String) {
        heat(s, -9)
        m.say(s, message)
        m.sound(s, Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f)
    }
    fun shelterFeedback(s: HuomoSession) {
        m.active(s).forEach { p ->
            val shelter = arena.shelter(p)
            if (shelter == null) {
                if (s.shelterHud.remove(p.uniqueId) != null) p.sendActionBar(net.kyori.adventure.text.Component.empty())
            } else {
                val state = shelter to (3 - s.shelters.used[shelter]).coerceAtLeast(0)
                if (s.shelterHud.put(p.uniqueId, state) != state || s.tick % 20 == 0L) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text("你已进入残骸保护区，当前残骸可抵挡次数：${state.second}/3"))
                }
            }
        }
    }
    private fun finish(s: HuomoSession, c: HuomoCast) {
        if (s.cast !== c) return
        if (s.stage == 2) when (c.skill) {
            HuomoSkill.MARK -> s.veinCycle.completeMark()
            HuomoSkill.VEINS -> s.veinCycle.completeVein()
            else -> Unit
        }
        if (c.skill == HuomoSkill.BREATH) {
            s.coreThermal.startRelief(s.tick)
            m.say(s, "§7火焰魔王释放大量魔焰后,核心暂时进入了§b[泄压]§7状态！")
            m.say(s, "§6趁现在攻击核心可以降低§c炉温§6！")
        }
        cancel(s)
        s.cooldownStart = s.tick
    }
    fun cancel(s: HuomoSession) {
        val c = s.cast
        if (c != null) {
            c.projectile?.let { m.remove(s, it) }
            c.seeds.keys.mapNotNull(Bukkit::getEntity).forEach { m.remove(s, it) }
            c.protectedAttacks.forEach(s.shelters::forget)
            if (c.skill == HuomoSkill.VEINS) { arena.restoreVeins(s.world); s.lastSpawn = s.tick }
        }
        s.cast = null
        s.skillBar?.removeAll(); s.skillBar = null
    }
    private fun horizontal(a: Location, b: Location): Double {
        val dx = a.x - b.x; val dz = a.z - b.z
        return dx * dx + dz * dz
    }
}
