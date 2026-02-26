package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HuiChunYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        // 用于记录被赋予护盾的玩家及其清理任务，防止重复触发时出现护盾被提前清空的问题
        val activeShieldTasks = ConcurrentHashMap<UUID, BukkitTask>()
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 8.0) ?: 8.0
        val healMultiplier = config?.getDouble("heal_multiplier", 1.5) ?: 1.5
        val shieldDuration = config?.getInt("shield_duration", 10) ?: 10
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

        var lowestHpPlayer: Player? = null
        var lowestHp = Double.MAX_VALUE

        // 遍历结算治疗，并找出当前血量最低的玩家
        for (target in targets) {
            if (target.isDead) continue

            // 记录血量最低的玩家
            if (target.health < lowestHp) {
                lowestHp = target.health
                lowestHpPlayer = target
            }

            // 执行群体治疗
            val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            target.health = (target.health + healAmount).coerceAtMost(maxHealth)

            // 为每个被治疗的目标播放绿色的星芒粒子
            target.world.spawnParticle(
                Particle.DUST,
                target.location.clone().add(0.0, 1.0, 0.0),
                15, 0.4, 0.5, 0.4,
                Particle.DustOptions(Color.LIME, 1.2f)
            )
        }

        // 为血量最低的玩家附加基于其最大生命值的黄心护盾
        lowestHpPlayer?.let { lowest ->
            val targetMaxHealth = lowest.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            val shieldAmount = targetMaxHealth * shieldMultiplier

            // 动态计算药水等级解锁黄心上限
            val amp = (shieldAmount / 4.0).toInt()
            val durationTicks = shieldDuration * 20L

            // 赋予吸收药水效果并覆写真实数值
            lowest.addPotionEffect(PotionEffect(
                PotionEffectType.ABSORPTION,
                durationTicks.toInt(),
                amp, false, false, true
            ))
            lowest.absorptionAmount = shieldAmount

            lowest.sendMessage("§a[回春域] §f纯净的生命能量汇聚于你，为你凝聚了护盾！")
            lowest.world.playSound(lowest.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f)

            // 取消其身上的旧清理任务
            activeShieldTasks[lowest.uniqueId]?.cancel()

            // 设置 10 秒后清理护盾的定时任务
            val task = object : BukkitRunnable() {
                override fun run() {
                    if (lowest.isOnline) {
                        lowest.removePotionEffect(PotionEffectType.ABSORPTION)
                        lowest.absorptionAmount = 0.0
                    }
                    activeShieldTasks.remove(lowest.uniqueId)
                }
            }.runTaskLater(plugin, durationTicks)

            activeShieldTasks[lowest.uniqueId] = task
        }

        return true
    }
}