package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillResult
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent

class AnhuishinuSkill(
    private val plugin: Hjh_database
) : BaihuWeaponSkill {
    private data class Settings(
        val enhancedShots: Int,
        val enhancedDurationMs: Long,
        val shotCooldownTicks: Int,
        val bonusDamageMultiplier: Double,
        val pierceLevel: Int,
        val slowTicks: Int,
        val slowAmplifier: Int
    )

    private data class HuntingState(
        val item: ItemStack,
        val startedTick: Int,
        val expiresAt: Long,
        var nextRecoveryAt: Long,
        val healthPerSecond: Double,
        val saturationPerSecond: Float,
        val settings: Settings
    )

    private data class EnhancedState(
        val item: ItemStack,
        val settings: Settings,
        var remainingShots: Int,
        val expiresAt: Long,
        var lastVolleyTick: Int = Int.MIN_VALUE
    )

    private val huntingStates = ConcurrentHashMap<UUID, HuntingState>()
    private val enhancedStates = ConcurrentHashMap<UUID, EnhancedState>()
    private val bonusDamageKey = NamespacedKey(plugin, "anhu_hunt_bonus_damage")
    private val slowTicksKey = NamespacedKey(plugin, "anhu_hunt_slow_ticks")
    private val slowAmplifierKey = NamespacedKey(plugin, "anhu_hunt_slow_amplifier")

    init {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tickStates() }, 5L, 5L)
    }

    override fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult {
        val arrow = projectile as? AbstractArrow ?: return BaihuWeaponSkillResult.FAIL
        arrow.remove()

        huntingStates.remove(player.uniqueId)?.let { clearVirtualCharge(it.item) }
        enhancedStates.remove(player.uniqueId)?.let { clearVirtualCharge(it.item) }

        val now = System.currentTimeMillis()
        val durationMs = (config.getDouble("hunting_duration", 5.0) * 1000.0).toLong().coerceAtLeast(1L)
        val settings = Settings(
            enhancedShots = config.getInt("enhanced_shots", 3).coerceAtLeast(1),
            enhancedDurationMs = (config.getDouble("enhanced_duration", 10.0) * 1000.0).toLong().coerceAtLeast(1L),
            shotCooldownTicks = (config.getDouble("enhanced_shot_cooldown", 0.5) * 20.0).toInt().coerceAtLeast(0),
            bonusDamageMultiplier = config.getDouble("bonus_damage_multiplier", 1.5).coerceAtLeast(0.0),
            pierceLevel = config.getInt("pierce_level", 127).coerceIn(1, 127),
            slowTicks = (config.getDouble("slow_seconds", 5.0) * 20.0).toInt().coerceAtLeast(1),
            slowAmplifier = config.getInt("slow_amplifier", 1).coerceAtLeast(0)
        )
        val state = HuntingState(
            item = item,
            startedTick = Bukkit.getCurrentTick(),
            expiresAt = now + durationMs,
            nextRecoveryAt = now + 1000L,
            // 兼容旧版 stamina_per_second；该效果语义为恢复生命，并非恢复饥饿度。
            healthPerSecond = config.getDouble(
                "health_per_second",
                config.getDouble("stamina_per_second", 4.0)
            ).coerceAtLeast(0.0),
            saturationPerSecond = config.getDouble("saturation_per_second", 2.0).toFloat().coerceAtLeast(0.0f),
            settings = settings
        )
        huntingStates[player.uniqueId] = state
        clearCurrentMonsterTargets(player)
        if (settings.shotCooldownTicks > 0) {
            // 激活射击后立即锁住弩，避免玩家未松开右键便误触下一发、提前结束游猎。
            player.setCooldown(Material.CROSSBOW, settings.shotCooldownTicks)
        }

        // 激活箭已被用于施法；下一刻装入虚拟箭，让游猎后的三次射击不依赖背包箭矢。
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (huntingStates[player.uniqueId] === state) ensureVirtualCharge(item)
        }, 1L)

        player.world.playSound(player.location, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 0.8f, 0.8f)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65f, 0.65f)
        player.world.spawnParticle(Particle.SNOWFLAKE, player.location.clone().add(0.0, 1.0, 0.0), 24, 0.55, 0.65, 0.55, 0.02)

        return BaihuWeaponSkillResult.success(
            message = config.getString("message", "&b&l武器技【寒山游猎】已发动")
        )
    }

    /**
     * 返回 true 表示本次射击属于游猎流程，监听器不应再尝试将它当作一次新的主动施法。
     */
    fun handleShot(event: EntityShootBowEvent, data: PlayerData): Boolean {
        val player = event.entity as? Player ?: return false
        val arrow = event.projectile as? AbstractArrow ?: return false
        val uuid = player.uniqueId
        val currentTick = Bukkit.getCurrentTick()

        val hunting = huntingStates[uuid]
        if (hunting != null) {
            // 多重射击可能在同一刻产生多个事件；激活当刻的所有箭都只用于施法，不提前结束游猎。
            if (currentTick == hunting.startedTick) {
                event.setConsumeItem(false)
                arrow.remove()
                return true
            }

            if (huntingStates.remove(uuid, hunting)) {
                enhancedStates[uuid] = EnhancedState(
                    item = hunting.item,
                    settings = hunting.settings,
                    remainingShots = hunting.settings.enhancedShots,
                    expiresAt = System.currentTimeMillis() + hunting.settings.enhancedDurationMs
                )
                announceEnhanced(player, hunting.settings.enhancedShots)
            }
        }

        val enhanced = enhancedStates[uuid] ?: return false
        if (System.currentTimeMillis() >= enhanced.expiresAt) {
            if (enhancedStates.remove(uuid, enhanced)) clearVirtualCharge(enhanced.item)
            return false
        }
        empowerArrow(player, arrow, data, enhanced)
        event.setConsumeItem(false)

        if (enhanced.lastVolleyTick != currentTick) {
            enhanced.lastVolleyTick = currentTick
            enhanced.remainingShots = (enhanced.remainingShots - 1).coerceAtLeast(0)
            val remaining = enhanced.remainingShots
            if (enhanced.settings.shotCooldownTicks > 0) {
                player.setCooldown(Material.CROSSBOW, enhanced.settings.shotCooldownTicks)
            }

            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (enhancedStates[uuid] !== enhanced || enhanced.lastVolleyTick != currentTick) return@Runnable
                if (remaining > 0) {
                    ensureVirtualCharge(enhanced.item)
                } else {
                    enhancedStates.remove(uuid, enhanced)
                }
            }, 1L)
        }
        return true
    }

    fun onProjectileDamage(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        val target = event.entity as? LivingEntity ?: return
        if (!isValidTarget(target)) return

        val pdc = arrow.persistentDataContainer
        val bonusDamage = pdc.get(bonusDamageKey, PersistentDataType.DOUBLE) ?: return
        val shooter = arrow.shooter as? Player ?: return
        val slowTicks = pdc.get(slowTicksKey, PersistentDataType.INTEGER) ?: 100
        val slowAmplifier = pdc.get(slowAmplifierKey, PersistentDataType.INTEGER) ?: 1

        if (bonusDamage > 0.0) dealArmorPiercingDamage(shooter, target, bonusDamage)
        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowAmplifier, false, true, true), true)
        spawnHitFrost(target)
    }

    fun onCrossbowLoad(event: EntityLoadCrossbowEvent): Boolean {
        val player = event.entity as? Player ?: return false
        val uuid = player.uniqueId
        if (!huntingStates.containsKey(uuid) && !enhancedStates.containsKey(uuid)) return false
        event.setConsumeItem(false)
        return true
    }

    fun onMobTarget(event: EntityTargetLivingEntityEvent) {
        val player = event.target as? Player ?: return
        if (!huntingStates.containsKey(player.uniqueId)) return
        event.isCancelled = true
        event.target = null
    }

    override fun deactivate(player: Player) {
        huntingStates.remove(player.uniqueId)?.let { clearVirtualCharge(it.item) }
        enhancedStates.remove(player.uniqueId)?.let { clearVirtualCharge(it.item) }
    }

    private fun tickStates() {
        val now = System.currentTimeMillis()
        for ((uuid, state) in huntingStates.entries) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline || player.isDead) {
                if (huntingStates.remove(uuid, state)) clearVirtualCharge(state.item)
                continue
            }

            val recoveryLimit = min(now, state.expiresAt)
            while (state.nextRecoveryAt <= recoveryLimit) {
                restoreHuntingResources(player, state)
                state.nextRecoveryAt += 1000L
            }

            if (now >= state.expiresAt) {
                if (huntingStates.remove(uuid, state)) {
                    enhancedStates[uuid] = EnhancedState(
                        item = state.item,
                        settings = state.settings,
                        remainingShots = state.settings.enhancedShots,
                        expiresAt = now + state.settings.enhancedDurationMs
                    )
                    ensureVirtualCharge(state.item)
                    announceEnhanced(player, state.settings.enhancedShots)
                }
                continue
            }

            player.world.spawnParticle(
                Particle.SNOWFLAKE,
                player.location.clone().add(0.0, 1.0, 0.0),
                3,
                0.4,
                0.55,
                0.4,
                0.01
            )
        }

        for ((uuid, state) in enhancedStates.entries) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline || player.isDead) {
                if (enhancedStates.remove(uuid, state)) clearVirtualCharge(state.item)
                continue
            }
            if (now >= state.expiresAt) {
                if (enhancedStates.remove(uuid, state)) {
                    clearVirtualCharge(state.item)
                    player.sendMessage("§7[寒山游猎] 强化射击已过期。")
                }
                continue
            }

            player.world.spawnParticle(
                Particle.SNOWFLAKE,
                player.location.clone().add(0.0, 1.0, 0.0),
                5,
                0.65,
                0.65,
                0.65,
                0.015
            )
        }
    }

    private fun restoreHuntingResources(player: Player, state: HuntingState) {
        if (state.healthPerSecond > 0.0) {
            val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.maxHealth
            player.health = min(maxHealth, player.health + state.healthPerSecond)
        }
        if (state.saturationPerSecond > 0.0f) {
            player.saturation = min(player.foodLevel.toFloat(), player.saturation + state.saturationPerSecond)
        }
        player.world.playSound(player.location, Sound.BLOCK_POWDER_SNOW_STEP, 0.25f, 1.5f)
    }

    private fun empowerArrow(player: Player, arrow: AbstractArrow, data: PlayerData, state: EnhancedState) {
        val pdc = arrow.persistentDataContainer
        pdc.set(bonusDamageKey, PersistentDataType.DOUBLE, data.archerDamage * state.settings.bonusDamageMultiplier)
        pdc.set(slowTicksKey, PersistentDataType.INTEGER, state.settings.slowTicks)
        pdc.set(slowAmplifierKey, PersistentDataType.INTEGER, state.settings.slowAmplifier)
        arrow.pierceLevel = max(arrow.pierceLevel, state.settings.pierceLevel)
        arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED

        arrow.world.spawnParticle(Particle.SNOWFLAKE, arrow.location, 10, 0.2, 0.2, 0.2, 0.01)
        player.world.playSound(player.location, Sound.ENTITY_SKELETON_SHOOT, 0.8f, 0.75f)
        startArrowTrail(arrow)
    }

    private fun startArrowTrail(arrow: AbstractArrow) {
        var elapsed = 0
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            elapsed += 2
            if (!arrow.isValid || arrow.isDead || arrow.isOnGround || elapsed >= ARROW_TRAIL_MAX_TICKS) {
                return@Runnable
            }
            arrow.world.spawnParticle(Particle.SNOWFLAKE, arrow.location, 3, 0.12, 0.12, 0.12, 0.005)
        }, 0L, 2L)
        plugin.server.scheduler.runTaskLater(plugin, Runnable { task.cancel() }, ARROW_TRAIL_MAX_TICKS.toLong())
    }

    private fun clearCurrentMonsterTargets(player: Player) {
        player.world.livingEntities.asSequence()
            .mapNotNull { it as? Mob }
            .filter { it.target?.uniqueId == player.uniqueId }
            .forEach { it.target = null }
    }

    private fun ensureVirtualCharge(item: ItemStack) {
        if (item.type != Material.CROSSBOW) return
        val meta = item.itemMeta as? CrossbowMeta ?: return
        val projectileCount = if (meta.hasEnchant(Enchantment.MULTISHOT)) 3 else 1
        meta.setChargedProjectiles(List(projectileCount) { ItemStack(Material.ARROW) })
        item.itemMeta = meta
    }

    private fun clearVirtualCharge(item: ItemStack) {
        if (item.type != Material.CROSSBOW) return
        val meta = item.itemMeta as? CrossbowMeta ?: return
        if (!meta.hasChargedProjectiles()) return
        meta.setChargedProjectiles(emptyList())
        item.itemMeta = meta
    }

    private fun announceEnhanced(player: Player, shots: Int) {
        player.sendMessage("§b[寒山游猎] §f游猎结束，接下来的 §b$shots §f次射击已强化。")
        player.world.playSound(player.location, Sound.ITEM_CROSSBOW_LOADING_END, 0.75f, 0.65f)
        player.world.spawnParticle(Particle.DUST, player.location.clone().add(0.0, 1.0, 0.0), 18, 0.45, 0.55, 0.45, 0.0, FROST_DUST)
    }

    private fun dealArmorPiercingDamage(player: Player, target: LivingEntity, amount: Double) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
        target.noDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            target.removeMetadata("hjh_physical_skill", plugin)
            target.removeMetadata("hjh_magic_damage", plugin)
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
            target.noDamageTicks = 0
        }
    }

    private fun spawnHitFrost(target: LivingEntity) {
        val loc = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        target.world.spawnParticle(Particle.DUST, loc, 14, 0.35, 0.45, 0.35, 0.0, FROST_DUST)
        target.world.spawnParticle(Particle.SNOWFLAKE, loc, 10, 0.32, 0.35, 0.32, 0.02)
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    companion object {
        private val FROST_DUST = Particle.DustOptions(Color.fromRGB(22, 55, 112), 1.1f)
        private const val ARROW_TRAIL_MAX_TICKS = 200
    }
}
