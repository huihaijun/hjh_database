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
        if (activeMeditations.containsKey(player.uniqueId)) {
            player.sendActionBar("§c§l正在冥想中，无法重复施展。")
            return false
        }
        val zfStr = data.zfStr

        // 读取配置参数
        val durationSeconds = config?.getInt("duration", 30) ?: 30
        val healthStrengthRatio = config?.getDouble("heal_strength_ratio", 0.04) ?: 0.04
        val missingHealthRatio = config?.getDouble("missing_health_ratio", 0.12) ?: 0.12
        val manaHealthRatio = config?.getDouble("mana_max_health_ratio", 0.08) ?: 0.08
        val manaStrengthPenalty = config?.getDouble("mana_strength_penalty", 0.08) ?: 0.08
        val speedReduction = (config?.getDouble("speed_reduction", 0.8) ?: 0.8).coerceIn(0.0, 1.0)

        val maxTicks = durationSeconds * 20

        // ==================== 修改区域 1：移速惩罚 ====================
        // 获取玩家的移动速度属性 (1.21.3 标准 API)
        val speedAttribute = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
            ?: player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED) // 兼容不同核心的命名

        if (speedAttribute != null) {
            val modifierKey = org.bukkit.NamespacedKey(plugin, "mingxiang_slowness")
            // 先尝试移除可能残留的旧修饰符
            speedAttribute.removeModifier(modifierKey)

            // 添加一个专属的移速修饰符，数值由医术配置控制。
            // 1.21.3 使用 ADD_MULTIPLIED_TOTAL (相当于以前的 MULTIPLY_SCALAR_1 或 ADD_SCALAR)
            val modifier = org.bukkit.attribute.AttributeModifier(
                modifierKey,
                -speedReduction,
                org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1
            )
            speedAttribute.addModifier(modifier)
        }
        // ==========================================================

        player.world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f)
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "doctor.cast.mingxiang")) {
            player.sendMessage("§a[冥想] §f你屏气凝神进入了冥想状态，期间受到伤害将被打断！")
        }

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
                    val maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                    val missingHealth = (maxHealth - player.health).coerceAtLeast(0.0)
                    val healAmount = zfStr * healthStrengthRatio + missingHealth * missingHealthRatio
                    plugin.medicalSpellManager.applyMedicalHeal(player, player, healAmount, "mingxiang")

                    // ==================== 修改区域 2：灵力恢复 ====================
                    // 调用你专属的 addLingli 方法，内部自带上限和下限防溢出处理
                    data.addLingli((maxHealth * manaHealthRatio - zfStr * manaStrengthPenalty).coerceAtLeast(0.0))
                    // ==========================================================

                    // 播放吸收灵气的音效
                    player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.3f, 2.0f)
                }

                // 每 5 ticks 播放一次环绕粒子特效 (附魔台字符汇聚效果)
                if (ticks % 5 == 0) {
                    val loc = player.location.clone().add(0.0, 1.0, 0.0)
                    player.world.spawnParticle(Particle.WITCH, loc, 6, 0.5, 0.8, 0.5, 0.05)
                }

                // 冥想自然结束
                if (ticks >= maxTicks) {
                    if (!plugin.passiveSubtitleManager.showCombatEvent(player, "doctor.effect.mingxiang.ended")) {
                        player.sendMessage("§a[冥想] §f冥想结束。")
                    }
                    player.world.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
                    cancelMeditation(player.uniqueId)
                    return
                }
                ticks += 5 // 任务每 5 ticks 执行一次循环
            }
        }.runTaskTimer(plugin, 0L, 5L)

        activeMeditations[player.uniqueId] = task

        return true
    }

    // 统一的取消冥想方法
    private fun cancelMeditation(uuid: UUID) {
        val task = activeMeditations.remove(uuid) ?: return
        task.cancel()
        val player = plugin.server.getPlayer(uuid)
        // 精准解除减速的 AttributeModifier
        if (player != null) {
            val speedAttribute = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
            if (speedAttribute != null) {
                val modifierKey = org.bukkit.NamespacedKey(plugin, "mingxiang_slowness")
                speedAttribute.removeModifier(modifierKey)
            }
            plugin.medicalSpellManager.startCooldown(player, "mingxiang")
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
            if (!plugin.passiveSubtitleManager.showCombatEvent(player, "doctor.effect.mingxiang.ended")) {
                player.sendMessage("§c[冥想] §7冥想被强制打断！")
            }
            // 播放玻璃破碎声代表冥想被打破
            player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f)
        }
    }
}
