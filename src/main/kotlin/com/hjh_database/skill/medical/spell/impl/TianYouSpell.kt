package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TianYouSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        // 记录身上拥有天佑护盾的玩家和对应的定时清理任务
        val activeTianYouTasks = ConcurrentHashMap<UUID, BukkitTask>()
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val shieldMultiplier = config?.getDouble("shield_multiplier", 3.0) ?: 3.0
        val explodeMultiplier = config?.getDouble("explode_multiplier", 1.5) ?: 1.5
        val durationSeconds = config?.getInt("duration", 15) ?: 15
        val explodeRadius = config?.getDouble("explode_radius", 3.0) ?: 3.0

        val shieldAmount = zfStr * shieldMultiplier
        val durationTicks = durationSeconds * 20L
        val center = player.location

        // 播放施法音效和光效
        player.world.playSound(center, Sound.ITEM_TOTEM_USE, 0.8f, 1.2f)
        player.world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f)
        player.world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, 1.0, 0.0), 100, radius / 2, 1.0, radius / 2, 0.1)

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

        // 遍历所有友军
        for (target in targets) {
            if (target.isDead) continue

            // 【核心逻辑 1】只在当前护盾低于技能护盾时才覆盖
            if (target.absorptionAmount < shieldAmount) {

                // 动态计算药水等级解锁黄心上限
                val amp = (shieldAmount / 4.0).toInt()
                target.addPotionEffect(PotionEffect(
                    PotionEffectType.ABSORPTION,
                    durationTicks.toInt(),
                    amp, false, false, true
                ))
                target.absorptionAmount = shieldAmount

                target.world.spawnParticle(Particle.DUST, target.location.clone().add(0.0, 1.0, 0.0), 30, 0.5, 0.8, 0.5, Particle.DustOptions(Color.YELLOW, 1.5f))
                target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f)

                // 取消该玩家身上的旧天佑任务
                activeTianYouTasks[target.uniqueId]?.cancel()

                // 注册 15 秒后的护盾消失与爆破任务
                val task = object : BukkitRunnable() {
                    override fun run() {
                        activeTianYouTasks.remove(target.uniqueId)
                        if (!target.isOnline || target.isDead) return

                        val remainingAbs = target.absorptionAmount

                        // 【核心逻辑 2】如果剩余护盾大于赋予的最大值，说明他获得了其他来源的更强护盾，不予消除！
                        if (remainingAbs > 0 && remainingAbs <= shieldAmount) {

                            // 消除护盾和药水效果
                            target.absorptionAmount = 0.0
                            target.removePotionEffect(PotionEffectType.ABSORPTION)

                            // 基于剩余护盾值造成伤害
                            val damage = remainingAbs * explodeMultiplier

                            // 寻找周围3格内的怪物
                            val explodeTargets = target.world.getNearbyEntities(target.location, explodeRadius, explodeRadius, explodeRadius)
                            var hitCount = 0

                            for (entity in explodeTargets) {
                                if (entity is LivingEntity && entity.uniqueId != target.uniqueId) {
                                    val tags = entity.scoreboardTags
                                    if (tags.contains("panling") && tags.contains("monster")) {
                                        // 造成魔法伤害，伤害来源归功于技能释放者(player)
                                        plugin.medicalSpellManager.applyMedicalDamage(player, entity, damage, "tianyou")
                                        hitCount++
                                    }
                                }
                            }

                            // 播放护盾碎裂及爆炸特效
                            target.world.playSound(target.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f)
                            if (hitCount > 0) {
                                target.world.playSound(target.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f)
                                target.world.spawnParticle(Particle.EXPLOSION, target.location.clone().add(0.0, 1.0, 0.0), 3, 0.5, 0.5, 0.5, 0.0)
                            }
                        }
                    }
                }.runTaskLater(plugin, durationTicks)

                activeTianYouTasks[target.uniqueId] = task
            }
        }

        return true
    }
}
