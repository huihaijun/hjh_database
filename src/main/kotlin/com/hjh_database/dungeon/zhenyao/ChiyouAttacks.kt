package com.hjh_database.dungeon.zhenyao

import com.hjh_database.client.ClientParticleShape
import com.hjh_database.client.ClientParticleLayer
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

/** 普攻与空间技能共用真实位置碰撞；追魂按九段高度分布，每段20个展示实体。 */
internal class ChiyouAttacks(private val f: ZhenyaoFinale) {
    private enum class Normal { IDLE, DASH, WAVE, BURST, CHARGE, BEAM }
    private var normal = Normal.IDLE
    val normalIdle get() = normal == Normal.IDLE
    private var normalStart = 0L
    private var recoverStart = -100L
    private var beamRecoverStart = -100L
    private var eyesRecoverStart = -100L
    private var nextNormal = 0L
    private var nextIsA = true
    private var dashEnd = f.top.clone()
    private var attackOrigin = f.top.clone()
    private var attackDirection = Vector(1, 0, 0)
    private data class Beam(val target: UUID, val id: UUID = UUID.randomUUID(), var aim: Location)
    private val beams = mutableListOf<Beam>()
    private val normalHits = hashSetOf<UUID>()
    private val beamCooldown = hashMapOf<UUID, Long>()

    private data class Rift(val entity: BlockDisplay, var center: Location, val travel: Vector,
                            val u: Vector, val v: Vector, val normal: Vector, val born: Long,
                            val hits: MutableSet<UUID> = hashSetOf())
    private val rifts = mutableListOf<Rift>()
    private var riftPreview: Rift? = null
    private var riftsSent = 0
    val riftsReleased get() = riftsSent == 3
    private val orbPlans = mutableListOf<Location>()
    private data class Orb(var position: Location, val display: ItemDisplay)
    private val orbs = mutableListOf<Orb>()
    val orbsResolved get() = orbs.isEmpty()
    private val orbCooldown = hashMapOf<UUID, Long>()
    private data class Sword(val entity: ItemDisplay, var point: Location, var target: UUID? = null)
    private var sword: Sword? = null
    private var swordSequence = TowerSwordSequence()
    val swordsPrepared get() = sword != null
    val swordsFinished get() = swordSequence.finished
    private var swordLegStart = 0L
    private var pickup: TowerSwordPickup? = null
    val lastSword get() = swordSequence.completed == 4

    fun animationFrame(): ChiyouAnimationFrame {
        val suffix=if(f.transformed) "air" else "ground"
        return when(normal) {
            Normal.DASH -> ChiyouAnimationFrame(if(f.transformed) "p2_a_dash" else "p1_a_dash",normalStart,true)
            Normal.WAVE -> ChiyouAnimationFrame("p1_a_wave",normalStart)
            Normal.BURST -> ChiyouAnimationFrame("p2_a_charge",normalStart)
            Normal.CHARGE -> ChiyouAnimationFrame("beam_charge_$suffix",normalStart)
            Normal.BEAM -> if(f.now-normalStart<10) ChiyouAnimationFrame("beam_white_$suffix",normalStart)
                else ChiyouAnimationFrame("beam_fire_$suffix",normalStart+10,true)
            Normal.IDLE -> when {
                f.now-eyesRecoverStart in 0L..11L -> ChiyouAnimationFrame("eyes_recover",eyesRecoverStart)
                f.now-beamRecoverStart in 0L..9L -> ChiyouAnimationFrame("beam_recover_$suffix",beamRecoverStart)
                f.now-recoverStart in 0L..9L -> ChiyouAnimationFrame("p2_a_release",recoverStart)
                f.flightEnabled -> ChiyouAnimationFrame("idle_air",0,true,2)
                (f.boss?.velocity?.lengthSquared()?:0.0)>.0025 -> ChiyouAnimationFrame("walk_ground",0,true,2)
                else -> ChiyouAnimationFrame("idle_ground",0,true,2)
            }
        }
    }

