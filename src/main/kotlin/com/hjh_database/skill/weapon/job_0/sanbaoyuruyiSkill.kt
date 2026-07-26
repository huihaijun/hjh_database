package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class sanbaoyuruyiSkill : WeaponSkill, Listener {

    companion object {
        private const val MONSTER_TAG = "monster"
        private const val PANLING_TAG = "panling"
        private const val BOSS_TAG = "instance_boss"
        private const val ARMOR_PIERCE_METADATA = "hjh_magic_damage"
        private const val EXACT_DAMAGE_METADATA = "HJH_MAGIC_DAMAGE"
        private const val INTERNAL_DAMAGE_METADATA = "sanbaoyuruyi_internal_damage"
        private const val SHIELD_AMPLIFIER = 4 // 伤害吸收 V = 20点护盾
    }

    private data class MarkState(
        val token: UUID,
        val targetId: UUID,
        val expiresAt: Long,
        val damageMultiplier: Double,
        val chargedAttackMultiplier: Double,
        val executePercent: Double,
        val shieldAmount: Double,
        val shieldDurationTicks: Int
    )

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
    private val mainPlugin = plugin as Hjh_database
    private val markedTargets = ConcurrentHashMap<UUID, MarkState>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 一个共享低频任务同时负责标记特效、过期清理和斩杀检测。
        object : BukkitRunnable() {
            override fun run() {
                if (markedTargets.isEmpty()) return
                val now = System.currentTimeMillis()

                for ((playerId, state) in markedTargets) {
                    val player = Bukkit.getPlayer(playerId)
                    val target = Bukkit.getEntity(state.targetId) as? LivingEntity

                    if (now >= state.expiresAt || player == null || !player.isOnline ||
                        target == null || !isMonster(target)
                    ) {
                        markedTargets.remove(playerId, state)
                        continue
                    }

                    if (!target.scoreboardTags.contains(BOSS_TAG) && isBelowExecuteLine(target, state.executePercent)) {
                        if (markedTargets.remove(playerId, state)) {
                            executeTarget(player, target, state)
                        }
                        continue
                    }

                    spawnMarkParticles(target)
                }
            }
        }.runTaskTimer(plugin, 1L, 5L)
    }

    override fun castActive(
        player: Player?,
        data: PlayerData?,
        config: ConfigurationSection?,
        projectile: Entity?
    ): Boolean {
        if (player == null || config == null) return false

        val target = getTargetEntity(player, config.getDouble("target_range", 10.0))
        if (target == null) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent("§c准心方向没有可标记的怪物！")
            )
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.8f, 0.55f)
            return false
        }

        val durationSeconds = config.getDouble("duration", 7.0).coerceAtLeast(0.1)
        val state = MarkState(
            token = UUID.randomUUID(),
            targetId = target.uniqueId,
            expiresAt = System.currentTimeMillis() + (durationSeconds * 1000.0).toLong(),
            damageMultiplier = config.getDouble("damage_boost", 1.5).coerceAtLeast(0.0),
            chargedAttackMultiplier = config.getDouble("charged_attack_multiplier", 1.2).coerceAtLeast(0.0),
            executePercent = config.getDouble("execute_percent", 0.20).coerceIn(0.0, 1.0),
            shieldAmount = config.getDouble("execute_shield_amount", 20.0).coerceAtLeast(0.0),
            shieldDurationTicks = (config.getDouble("execute_shield_duration", 30.0) * 20.0)
                .toInt()
                .coerceAtLeast(1)
        )
        markedTargets[player.uniqueId] = state

        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.75f)
        target.world.playSound(target.location, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.65f)
        target.world.playSound(target.location, Sound.ENTITY_WARDEN_HEARTBEAT, 0.45f, 1.8f)
        spawnLineParticles(player, target)
        target.world.spawnParticle(
            Particle.WAX_ON,
            target.location.clone().add(0.0, target.height * 0.55, 0.0),
            32,
            0.65,
            target.height * 0.35,
            0.65,
            0.08
        )
        return true
    }

    /**
     * 在 CombatListener 计算护甲前标记本次伤害为穿甲。
     * 标记只维持一个伤害事件，并在 HIGHEST 阶段立刻移除。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamagePre(event: EntityDamageByEntityEvent) {
        val attacker = resolvePlayer(event.damager) ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (victim.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val state = getActiveMark(attacker.uniqueId, victim.uniqueId) ?: return
        if (state.damageMultiplier <= 0.0) return
        victim.setMetadata(ARMOR_PIERCE_METADATA, FixedMetadataValue(plugin, true))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamagePost(event: EntityDamageByEntityEvent) {
        val attacker = resolvePlayer(event.damager) ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (victim.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val state = getActiveMark(attacker.uniqueId, victim.uniqueId)
        if (victim.hasMetadata(ARMOR_PIERCE_METADATA)) {
            victim.removeMetadata(ARMOR_PIERCE_METADATA, plugin)
        }
        if (state == null || event.isCancelled || event.damage <= 0.0) return

        event.damage *= state.damageMultiplier

        // 只有直接、完全蓄力的普通攻击会追加120%近战强度伤害；横扫副目标不会重复触发。
        if (event.damager === attacker &&
            event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            attacker.attackCooldown >= 0.9f &&
            state.chargedAttackMultiplier > 0.0
        ) {
            val attack = mainPlugin.playerManager.getData(attacker.uniqueId)?.attack ?: return
            val extraDamage = attack * state.chargedAttackMultiplier
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!attacker.isOnline || !victim.isValid || victim.isDead || !isMonster(victim)) return@Runnable
                dealExactPiercingDamage(attacker, victim, extraDamage)
            })
        }
    }

    /**
     * 标记目标以斩杀以外的方式死亡时，立即清空镇邪的武器技能冷却。
     * 技能斩杀会在造成致死伤害前移除标记，因此不会进入这里刷新冷却。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onMarkedTargetDeath(event: EntityDeathEvent) {
        val targetId = event.entity.uniqueId
        val now = System.currentTimeMillis()

        for ((playerId, state) in markedTargets) {
            if (state.targetId != targetId || !markedTargets.remove(playerId, state)) continue
            if (now >= state.expiresAt) continue

            val player = Bukkit.getPlayer(playerId) ?: continue
            mainPlugin.weaponSkillManager.resetCooldown(player, Material.DIAMOND_AXE)
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent("§e§l[镇邪] §f标记目标已死亡，技能冷却已刷新！")
            )
            player.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.75f, 1.75f)
            player.world.spawnParticle(
                Particle.WAX_ON,
                player.location.clone().add(0.0, 1.0, 0.0),
                22,
                0.55,
                0.75,
                0.55,
                0.08
            )
        }
    }

    private fun getActiveMark(playerId: UUID, targetId: UUID): MarkState? {
        val state = markedTargets[playerId] ?: return null
        if (state.targetId != targetId) return null
        if (System.currentTimeMillis() >= state.expiresAt) {
            markedTargets.remove(playerId, state)
            return null
        }
        return state
    }

    private fun executeTarget(player: Player, target: LivingEntity, state: MarkState) {
        if (!target.isValid || target.isDead || target.scoreboardTags.contains(BOSS_TAG)) return

        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: target.health
        val lethalDamage = (maxHealth + target.health + target.absorptionAmount + 100.0) * 100.0
        dealExactPiercingDamage(player, target, lethalDamage)

        if (!target.isDead) return
        grantAbsorptionShield(player, state.shieldAmount, state.shieldDurationTicks)

        val effectLocation = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        target.world.spawnParticle(Particle.FLASH, effectLocation, 2)
        target.world.spawnParticle(Particle.TOTEM_OF_UNDYING, effectLocation, 32, 0.7, 0.8, 0.7, 0.08)
        target.world.playSound(target.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 1.55f)
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e§l[镇邪] §f斩杀成功，获得 §b20 §f点护盾！"))
    }

    private fun dealExactPiercingDamage(attacker: Player, target: LivingEntity, amount: Double) {
        if (amount <= 0.0 || !target.isValid || target.isDead) return

        val previousNoDamageTicks = target.noDamageTicks
        target.setMetadata(INTERNAL_DAMAGE_METADATA, FixedMetadataValue(plugin, true))
        target.setMetadata(EXACT_DAMAGE_METADATA, FixedMetadataValue(plugin, amount))
        target.noDamageTicks = 0

        try {
            target.damage(amount, attacker)
        } finally {
            if (target.hasMetadata(INTERNAL_DAMAGE_METADATA)) {
                target.removeMetadata(INTERNAL_DAMAGE_METADATA, plugin)
            }
            if (target.hasMetadata(EXACT_DAMAGE_METADATA)) {
                target.removeMetadata(EXACT_DAMAGE_METADATA, plugin)
            }
            if (!target.isDead && target.isValid) {
                // 技能伤害不新增无敌帧，也不抹掉普攻原本尚未结束的无敌帧。
                target.noDamageTicks = previousNoDamageTicks.coerceAtMost(target.maximumNoDamageTicks)
            }
        }
    }

    private fun grantAbsorptionShield(player: Player, amount: Double, durationTicks: Int) {
        if (amount <= 0.0 || player.isDead) return

        val amplifier = ((amount / 4.0).toInt() - 1).coerceAtLeast(SHIELD_AMPLIFIER)
        val existing = player.getPotionEffect(PotionEffectType.ABSORPTION)
        if (existing == null || existing.amplifier < amplifier ||
            (existing.amplifier == amplifier && existing.duration < durationTicks)
        ) {
            player.addPotionEffect(
                PotionEffect(PotionEffectType.ABSORPTION, durationTicks, amplifier, false, true, true),
                true
            )
        }
        player.absorptionAmount = max(player.absorptionAmount, amount)
        player.world.spawnParticle(
            Particle.DUST,
            player.location.clone().add(0.0, 1.0, 0.0),
            35,
            0.7,
            0.9,
            0.7,
            Particle.DustOptions(Color.AQUA, 1.35f)
        )
        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.35f)
    }

    private fun isBelowExecuteLine(target: LivingEntity, executePercent: Double): Boolean {
        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return false
        return maxHealth > 0.0 && target.health / maxHealth < executePercent
    }

    private fun isMonster(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return entity.isValid && !entity.isDead && tags.contains(PANLING_TAG) && tags.contains(MONSTER_TAG)
    }

    private fun resolvePlayer(entity: Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        else -> null
    }

    private fun getTargetEntity(player: Player, range: Double): LivingEntity? {
        val result = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            range.coerceAtLeast(1.0),
            0.45
        ) { entity ->
            entity is LivingEntity && entity !== player && isMonster(entity)
        }
        return result?.hitEntity as? LivingEntity
    }

    private fun spawnMarkParticles(target: LivingEntity) {
        val center = target.location.clone().add(0.0, target.height + 0.35, 0.0)
        target.world.spawnParticle(
            Particle.DUST,
            center,
            4,
            0.32,
            0.08,
            0.32,
            0.0,
            Particle.DustOptions(Color.AQUA, 1.2f)
        )
        target.world.spawnParticle(Particle.HAPPY_VILLAGER, center, 2, 0.3, 0.12, 0.3, 0.0)
    }

    private fun spawnLineParticles(player: Player, target: LivingEntity) {
        val start = player.eyeLocation.clone().add(0.0, -0.25, 0.0)
        val end = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        val delta = end.toVector().subtract(start.toVector())
        val distance = delta.length()
        if (distance <= 0.01) return

        val direction = delta.normalize()
        var travelled = 0.0
        while (travelled <= distance) {
            val point = start.clone().add(direction.clone().multiply(travelled))
            player.world.spawnParticle(
                Particle.DUST,
                point,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                Particle.DustOptions(Color.AQUA, 0.8f)
            )
            travelled += 0.35
        }
    }

    override fun deactivate(player: Player) {
        markedTargets.remove(player.uniqueId)
    }
}
