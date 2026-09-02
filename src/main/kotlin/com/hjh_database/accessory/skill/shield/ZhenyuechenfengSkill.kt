package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.AccessoryHudValueKind
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.weapon.CrystalData
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** 战士饰品盾牌“镇岳沉锋”：[负山岳]完整状态机。 */
class ZhenyuechenfengSkill(plugin: Hjh_database) : BaseShieldSkill(plugin) {
    private data class MountainState(
        var stacks: Int,
        var expiresAt: Long,
        val maxStacks: Int,
        val stackDurationExtensionMillis: Long,
        val dropIntervalMillis: Long,
        val meleeStackGain: Int,
        val shieldStackGain: Int,
        var decaying: Boolean = false,
        var nextDropAt: Long = Long.MAX_VALUE,
        var decayEndsAt: Long = 0L,
        var rewardMask: Int = 0,
        var nextMeleeStackAt: Long = 0L
    )

    private data class PendingMelee(val targetId: UUID, val tick: Int)

    private val states = HashMap<UUID, MountainState>()
    private val pendingMelee = HashMap<UUID, PendingMelee>()
    /**
     * 同一只怪物只保存一份镇岳削弱状态。不同玩家重复触发时仅刷新截止时间，
     * 不会叠乘15%进攻削弱，也不会互相提前移除效果。
     */
    private val weakenedTargets = HashMap<UUID, Long>()
    private val crystalKey = NamespacedKey(plugin, "crystal_id")
    private var stateTask: BukkitTask? = null

    override fun getBlockCooldownMillis(crystalData: CrystalData): Long = BLOCK_COOLDOWN_MILLIS

