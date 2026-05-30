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
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MingXiangSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    companion object {
        // 记录正在冥想的玩家及其对应的定时任务
        val activeMeditations = ConcurrentHashMap<UUID, BukkitTask>()
    }

    init {
        // 注册事件监听器，用于监听受到伤害时打断冥想
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 读取配置参数
        val durationSeconds = config?.getInt("duration", 10) ?: 10
        val regenMultiplier = config?.getDouble("regen_multiplier", 0.2) ?: 0.2

        val maxTicks = durationSeconds * 20
        val regenAmount = zfStr * regenMultiplier

        // 如果玩家已经在冥想，先取消旧的
        activeMeditations[player.uniqueId]?.cancel()

        // ==================== 修改区域 1：移速惩罚 ====================
        // 获取玩家的移动速度属性 (1.21.3 标准 API)
        val speedAttribute = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
            ?: player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED) // 兼容不同核心的命名

        if (speedAttribute != null) {
            val modifierKey = org.bukkit.NamespacedKey(plugin, "mingxiang_slowness")
            // 先尝试移除可能残留的旧修饰符
            speedAttribute.removeModifier(modifierKey)

            // 添加一个专属的移速修饰符：在总移速基础上减少 80% (-0.8)
            // 1.21.3 使用 ADD_MULTIPLIED_TOTAL (相当于以前的 MULTIPLY_SCALAR_1 或 ADD_SCALAR)
            val modifier = org.bukkit.attribute.AttributeModifier(
                modifierKey,
                -0.8,
                org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1
            )
            speedAttribute.addModifier(modifier)
        }
        // ==========================================================

        player.world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f)
        player.sendMessage("§a[冥想] §f你屏气凝神进入了冥想状态，期间受到伤害将被打断！")

        // 2. 开启冥想持续恢复任务
        val task = object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                // 安全检查
                if (!player.isOnline || player.isDead) {
                    cancelMeditation(player.uniqueId)
                    return
                }

                // 每 10 ticks (0.5秒) 触发一次恢复
                if (ticks > 0 && ticks % 10 == 0) {
                    // --- 恢复生命值 ---
                    plugin.medicalSpellManager.applyMedicalHeal(player, player, regenAmount, "mingxiang")

                    // ==================== 修改区域 2：灵力恢复 ====================
                    // 调用你专属的 addLingli 方法，内部自带上限和下限防溢出处理
                    data.addLingli(regenAmount)
                    // ==========================================================

                    // 播放吸收灵气的音效
                    player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.3f, 2.0f)
                }

                // 每 5 ticks 播放一次环绕粒子特效 (附魔台字符汇聚效果)
                if (ticks % 5 == 0) {
                    val loc = player.location.clone().add(0.0, 1.0, 0.0)
                    player.world.spawnParticle(Particle.WITCH, loc, 6, 0.5, 0.8, 0.5, 0.05)
                }

                ticks += 5 // 任务每 5 ticks 执行一次循环

                // 冥想自然结束
                if (ticks >= maxTicks) {
                    player.sendMessage("§a[冥想] §f冥想结束。")
                    player.world.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
                    cancelMeditation(player.uniqueId)
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)

        activeMeditations[player.uniqueId] = task

        return true
    }

    // 统一的取消冥想方法
    private fun cancelMeditation(uuid: UUID) {
        activeMeditations.remove(uuid)?.cancel()
        val player = plugin.server.getPlayer(uuid)
        // 精准解除减速的 AttributeModifier
        if (player != null) {
            val speedAttribute = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
            if (speedAttribute != null) {
                val modifierKey = org.bukkit.NamespacedKey(plugin, "mingxiang_slowness")
                speedAttribute.removeModifier(modifierKey)
            }
        }
    }

    // ==================== 伤害打断逻辑 ====================
    // 使用 MONITOR 优先级，并忽略已取消的事件，确保只有受到“真实且有效的伤害”时才打断
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDamage(e: EntityDamageEvent) {
        if (e.damage <= 0) return // 如果伤害值为0（如被护盾抵挡），则不视为受击

        val player = e.entity as? Player ?: return

        if (activeMeditations.containsKey(player.uniqueId)) {
            cancelMeditation(player.uniqueId)
            player.sendMessage("§c[冥想] §7冥想被强制打断！")
            // 播放玻璃破碎声代表冥想被打破
            player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f)
        }
    }
}
