package com.hjh_database.feather.impl

import com.hjh_database.data.PlayerData
import com.hjh_database.feather.FeatherBase
import com.hjh_database.feather.FeatherEndReason
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 三种移速羽毛的公共实现。
 *
 * 所有羽毛共用一个专属修饰器标签，保证同一玩家只能存在一个羽毛移速效果；
 * Paper 1.21.3中的ADD_SCALAR对应multiply_base：按属性基础值计算增量，
 * 同时通过独立NamespacedKey避免与其他系统的加速、减速生命周期互相覆盖。
 */
abstract class SpeedFeather(
    final override val id: String,
    private val featherName: String,
    private val skillName: String,
    private val color: String,
    private val initialSpeedBonus: Double,
    final override val durationSeconds: Int,
    private val reductionPerDamage: Double?,
    private val damageParticleColor: Color,
    final override val cooldownSeconds: Int = 10
) : FeatherBase {

    private val activeBonuses = HashMap<UUID, Double>()

    final override fun canUse(player: Player, data: PlayerData): Boolean {
        val requiredQuestId = when (data.race) {
            0 -> "main_shen_6"
            2 -> "main_ren_5"
            4 -> "main_yao_5"
            else -> null
        }

        if (requiredQuestId == null || !data.completedQuests.contains(requiredQuestId)) {
            player.sendMessage("§c请先完成前置任务后，再使用$featherName。")
            return false
        }
        return true
    }

    final override fun onStart(player: Player) {
        activeBonuses[player.uniqueId] = initialSpeedBonus
        applyBaseSpeedBonus(player, initialSpeedBonus)

        player.sendMessage(
            "$color[$skillName] §f$featherName 已生效，基础移速提升§b${percent(initialSpeedBonus)}%§f。"
        )
        player.playSound(player.location, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 1.2f)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 2.0f)
    }

    final override fun onDamage(player: Player): Boolean {
        val reduction = reductionPerDamage ?: return true
        val current = activeBonuses[player.uniqueId] ?: initialSpeedBonus
        val remaining = (current - reduction).coerceAtLeast(0.0)
        playDamageReductionParticles(player)
        if (remaining <= 0.000001) return true

        activeBonuses[player.uniqueId] = remaining
        applyBaseSpeedBonus(player, remaining)
        return false
    }

    final override fun onEnd(player: Player, reason: FeatherEndReason) {
        activeBonuses.remove(player.uniqueId)
        removeSharedModifier(player)

        // 切换羽毛、重新释放、退出或插件关闭时静默清理，只保留新效果的开启提示。
        if (reason == FeatherEndReason.REPLACED ||
            reason == FeatherEndReason.QUIT ||
            reason == FeatherEndReason.DISABLE
        ) {
            return
        }

        val message = if (reason == FeatherEndReason.DAMAGED) {
            if (reductionPerDamage == null) {
                "$color[$skillName] §f受伤使$featherName 的加速效果消失。"
            } else {
                "$color[$skillName] §f$featherName 的加速效果已因连续受伤耗尽。"
            }
        } else {
            "$color[$skillName] §f$featherName 的加速效果已结束。"
        }
        player.sendMessage(message)
        player.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.5f)
    }

    private fun applyBaseSpeedBonus(player: Player, amount: Double) {
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.getModifier(SHARED_SPEED_KEY)?.let(attribute::removeModifier)
        attribute.addTransientModifier(
            AttributeModifier(
                SHARED_SPEED_KEY,
                amount,
                // Paper 1.21.3旧命名ADD_SCALAR，即Mojang语义中的multiply_base。
                AttributeModifier.Operation.ADD_SCALAR
            )
        )
    }

    private fun playDamageReductionParticles(player: Player) {
        val center = player.location.clone().add(0.0, 0.75, 0.0)
        player.world.spawnParticle(Particle.CLOUD, center, 7, 0.32, 0.42, 0.32, 0.015)
        player.world.spawnParticle(
            Particle.DUST,
            center,
            6,
            0.28,
            0.38,
            0.28,
            0.0,
            Particle.DustOptions(damageParticleColor, 0.8f)
        )
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt()

    companion object {
        /**
         * 羽毛系统唯一的移速标签；不会清理或覆盖其他技能、装备、怪物效果的标签。
         */
        val SHARED_SPEED_KEY: NamespacedKey =
            NamespacedKey.fromString("hjh_database:feather_speed")!!

        fun removeSharedModifier(player: Player) {
            val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
            attribute.getModifier(SHARED_SPEED_KEY)?.let(attribute::removeModifier)
        }
    }
}
