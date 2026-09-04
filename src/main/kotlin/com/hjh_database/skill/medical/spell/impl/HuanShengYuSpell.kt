package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HuanShengYuSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    // 记录身上带有“圣羽庇护”的玩家、对应的护盾量和过期时间
    data class FeatherBuff(val shieldAmount: Double, val expiryTime: Long)

    companion object {
        val activeFeathers = ConcurrentHashMap<UUID, FeatherBuff>()
    }

    init {
        // 注册伤害监听，用于拦截致命伤害
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val radius = config?.getDouble("radius", 8.0) ?: 8.0
        val shieldMultiplier = config?.getDouble("shield_multiplier", 2.0) ?: 2.0
        val triggerDuration = config?.getInt("trigger_duration", 10) ?: 10
        val buffDuration = config?.getInt("buff_duration", 5) ?: 5 // 庇护状态默认存在5秒

        val shieldAmount = zfStr * shieldMultiplier
        val expiry = System.currentTimeMillis() + (buffDuration * 1000L)
        val center = player.location

        // 播放施法音效与初始特效 (模拟羽毛散开)
        player.world.playSound(center, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1.0f, 1.5f)
        player.world.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 2.0f)
        player.world.spawnParticle(Particle.CLOUD, center.clone().add(0.0, 1.5, 0.0), 50, radius / 2, 0.5, radius / 2, 0.05)
        player.world.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0.0, 2.0, 0.0), 100, radius / 2, 1.0, radius / 2, 0.02)

        // 寻找范围内的友军 (包含自己)
        val nearbyEntities = player.world.getNearbyEntities(center, radius, radius, radius)
        val targets = mutableListOf<Player>()
        for (entity in nearbyEntities) {
            if (entity is Player && entity.location.distance(center) <= radius) {
                targets.add(entity)
            }
        }
        if (!targets.contains(player)) targets.add(player)

        // 赋予圣羽庇护状态
        for (target in targets) {
            if (target.isDead) continue

            activeFeathers[target.uniqueId] = FeatherBuff(shieldAmount, expiry)
            target.world.spawnParticle(Particle.END_ROD, target.location.clone().add(0.0, 2.0, 0.0), 5, 0.2, 0.2, 0.2, 0.05)
        }

        return true
    }

    // ==================== 致命伤害拦截与触发 ====================
    // 使用 HIGHEST 优先级，确保在结算死亡前最后一步进行拦截
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFatalDamage(e: EntityDamageEvent) {
        val player = e.entity as? Player ?: return

        // 排除虚空伤害和自杀，防止卡在虚空无限免死
        if (e.cause == EntityDamageEvent.DamageCause.VOID || e.cause == EntityDamageEvent.DamageCause.SUICIDE) {
            return
        }

        val buff = activeFeathers[player.uniqueId] ?: return

        // 检查庇护是否过期
        if (System.currentTimeMillis() > buff.expiryTime) {
            activeFeathers.remove(player.uniqueId)
            return
        }

        // 检查是否为致命伤害 (当前生命值 <= 最终将受到的伤害)
        if (player.health - e.finalDamage <= 0) {
            // 1. 取消伤害！
            e.isCancelled = true

            // 2. 消耗掉圣羽庇护
            activeFeathers.remove(player.uniqueId)

            // 3. 赋予基于阵法强度的护盾
            val shieldAmount = buff.shieldAmount
            val amp = (shieldAmount / 4.0).toInt()

            // 持续10秒的吸收药水 (200 ticks)
            player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 200, amp, false, false, true))
            player.absorptionAmount = shieldAmount

            // 4. 赋予 抗性提升 III (等级2) 和 速度 II (等级1)，持续10秒
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 200, 2, false, false, true))
            player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 200, 1, false, false, true))

            // 5. 播放触发音效与满屏的羽毛（雪花）粒子
            player.world.playSound(player.location, Sound.ITEM_TOTEM_USE, 0.5f, 1.5f) // 音量调小且音调变高，使其不那么刺耳
            player.world.playSound(player.location, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 0.8f)
            player.world.spawnParticle(Particle.SNOWFLAKE, player.location.clone().add(0.0, 1.0, 0.0), 100, 1.0, 1.0, 1.0, 0.05)
            player.world.spawnParticle(Particle.END_ROD, player.location.clone().add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, 0.1)

            player.sendMessage("§f[唤圣羽] §e圣羽破碎！你躲过了致命一击并获得了圣光庇护！")
        }
    }
}