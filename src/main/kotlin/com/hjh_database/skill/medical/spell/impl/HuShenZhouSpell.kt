package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HuShenZhouSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    companion object {
        // 记录玩家身上的免伤光罩状态 (true代表有光罩)
        val activeImmunity = ConcurrentHashMap<UUID, Boolean>()
        // 记录玩家的护盾倒计时任务，用于多次释放时取消旧任务（刷新持续时间）
        val activeTasks = ConcurrentHashMap<UUID, BukkitTask>()
    }

    init {
        // 将自身作为监听器注册，用于监听受击免伤的逻辑
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr
        // 读取配置中的参数
        val multiplier = config?.getDouble("absorption_multiplier", 3.0) ?: 3.0
        val durationSeconds = config?.getInt("duration", 10) ?: 10
        val durationTicks = durationSeconds * 20L

        // 吸收伤害量 = 阵法强度 * 300%
        val shieldAmount = zfStr * multiplier

        // 1. 赋予吸收伤害的护盾 (赋予黄心)
        // 【修复】动态计算需要的药水等级，确保客户端能渲染出所有黄心
        // 比如 12点护盾 / 4 = 3 (即吸收 IV，上限16点)，给客户端留出足够的显示空间
        val amp = (shieldAmount / 4.0).toInt()

        player.addPotionEffect(org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.ABSORPTION,
            durationTicks.toInt(),
            amp, false, false, true
        ))

        // 然后再覆写具体的吸收数值，精准设定为你的 12 点
        player.absorptionAmount = shieldAmount

        // 2. 赋予一次性免伤光罩
        activeImmunity[player.uniqueId] = true

        // 3. 播放视觉与音效 (金光护体效果)
        val loc = player.location
        player.world.playSound(loc, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.2f)
        player.world.playSound(loc, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 2.0f)

        // 1.21.3版本使用 Particle.DUST 替代了旧版本的 Particle.REDSTONE
        player.world.spawnParticle(
            Particle.DUST,
            loc.clone().add(0.0, 1.0, 0.0),
            30, 0.5, 0.8, 0.5,
            Particle.DustOptions(Color.YELLOW, 1.5f)
        )

        // 4. 清理旧的倒计时任务（实现“多次释放只刷新时间”的核心）
        activeTasks[player.uniqueId]?.cancel()

        // 5. 开启新定时任务（10秒后清除护盾和光罩）
        val task = object : BukkitRunnable() {
            override fun run() {
                if (player.isOnline) {
                    // 时间到，清除剩余的吸收血量
                    // 【修改】时间到，同时清除药水效果和吸收血量
                    player.removePotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION)
                    player.absorptionAmount = 0.0
                    // 如果光罩还没被打破，则默默消散
                    if (activeImmunity.remove(player.uniqueId) == true) {
                        player.sendMessage("§7[护身咒] 你的免伤光罩灵力耗尽，已自动消散。")
                    }
                }
                activeTasks.remove(player.uniqueId)
            }
        }.runTaskLater(plugin, durationTicks)

        activeTasks[player.uniqueId] = task

        return true
    }

    // ==================== 伤害抵挡逻辑 ====================
    // 优先级设为 HIGH，确保能抢在普通伤害结算前拦截
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerDamage(e: EntityDamageEvent) {
        val player = e.entity as? Player ?: return

        // 如果玩家拥有免伤光罩
        if (activeImmunity.containsKey(player.uniqueId)) {
            // 虚空掉落和 /kill 指令的伤害不予免疫
            if (e.cause == EntityDamageEvent.DamageCause.VOID || e.cause == EntityDamageEvent.DamageCause.CUSTOM) return

            // 免疫此次伤害
            e.damage = 0.0
            e.isCancelled = true

            // 消耗光罩
            activeImmunity.remove(player.uniqueId)

            // 播放光罩破碎的特效和音效
            player.world.playSound(player.location, Sound.ITEM_SHIELD_BREAK, 1.0f, 0.8f)
            player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f)
            player.world.spawnParticle(Particle.CRIT, player.location.clone().add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.2)

            player.sendMessage("§a[护身咒] §f你的免伤光罩抵挡了一次伤害并破碎了！")
        }
    }
}