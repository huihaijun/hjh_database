package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.skill.element_zf.FormationCast
import com.hjh_database.skill.element_zf.FormationDamageEvent
import com.hjh_database.weapon.CrystalData
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** 普通回流保持 BaseRefluxSkill 原规则；风场和火种均按施法者隔离。 */
class XunlilingshuSkill(plugin: Hjh_database) : BaseRefluxSkill(plugin) {
    class CastPlan
    private data class Wind(val until: Long)
    private data class State(var wind: Wind? = null, val seeds: MutableMap<UUID, Long> = HashMap())
    private data class BatchKey(val owner: UUID, val castId: UUID, val tick: Int)
    private data class Hit(val location: Location, var damage: Double, val planting: Boolean, val time: Long)
    private data class Batch(val cast: FormationCast?, val hits: MutableMap<UUID, Hit> = LinkedHashMap())
    private data class FlameArc(val owner: UUID, val start: Location, val end: Location, var frame: Int = 0)

    private val states = HashMap<UUID, State>()
    private val prepared = HashMap<UUID, CastPlan>()
    private val batches = LinkedHashMap<BatchKey, Batch>()
    private val arcs = ArrayList<FlameArc>()
    private var tickTask: BukkitTask? = null
    private var batchTask: BukkitTask? = null
    private var visualTicks = 0

    override val accessoryId = "xunlilingshu"
    override fun getThresholdPercent(crystalData: CrystalData) = 0.5
    override fun getCostPerLevel(crystalData: CrystalData) = 1.5
    override fun getTriggerProbability(crystalData: CrystalData) = 0.65

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState =
        super.getHudState(player, item, crystalData).copy(
            // 保留基类的实际冷却，供模组绘制10秒冷却遮罩；风场另用持续时间条显示。
            effectEndMillis = states[player.uniqueId]?.wind?.until ?: 0L,
            effectDurationMillis = WIND_MILLIS
        )

    fun prepareCast(player: Player): CastPlan? {
        if (getTrackedCooldownEnd(player) > System.currentTimeMillis()) return null
        return CastPlan().also { prepared[player.uniqueId] = it }
    }

