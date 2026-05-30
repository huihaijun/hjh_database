package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.min

// 【改动1】继承 AbstractElementSkill，去掉 private val
class WaterSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】将 cast 改为 onCast
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {

        // 1. 读取配置 (父类已处理好了非空和路径检查)
        val damagePercent = safeConfig.getDouble("$path.damage_percent", 1.0)
        val range = safeConfig.getDouble("$path.range", 4.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        val slowDurationSeconds = safeConfig.getDouble("$path.slow_duration", 2.0)
        val slowAmplifier = safeConfig.getInt("$path.slow_amplifier", 1)

        // ！！！ 消耗物品逻辑已交由父类处理，此处彻底删除 ！！！

        // 2. 获取数据并增加灵力
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.savePlayer(data)
            }
        }

        // 3. 寻找目标
        val targets = findTargets(player, range)

        // 4. 造成伤害与控制
        // 如果有目标，才循环造成伤害；没有目标就跳过，但不打断流程
        if (targets.isNotEmpty()) {
            val baseDamage = data.zfStr
            val finalDamage = baseDamage * damagePercent

            for (target in targets) {
                // 法术伤害
                target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
                target.damage(finalDamage, player)
                target.removeMetadata("hjh_magic_damage", plugin)

                // 减速
                target.addPotionEffect(
                    PotionEffect(
                        PotionEffectType.SLOWNESS,
                        (slowDurationSeconds * 20).toInt(),
                        slowAmplifier
                    )
                )
            }
        }

        // 5. 提示与特效 (空放也会播放扇形特效)
        playConeEffects(player, range)

        return true // 总是进入冷却
    }

    // ============================================
    // 下方的 findTargets 和 playConeEffects 完完全全保持你源码的原样！
    // ============================================

    private fun findTargets(player: Player, range: Double): List<LivingEntity> {
        val entities = player.getNearbyEntities(range, range, range)
        val playerLoc = player.location
        val direction = playerLoc.direction

        return entities.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player }
            .filter { e ->
                val tags = e.scoreboardTags
                tags.contains("panling") && tags.contains("monster")
            }
            .filter { e ->
                val toEntity = e.location.toVector().subtract(playerLoc.toVector()).normalize()
                direction.dot(toEntity) > 0.5
            }
            .filter { e -> e.location.distance(playerLoc) <= range }
            .toList()
    }

    /**
     * 播放锥形/扇形 冰霜喷射特效
     */
    private fun playConeEffects(player: Player, range: Double) {
        // 起点：玩家眼睛稍微往下一点，模拟嘴巴/手部吹气
        val start = player.eyeLocation.add(0.0, -0.2, 0.0)
        val mainDir = start.direction.normalize()

        val world = player.world

        // 步长 0.5，意味着每半格生成一簇粒子
        var d = 0.5
        while (d < range) {
            // 中心点：沿着视线方向延伸 d 米
            val centerPoint = start.clone().add(mainDir.clone().multiply(d))

            // 扩散半径：距离越远，粒子扩散范围越大 (d * 0.6 约等于 60度角的开口)
            val spread = d * 0.6

            // 在这个距离切面上生成多个随机粒子，填满体积
            // 距离越远，需要的粒子越多才能填满视觉，所以 i < 5 + d
            val particleCount = (5 + d * 2).toInt()

            for (i in 0 until particleCount) {
                // 生成随机偏移量 (-spread/2 到 +spread/2)
                val offsetX = (Math.random() - 0.5) * spread
                val offsetY = (Math.random() - 0.5) * spread
                val offsetZ = (Math.random() - 0.5) * spread

                val particleLoc = centerPoint.clone().add(offsetX, offsetY, offsetZ)

                // 1. 雪花粒子 (主要视觉)
                world.spawnParticle(Particle.SNOWFLAKE, particleLoc, 1, 0.0, 0.0, 0.0, 0.01)

                // 2. 蓝色尘埃 (增加魔法感) - 30% 概率生成
                if (Math.random() < 0.3) {
                    world.spawnParticle(
                        Particle.DUST, particleLoc, 1,
                        Particle.DustOptions(Color.fromRGB(150, 240, 255), 0.8f)
                    ) // 冰蓝色
                }

                // 3. 云雾 (增加厚重感) - 10% 概率生成，只在远端生成
                if (d > 2.0 && Math.random() < 0.1) {
                    world.spawnParticle(Particle.CLOUD, particleLoc, 0, 0.0, 0.0, 0.0, 0.05)
                }
            }
            d += 0.5
        }

        // 音效
        world.playSound(start, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f) // 碎裂声
        world.playSound(start, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.6f, 1.8f) // 呼啸声（高音调模拟寒风）
    }
}