    override fun onBlockSuccess(
        player: Player,
        event: EntityDamageByEntityEvent,
        crystalData: CrystalData
    ) {
        // 镇岳沉锋只会由统一盾牌入口路由怪物普攻；这里负责把本次伤害完整抵挡。
        event.isCancelled = true
        event.damage = 0.0

        val current = validState(player)
        val state = when {
            current == null -> startMountainState(player, crystalData)
            current.decaying -> current // 满层衰减期间仍可挡伤害，但不再叠层。
            else -> addStacks(player, current, current.shieldStackGain)
        }

        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§a§l饰品技【负山岳】发动！§a，当前【山势】层数为§b${state.stacks}§f层")
        )
        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 0.95f, 0.72f)
    }

    override fun handleShiftClick(
        player: Player,
        item: ItemStack,
        isExtract: Boolean,
        crystalData: CrystalData
    ): Boolean = false

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState {
        val state = validState(player)
        return super.getHudState(player, item, crystalData).copy(
            valueKind = AccessoryHudValueKind.MOUNTAIN_STACKS,
            currentValue = state?.stacks ?: 0,
            maxValue = state?.maxStacks ?: crystalData.mountainMaxStacks,
            effectEndMillis = if (state?.decaying == true) state.decayEndsAt else 0L,
            effectDurationMillis = if (state?.decaying == true) state.maxStacks * state.dropIntervalMillis else 0L
        )
    }

    /** 预攻击只记录真实左键攻击；技能内部调用 damage(...) 不会经过这里。 */
    fun onPreMeleeAttack(event: PrePlayerAttackEntityEvent) {
        val player = event.player
        val target = event.attacked as? LivingEntity ?: return
        val state = validState(player) ?: return
        if (state.decaying || !isActiveShield(player) || !isActiveMeleeWeapon(player)) return
        pendingMelee[player.uniqueId] = PendingMelee(target.uniqueId, Bukkit.getCurrentTick())
    }

    /** 仅在真实近战普攻最终成功命中后叠层，0.6秒内最多触发一次。 */
    fun onMeleeDamageResolved(event: EntityDamageByEntityEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK || event.finalDamage <= 0.0) return
        val player = event.damager as? Player ?: return
        val target = event.entity as? LivingEntity ?: return
        val pending = pendingMelee.remove(player.uniqueId) ?: return
        if (pending.targetId != target.uniqueId || pending.tick != Bukkit.getCurrentTick()) return

        val state = validState(player) ?: return
        if (state.decaying || !isActiveShield(player) || !isActiveMeleeWeapon(player)) return
        val now = System.currentTimeMillis()
        if (now < state.nextMeleeStackAt) return
        state.nextMeleeStackAt = now + MELEE_STACK_COOLDOWN_MILLIS
        addStacks(player, state, state.meleeStackGain)
    }

    /** [山势]每层独立提供4%全伤害减免。 */
    fun onDamageTaken(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.isCancelled || event.damage <= 0.0) return
        val state = validState(player) ?: return
        if (!isActiveShield(player)) {
            clearState(player, playEndingEffect = false)
            return
        }
        event.damage *= (1.0 - state.stacks * DAMAGE_REDUCTION_PER_STACK).coerceAtLeast(0.0)
    }

    /** 满层开始直至最后一层掉光，每次实际受伤都会引发一次山动压制。 */
    fun onDamageTakenResolved(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.finalDamage <= 0.0) return
        val state = validState(player) ?: return
        if (!state.decaying || !isActiveShield(player)) return
        triggerMountainSuppression(player)
    }

    /** 镇岳削弱会降低怪物造成的所有伤害；同目标始终只结算一份15%。 */
    fun onWeakenedMonsterDamage(event: EntityDamageByEntityEvent) {
        val source = resolveDamageSource(event) ?: return
        val until = weakenedTargets[source.uniqueId] ?: return
        if (until <= System.currentTimeMillis() || !source.isValid || source.isDead) {
            weakenedTargets.remove(source.uniqueId)
            return
        }
        event.damage *= OFFENSE_DAMAGE_MULTIPLIER
    }

    private fun startMountainState(player: Player, crystalData: CrystalData): MountainState {
        val now = System.currentTimeMillis()
        val state = MountainState(
            stacks = 1,
            expiresAt = now + crystalData.mountainInitialDurationMillis,
            maxStacks = crystalData.mountainMaxStacks,
            stackDurationExtensionMillis = crystalData.mountainStackExtensionMillis,
            dropIntervalMillis = crystalData.mountainDropIntervalMillis,
            meleeStackGain = crystalData.mountainMeleeStackGain,
            shieldStackGain = crystalData.mountainShieldStackGain
        )
        states[player.uniqueId] = state
        ensureStateTask()
        playStackEffect(player, state.stacks)
        return state
    }

    private fun addStacks(player: Player, state: MountainState, amount: Int): MountainState {
        if (state.decaying || amount <= 0) return state
        val previous = state.stacks
        state.stacks = (state.stacks + amount).coerceAtMost(state.maxStacks)
        // 每次“叠层动作”延长1.5秒；盾牌一次增加2层也只延长一次。
        state.expiresAt += state.stackDurationExtensionMillis
        grantCrossedMilestoneRewards(player, state, previous, state.stacks)

        if (state.stacks >= state.maxStacks) enterDecayPhase(player, state)
        else playStackEffect(player, state.stacks)

        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§e§l[山势] §f当前层数：§b${state.stacks}§f/§b${state.maxStacks}")
        )
        return state
    }

    private fun grantCrossedMilestoneRewards(
        player: Player,
        state: MountainState,
        previous: Int,
        current: Int
    ) {
        if (previous < 3 && current >= 3 && state.rewardMask and REWARD_SHIELD == 0) {
            state.rewardMask = state.rewardMask or REWARD_SHIELD
            grantAbsorption(player)
        }
        if (previous < 5 && current >= 5 && state.rewardMask and REWARD_REGEN == 0) {
            state.rewardMask = state.rewardMask or REWARD_REGEN
            grantRegenerationTwo(player)
        }
        if (previous < 8 && current >= 8 && state.rewardMask and REWARD_REGEN_UPGRADE == 0) {
            state.rewardMask = state.rewardMask or REWARD_REGEN_UPGRADE
            upgradeRegeneration(player)
        }
    }

    private fun grantAbsorption(player: Player) {
        val existing = player.getPotionEffect(PotionEffectType.ABSORPTION)
        val amplifier = max(existing?.amplifier ?: -1, ABSORPTION_AMPLIFIER)
        val duration = max(existing?.duration ?: 0, ABSORPTION_DURATION_TICKS)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, duration, amplifier, false, true, true), true)
        player.absorptionAmount = max(player.absorptionAmount, ABSORPTION_AMOUNT)
        playMilestoneEffect(player, 0x86A6B8, Sound.ITEM_SHIELD_BLOCK, 1.25f)
    }

    private fun grantRegenerationTwo(player: Player) {
        val existing = player.getPotionEffect(PotionEffectType.REGENERATION)
        val amplifier = max(existing?.amplifier ?: -1, REGEN_TWO_AMPLIFIER)
        val duration = max(existing?.duration ?: 0, REGEN_TWO_DURATION_TICKS)
        player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, duration, amplifier, false, true, true), true)
        playMilestoneEffect(player, 0x76C77C, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.15f)
    }

    private fun upgradeRegeneration(player: Player) {
        val existing = player.getPotionEffect(PotionEffectType.REGENERATION)
        // 若5层的恢复已经结束，仍以生命恢复Ⅱ为升级起点，保证8层至少得到生命恢复Ⅲ。
        val baseAmplifier = existing?.amplifier ?: REGEN_TWO_AMPLIFIER
        val upgradedAmplifier = min(baseAmplifier + 1, MAX_REGEN_AMPLIFIER)
        player.addPotionEffect(
            PotionEffect(PotionEffectType.REGENERATION, REGEN_UPGRADE_DURATION_TICKS, upgradedAmplifier, false, true, true),
            true
        )
        playMilestoneEffect(player, 0xE3C65B, Sound.BLOCK_BEACON_POWER_SELECT, 1.45f)
    }

    private fun enterDecayPhase(player: Player, state: MountainState) {
        if (state.decaying) return
        state.decaying = true
        state.expiresAt = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        state.nextDropAt = now + state.dropIntervalMillis
        state.decayEndsAt = now + state.stacks * state.dropIntervalMillis
        pendingMelee.remove(player.uniqueId)

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            data.tempBonuses[ATTACK_BONUS_KEY] = DECAY_ATTACK_BONUS
            plugin.playerManager.updateStats(player)
        }

        playFullEffect(player, state.decayEndsAt - now)
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§6§l【山势已满】§f山岳锋芒展开，近战强度提升§c150%§f！")
        )
    }

    private fun ensureStateTask() {
        if (stateTask != null) return
        stateTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            states.keys.toList().forEach { uuid ->
                val player = Bukkit.getPlayer(uuid)
                val state = states[uuid] ?: return@forEach
                if (player == null || !player.isOnline || player.isDead || !isActiveShield(player)) {
                    if (player != null) clearState(player, playEndingEffect = false)
                    else states.remove(uuid)
                    return@forEach
                }

                if (!state.decaying) {
                    if (now >= state.expiresAt) clearState(player, playEndingEffect = true)
                    return@forEach
                }

                // 其他属性刷新可能在衰减期间重算面板；逐轮确认独立加成仍存在，直至0层才清理。
                ensureDecayAttackBonus(player)

                if (now >= state.nextDropAt) {
                    // 服务器短暂卡顿后按真实时间补齐掉层，但只播放一次当前结果，避免粒子爆量。
                    while (state.stacks > 0 && now >= state.nextDropAt) {
                        state.stacks--
                        state.nextDropAt += state.dropIntervalMillis
                    }
                    if (state.stacks <= 0) {
                        clearState(player, playEndingEffect = true)
                    } else {
                        playDecayEffect(player, state.stacks)
                    }
                }
            }
            weakenedTargets.entries.removeIf { (uuid, until) ->
                until <= now || (Bukkit.getEntity(uuid) as? LivingEntity)?.let { !it.isValid || it.isDead } != false
            }
            if (states.isEmpty() && weakenedTargets.isEmpty()) {
                stateTask?.cancel()
                stateTask = null
            }
        }, STATE_TASK_PERIOD_TICKS, STATE_TASK_PERIOD_TICKS)
    }

    private fun validState(player: Player): MountainState? {
        val state = states[player.uniqueId] ?: return null
        if (!state.decaying && System.currentTimeMillis() >= state.expiresAt) {
            clearState(player, playEndingEffect = true)
            return null
        }
        return state
    }

    private fun isActiveShield(player: Player): Boolean {
        val item = player.inventory.itemInOffHand
        if (item.type != Material.SHIELD) return false
        val id = item.itemMeta?.persistentDataContainer
            ?.get(crystalKey, PersistentDataType.STRING) ?: return false
        if (id != CRYSTAL_ID) return false
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        val crystal = plugin.playerManager.crystalManager.loadedCrystals[id] ?: return false
        return plugin.playerManager.crystalManager.isActive(crystal, data, "offhand", player, item)
    }

    private fun isActiveMeleeWeapon(player: Player): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        if (data.job != 0) return false
        val weapon = plugin.equipmentActivationManager.resolveHeldWeapon(player, data) ?: return false
        val type = player.inventory.itemInMainHand.type.name
        return weapon.id.isNotBlank() && (type.endsWith("_SWORD") || type.endsWith("_AXE"))
    }

    private fun triggerMountainSuppression(player: Player) {
        val center = player.location.clone()
        val radiusSquared = MOUNTAIN_RADIUS * MOUNTAIN_RADIUS
        val targets = player.world.getNearbyEntities(center, MOUNTAIN_RADIUS, MOUNTAIN_RADIUS, MOUNTAIN_RADIUS)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster") }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .toList()

        targets.forEach { target ->
            target.addPotionEffect(
                PotionEffect(PotionEffectType.SLOWNESS, SUPPRESSION_DURATION_TICKS, SLOW_AMPLIFIER,
                    false, true, true),
                true
            )
            // 只延长全局唯一状态；多人持盾也不会把15%重复相乘。
            weakenedTargets[target.uniqueId] = System.currentTimeMillis() + SUPPRESSION_DURATION_MILLIS
        }

        playMountainEffect(player)
        player.world.playSound(center, Sound.BLOCK_DEEPSLATE_BREAK, 0.55f, 0.62f)
        ensureStateTask()
    }

    private fun resolveDamageSource(event: EntityDamageByEntityEvent): LivingEntity? = when (val damager = event.damager) {
        is Projectile -> damager.shooter as? LivingEntity
        is LivingEntity -> damager
        else -> null
    }

    private fun ensureDecayAttackBonus(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses[ATTACK_BONUS_KEY] == DECAY_ATTACK_BONUS) return
        data.tempBonuses[ATTACK_BONUS_KEY] = DECAY_ATTACK_BONUS
        plugin.playerManager.updateStats(player)
    }

    private fun clearState(player: Player, playEndingEffect: Boolean) {
        val removed = states.remove(player.uniqueId) ?: return
        pendingMelee.remove(player.uniqueId)
        plugin.clientBridge.cancelTimedEffect(player.world.players, fullEffectKey(player.uniqueId))
        if (removed.decaying) {
            plugin.playerManager.getData(player.uniqueId)?.let { data ->
                if (data.tempBonuses.remove(ATTACK_BONUS_KEY) != null && player.isOnline) {
                    plugin.playerManager.updateStats(player)
                }
            }
        }
        if (playEndingEffect && player.isOnline) playEmptyEffect(player)
    }

    fun cleanup(player: Player) = clearState(player, playEndingEffect = false)

    fun shutdown() {
        stateTask?.cancel()
        stateTask = null
        states.keys.toList().mapNotNull(Bukkit::getPlayer).forEach { clearState(it, playEndingEffect = false) }
        states.clear()
        pendingMelee.clear()
        weakenedTargets.clear()
    }

    private fun playStackEffect(player: Player, stacks: Int) {
        plugin.clientBridge.emitParticles(
            player.world.players,
            player.location.clone().add(0.0, 0.75, 0.0),
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.BURST, 7, 0x9A7650, 1.05f,
                    .55 + stacks * .025, .65, .025),
                ClientParticleLayer("minecraft:ash", ClientParticleShape.CLOUD, 3, radius = .45, height = .5, speed = .015)
            )
        )
        player.world.playSound(player.location, Sound.BLOCK_STONE_PLACE, 0.42f, 0.82f + stacks * .025f)
    }

    private fun playMilestoneEffect(player: Player, color: Int, sound: Sound, pitch: Float) {
        plugin.clientBridge.emitParticles(
            player.world.players,
            player.location.clone().add(0.0, 0.9, 0.0),
            listOf(ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 18, color, 1.2f, 1.05, .25, .02))
        )
        player.world.playSound(player.location, sound, 0.62f, pitch)
    }

    private fun playFullEffect(player: Player, decayDurationMillis: Long) {
        val origin = player.location.clone().add(0.0, 0.15, 0.0)
        plugin.clientBridge.emitParticles(
            player.world.players,
            origin,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.BURST, 30, 0xD7B869, 1.45f, 1.8, 1.4, .08),
                ClientParticleLayer("minecraft:ash", ClientParticleShape.BURST, 18, radius = 1.5, height = 1.3, speed = .07)
            )
        )
        plugin.clientBridge.emitTimedParticles(
            player.world.players,
            origin,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 12, 0xB99558, 1.05f, 1.05, .2, .01),
                ClientParticleLayer("minecraft:ash", ClientParticleShape.CLOUD, 5, radius = .65, height = 1.5, speed = .012)
            ),
            durationTicks = (decayDurationMillis / 50L).toInt().coerceAtLeast(1) + 40,
            intervalTicks = 5,
            key = fullEffectKey(player.uniqueId)
        )
        player.world.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.95f, 0.62f)
        player.world.playSound(player.location, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.75f, 0.72f)
    }

    private fun playDecayEffect(player: Player, stacks: Int) {
        plugin.clientBridge.emitParticles(
            player.world.players,
            player.location.clone().add(0.0, 0.35, 0.0),
            listOf(ClientParticleLayer("minecraft:ash", ClientParticleShape.BURST, 8,
                radius = .65, height = .5, speed = .035))
        )
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§7§l[山势衰减] §f当前剩余§b$stacks§f层")
        )
        player.world.playSound(player.location, Sound.BLOCK_DEEPSLATE_BREAK, 0.35f, 0.8f)
    }

    private fun playEmptyEffect(player: Player) {
        plugin.clientBridge.emitParticles(
            player.world.players,
            player.location.clone().add(0.0, 0.55, 0.0),
            listOf(ClientParticleLayer("minecraft:ash", ClientParticleShape.BURST, 16,
                radius = .9, height = .8, speed = .055))
        )
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§7§l【山势散尽】"))
        player.world.playSound(player.location, Sound.BLOCK_STONE_BREAK, 0.72f, 0.58f)
    }

    private fun playMountainEffect(player: Player) {
        plugin.clientBridge.emitParticles(
            player.world.players,
            player.location.clone().add(0.0, 0.12, 0.0),
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 28, 0x806444, 1.25f,
                    MOUNTAIN_RADIUS, .18, .035),
                ClientParticleLayer("minecraft:ash", ClientParticleShape.BURST, 12,
                    radius = 1.7, height = .7, speed = .065)
            )
        )
    }

    private fun fullEffectKey(uuid: UUID) = "zhenyue_full_$uuid"

    companion object {
        private const val CRYSTAL_ID = "zhenyuechenfeng"
        private const val ATTACK_BONUS_KEY = "zhenyuechenfeng::attack_percent"
        private const val BLOCK_COOLDOWN_MILLIS = 3_000L
        private const val MELEE_STACK_COOLDOWN_MILLIS = 600L
        private const val STATE_TASK_PERIOD_TICKS = 5L
        private const val DAMAGE_REDUCTION_PER_STACK = 0.04
        private const val DECAY_ATTACK_BONUS = 1.5
        private const val MOUNTAIN_RADIUS = 6.0
        private const val SUPPRESSION_DURATION_MILLIS = 3_000L
        private const val SUPPRESSION_DURATION_TICKS = 3 * 20
        private const val SLOW_AMPLIFIER = 1
        private const val OFFENSE_DAMAGE_MULTIPLIER = 0.85
        private const val ABSORPTION_AMOUNT = 30.0
        private const val ABSORPTION_AMPLIFIER = 7
        private const val ABSORPTION_DURATION_TICKS = 30 * 20
        private const val REGEN_TWO_AMPLIFIER = 1
        private const val REGEN_TWO_DURATION_TICKS = 6 * 20
        private const val REGEN_UPGRADE_DURATION_TICKS = 10 * 20
        private const val MAX_REGEN_AMPLIFIER = 4
        private const val REWARD_SHIELD = 1
        private const val REWARD_REGEN = 1 shl 1
        private const val REWARD_REGEN_UPGRADE = 1 shl 2
    }
}