    /** 只在施法成功后开启风场，失败、湿气中断等不会产生风场或冷却。 */
    fun completeCast(player: Player, plan: CastPlan, success: Boolean) {
        if (prepared.remove(player.uniqueId) !== plan || !success) return
        val now = System.currentTimeMillis()
        states.getOrPut(player.uniqueId, ::State).wind = Wind(now + WIND_MILLIS)
        startTrackedCooldown(player, COOLDOWN_MILLIS, now)
        plugin.playerManager.getPlayerData(player)?.let { data ->
            data.tempBonuses[ZF_KEY] = 0.35
            data.tempBonuses[SPEED_KEY] = 0.15
            plugin.playerManager.updateStats(player)
        }
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.65f, 1.35f)
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&a&l饰品技【风火归元】发动！"))
        ensureTickTask()
    }

    fun onFormationHit(event: FormationDamageEvent) {
        if (event.element == null || !hasMonsterTags(event.target)) return
        val owner = event.caster.uniqueId
        val state = states[owner]
        if (state == null && owner !in prepared) return
        val now = System.currentTimeMillis()
        val planting = (state?.wind?.until ?: 0L) > now || owner in prepared
        if (!planting && (state?.seeds?.get(event.target.uniqueId) ?: 0L) <= now) return

        // 同次施法在同一 tick 内的命中统一排队；下一 tick 才选传导对象。
        val key = BatchKey(owner, event.cast?.id ?: FALLBACK_CAST_ID, Bukkit.getCurrentTick())
        val batch = batches.getOrPut(key) { Batch(event.cast) }
        val previous = batch.hits[event.target.uniqueId]
        if (previous == null) {
            batch.hits[event.target.uniqueId] = Hit(event.target.location.clone(), event.actualDamage, planting, now)
        } else {
            previous.damage += event.actualDamage
        }
        if (batchTask == null) {
            batchTask = Bukkit.getScheduler().runTask(plugin, Runnable {
                batchTask = null
                val ready = batches.toMap()
                batches.clear()
                for ((batchKey, pending) in ready) resolveBatch(batchKey.owner, pending)
            })
        }
    }

    private fun resolveBatch(owner: UUID, batch: Batch) {
        val player = Bukkit.getPlayer(owner) ?: return
        val state = states[owner] ?: return
        if (!player.isOnline || player.isDead) return
        val now = System.currentTimeMillis()
        state.seeds.entries.removeIf { it.value <= now }
        for ((id, hit) in batch.hits) {
            if (!hit.planting) continue
            val target = Bukkit.getEntity(id) as? LivingEntity ?: continue
            if (isMonster(target)) state.seeds[id] = hit.time + SEED_MILLIS
        }
        val sources = batch.hits.filter { !it.value.planting && it.value.damage > 0.0 }.keys.toList()
        if (sources.isEmpty()) {
            ensureTickTask()
            return
        }

        val recipients = state.seeds.keys.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }
            .filter(::isMonster).associateBy { it.uniqueId }
        val transfers = FireSeedConduction.plan(
            sources,
            state.seeds.keys,
            (batch.cast?.hitTargets.orEmpty() + batch.hits.keys).toSet(),
            { source ->
                val origin = batch.hits.getValue(source).location
                recipients.values.filter {
                    it.world == origin.world && it.location.distanceSquared(origin) <= CONDUCTION_RADIUS_SQUARED
                }.map { it.uniqueId }
            }
        )
        // 先消耗全部参与者的火种，再触发任何伤害，避免伤害回调与连锁递归。
        for (transfer in transfers) {
            state.seeds.remove(transfer.source)
            transfer.targets.forEach(state.seeds::remove)
        }
        for (transfer in transfers) {
            val hit = batch.hits.getValue(transfer.source)
            for (targetId in transfer.targets) {
                val target = recipients[targetId] ?: continue
                arcs.add(FlameArc(owner, hit.location.clone().add(0.0, 0.8, 0.0), entityCenter(target)))
                // 不发布元素阵法命中事件，传导伤害不能再次种火或传导。
                FormationMagicDamage.deal(plugin, player, target, hit.damage * 0.5, suppressKnockback = true)
            }
        }
        ensureTickTask()
    }

    private fun ensureTickTask() {
        if (tickTask != null) return
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            for ((owner, state) in states.toMap()) {
                val player = Bukkit.getPlayer(owner)
                if (player == null || !player.isOnline || player.isDead ||
                    (visualTicks % 10 == 0 && !plugin.accessorySkillManager.isXunlilingshuActive(player))) {
                    if (player != null) cleanup(player) else states.remove(owner)
                    continue
                }
                if (state.wind != null && state.wind!!.until <= now) {
                    state.wind = null
                    removeBonuses(player)
                    player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&e&l【巽风】风场已结束！"))
                }
                if (visualTicks % 10 == 0) {
                    state.seeds.entries.removeIf { it.value <= now }
                    if (state.wind != null) {
                        renderWind(player)
                        spreadSeeds(player, state, now)
                    }
                    renderSeeds(state)
                }
                if (state.wind == null && state.seeds.isEmpty()) states.remove(owner)
            }
            renderArcs()
            visualTicks += 2
            if (states.isEmpty() && arcs.isEmpty()) {
                tickTask?.cancel()
                tickTask = null
            }
        }, 2L, 2L)
    }

    /** 风场以当前玩家位置和水平朝向为准，随移动、转身更新。 */
    private fun forward(player: Player): Vector {
        val yaw = Math.toRadians(player.location.yaw.toDouble())
        return Vector(-sin(yaw), 0.0, cos(yaw))
    }

    private fun inWind(player: Player, target: LivingEntity): Boolean {
        if (player.world != target.world) return false
        val offset = target.location.toVector().subtract(player.location.toVector())
        return FollowingWindGeometry.contains(offset.x, offset.y, offset.z, player.location.yaw.toDouble())
    }

    private fun spreadSeeds(player: Player, state: State, now: Long) {
        val center = player.location.add(forward(player).multiply(5.0))
        val targets = player.world.getNearbyEntities(center, 11.0, 5.0, 11.0)
            .filterIsInstance<LivingEntity>().filter { isMonster(it) && inWind(player, it) }
        val donors = targets.filter { (state.seeds[it.uniqueId] ?: 0L) > now }
        if (donors.isEmpty()) return
        // 用本轮开始时的火种快照传播，已存在的火种不互相刷新寿命。
        for (target in targets) {
            if ((state.seeds[target.uniqueId] ?: 0L) > now) continue
            val donor = donors.minByOrNull { it.location.distanceSquared(target.location) } ?: continue
            state.seeds[target.uniqueId] = now + SEED_MILLIS
            arcs.add(FlameArc(player.uniqueId, entityCenter(donor), entityCenter(target)))
        }
    }

    private fun renderWind(player: Player) {
        val origin = player.location.clone().add(0.0, 0.25, 0.0)
        val direction = forward(player)
        val right = Vector(direction.z, 0.0, -direction.x)
        val phase = (visualTicks % 20) / 2.0
        for (side in listOf(-4.0, -2.0, 0.0, 2.0, 4.0)) {
            for (step in 0..3) {
                val distance = (phase + step * 2.5) % 10.0
                val point = origin.clone().add(direction.clone().multiply(distance)).add(right.clone().multiply(side))
                player.world.spawnParticle(Particle.CLOUD, point, 0, direction.x * 0.16, 0.035, direction.z * 0.16, 1.0)
            }
        }
        for (step in 0..10 step 2) {
            for (side in listOf(-4.0, 4.0)) {
                val point = origin.clone().add(direction.clone().multiply(step.toDouble())).add(right.clone().multiply(side))
                player.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, WIND_DUST)
            }
        }
    }

    private fun renderSeeds(state: State) {
        for (id in state.seeds.keys) {
            val target = Bukkit.getEntity(id) as? LivingEntity ?: continue
            if (isMonster(target)) target.world.spawnParticle(Particle.SMALL_FLAME, entityCenter(target), 2, 0.2, 0.25, 0.2, 0.005)
        }
    }

    /** 共用一个动画任务，四帧抛物线，不生成投射物/盔甲架实体。 */
    private fun renderArcs() {
        val iterator = arcs.iterator()
        while (iterator.hasNext()) {
            val arc = iterator.next()
            for (substep in 0..2) {
                val t = ((arc.frame * 3 + substep + 1) / 12.0).coerceAtMost(1.0)
                val point = arc.start.clone().multiply(1.0 - t).add(arc.end.clone().multiply(t))
                    .add(0.0, 4.0 * t * (1.0 - t) * 2.0, 0.0)
                point.world.spawnParticle(Particle.FLAME, point, 2, 0.035, 0.035, 0.035, 0.005)
            }
            arc.frame++
            if (arc.frame >= 4) iterator.remove()
        }
    }

    private fun entityCenter(target: LivingEntity) = target.location.add(0.0, target.height * 0.55, 0.0)
    private fun hasMonsterTags(target: LivingEntity) =
        target !is Player && target.scoreboardTags.contains("panling") && target.scoreboardTags.contains("monster")
    private fun isMonster(target: LivingEntity) = target.isValid && !target.isDead && hasMonsterTags(target)

    private fun removeBonuses(player: Player) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val zf = data.tempBonuses.remove(ZF_KEY)
        val speed = data.tempBonuses.remove(SPEED_KEY)
        if (zf != null || speed != null) plugin.playerManager.updateStats(player)
    }

    fun cleanup(player: Player) {
        states.remove(player.uniqueId)
        prepared.remove(player.uniqueId)
        batches.keys.removeIf { it.owner == player.uniqueId }
        arcs.removeIf { it.owner == player.uniqueId }
        removeBonuses(player)
    }

    fun shutdown() {
        states.keys.toList().forEach { Bukkit.getPlayer(it)?.let(::cleanup) }
        states.clear()
        prepared.clear()
        batches.clear()
        arcs.clear()
        tickTask?.cancel()
        batchTask?.cancel()
        tickTask = null
        batchTask = null
        shutdownHudState()
    }

    companion object {
        private const val COOLDOWN_MILLIS = 10_000L
        private const val WIND_MILLIS = 5_000L
        private const val SEED_MILLIS = 15_000L
        private const val CONDUCTION_RADIUS_SQUARED = 100.0
        private const val ZF_KEY = "xunlilingshu::zf_str_percent"
        private const val SPEED_KEY = "xunlilingshu::speed_percent"
        private val FALLBACK_CAST_ID = UUID(0L, 0L)
        private val WIND_DUST = Particle.DustOptions(Color.fromRGB(155, 235, 220), 0.85f)
    }
}
