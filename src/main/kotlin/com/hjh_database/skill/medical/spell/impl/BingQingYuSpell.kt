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

class BingQingYuSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
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
        val speedDuration = config?.getInt("speed_duration", 10) ?: 10
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
            var cleansed = false

            // 1. 遍历并驱散负面效果
            for (effect in target.activePotionEffects) {
                if (NEGATIVE_EFFECTS.contains(effect.type)) {
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

        return true
    }
}