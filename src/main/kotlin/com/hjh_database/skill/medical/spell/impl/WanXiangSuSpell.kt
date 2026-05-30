package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
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

class WanXiangSuSpell(private val plugin: Hjh_database) : MedicalSpell {

    // 内部数据类：只需记录一个统一的百分比值 boostPct 即可
    data class WanXiangBuff(var task: BukkitTask, val boostPct: Double)

    companion object {
        val activeBuffs = ConcurrentHashMap<UUID, WanXiangBuff>()
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val healMultiplier = config?.getDouble("heal_multiplier", 4.0) ?: 4.0
        val buffDuration = config?.getInt("buff_duration", 15) ?: 15
        val statBoost = config?.getDouble("stat_boost", 0.2) ?: 0.2 // 20%

        val healAmount = zfStr * healMultiplier
        val durationTicks = buffDuration * 20L
        val center = player.location

        // 播放音效与特效
        player.world.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.5f)
        player.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center.clone().add(0.0, 1.0, 0.0), 200, radius / 2, 1.0, radius / 2, 0.2)

        val nearbyEntities = player.world.getNearbyEntities(center, radius, radius, radius)
        val targets = mutableListOf<Player>()
        for (entity in nearbyEntities) {
            if (entity is Player && entity.location.distance(center) <= radius) {
                targets.add(entity)
            }
        }
        if (!targets.contains(player)) targets.add(player)

        // 遍历友军结算
        for (target in targets) {
            if (target.isDead) continue

            // 1. 瞬间抬血
            plugin.medicalSpellManager.applyMedicalHeal(player, target, healAmount, "wanxiangsu")

            // 2. 生命恢复 II
            target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, durationTicks.toInt(), 1, false, false, true))

            target.world.spawnParticle(Particle.HAPPY_VILLAGER, target.location.clone().add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, 0.0)

            // 3. 进攻属性增益逻辑 (完全采用百分比)
            val targetData = plugin.playerManager.getData(target.uniqueId) ?: continue
            val existingBuff = activeBuffs[target.uniqueId]

            if (existingBuff != null) {
                // 已有增益：仅重置定时器，绝不叠加
                existingBuff.task.cancel()
                existingBuff.task = createRemoveTask(target.uniqueId, durationTicks)
            } else {
                // 没有增益：精准赋予三个流派的百分比加成
                targetData.tempBonuses["attack_percent"] = (targetData.tempBonuses["attack_percent"] ?: 0.0) + statBoost
                targetData.tempBonuses["archer_damage_percent"] = (targetData.tempBonuses["archer_damage_percent"] ?: 0.0) + statBoost
                targetData.tempBonuses["zf_str_percent"] = (targetData.tempBonuses["zf_str_percent"] ?: 0.0) + statBoost

                plugin.playerManager.updateStats(target)

                val task = createRemoveTask(target.uniqueId, durationTicks)
                activeBuffs[target.uniqueId] = WanXiangBuff(task, statBoost)
            }
        }

        return true
    }

    private fun createRemoveTask(uuid: UUID, delayTicks: Long): BukkitTask {
        return object : BukkitRunnable() {
            override fun run() {
                val buff = activeBuffs.remove(uuid) ?: return
                val targetData = plugin.playerManager.getData(uuid)

                if (targetData != null) {
                    // 到期精准扣除百分比
                    targetData.tempBonuses["attack_percent"] = (targetData.tempBonuses["attack_percent"] ?: 0.0) - buff.boostPct
                    targetData.tempBonuses["archer_damage_percent"] = (targetData.tempBonuses["archer_damage_percent"] ?: 0.0) - buff.boostPct
                    targetData.tempBonuses["zf_str_percent"] = (targetData.tempBonuses["zf_str_percent"] ?: 0.0) - buff.boostPct

                    val player = plugin.server.getPlayer(uuid)
                    if (player != null && player.isOnline) {
                        plugin.playerManager.updateStats(player)
                    }
                }
            }
        }.runTaskLater(plugin, delayTicks)
    }
}
