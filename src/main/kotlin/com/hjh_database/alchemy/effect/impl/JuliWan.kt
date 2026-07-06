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
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.math.min

open class JuliWan(
    override val id: String = "juliwan",
    private val fixedTier: AlchemyTier? = null
) : AlchemyEffect {

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id, "§b巨力丸")
        if (fixedTier != null) return recipe

        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as Hjh_database
        addTier(recipe, plugin, AlchemyTier.LOW, "juliwan0")
        addTier(recipe, plugin, AlchemyTier.MID, "juliwan1")
        addTier(recipe, plugin, AlchemyTier.HIGH, "juliwan2")
        return recipe
    }

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as? Hjh_database ?: return 0
        val effectiveTier = fixedTier ?: tier
        val currentTier = data.activePills
            .filter { it.effectId.startsWith(PREFIX) }
            .maxByOrNull { it.tier.ordinal }
            ?.tier

        if (currentTier != null && currentTier.ordinal > effectiveTier.ordinal) {
            player.sendMessage("§b[巨力丸] §7更强的药力仍在流转，本次只触发药毒，不覆盖现有效果。")
            return 0
        }

        removeBonus(data)
        data.activePills.removeIf { it.effectId.startsWith(PREFIX) }
        plugin.playerManager.updateStats(player)

        val config = config(effectiveTier)
        val bonus = when (data.job) {
            WARRIOR_JOB -> min(data.attack * config.percent, config.cap)
            ARCHER_JOB -> min(data.archerDamage * config.percent, config.cap)
            WARLOCK_JOB, DOCTOR_JOB -> min(data.zfStr * config.percent, config.cap)
            else -> min(data.attack * config.percent, config.cap)
        }
        val bonusKey = bonusKey(data.job)
        data.tempBonuses[bonusKey] = bonus
        plugin.playerManager.updateStats(player)

        player.world.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.4f)
        player.world.spawnParticle(Particle.CRIT, player.location.clone().add(0.0, 1.0, 0.0), 18, 0.35, 0.45, 0.35, 0.02)
        player.sendMessage("§b[巨力丸] §f进攻属性提升，持续 §b${config.duration} §f秒。")
        return config.duration
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
        val plugin = Bukkit.getPluginManager().getPlugin("Hjh_database") as? Hjh_database ?: return
        removeBonus(data)
        plugin.playerManager.updateStats(player)
        player.sendMessage("§b[巨力丸] §7药力散去，进攻属性恢复正常。")
    }

    private fun addTier(recipe: AlchemyRecipe, plugin: Hjh_database, tier: AlchemyTier, resultId: String) {
        val result = plugin.resourceManager.getItem(resultId)?.clone() ?: ItemStack(Material.POTION)
        recipe.tierData[tier] = TierConfig(arrayListOf(), result)
    }

    private fun config(tier: AlchemyTier): JuliConfig = when (tier) {
        AlchemyTier.LOW -> JuliConfig(0.25, 20.0, 10L)
        AlchemyTier.MID -> JuliConfig(0.30, 30.0, 12L)
        AlchemyTier.HIGH -> JuliConfig(0.50, 50.0, 15L)
    }

    private data class JuliConfig(val percent: Double, val cap: Double, val duration: Long)

    companion object {
        private const val PREFIX = "juliwan"
        private const val ATTACK_KEY = "alchemy_juliwan_attack"
        private const val ARCHER_KEY = "alchemy_juliwan_archer_damage"
        private const val ZF_STR_KEY = "alchemy_juliwan_zf_str"
        private const val WARRIOR_JOB = 0
        private const val ARCHER_JOB = 1
        private const val WARLOCK_JOB = 2
        private const val DOCTOR_JOB = 3

        fun applyStatBonuses(bonuses: MutableMap<String, Double>, data: PlayerData) {
            data.tempBonuses[ATTACK_KEY]?.let { bonuses.merge("attack", it) { a, b -> a + b } }
            data.tempBonuses[ARCHER_KEY]?.let { bonuses.merge("archer_damage", it) { a, b -> a + b } }
            data.tempBonuses[ZF_STR_KEY]?.let { bonuses.merge("zf_str", it) { a, b -> a + b } }
        }

        private fun bonusKey(job: Int?): String = when (job) {
            WARRIOR_JOB -> ATTACK_KEY
            ARCHER_JOB -> ARCHER_KEY
            WARLOCK_JOB, DOCTOR_JOB -> ZF_STR_KEY
            else -> ATTACK_KEY
        }

        private fun removeBonus(data: PlayerData) {
            data.tempBonuses.remove(ATTACK_KEY)
            data.tempBonuses.remove(ARCHER_KEY)
            data.tempBonuses.remove(ZF_STR_KEY)
        }
    }
}

class JuliWan0 : JuliWan("juliwan0", AlchemyTier.LOW)
class JuliWan1 : JuliWan("juliwan1", AlchemyTier.MID)
class JuliWan2 : JuliWan("juliwan2", AlchemyTier.HIGH)
