package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
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
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class kaishandaoSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database

    // 存储激活状态的数据类
    private data class BuffData(
        val expireTime: Long,       // 过期时间戳
        var currentStacks: Int = 0, // 当前层数
        val maxStacks: Int,         // 最大层数
        val damageStep: Double      // 每层增伤
    )

    // 记录谁开启了技能 (UUID -> Buff数据)
    private val activeBuffs = ConcurrentHashMap<UUID, BuffData>()

    init {
        // 注册事件监听
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        // 1. 读取配置
        val duration = config.getDouble("duration", 5.0)
        val damageStep = config.getDouble("damage_step", 0.1)
        val maxBonus = config.getDouble("max_bonus", 0.5)
        val message = config.getString("message", "&a&l武器技【追击】发动！")

        val maxStacks = (maxBonus / damageStep).toInt()

        // 2. 设置 Buff 数据
        val expireTime = System.currentTimeMillis() + (duration * 1000).toLong()

        // 重置层数为 0 (意味着开启后第一刀是原伤害，第二刀才开始+10%)
        activeBuffs[player.uniqueId] = BuffData(expireTime, 0, maxStacks, damageStep)

        // 3. 视觉效果
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THUNDER, 1f, 0.8f)
        player.world.spawnParticle(Particle.ANGRY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 5, 0.3, 0.3, 0.3, 0.0)

        // 4. 发送 Actionbar
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', message))
            )
        }

        return true
    }

    // ★★★ 核心修复：优先级设为 HIGHEST ★★★
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager

        if (damager is Player) {
            val uuid = damager.uniqueId
            val buff = activeBuffs[uuid] ?: return // 没有 Buff 就跳过
            if (!plugin.equipmentActivationManager.isHoldingActiveWeapon(damager, "kaishandao")) return

            // 检查是否过期
            if (System.currentTimeMillis() > buff.expireTime) {
                activeBuffs.remove(uuid)
                return
            }

            // 1. 记录基础伤害 (来自 CombatListener)
            val baseDamage = event.damage

            // 2. 计算增伤比例 (当前层数 * 单层比例)
            val bonusRatio = buff.currentStacks * buff.damageStep

            if (bonusRatio > 0) {
                // 计算新伤害
                val extraDamage = baseDamage * bonusRatio
                val finalDamage = baseDamage + extraDamage

                // 应用伤害
                event.damage = finalDamage
            }

            // 3. 叠加层数 (为下次攻击做准备)
            if (buff.currentStacks < buff.maxStacks) {
                buff.currentStacks++
            }
        }
    }

    override fun deactivate(player: Player) {
        activeBuffs.remove(player.uniqueId)
    }
}
