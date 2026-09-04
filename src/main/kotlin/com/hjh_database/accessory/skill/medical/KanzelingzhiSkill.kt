package com.hjh_database.accessory.skill.medical

import com.hjh_database.Hjh_database
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.weapon.CrystalData
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectTypeCategory
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** 五阶医师饰品“坎泽灵枝”——生灵润泽。 */
class KanzelingzhiSkill(plugin: Hjh_database) : BaseMedicalOverflowSkill(plugin) {
    companion object {
        private const val DOMAIN_RADIUS = 6.0
        private const val DOMAIN_DURATION_TICKS = 100
        private const val DOMAIN_INTERVAL_TICKS = 5
        private const val OFFENSE_BONUS = 0.15

        // 同一种饰品统一使用同一组来源标签：多个领域只刷新时间，不会互相叠加。
        private const val ATTACK_BONUS_KEY = "kanzelingzhi::attack_percent"
        private const val ARCHER_BONUS_KEY = "kanzelingzhi::archer_damage_percent"
        private const val FORMATION_BONUS_KEY = "kanzelingzhi::zf_str_percent"
    }

    private val buffExpiresAt = HashMap<UUID, Long>()
    private val buffCleanupTasks = HashMap<UUID, BukkitTask>()
    private val activeDomainTasks = HashSet<BukkitTask>()
    private val spiritDust = Particle.DustOptions(Color.fromRGB(91, 224, 181), 1.05f)

    override fun getBirdDisplayName(): String = "生灵鸟"

    override fun getActionBarSkillName(): String = "生灵润泽"

    override fun getBirdCustomName(): String = "§b生灵鸟"

    override fun getHealStorageMultiplier(crystalData: CrystalData): Double = 0.5

    /** “最多追击3次”指首个目标之后还可继续寻找三次。 */
    override fun getMaxDamageRetargets(): Int = 3

    override fun getOverflowRetargetRange(): Double = 16.0

    override fun getTrailDust(): Particle.DustOptions = spiritDust

    override fun getKnockbackStrength(): Double = 0.35

    override fun canKnockback(target: LivingEntity): Boolean =
        !target.scoreboardTags.contains("instance_boss")

    override fun onSpiritBirdResolved(owner: Player, location: Location) {
        if (!owner.isOnline || location.world == null) return
        deployLifeDomain(owner, location.clone().add(0.0, 0.65, 0.0))
    }

    fun cleanup(player: Player) {
        buffCleanupTasks.remove(player.uniqueId)?.cancel()
        buffExpiresAt.remove(player.uniqueId)
        removeOffenseBuff(player)
    }

    fun shutdown() {
        activeDomainTasks.toList().forEach(BukkitTask::cancel)
        activeDomainTasks.clear()
        buffCleanupTasks.values.toList().forEach(BukkitTask::cancel)
        buffCleanupTasks.clear()
        buffExpiresAt.keys.toList().forEach { uuid ->
            Bukkit.getPlayer(uuid)?.let(::removeOffenseBuff)
        }
        buffExpiresAt.clear()
    }

    private fun deployLifeDomain(owner: Player, center: Location) {
        val world = center.world ?: return
        val domainEndsAt = System.currentTimeMillis() + DOMAIN_DURATION_TICKS * 50L
        val effectKey = "kanzelingzhi_domain_${owner.uniqueId}_${System.nanoTime()}"

        // 客户端负责在5秒内采样领域粒子，服务端仅发送一次描述，避免高频粒子包造成压力。
        plugin.clientBridge.emitTimedParticles(
            world.players,
            center,
            listOf(
                ClientParticleLayer(
                    "minecraft:dust",
                    ClientParticleShape.RING,
                    42,
                    0x5BE0B5,
                    1.15f,
                    DOMAIN_RADIUS,
                    .18,
                    .018
                ),
                ClientParticleLayer(
                    "minecraft:spore_blossom_air",
                    ClientParticleShape.CLOUD,
                    16,
                    radius = DOMAIN_RADIUS * .82,
                    height = 1.35,
                    speed = .012
                ),
                ClientParticleLayer(
                    "minecraft:happy_villager",
                    ClientParticleShape.RING,
                    10,
                    radius = DOMAIN_RADIUS * .62,
                    height = .35,
                    speed = .01
                )
            ),
            durationTicks = DOMAIN_DURATION_TICKS,
            intervalTicks = DOMAIN_INTERVAL_TICKS,
            key = effectKey
        )

        world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, .75f, 1.45f)
        world.playSound(center, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, .6f, 1.3f)

        // “展开瞬间”仅结算一次驱散；领域中的进攻属性则在5秒内持续刷新。
        alliesInDomain(owner, center).forEach { ally ->
            removeHarmfulEffects(ally)
            refreshOffenseBuff(ally, domainEndsAt)
        }