    fun resetNormal(delay: Long = 0) { stopNormal(); nextNormal = f.now + delay }
    fun resetAfterEyes() { resetNormal(12); eyesRecoverStart=f.now }
    fun stopNormal() {
        beams.forEach { clearBeam(it) }; beams.clear()
        normal = Normal.IDLE; normalHits.clear(); beamCooldown.clear(); f.boss?.velocity = Vector()
    }
    private fun finishNormal() {
        if(normal==Normal.BURST) recoverStart=f.now
        if(normal==Normal.BEAM) beamRecoverStart=f.now
        stopNormal(); nextNormal = f.now + TowerFinalRules.NORMAL_INTERVAL
    }
    fun tickNormal() {
        val boss = f.boss ?: return
        when (normal) {
            Normal.IDLE -> {
                if (f.now < nextNormal) {
                    if(f.now-eyesRecoverStart in 0L..11L || f.now-beamRecoverStart in 0L..9L) f.freeze() else f.idleMotion()
                    return
                }
                normalStart = f.now; normalHits.clear(); beamCooldown.clear()
                if (nextIsA) {
                    val target = f.nearest(boss.location) ?: return
                    attackDirection = target.location.toVector().subtract(boss.location.toVector())
                    if (!f.transformed) attackDirection.y = 0.0
                    if (attackDirection.lengthSquared() < .01) attackDirection = Vector(1, 0, 0)
                    attackDirection.normalize()
                    dashEnd = target.location.clone().subtract(attackDirection.clone().multiply(2))
                    if (!f.transformed) dashEnd.y = 125.0
                    f.effect(dashEnd, ClientParticleShape.RING, 2.0, 0xFF2424, 45)
                    f.perform(Sound.ENTITY_ENDER_DRAGON_FLAP, 0xD6E1ED, pitch = 1.2f)
                    normal = Normal.DASH
                } else {
                    updateChargeBeams()
                    if (beams.isEmpty()) return
                    f.perform(Sound.BLOCK_BEACON_ACTIVATE, 0xFFFFFF, ClientParticleShape.SPHERE)
                    normal = Normal.CHARGE
                }
                nextIsA = !nextIsA
            }
            Normal.DASH -> {
                if (f.moveToward(dashEnd, TowerFinalRules.DASH_BASE_SPEED, detour = false) || f.now - normalStart >= 35) {
                    f.freeze(); attackOrigin = boss.location.clone().add(0.0, 1.0, 0.0)
                    normal = if (f.transformed) Normal.BURST else Normal.WAVE
                    normalStart = f.now
                    if (f.transformed) f.perform(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0xFFFFFF, radius = 6.0)
                }
            }
            Normal.BURST -> {
                f.freeze()
                val center = boss.location.clone().add(0.0, boss.height / 2, 0.0)
                if ((f.now - normalStart) % 4 == 0L) {
                    f.plugin.clientBridge.emitParticles(f.players(), center, listOf(
                        ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 28, 0xFFFFFF, 1.5f, 6.0, .12),
                        ClientParticleLayer("minecraft:dust", ClientParticleShape.SPHERE, 20, 0x181020, 1.5f, 2.5, 2.5)
                    ), ignoreDistance = true)
                }
                if (f.now - normalStart >= 20) {
                    f.perform(Sound.ENTITY_GENERIC_EXPLODE, 0xEAE5F5, ClientParticleShape.SPHERE, 6.0)
                    f.players().filter { it.location.distanceSquared(boss.location) <= 36 }.forEach { player ->
                        f.damage(player, 50.0, normal = true, scaleWithBoss = false)
                        if (!player.isDead) player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 2))
                    }
                    finishNormal()
                }
            }
            Normal.WAVE -> {
                f.freeze()
                val tick = f.now - normalStart
                if (tick % 2 == 0L && tick <= 40) {
                    val center = attackOrigin.clone().add(attackDirection.clone().multiply(tick / 4.0))
                    val right = perpendicular(attackDirection)
                    val up = attackDirection.clone().crossProduct(right).normalize()
                    val from = center.clone().subtract(right.clone().multiply(2))
                    val to = center.clone().add(right.clone().multiply(2))
                    f.effect(from, ClientParticleShape.LINE, .25, 0xB31938, 30, to)
                    normalTargets().forEach { entity ->
                        val relative = entity.location.clone().add(0.0, entity.height / 2, 0.0).toVector().subtract(center.toVector())
                        if (abs(relative.dot(right)) <= 2 + entity.width / 2 &&
                            abs(relative.dot(up)) <= 1.5 + entity.height / 2 &&
                            abs(relative.dot(attackDirection)) <= .45 + entity.width / 2 && normalHits.add(entity.uniqueId)) {
                            f.damage(entity, 50.0, true)
                        }
                    }
                }
                if (tick >= 40) finishNormal()
            }
            Normal.CHARGE -> {
                f.freeze()
                val elapsed = f.now - normalStart
                // 蓄力末段保留瞄准快照，让玩家能离开预警点；出光时不能再覆盖为玩家实时位置。
                if (TowerFinalRules.beamUpdatesChargeAim(elapsed)) updateChargeBeams()
                val direction = beams.firstOrNull()?.aim?.toVector()?.subtract(ChiyouBeamAnchor.origin(boss,f.transformed,elapsed).toVector()) ?: Vector()
                if (direction.lengthSquared() > .000001) f.face(direction)
                if (elapsed % 2 == 0L) beams.forEach { renderBeam(it, ChiyouBeamAnchor.origin(boss,f.transformed,elapsed), false) }
                if (elapsed >= TowerFinalRules.BEAM_CHARGE_TICKS) {
                    normal = Normal.BEAM; normalStart = f.now
                    beams.forEach { renderBeam(it, ChiyouBeamAnchor.origin(boss,f.transformed), false) }
                    f.perform(Sound.ENTITY_WARDEN_SONIC_BOOM, 0xFF3030, ClientParticleShape.SPHERE)
                }
            }
            Normal.BEAM -> {
                f.freeze()
                val players = f.players().associateBy { it.uniqueId }
                if (players.isEmpty() || f.now - normalStart >= 100) { finishNormal(); return }
                val armed = TowerFinalRules.beamIsArmed(f.now - normalStart)
                val origin = ChiyouBeamAnchor.origin(boss,f.transformed)
                // 二阶段每名参与者对应独立光束和瞄准点，交叉光束共用命中冷却。
                for (beam in beams.toList()) {
                    val target = players[beam.target]
                    if (target == null) { clearBeam(beam); beams.remove(beam); continue }
                    if (armed) {
                        val delta = target.eyeLocation.toVector().subtract(beam.aim.toVector())
                        if (delta.lengthSquared() > .000001) beam.aim.add(delta.clone().normalize().multiply(min(TowerFinalRules.BEAM_TRACK_SPEED, delta.length())))
                    }
                    val endpoint = clipBeam(origin, extendBeam(origin, beam.aim))
                    if (armed) normalTargets().forEach { entity ->
                        val center = entity.location.clone().add(0.0, entity.height / 2, 0.0)
                        if (segmentDistance(center, origin, endpoint) <= 1.5 + entity.width / 2 &&
                            f.now >= beamCooldown.getOrDefault(entity.uniqueId, 0L) &&
                            clipBeam(origin, center).distanceSquared(center) < .0001) {
                            beamCooldown[entity.uniqueId] = f.now + 20
                            f.damage(entity, 40.0, true)
                        }
                    }
                    if (f.now % 2 == 0L || f.now - normalStart == TowerFinalRules.BEAM_ARM_TICKS) renderBeam(beam, origin, armed)
                }
                if (beams.isEmpty()) finishNormal()
            }
        }
    }
    private fun normalTargets(): List<LivingEntity> = f.players() +
        if (f.mode == ZhenyaoFinale.Mode.EYES) f.eyes.filter { it.isValid && !it.isDead } else emptyList()

    private fun clipBeam(origin: Location, target: Location): Location {
        val delta = target.toVector().subtract(origin.toVector())
        val distance = delta.length()
        if (distance < .0001) return origin.clone()
        return f.world.rayTraceBlocks(origin, delta.multiply(1 / distance), distance,
            FluidCollisionMode.NEVER, true)?.hitPosition?.toLocation(f.world) ?: target.clone()
    }

    private fun extendBeam(origin: Location, aim: Location): Location {
        val direction = aim.toVector().subtract(origin.toVector())
        if (direction.lengthSquared() < .000001) return origin.clone()
        // 瞄准点只决定方向；视觉光束继续贯穿到塔外，命中仍以墙柱射线为界。
        return origin.clone().add(direction.normalize().multiply(TowerFinalRules.BEAM_LENGTH))
    }

    private fun updateChargeBeams() {
        val targets = if (f.transformed) f.players() else listOfNotNull(f.farthest(f.boss!!.location))
        val ids = targets.map { it.uniqueId }.toSet()
        beams.filter { it.target !in ids }.forEach { clearBeam(it) }
        beams.removeAll { it.target !in ids }
        targets.forEach { player ->
            val existing = beams.firstOrNull { it.target == player.uniqueId }
            if (existing != null) existing.aim = player.eyeLocation
            else beams += Beam(player.uniqueId, aim = player.eyeLocation)
        }
    }
    private fun renderBeam(beam: Beam, origin: Location, damaging: Boolean) =
        f.plugin.clientBridge.beaconBeam(f.players(), beam.id, origin, extendBeam(origin, beam.aim),
            color = if (damaging) 0xFF2020 else 0xFFFFFF)
    private fun clearBeam(beam: Beam, viewers: Collection<Player> = f.players()) =
        f.plugin.clientBridge.beaconBeam(viewers, beam.id, f.top, f.top, 0)
    fun removePlayer(player: Player) { beams.forEach { clearBeam(it, listOf(player)) } }

    fun startRifts() {
        riftsSent = 0
        riftPreview?.entity?.remove()
        riftPreview = createRiftPreview(0)
    }
    fun tickRifts(elapsed: Long): Boolean {
        if (riftsSent < 3 && elapsed >= riftsSent * 40L) {
            val preview = checkNotNull(riftPreview) { "裂空预警展示丢失" }
            // 预警时已锁定方向；释放只改颜色，不重新瞄准，避免品红预警与红色攻击不一致。
            preview.entity.block = Material.RED_STAINED_GLASS.createBlockData()
            rifts += preview.copy(born = f.now)
            riftsSent++
            riftPreview = if (riftsSent < 3) createRiftPreview(riftsSent) else null
            f.perform(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0xFF3030, pitch = .6f)
        }
        // 最后一片发出后仍会飞行4秒；全部消失才算结算完毕。
        return riftsReleased && rifts.isEmpty()
    }
    private fun createRiftPreview(index: Int): Rift? {
            val boss = f.boss ?: return null
            val target = f.farthest(boss.location) ?: return null
            val travel = target.eyeLocation.toVector().subtract(boss.eyeLocation.toVector())
            if (travel.lengthSquared() < .0001) travel.setZ(1.0)
            travel.normalize()
            val yaw = atan2(travel.x, travel.z).toFloat()
            val rotation = when (index) {
                0 -> Quaternionf().rotateX((Math.PI / 2).toFloat())
                1 -> Quaternionf().rotateY(yaw)
                else -> Quaternionf().rotateY(yaw).rotateX((Math.PI / 4).toFloat())
            }
            val u = vector(rotation.transform(Vector3f(1f, 0f, 0f)))
            val v = vector(rotation.transform(Vector3f(0f, 1f, 0f)))
            val n = vector(rotation.transform(Vector3f(0f, 0f, 1f)))
            val center = boss.eyeLocation.clone()
            val entity = f.world.spawn(center, BlockDisplay::class.java)
            f.own(entity); entity.block = Material.MAGENTA_STAINED_GLASS.createBlockData()
            entity.brightness = Display.Brightness(15, 15)
            entity.teleportDuration = 1
            entity.transformation = Transformation(rotation.transform(Vector3f(-10f, -10f, -.08f)), rotation,
                Vector3f(20f, 20f, .16f), Quaternionf())
            entity.displayWidth = 30f; entity.displayHeight = 30f
            return Rift(entity, center, travel, u, v, n, f.now)
    }
    fun tickTransient() {
        if (f.now % 10 == 0L) riftPreview?.let { preview ->
            f.effect(preview.center, ClientParticleShape.LINE, .08, 0xE03CCD, 16,
                preview.center.clone().add(preview.travel.clone().multiply(8)))
        }
        for (rift in rifts.toList()) {
            if (f.now - rift.born >= 80) { rift.entity.remove(); rifts.remove(rift); continue }
            val step = rift.travel.clone().multiply(TowerFinalRules.movementSpeed(TowerFinalRules.DASH_BASE_SPEED, f.transformed))
            rift.center.add(step); rift.entity.teleport(rift.center)
            f.players().forEach { player ->
                val box = player.boundingBox
                val p = box.center.subtract(rift.center.toVector())
                fun extent(axis: Vector) = abs(axis.x) * box.widthX / 2 + abs(axis.y) * box.height / 2 + abs(axis.z) * box.widthZ / 2
                if (abs(p.dot(rift.u)) <= 10 + extent(rift.u) && abs(p.dot(rift.v)) <= 10 + extent(rift.v) &&
                    abs(p.dot(rift.normal)) <= .4 + abs(step.dot(rift.normal)) + extent(rift.normal) && rift.hits.add(player.uniqueId)) {
                    f.damage(player, 50.0)
                }
            }
        }
    }

    fun prepareOrbs() {
        clearOrbs()
        val players = f.players()
        if (players.isEmpty()) return
        for (floor in 2..10) {
            val range = TowerFinalRules.orbHeightRange(floor)
            val candidates = sequence {
                repeat(240) {
                    val y = Random.nextDouble(range.start, range.endInclusive)
                    val radius = f.radiusAt(y) - 1
                    yield(Location(f.world, -1001.5 + Random.nextDouble(-radius, radius), y,
                        3006.5 + Random.nextDouble(-radius, radius)))
                }
                // 随机点被柱梁遮挡时，仍在同一高度段内找点，保证上下分布而非退回玩家身边。
                for (ratio in listOf(.25, .5, .75)) for (x in -1027..-976 step 3) for (z in 2981..3032 step 3) {
                    yield(Location(f.world, x + .5, range.start + (range.endInclusive - range.start) * ratio, z + .5))
                }
            }
            var count = 0
            for (point in candidates) {
                if (f.inAir(point, .8, .8) && players.none { it.location.distanceSquared(point) < 9 } &&
                    orbPlans.none { it.distanceSquared(point) < 2.25 }) {
                    orbPlans += point
                    if (++count == TowerFinalRules.ORBS_PER_FLOOR) break
                }
            }
            check(count == TowerFinalRules.ORBS_PER_FLOOR) { "第${floor}层高度缺少追魂球安全生成空间" }
        }
    }
    fun previewOrbs() {
        if (f.now % 10 != 0L) return
        orbPlans.forEach { f.effect(it, ClientParticleShape.SPHERE, .35, 0xDD285B, 4) }
    }
    fun releaseOrbs() {
        f.perform(Sound.ENTITY_EVOKER_CAST_SPELL, 0x26BDB5, ClientParticleShape.SPHERE, 3.5)
        orbPlans.forEach { point ->
            // 屏障的方块模型不可见，物品展示会直接渲染红色屏障图标，无需玩家手持屏障。
            val display = f.world.spawn(point, ItemDisplay::class.java)
            f.own(display); display.setItemStack(ItemStack(Material.BARRIER))
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            display.billboard = Display.Billboard.CENTER
            display.brightness = Display.Brightness(15, 15); display.teleportDuration = 1
            display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(.8f), Quaternionf())
            orbs += Orb(point.clone(), display)
        }
        orbPlans.clear()
    }
    fun tickOrbs() {
        val players = f.players()
        for (orb in orbs.toList()) {
            val target = players.filter { it.location.distanceSquared(orb.position) <= 900 }
                .minByOrNull { it.location.distanceSquared(orb.position) }
            if (target != null) {
                val delta = target.eyeLocation.toVector().subtract(orb.position.toVector())
                if (delta.lengthSquared() > .001) delta.normalize().multiply(.15)
                if (!f.movementClear(orb.position, delta)) { orb.display.remove(); orbs.remove(orb); continue }
                val next = orb.position.clone().add(delta)
                val hit = players.firstOrNull {
                    segmentDistance(it.location.clone().add(0.0, it.height / 2, 0.0), orb.position, next) <= .6 + it.width / 2
                }
                if (hit != null) {
                    if (f.now >= orbCooldown.getOrDefault(hit.uniqueId, 0L)) {
                        orbCooldown[hit.uniqueId] = f.now + 10; f.damage(hit, 25.0)
                    }
                    orb.display.remove(); orbs.remove(orb); continue
                }
                orb.position = next
                orb.display.teleport(next)
            }
        }
    }
    fun clearOrbs() { orbs.forEach { it.display.remove() }; orbs.clear(); orbPlans.clear(); orbCooldown.clear() }

    fun prepareSwords() {
        clearSwords()
        swordSequence = TowerSwordSequence()
        prepareNextSword()
    }
    fun prepareNextSword() {
        check(sword == null && !swordSequence.finished)
        val entity = f.world.spawn(f.boss!!.location.clone().add(0.0, 3.0, 0.0), ItemDisplay::class.java)
        f.own(entity); entity.setItemStack(ItemStack(Material.NETHERITE_SWORD))
        entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
        entity.billboard = Display.Billboard.FIXED
        entity.setRotation(0f, 0f)
        entity.brightness = Display.Brightness(15, 15)
        entity.teleportDuration = 1
        entity.interpolationDuration = 0
        entity.transformation = Transformation(Vector3f(), Quaternionf().rotateZ((-3 * Math.PI / 4).toFloat()), Vector3f(1.8f), Quaternionf())
        sword = Sword(entity, entity.location.clone())
    }
    fun orbitSwords(elapsed: Long) {
        val current = sword ?: return
        // The preview blade is held from tick 36 until release: do not show a second world blade.
        if(elapsed==36L) current.entity.setItemStack(ItemStack(Material.AIR))
        val center = f.boss!!.location.clone().add(0.0, 3.0, 0.0)
        val angle = elapsed * .08
        current.point = center.add(cos(angle) * 3, .3 * sin(angle), sin(angle) * 3)
        current.entity.teleport(current.point)
    }
    fun beginSwordTrack() {
        val current = checkNotNull(sword)
        current.entity.setItemStack(ItemStack(Material.NETHERITE_SWORD))
        val boss=checkNotNull(f.boss)
        current.point=ChiyouSwordAnchor.release(boss)
        current.entity.teleport(current.point)
        current.target = f.players().randomOrNull()?.uniqueId
        swordSequence.startTracking(f.now)
        f.perform(Sound.ENTITY_ARROW_SHOOT, 0xFFDB8A, pitch = 1.2f)
        f.players().firstOrNull { it.uniqueId == current.target }?.let {
            f.broadcast("§6第${swordSequence.completed + 1}把魔剑正在追踪§e${it.name}§6，注意蚩尤随后发动的收剑斩击！")
        }
    }
    fun trackSword(): Boolean {
        val current = checkNotNull(sword)
        if (swordSequence.update(f.now)) {
            f.effect(current.point, ClientParticleShape.RING, 15.0, 0xFF3030, 70)
            return true
        }
        val players = f.players()
        val target = players.firstOrNull { it.uniqueId == current.target } ?: players.randomOrNull()?.also { current.target = it.uniqueId }
        if (target == null) return false
        val delta = target.location.clone().add(0.0, 1.0, 0.0).toVector().subtract(current.point.toVector())
        if (delta.lengthSquared() < .0001) return false
        val step = delta.clone().normalize().multiply(TowerFinalRules.swordStep(delta.length()))
        val mob = f.boss ?: return false
        // 停剑处必须容得下放大后的蚩尤，避免追到梁缝里后收剑只能穿墙。
        if (f.movementClear(current.point, step, mob.width, mob.height)) {
            val rotation = Quaternionf().rotationTo(Vector3f(1f, 1f, 0f).normalize(),
                Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat()).normalize())
            current.entity.transformation = Transformation(Vector3f(), rotation, Vector3f(1.8f), Quaternionf())
            val previous = current.point.clone()
            val next = current.point.clone().add(step)
            check(current.entity.teleport(next)) { "追魂魔剑展示移动失败" }
            current.point = next
            if (f.now % 2 == 0L) f.plugin.clientBridge.emitParticles(f.players(), previous, listOf(
                ClientParticleLayer("minecraft:end_rod", ClientParticleShape.LINE, 5, radius = .04, height = .04),
                ClientParticleLayer("minecraft:crit", ClientParticleShape.LINE, 3, radius = .08, height = .08)
            ), next, ignoreDistance = true)
        }
        return false
    }
    fun beginSwordDash() {
        swordLegStart = f.now
        f.perform(Sound.ITEM_TRIDENT_RETURN, 0xDDEAFF, pitch = .7f)
    }
    fun tickSwordDash(): Boolean {
        val current = checkNotNull(sword)
        val boss = f.boss ?: return false
        val reached = f.moveToward(current.point, TowerFinalRules.DASH_BASE_SPEED)
        if (f.now % 5 == 0L) f.effect(boss.eyeLocation, ClientParticleShape.CLOUD, 1.0, 0xDDEAFF, 10)
        if (!reached && f.now - swordLegStart < 240) return false
        if (reached) { f.freeze(); return true }
        f.broadcast("§7魔剑被塔内障碍阻隔，蚩尤散去了这把魔剑。")
        current.entity.remove(); sword=null; swordSequence.collected()
        return true
    }
    fun beginSwordPickup() { pickup=TowerSwordPickup(f.now) }
    fun tickSwordPickup(): Boolean {
        val current=checkNotNull(sword)
        val boss=f.boss?:return false
        for(event in checkNotNull(pickup).update(f.now)) when(event) {
            TowerSwordPickup.Event.GRAB -> current.entity.setItemStack(ItemStack(Material.AIR))
            TowerSwordPickup.Event.HIT -> {
            f.plugin.clientBridge.emitParticles(f.players(), boss.location.clone().add(0.0, 1.3, 0.0), listOf(
                ClientParticleLayer("minecraft:sweep_attack", ClientParticleShape.RING, 16, radius = 10.0, height = .4),
                ClientParticleLayer("minecraft:crit", ClientParticleShape.RING, 24, radius = 15.0, height = 1.0, speed = .04),
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 72, 0xE72F48, 1.4f, 15.0, .08)
            ), boss.location.clone().add(boss.location.direction.multiply(15)), ignoreDistance = true)
            f.players().filter { it.location.distanceSquared(boss.location) <= 225 }.forEach {
                f.damage(it, 60.0, scaleWithBoss = false)
            }
            f.perform(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0xE72F48, radius = 4.0, pitch = .6f)
            }
            TowerSwordPickup.Event.FINISH -> {
                current.entity.remove();sword=null;pickup=null;swordSequence.collected();return true
            }
        }
        return false
    }
    private fun clearSwords() { sword?.entity?.remove(); sword = null; pickup=null }
    fun clear() {
        stopNormal(); clearOrbs(); clearSwords(); riftPreview?.entity?.remove(); riftPreview = null
        rifts.forEach { it.entity.remove() }; rifts.clear()
    }
    private fun perpendicular(direction: Vector): Vector {
        val up = if (abs(direction.y) > .9) Vector(1, 0, 0) else Vector(0, 1, 0)
        return direction.clone().crossProduct(up).normalize()
    }
    private fun vector(v: Vector3f) = Vector(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    private fun segmentDistance(point: Location, start: Location, end: Location): Double {
        val line = end.toVector().subtract(start.toVector())
        val offset = point.toVector().subtract(start.toVector())
        val t = if (line.lengthSquared() < .0001) 0.0 else (offset.dot(line) / line.lengthSquared()).coerceIn(0.0, 1.0)
        return offset.subtract(line.multiply(t)).length()
    }
}
