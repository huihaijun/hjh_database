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
import kotlin.math.ceil

class HuiChunYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        // 用于记录被赋予护盾的玩家及其清理任务，防止重复触发时出现护盾被提前清空的问题
        val activeShieldTasks = ConcurrentHashMap<UUID, BukkitTask>()
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 8.0) ?: 8.0
        val healMultiplier = config?.getDouble("heal_multiplier", 2.5) ?: 2.5
        val shieldDuration = config?.getInt("shield_duration", 30) ?: 30
        val shieldMultiplier = config?.getDouble("shield_multiplier", 0.5) ?: 0.5 // 50%的自身生命值

        val healAmount = zfStr * healMultiplier
        val center = player.location

        // 播放施法音效和范围治愈特效
        player.world.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.0, 1.0, 0.0), 50, radius / 2, 1.0, radius / 2, 0.0)

        // 寻找范围内的友军 (包含自己)
        val nearbyEntities = player.world.getNearbyEntities(center, radius, radius, radius)
        val targets = mutableListOf<Player>()

        for (entity in nearbyEntities) {
            if (entity is Player && entity.location.distance(center) <= radius) {
                targets.add(entity)
            }
        }
        if (!targets.contains(player)) {
            targets.add(player)
        }

        var lowestHpAlly: Player? = null
        var lowestHp = Double.MAX_VALUE

        // 遍历结算治疗，并找出施法者之外当前生命值最低的友军。
        for (target in targets) {
            if (target.isDead) continue

            if (target.uniqueId != player.uniqueId && target.health < lowestHp) {
                lowestHp = target.health
                lowestHpAlly = target
            }

            // 执行群体治疗
            plugin.medicalSpellManager.applyMedicalHeal(player, target, healAmount, "huichunyu")

            // 为每个被治疗的目标播放绿色的星芒粒子
            target.world.spawnParticle(
                Particle.DUST,
                target.location.clone().add(0.0, 1.0, 0.0),
                15, 0.4, 0.5, 0.4,
                Particle.DustOptions(Color.LIME, 1.2f)
            )
        }

        // 施法者固定获得护盾；范围内存在其他友军时，再给生命值最低者一份。
        val shieldTargets = linkedSetOf(player)
        lowestHpAlly?.let(shieldTargets::add)
        for (target in shieldTargets) {
            if (!target.isDead && target.isOnline) {
                applyShield(target, shieldMultiplier, shieldDuration)
            }
        }

        return true
    }

    private fun applyShield(target: Player, multiplier: Double, durationSeconds: Int) {
        val targetMaxHealth = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        val shieldAmount = targetMaxHealth * multiplier
        if (shieldAmount <= 0.0 || durationSeconds <= 0) return

        val amplifier = (ceil(shieldAmount / 4.0).toInt() - 1).coerceAtLeast(0)
        val durationTicks = durationSeconds * 20L

        target.addPotionEffect(
            PotionEffect(PotionEffectType.ABSORPTION, durationTicks.toInt(), amplifier, false, false, true),
            true
        )
        target.absorptionAmount = shieldAmount
        target.sendMessage("§a[回春域] §f纯净的生命能量汇聚于你，为你凝聚了护盾！")
        target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f)
        target.world.spawnParticle(
            Particle.DUST,
            target.location.clone().add(0.0, 1.0, 0.0),
            24, 0.45, 0.65, 0.45, 0.0,
            Particle.DustOptions(Color.YELLOW, 1.15f)
        )

        activeShieldTasks[target.uniqueId]?.cancel()
        val task = object : BukkitRunnable() {
            override fun run() {
                if (target.isOnline) {
                    target.removePotionEffect(PotionEffectType.ABSORPTION)
                    target.absorptionAmount = 0.0
                }
                activeShieldTasks.remove(target.uniqueId)
            }
        }.runTaskLater(plugin, durationTicks)
        activeShieldTasks[target.uniqueId] = task
    }
}
