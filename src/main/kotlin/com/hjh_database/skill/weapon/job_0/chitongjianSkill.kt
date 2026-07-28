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
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class chitongjianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database

    // 记录开启了技能但还没砍出那一刀的玩家
    private val primedPlayers = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        // 1. 标记玩家为“蓄力”状态
        primedPlayers.add(player.uniqueId)

        // 2. 消息与音效 (仅发送配置的消息)
        val message = config.getString("message", "&a&l武器技【凤焰斩】发动！")
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', message))
            )
        }

        // 播放烈焰音效
        player.world.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 1f, 1f)
        // 剑身冒火粒子特效
        player.world.spawnParticle(Particle.FLAME, player.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3, 0.05)

        // 3. 设置超时自动取消 (防止玩家开了技能一直不砍人，占用内存)
        // 设置为 10秒后过期
        object : BukkitRunnable() {
            override fun run() {
                primedPlayers.remove(player.uniqueId)
            }
        }.runTaskLater(plugin, 200L)

        return true
    }

    // ★★★ 核心逻辑：监听攻击 ★★★
    // 使用 HIGHEST 优先级，确保在 CombatListener (HIGH) 计算完基础伤害和护甲减免后执行
    // 公式：(攻击力 * 减伤系数) * 1.5 等同于 (攻击力 * 1.5) * 减伤系数
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Player) return
        if (!primedPlayers.contains(damager.uniqueId)) return
        if (!plugin.equipmentActivationManager.isHoldingActiveWeapon(damager, "chitongjian")) return

        val victim = event.entity as? LivingEntity ?: return

            // 1. 消耗技能状态
        primedPlayers.remove(damager.uniqueId)

            // 2. 读取配置倍率 (150% = 1.5)
            // 这里为了性能没有每次去读文件，如需热重载可从 configCache 读取，这里硬编码或建议存变量
        val multiplier = 2.0

            // 3. 应用伤害加成
        event.damage = event.damage * multiplier

            // 4. 命中特效
        victim.world.spawnParticle(Particle.LAVA, victim.location.add(0.0, 1.0, 0.0), 15)
        victim.world.playSound(victim.location, Sound.ENTITY_BLAZE_HURT, 1f, 1f)

            // 5. 施加【凤焰】标记 (DoT)
        applyPhoenixFlame(victim, damager)
    }

    private fun applyPhoenixFlame(victim: LivingEntity, attacker: Player) {
        // 获取最大生命值 (兼容 1.21.3)
        val maxHp = victim.getAttribute(Attribute.MAX_HEALTH)?.value
            ?: victim.getAttribute(Attribute.MAX_HEALTH)?.value
            ?: 20.0

        // 计算每秒伤害：最大生命 2%，上限 100
        val damagePerTick = min(maxHp * 0.02, 100.0)

        // 启动定时任务：持续 5秒，每秒 (20 ticks) 一次
        object : BukkitRunnable() {
            var count = 0
            val maxCount = 5

            override fun run() {
                // 如果怪物死了或无效，或者次数到了，停止
                if (!victim.isValid || victim.isDead || count >= maxCount) {
                    this.cancel()
                    return
                }

                // 1. 给受击者打上 "这是技能伤害" 的标记
                // 注意：FixedMetadataValue 需要 plugin 实例，你在 chitongjianSkill 里有拿到 plugin
                    victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                // 1. 备份当前的无敌时间 (可选，但直接归零通常手感更好)
                // val oldNoDamageTicks = victim.noDamageTicks
                // 2. 强制破除无敌帧：确保这次 DoT 伤害必定生效，不被普攻卡掉
                victim.noDamageTicks = 0
                try {
                    // 造成伤害 (CombatListener 会捕获此伤害，发现标记后放行，但计算护甲)
                    victim.damage(damagePerTick, attacker)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // ★★★ 务必移除标记，防止影响后续攻击 ★★★
                    //  或者完全依赖 CombatListener 的 removeMetadata 也可以，这里保留 cleanup 是好习惯)
                    if (victim.hasMetadata("hjh_physical_skill")) {
                        victim.removeMetadata("hjh_physical_skill", plugin)
                    }
                    // 3. 消除技能产生的无敌帧：
                    // 调用 damage() 成功后，MC 会自动给怪加上 10 tick 无敌。我们必须立马把它消掉，否则玩家会觉得“这怪怎么砍不动了”
                    victim.noDamageTicks = 0
                }
                // 灼烧特效
                victim.world.spawnParticle(Particle.FLAME, victim.location.add(0.0, 1.0, 0.0), 5, 0.2, 0.5, 0.2, 0.02)

                count++
            }
        }.runTaskTimer(plugin, 20L, 20L) // 延迟20tick执行第一次，之后每20tick执行一次
    }

    override fun deactivate(player: Player) {
        primedPlayers.remove(player.uniqueId)
    }
}