        lateinit var task: BukkitTask
        task = object : BukkitRunnable() {
            var livedTicks = 0

            override fun run() {
                if (livedTicks >= DOMAIN_DURATION_TICKS || !owner.isOnline || center.world == null) {
                    activeDomainTasks.remove(task)
                    cancel()
                    return
                }

                alliesInDomain(owner, center).forEach { refreshOffenseBuff(it, domainEndsAt) }
                drawVanillaDomainFallback(center, livedTicks)
                livedTicks += DOMAIN_INTERVAL_TICKS
            }
        }.runTaskTimer(plugin, 0L, DOMAIN_INTERVAL_TICKS.toLong())
        activeDomainTasks += task
    }

    private fun alliesInDomain(owner: Player, center: Location): List<Player> {
        val world = center.world ?: return emptyList()
        val radiusSquared = DOMAIN_RADIUS * DOMAIN_RADIUS
        val allies = world.getNearbyEntities(center, DOMAIN_RADIUS, DOMAIN_RADIUS, DOMAIN_RADIUS)
            .asSequence()
            .filterIsInstance<Player>()
            .filter { it.isOnline && !it.isDead && it.location.distanceSquared(center) <= radiusSquared }
            .toMutableList()
        if (
            owner.world == world &&
            owner.location.distanceSquared(center) <= radiusSquared &&
            owner !in allies
        ) {
            allies.add(owner)
        }
        return allies
    }

    private fun removeHarmfulEffects(player: Player) {
        player.activePotionEffects
            .asSequence()
            .filter { it.type.category == PotionEffectTypeCategory.HARMFUL }
            .map { it.type }
            .toList()
            .forEach(player::removePotionEffect)

        player.world.spawnParticle(Particle.WAX_ON, player.location.add(0.0, 1.0, 0.0), 10, .35, .5, .35, .025)
        player.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, .45f, 1.7f)
    }

    private fun refreshOffenseBuff(player: Player, endMillis: Long) {
        val uuid = player.uniqueId
        val previousEnd = buffExpiresAt[uuid] ?: 0L
        if (endMillis > previousEnd) buffExpiresAt[uuid] = endMillis

        val data = plugin.playerManager.getPlayerData(player) ?: return
        val alreadyApplied = data.tempBonuses[ATTACK_BONUS_KEY] == OFFENSE_BONUS &&
            data.tempBonuses[ARCHER_BONUS_KEY] == OFFENSE_BONUS &&
            data.tempBonuses[FORMATION_BONUS_KEY] == OFFENSE_BONUS
        if (!alreadyApplied) {
            data.tempBonuses[ATTACK_BONUS_KEY] = OFFENSE_BONUS
            data.tempBonuses[ARCHER_BONUS_KEY] = OFFENSE_BONUS
            data.tempBonuses[FORMATION_BONUS_KEY] = OFFENSE_BONUS
            plugin.playerManager.updateStats(player)
        }

        if (buffCleanupTasks.containsKey(uuid)) return
        buffCleanupTasks[uuid] = object : BukkitRunnable() {
            override fun run() {
                val current = Bukkit.getPlayer(uuid)
                val expiresAt = buffExpiresAt[uuid] ?: 0L
                if (current == null || !current.isOnline || System.currentTimeMillis() >= expiresAt) {
                    current?.let(::removeOffenseBuff)
                    buffExpiresAt.remove(uuid)
                    buffCleanupTasks.remove(uuid)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, DOMAIN_INTERVAL_TICKS.toLong(), DOMAIN_INTERVAL_TICKS.toLong())
    }

    private fun removeOffenseBuff(player: Player) {
        val data = plugin.playerManager.getPlayerData(player) ?: return
        var changed = false
        changed = data.tempBonuses.remove(ATTACK_BONUS_KEY) != null || changed
        changed = data.tempBonuses.remove(ARCHER_BONUS_KEY) != null || changed
        changed = data.tempBonuses.remove(FORMATION_BONUS_KEY) != null || changed
        if (changed && player.isOnline) plugin.playerManager.updateStats(player)
    }

    private fun drawVanillaDomainFallback(center: Location, ticks: Int) {
        if (ticks % 10 != 0) return
        val world = center.world ?: return
        val phase = ticks * .08
        for (i in 0 until 12) {
            val angle = phase + Math.PI * 2.0 * i / 12.0
            val point = center.clone().add(cos(angle) * DOMAIN_RADIUS, .05, sin(angle) * DOMAIN_RADIUS)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, spiritDust)
        }
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 4, DOMAIN_RADIUS * .55, .7, DOMAIN_RADIUS * .55, .005)
    }
}
