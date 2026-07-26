package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import com.hjh_database.spawner.impl.NorthWetnessSkill
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionEffectTypeCategory
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BingQingYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    private val vulnerabilityParticleTasks = ConcurrentHashMap<UUID, BukkitTask>()

    companion object {
        const val VULNERABILITY_UNTIL_METADATA = "hjh_bingqingyu_vulnerability_until"
        const val VULNERABILITY_AMOUNT_METADATA = "hjh_bingqingyu_vulnerability_amount"

        // 定义需要驱散的负面药水效果集合 (适配 1.21.3)
        val NEGATIVE_EFFECTS = setOf(
            PotionEffectType.SLOWNESS,          // 缓慢
            PotionEffectType.MINING_FATIGUE,    // 挖掘疲劳
            PotionEffectType.NAUSEA,            // 反胃
            PotionEffectType.BLINDNESS,         // 失明
            PotionEffectType.HUNGER,            // 饥饿
            PotionEffectType.WEAKNESS,          // 虚弱
            PotionEffectType.POISON,            // 中毒
            PotionEffectType.WITHER,            // 凋零
            PotionEffectType.DARKNESS,          // 黑暗 (1.19+)
            PotionEffectType.OOZING,            // 渗血 (1.21 药水)
            PotionEffectType.WEAVING,           // 盘丝 (1.21 药水)
            PotionEffectType.INFESTED           // 感染 (1.21 药水)
        )
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        // 读取配置
        val radius = config?.getDouble("radius", 10.0) ?: 10.0
        val speedDuration = config?.getInt("speed_duration", 20) ?: 20
        val vulnerability = (config?.getDouble("vulnerability", 0.15) ?: 0.15).coerceAtLeast(0.0)
        val vulnerabilityDuration = (config?.getDouble("vulnerability_duration", 7.0) ?: 7.0).coerceAtLeast(0.0)
        val speedTicks = speedDuration * 20

        val center = player.location

        // 播放冰雪音效 (玻璃碎裂代表冰晶，紫水晶代表清脆的净化音)
        player.world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f)
        player.world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.5f)

        // 播放大范围冰雪粒子特效
        player.world.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0.0, 1.0, 0.0), 100, radius / 2, 1.0, radius / 2, 0.05)
        player.world.spawnParticle(Particle.FIREWORK, center.clone().add(0.0, 1.0, 0.0), 50, radius / 2, 1.0, radius / 2, 0.1)

        // 获取范围内的玩家 (友军)
        val nearbyEntities = player.world.getNearbyEntities(center, radius, radius, radius)
        val targets = mutableListOf<Player>()

        for (entity in nearbyEntities) {
            // 这里默认同为玩家即视为友军，如果有组队系统可在此处添加 team 判断
            if (entity is Player && entity.location.distance(center) <= radius) {
                targets.add(entity)
            }
        }
        // 始终包含施法者自己
        if (!targets.contains(player)) {
            targets.add(player)
        }

        // 结算驱散与增益
        for (target in targets) {
            var cleansed = NorthWetnessSkill.cleanseToOnePercent(target)

            // 1. 遍历并驱散负面效果
            for (effect in target.activePotionEffects) {
                if (effect.type.category == PotionEffectTypeCategory.HARMFUL || NEGATIVE_EFFECTS.contains(effect.type)) {
                    target.removePotionEffect(effect.type)
                    cleansed = true
                }
            }

            // 2. 赋予速度 II (在Bukkit中等级填1即代表原版的速度II)
            target.addPotionEffect(PotionEffect(PotionEffectType.SPEED, speedTicks, 1))

            // 提示音与消息
            if (cleansed) {
                target.sendMessage("§b[冰清域] §f一阵凛冽的寒风拂过，驱散了你身上的负面状态并赋予了加速！")
            } else {
                target.sendMessage("§b[冰清域] §f一阵凛冽的寒风拂过，你获得了加速效果！")
            }
        }

        // 冰清域易伤使用独立 metadata。重复施放只刷新该效果的持续时间，
        // 不会覆盖毒火烛、夺魂丹等其他来源的易伤标记。
        if (vulnerability > 0.0 && vulnerabilityDuration > 0.0) {
            val radiusSquared = radius * radius
            for (entity in nearbyEntities) {
                val monster = entity as? LivingEntity ?: continue
                val tags = monster.scoreboardTags
                if (!tags.contains("panling") || !tags.contains("monster")) continue
                if (!monster.isValid || monster.isDead || monster.location.distanceSquared(center) > radiusSquared) continue
                applyVulnerability(monster, vulnerability, vulnerabilityDuration)
            }
        }

        return true
    }

    private fun applyVulnerability(target: LivingEntity, amount: Double, durationSeconds: Double) {
        val until = System.currentTimeMillis() + (durationSeconds * 1000.0).toLong()
        target.setMetadata(VULNERABILITY_UNTIL_METADATA, FixedMetadataValue(plugin, until))
        target.setMetadata(VULNERABILITY_AMOUNT_METADATA, FixedMetadataValue(plugin, amount))

        val center = target.location.clone().add(0.0, target.height * 0.55, 0.0)
        target.world.spawnParticle(Particle.SNOWFLAKE, center, 18, 0.4, 0.55, 0.4, 0.025)
        target.world.spawnParticle(
            Particle.DUST, center, 12, 0.35, 0.45, 0.35, 0.0,
            Particle.DustOptions(Color.fromRGB(120, 220, 255), 1.0f)
        )

        // 已有同类任务时仅刷新 metadata 中的截止时间，不叠加第二个易伤或粒子任务。
        if (vulnerabilityParticleTasks.containsKey(target.uniqueId)) return

        val task = object : BukkitRunnable() {
            override fun run() {
                val effectUntil = target.getMetadata(VULNERABILITY_UNTIL_METADATA)
                    .firstOrNull { it.owningPlugin == plugin }
                    ?.asLong() ?: 0L
                if (!target.isValid || target.isDead || effectUntil <= System.currentTimeMillis()) {
                    target.removeMetadata(VULNERABILITY_UNTIL_METADATA, plugin)
                    target.removeMetadata(VULNERABILITY_AMOUNT_METADATA, plugin)
                    vulnerabilityParticleTasks.remove(target.uniqueId)
                    cancel()
                    return
                }

                val particleCenter = target.location.clone().add(0.0, target.height * 0.55, 0.0)
                target.world.spawnParticle(Particle.SNOWFLAKE, particleCenter, 4, 0.36, 0.45, 0.36, 0.012)
                target.world.spawnParticle(
                    Particle.DUST, particleCenter, 3, 0.3, 0.38, 0.3, 0.0,
                    Particle.DustOptions(Color.fromRGB(95, 205, 255), 0.85f)
                )
            }
        }.runTaskTimer(plugin, 0L, 5L)
        vulnerabilityParticleTasks[target.uniqueId] = task
    }
}
