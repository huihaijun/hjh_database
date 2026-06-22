package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class jutongzhanchuiSkill : WeaponSkill {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
    private val shieldedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null || config == null) return false

        // 1. 读取配置
        val radius = config.getDouble("radius", 5.0)
        val damageMult = config.getDouble("damage_multiplier", 0.5) // 50%
        val stunDuration = config.getDouble("stun_duration", 0.5)
        val shieldHearts = config.getInt("shield_hearts", 6) // 6颗心 = 12点血
        val shieldDuration = config.getDouble("shield_duration", 20.0)
        val message = config.getString("message", "&a&l武器技【撼地破】发动！")

        // 2. 视觉效果 (震撼感)
        // 播放重击地面声音
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f)
        player.world.playSound(player.location, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f)

        // 地面震荡粒子
        player.world.spawnParticle(Particle.EXPLOSION, player.location, 1)
        player.world.spawnParticle(Particle.CLOUD, player.location, 20, radius / 2, 0.2, radius / 2, 0.1)

        // 3. 给予自身护盾 (伤害吸收)
        // 伤害吸收: 每1级提供4点血(2心)。
        // 目标 12点血 -> 需要 12/4 = 3级 -> Amplifier = 2
        var amplifier = (shieldHearts * 2 / 4) - 1
        if (amplifier < 0) amplifier = 0

        // 移除旧的护盾(如果有)，加上新的
        player.removePotionEffect(PotionEffectType.ABSORPTION)
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, (shieldDuration * 20).toInt(), amplifier))
        shieldedPlayers.add(player.uniqueId)

        // 4. AOE 伤害与控制逻辑
        val nearby = player.getNearbyEntities(radius, 3.0, radius)

        // 计算伤害值 (面板最大生命 * 倍率)
        val damageAmount = data.maxHealth * damageMult

        for (target in nearby) {
            // 排除自己，且必须是活体
            if (target is LivingEntity && target != player) {

                // ★★★ 标签筛选：必须同时拥有 panling 和 monster ★★★
                if (target.scoreboardTags.contains("panling") && target.scoreboardTags.contains("monster")) {

                    // A. 击飞 (低高度)
                    // 向量 y=0.45 大约跳起 1 格多一点
                    target.velocity = Vector(0.0, 0.45, 0.0)

                    // B. 晕眩 (高等级缓慢 + 盲目)
                    target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (stunDuration * 20).toInt(), 255))
                    target.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, (stunDuration * 20).toInt(), 1))

                    // C. 造成伤害 (使用 hjh_physical_skill 标记)
                    applySkillDamage(target, player, damageAmount)
                }
            }
        }

        // 5. 提示消息
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', message))
            )
        }

        return true
    }

    private fun applySkillDamage(victim: LivingEntity, attacker: Player, amount: Double) {
        // ★★★ 核心：打上标记，绕过 CombatListener 的武器检查，但保留护甲计算 ★★★
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        try {
            victim.damage(amount, attacker)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 清理标记
            if (victim.hasMetadata("hjh_physical_skill")) {
                victim.removeMetadata("hjh_physical_skill", plugin)
            }
        }
    }

    override fun deactivate(player: Player) {
        if (shieldedPlayers.remove(player.uniqueId)) {
            player.removePotionEffect(PotionEffectType.ABSORPTION)
        }
    }
}
