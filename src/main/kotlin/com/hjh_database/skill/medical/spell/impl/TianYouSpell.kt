package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class TianYouSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        // 记录身上拥有天佑护盾的玩家和对应的定时结算任务。
        val activeTianYouTasks = ConcurrentHashMap<UUID, BukkitTask>()

        // 独立来源前缀确保天佑只刷新/移除自己的加成，不会影响其他技能。
        private const val ATTACK_PERCENT_KEY = "tianyou::attack_percent"
        private const val ARCHER_DAMAGE_PERCENT_KEY = "tianyou::archer_damage_percent"
        private const val ZF_STR_PERCENT_KEY = "tianyou::zf_str_percent"
    }

    private data class OffenseBoostState(val token: UUID, val task: BukkitTask)
    private val activeOffenseBoosts = ConcurrentHashMap<UUID, OffenseBoostState>()

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val shieldMultiplier = config?.getDouble("shield_multiplier", 3.0) ?: 3.0
        val durationSeconds = config?.getInt("duration", 15) ?: 15
        val offensePercentPerShield = config?.getDouble("offense_percent_per_shield", 0.01) ?: 0.01
        val offenseBoostCap = config?.getDouble("offense_boost_cap", 0.3) ?: 0.3
        val offenseBoostDuration = config?.getInt("offense_boost_duration", 5) ?: 5

        val shieldAmount = data.zfStr * shieldMultiplier
        val durationTicks = durationSeconds * 20L
        val center = player.location

        player.world.playSound(center, Sound.ITEM_TOTEM_USE, 0.8f, 1.2f)
        player.world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f)
        player.world.spawnParticle(
            Particle.END_ROD,
            center.clone().add(0.0, 1.0, 0.0),
            100,
            radius / 2,
            1.0,
            radius / 2,
            0.1
        )

        val targets = player.world.getNearbyEntities(center, radius, radius, radius)
            .asSequence()
            .filterIsInstance<Player>()
            .filter { it.location.distanceSquared(center) <= radius * radius }
            .toMutableSet()
        targets.add(player)

        for (target in targets) {
            if (target.isDead || target.absorptionAmount >= shieldAmount) continue

            val amplifier = (shieldAmount / 4.0).toInt()
            target.addPotionEffect(
                PotionEffect(
                    PotionEffectType.ABSORPTION,
                    durationTicks.toInt(),
                    amplifier,
                    false,
                    false,
                    true
                )
            )
            target.absorptionAmount = shieldAmount

            target.world.spawnParticle(
                Particle.DUST,
                target.location.clone().add(0.0, 1.0, 0.0),
                30,
                0.5,
                0.8,
                0.5,
                Particle.DustOptions(Color.YELLOW, 1.5f)
            )
            target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f)

            activeTianYouTasks[target.uniqueId]?.cancel()
            val task = object : BukkitRunnable() {
                override fun run() {
                    activeTianYouTasks.remove(target.uniqueId)
                    if (!target.isOnline || target.isDead) return

                    val remainingAbsorption = target.absorptionAmount

                    // 大于最初赋予值时视为其他来源的更强护盾，避免误删该护盾。
                    if (remainingAbsorption <= 0.0 || remainingAbsorption > shieldAmount) return

                    target.absorptionAmount = 0.0
                    target.removePotionEffect(PotionEffectType.ABSORPTION)

                    // 每1点剩余护盾转为1%进攻属性，默认最多30%。
                    val offenseBoost = (remainingAbsorption * offensePercentPerShield)
                        .coerceIn(0.0, offenseBoostCap)
                    applyOffenseBoost(target, offenseBoost, offenseBoostDuration)

                    target.world.playSound(target.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f)
                    target.world.playSound(target.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.4f)
                    target.world.spawnParticle(
                        Particle.ENCHANT,
                        target.location.clone().add(0.0, 1.0, 0.0),
                        45,
                        0.6,
                        0.9,
                        0.6,
                        0.15
                    )
                }
            }.runTaskLater(plugin, durationTicks)

            activeTianYouTasks[target.uniqueId] = task
        }

        return true
    }

    private fun applyOffenseBoost(target: Player, percent: Double, durationSeconds: Int) {
        if (percent <= 0.0 || durationSeconds <= 0) return

        val targetData = plugin.playerManager.getData(target.uniqueId) ?: return
        activeOffenseBoosts.remove(target.uniqueId)?.task?.cancel()

        targetData.tempBonuses[ATTACK_PERCENT_KEY] = percent
        targetData.tempBonuses[ARCHER_DAMAGE_PERCENT_KEY] = percent
        targetData.tempBonuses[ZF_STR_PERCENT_KEY] = percent
        plugin.playerManager.updateStats(target)

        val token = UUID.randomUUID()
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val current = activeOffenseBoosts[target.uniqueId] ?: return@Runnable
            if (current.token != token) return@Runnable

            activeOffenseBoosts.remove(target.uniqueId)
            val currentData = plugin.playerManager.getData(target.uniqueId) ?: return@Runnable
            currentData.tempBonuses.remove(ATTACK_PERCENT_KEY)
            currentData.tempBonuses.remove(ARCHER_DAMAGE_PERCENT_KEY)
            currentData.tempBonuses.remove(ZF_STR_PERCENT_KEY)
            if (target.isOnline) {
                plugin.playerManager.updateStats(target)
            }
        }, durationSeconds * 20L)

        activeOffenseBoosts[target.uniqueId] = OffenseBoostState(token, task)
        val displayPercent = (percent * 100.0).roundToInt()
        target.sendMessage("§e[天佑] §f剩余护盾转化为 §c${displayPercent}% §f进攻属性，持续 §b${durationSeconds} §f秒。")
    }
}
