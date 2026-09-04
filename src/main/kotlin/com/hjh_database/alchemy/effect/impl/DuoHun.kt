package com.hjh_database.alchemy.effect.impl

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class DuoHun(
    override val id: String = "duohun",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§c夺魂")
        if (fixedTier != null) return recipe

        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        addTier(recipe, plugin, AlchemyTier.LOW, "duohun0")
        addTier(recipe, plugin, AlchemyTier.MID, "duohun1")
        addTier(recipe, plugin, AlchemyTier.HIGH, "duohun2")
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long = 0

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
    }

    private fun addTier(recipe: AlchemyRecipe, plugin: Hjh_database, tier: AlchemyTier, resultId: String) {
        val result = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.SPLASH_POTION)
        recipe.tierData[tier] = TierConfig(arrayListOf(), result)
    }

    companion object {
        private val vulnerabilityMarks = ConcurrentHashMap<UUID, VulnerabilityMark>()
        private val freezeMarks = ConcurrentHashMap<UUID, FreezeMark>()

        fun apply(plugin: Hjh_database, caster: Player, target: LivingEntity, tier: AlchemyTier) {
            if (!isPanlingMonster(target)) return
            val config = config(tier)
            applyVulnerabilityMark(plugin, target, config)
            applyFreeze(plugin, target, config.freezeTicks)
            target.world.spawnParticle(Particle.SMOKE, target.location.clone().add(0.0, 1.0, 0.0), 12, 0.35, 0.45, 0.35, 0.02)
            target.world.playSound(target.location, Sound.ENTITY_EVOKER_CAST_SPELL, 0.45f, 0.7f)
            caster.spawnParticle(Particle.WITCH, target.location.clone().add(0.0, 1.0, 0.0), 4, 0.2, 0.25, 0.2, 0.01)
        }

        fun applyVulnerability(target: LivingEntity, damage: Double): Double {
            if (!isPanlingMonster(target)) return damage
            val mark = vulnerabilityMarks[target.uniqueId] ?: return damage
            if (mark.until <= System.currentTimeMillis()) {
                vulnerabilityMarks.remove(target.uniqueId, mark)
                return damage
            }
            return damage * (1.0 + mark.vulnerability)
        }

        private fun applyVulnerabilityMark(plugin: Hjh_database, target: LivingEntity, config: DuoHunConfig) {
            val now = System.currentTimeMillis()
            val next = vulnerabilityMarks.compute(target.uniqueId) { _, old ->
                if (old == null || old.until <= now || config.vulnerability > old.vulnerability) {
                    VulnerabilityMark(config.vulnerability, now + config.vulnerabilityDurationMillis)
                } else if (config.vulnerability == old.vulnerability) {
                    old.copy(until = now + config.vulnerabilityDurationMillis)
                } else {
                    old
                }
            } ?: return

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val mark = vulnerabilityMarks[target.uniqueId] ?: return@Runnable
                if (mark.until > System.currentTimeMillis()) return@Runnable
                vulnerabilityMarks.remove(target.uniqueId, mark)
            }, (config.vulnerabilityDurationMillis / 50L) + 1L)
        }

        private fun applyFreeze(plugin: Hjh_database, target: LivingEntity, ticks: Long) {
            val now = System.currentTimeMillis()
            val until = now + ticks * 50L
            freezeMarks.compute(target.uniqueId) { _, old ->
                if (old == null || old.until <= now || ticks > old.freezeTicks) {
                    val hadAi = target.hasAI()
                    target.setAI(false)
                    FreezeMark(until, hadAi, ticks)
                } else if (ticks == old.freezeTicks) {
                    target.setAI(false)
                    old.copy(until = until)
                } else {
                    old
                }
            }

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val mark = freezeMarks[target.uniqueId] ?: return@Runnable
                if (mark.until > System.currentTimeMillis()) return@Runnable
                freezeMarks.remove(target.uniqueId, mark)
                if (target.isValid && !target.isDead && mark.hadAi) {
                    target.setAI(true)
                }
            }, ticks + 1L)
        }

        private fun config(tier: AlchemyTier): DuoHunConfig = when (tier) {
            AlchemyTier.LOW -> DuoHunConfig(0.20, 16L)
            AlchemyTier.MID -> DuoHunConfig(0.30, 20L)
            AlchemyTier.HIGH -> DuoHunConfig(0.40, 20L)
        }

        private data class DuoHunConfig(
            val vulnerability: Double,
            val freezeTicks: Long,
            val vulnerabilityDurationMillis: Long = 8_000L
        )

        private fun isPanlingMonster(target: LivingEntity): Boolean {
            val tags = target.scoreboardTags
            return tags.contains("panling") && tags.contains("monster")
        }

        private data class VulnerabilityMark(val vulnerability: Double, val until: Long)
        private data class FreezeMark(val until: Long, val hadAi: Boolean, val freezeTicks: Long)
    }
}

class DuoHun0 : DuoHun("duohun0", AlchemyTier.LOW)
class DuoHun1 : DuoHun("duohun1", AlchemyTier.MID)
class DuoHun2 : DuoHun("duohun2", AlchemyTier.HIGH)
