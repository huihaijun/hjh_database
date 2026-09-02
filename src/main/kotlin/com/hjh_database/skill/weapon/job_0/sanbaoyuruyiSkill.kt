package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

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
        val splashDamageRatio: Double,
        val splashRadius: Double,
        val splashMaxTargets: Int,
        val cooldownReductionPerChargedHitSeconds: Double,
        val maxChargedHitCooldownReductionSeconds: Double,
        var appliedChargedHitCooldownReductionSeconds: Double,
        val executePercent: Double,
        val shieldAmount: Double,
        val shieldDurationTicks: Int
    )

    private data class AttackSpeedBuffState(val expiresAt: Long)

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
    private val mainPlugin = plugin as Hjh_database
    private val markedTargets = ConcurrentHashMap<UUID, MarkState>()
    private val attackSpeedBuffs = ConcurrentHashMap<UUID, AttackSpeedBuffState>()
    private val attackSpeedModifierKey = NamespacedKey(plugin, "sanbaoyuruyi_attack_speed")
    private val noDamageOriginalMaximumKey = NamespacedKey(plugin, "sanbaoyuruyi_original_max_no_damage_ticks")
    private val noDamageRestoreTokenKey = NamespacedKey(plugin, "sanbaoyuruyi_no_damage_restore_token")
    private val noDamageRestoreSequence = AtomicLong()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // 兼容热重载：新实例启动时清理由旧实例遗留在在线玩家身上的瞬时修饰器。
        Bukkit.getOnlinePlayers().forEach(::removeAttackSpeedModifier)
        restoreInterruptedNoDamageFrameOverrides()

        // 一个共享低频任务同时负责标记特效、攻速增益过期、状态清理和斩杀检测。
        object : BukkitRunnable() {
            override fun run() {
                if (markedTargets.isEmpty() && attackSpeedBuffs.isEmpty()) return
                val now = System.currentTimeMillis()

                for ((playerId, buff) in attackSpeedBuffs) {
                    val player = Bukkit.getPlayer(playerId)
                    if (now >= buff.expiresAt || player == null || !player.isOnline) {
                        if (attackSpeedBuffs.remove(playerId, buff) && player != null) {
                            removeAttackSpeedModifier(player)
                        }
                    }
                }

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
        val cooldownReduction = data?.coolReduce?.coerceIn(0.0, 0.5) ?: 0.0
        val actualCooldown = config.getDouble("cooldown", 12.0).coerceAtLeast(0.0) * (1.0 - cooldownReduction)
        val cooldownReductionPerHitRatio = config.getDouble("charged_hit_cooldown_reduction", 0.10)
            .coerceIn(0.0, 1.0)
        val maxCooldownReductionRatio = config.getDouble("max_charged_hit_cooldown_reduction", 0.60)
            .coerceIn(0.0, 1.0)
        val state = MarkState(
            token = UUID.randomUUID(),
            targetId = target.uniqueId,
            expiresAt = System.currentTimeMillis() + (durationSeconds * 1000.0).toLong(),
            damageMultiplier = config.getDouble("damage_boost", 2.5).coerceAtLeast(0.0),
            splashDamageRatio = config.getDouble("splash_damage_ratio", 0.80).coerceAtLeast(0.0),
            splashRadius = config.getDouble("splash_radius", 3.0).coerceAtLeast(0.0),
            splashMaxTargets = config.getInt("splash_max_targets", 3).coerceAtLeast(0),
            cooldownReductionPerChargedHitSeconds = actualCooldown * cooldownReductionPerHitRatio,
            maxChargedHitCooldownReductionSeconds = actualCooldown * maxCooldownReductionRatio,
            appliedChargedHitCooldownReductionSeconds = 0.0,
            executePercent = config.getDouble("execute_percent", 0.20).coerceIn(0.0, 1.0),
            shieldAmount = config.getDouble("execute_shield_amount", 20.0).coerceAtLeast(0.0),
            shieldDurationTicks = (config.getDouble("execute_shield_duration", 30.0) * 20.0)
                .toInt()
                .coerceAtLeast(1)
        )
        markedTargets[player.uniqueId] = state
        applyAttackSpeedBuff(
            player,
            config.getDouble("attack_speed_bonus", 0.50),
            durationSeconds
        )

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
     * Paper 的预攻击事件发生在原版无敌帧裁剪伤害之前。
     * 仅对“标记有效 + 玉如意仍激活 + 完全蓄力”的直接普攻临时关闭目标无敌帧；
     * 连点器产生的未蓄满攻击不会进入这里，避免把普通攻击全局改成无无敌帧。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFullyChargedMarkedAttackPre(event: PrePlayerAttackEntityEvent) {
        val attacker = event.player
        val victim = event.attacked as? LivingEntity ?: return
        if (isFullyChargedMarkedAttack(attacker, victim)) {
            temporarilyDisableNoDamageFrames(victim)
        } else if (victim.persistentDataContainer.has(noDamageOriginalMaximumKey, PersistentDataType.INTEGER)) {
            // 同一 tick 内若紧跟着连点器产生的未蓄满普攻，先恢复原版设置再让该攻击继续。
            // 因此未蓄满攻击仍会受无敌帧限制，并在命中后正常留下新的无敌帧。
            restoreNoDamageFrameOverride(victim)
        }
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
        if (!mainPlugin.equipmentActivationManager.isHoldingActiveWeapon(attacker, "sanbaoyuruyi")) return
        if (state.damageMultiplier <= 0.0) return
        victim.setMetadata(ARMOR_PIERCE_METADATA, FixedMetadataValue(plugin, true))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamagePost(event: EntityDamageByEntityEvent) {
        val attacker = resolvePlayer(event.damager) ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (victim.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        if (victim.hasMetadata(ARMOR_PIERCE_METADATA)) {
            victim.removeMetadata(ARMOR_PIERCE_METADATA, plugin)
        }
        val state = getActiveMark(attacker.uniqueId, victim.uniqueId)
        if (state == null || event.isCancelled || event.damage <= 0.0) return
        if (!mainPlugin.equipmentActivationManager.isHoldingActiveWeapon(attacker, "sanbaoyuruyi")) return

        event.damage *= state.damageMultiplier
    }

    /**
     * 等所有伤害监听器完成结算后再读取 finalDamage，确保镇压伤害真正跟随该次普攻的最终伤害，
     * 包括暴击、易伤、标记增伤及其他独立效果，而不是重新读取近战强度。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFullyChargedAttackResolved(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (victim.hasMetadata(INTERNAL_DAMAGE_METADATA)) return

        val state = getActiveMark(attacker.uniqueId, victim.uniqueId) ?: return
        if (!mainPlugin.equipmentActivationManager.isHoldingActiveWeapon(attacker, "sanbaoyuruyi")) return

        // 只有直接、完全蓄力的普通攻击触发镇压溅射与冷却缩减；横扫不会重复触发。
        // 这里不再额外补一段近战强度伤害，避免以后仅删 lore 却遗漏实际结算。
        if (isFullyChargedMarkedAttack(attacker, victim) && event.finalDamage > 0.0) {
            reduceCooldownForChargedHit(attacker, state)
            val splashDamage = event.finalDamage * state.splashDamageRatio
            val impactLocation = victim.location.clone().add(0.0, victim.height * 0.45, 0.0)
            val primaryTargetId = victim.uniqueId
            if (splashDamage > 0.0 && state.splashRadius > 0.0 && state.splashMaxTargets > 0) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!attacker.isOnline) return@Runnable
                    suppressNearbyTargets(
                        attacker,
                        impactLocation,
                        primaryTargetId,
                        state.splashRadius,
                        state.splashMaxTargets,
                        splashDamage
                    )
                })
            }
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

    private fun executeTarget(player: Player, target: LivingEntity, state: MarkState): Boolean {
        if (!target.isValid || target.isDead || target.scoreboardTags.contains(BOSS_TAG)) return false

        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: target.health
        val lethalDamage = (maxHealth + target.health + target.absorptionAmount + 100.0) * 100.0
        dealExactPiercingDamage(player, target, lethalDamage)

        if (!target.isDead) return false
        grantAbsorptionShield(player, state.shieldAmount, state.shieldDurationTicks)

        val effectLocation = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        target.world.spawnParticle(Particle.FLASH, effectLocation, 2)
        target.world.spawnParticle(Particle.TOTEM_OF_UNDYING, effectLocation, 32, 0.7, 0.8, 0.7, 0.08)
        target.world.playSound(target.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 1.55f)
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e§l[镇邪] §f斩杀成功，获得 §b20 §f点护盾！"))
        return true
    }

    private fun applyAttackSpeedBuff(player: Player, rawBonus: Double, durationSeconds: Double) {
        val bonus = rawBonus.coerceAtLeast(0.0)
        val expiresAt = System.currentTimeMillis() + (durationSeconds * 1000.0).toLong()
        attackSpeedBuffs[player.uniqueId] = AttackSpeedBuffState(expiresAt)

        val attribute = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
        attribute.getModifier(attackSpeedModifierKey)?.let(attribute::removeModifier)
        if (bonus > 0.0) {
            attribute.addTransientModifier(
                AttributeModifier(
                    attackSpeedModifierKey,
                    bonus,
                    AttributeModifier.Operation.ADD_SCALAR
                )
            )
        }
    }

    private fun removeAttackSpeedModifier(player: Player) {
        val attribute = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
        attribute.getModifier(attackSpeedModifierKey)?.let(attribute::removeModifier)
    }

    private fun reduceCooldownForChargedHit(player: Player, state: MarkState) {
        val remainingAllowance = state.maxChargedHitCooldownReductionSeconds -
            state.appliedChargedHitCooldownReductionSeconds
        val reduction = min(state.cooldownReductionPerChargedHitSeconds, remainingAllowance)
        if (!player.isOnline || reduction <= 0.0001) return

        state.appliedChargedHitCooldownReductionSeconds += reduction
        mainPlugin.weaponSkillManager.reduceCooldown(
            player,
            reduction,
            Material.DIAMOND_AXE
        )
        player.world.spawnParticle(
            Particle.WAX_ON,
            player.location.clone().add(0.0, 1.0, 0.0),
            5,
            0.28,
            0.4,
            0.28,
            0.02
        )
    }

    private fun isFullyChargedMarkedAttack(attacker: Player, victim: LivingEntity): Boolean {
        if (attacker.attackCooldown < 0.9f) return false
        if (getActiveMark(attacker.uniqueId, victim.uniqueId) == null) return false
        return mainPlugin.equipmentActivationManager.isHoldingActiveWeapon(attacker, "sanbaoyuruyi")
    }

    private fun temporarilyDisableNoDamageFrames(target: LivingEntity) {
        val pdc = target.persistentDataContainer
        if (!pdc.has(noDamageOriginalMaximumKey, PersistentDataType.INTEGER)) {
            pdc.set(noDamageOriginalMaximumKey, PersistentDataType.INTEGER, target.maximumNoDamageTicks)
        }
        val token = noDamageRestoreSequence.incrementAndGet()
        pdc.set(noDamageRestoreTokenKey, PersistentDataType.LONG, token)
        target.maximumNoDamageTicks = 0
        target.noDamageTicks = 0

        val targetId = target.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = Bukkit.getEntity(targetId) as? LivingEntity ?: return@Runnable
            val currentToken = current.persistentDataContainer
                .get(noDamageRestoreTokenKey, PersistentDataType.LONG)
            if (currentToken == token) restoreNoDamageFrameOverride(current)
        })
    }

    private fun restoreNoDamageFrameOverride(target: LivingEntity) {
        val pdc = target.persistentDataContainer
        val originalMaximum = pdc.get(noDamageOriginalMaximumKey, PersistentDataType.INTEGER)
        if (originalMaximum != null) target.maximumNoDamageTicks = originalMaximum.coerceAtLeast(0)
        target.noDamageTicks = 0
        pdc.remove(noDamageOriginalMaximumKey)
        pdc.remove(noDamageRestoreTokenKey)
    }

    /** 防止恰好在这一 tick 热重载时，把怪物的 maximumNoDamageTicks 永久留在0。 */
    private fun restoreInterruptedNoDamageFrameOverrides() {
        Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { chunk ->
                chunk.entities.filterIsInstance<LivingEntity>().forEach { entity ->
                    if (entity.persistentDataContainer.has(noDamageOriginalMaximumKey, PersistentDataType.INTEGER)) {
                        restoreNoDamageFrameOverride(entity)
                    }
                }
            }
        }
    }

    private fun suppressNearbyTargets(
        attacker: Player,
        center: org.bukkit.Location,
        primaryTargetId: UUID,
        radius: Double,
        maxTargets: Int,
        damage: Double
    ) {
        if (damage <= 0.0 || center.world != attacker.world) return
        val radiusSquared = radius * radius
        val targets = center.world!!.getNearbyEntities(center, radius, radius, radius).asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != primaryTargetId && isMonster(it) }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .sortedBy { it.location.distanceSquared(center) }
            .take(maxTargets)
            .toList()

        for (target in targets) {
            dealExactPiercingDamage(attacker, target, damage)
            val effect = target.location.clone().add(0.0, target.height + 0.2, 0.0)
            target.world.spawnParticle(
                Particle.DUST,
                effect,
                5,
                0.25,
                0.08,
                0.25,
                0.0,
                Particle.DustOptions(Color.fromRGB(235, 205, 90), 0.85f)
            )
            target.world.spawnParticle(Particle.ENCHANTED_HIT, effect, 3, 0.2, 0.12, 0.2, 0.0)
        }
        if (targets.isNotEmpty()) {
            center.world!!.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.45f, 0.65f)
        }
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

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        markedTargets.remove(event.player.uniqueId)
        attackSpeedBuffs.remove(event.player.uniqueId)
        removeAttackSpeedModifier(event.player)
    }

    override fun deactivate(player: Player) {
        markedTargets.remove(player.uniqueId)
    }
}
