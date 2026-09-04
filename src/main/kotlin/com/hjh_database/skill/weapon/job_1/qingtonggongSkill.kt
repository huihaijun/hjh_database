package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class qingtonggongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 全局标记池：记录怪物UUID -> 标记过期时间戳
    // 这样所有玩家共享这个标记池
    private val markedTargets = ConcurrentHashMap<UUID, Long>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 视觉特效任务：在被标记的怪物头顶产生“鹰眼”特效，并清理过期数据
        object : BukkitRunnable() {
            override fun run() {
                if (markedTargets.isEmpty()) return
                val now = System.currentTimeMillis()
                val iter = markedTargets.iterator()

                while (iter.hasNext()) {
                    val entry = iter.next()
                    val targetId = entry.key
                    val expireTime = entry.value

                    if (now > expireTime) {
                        iter.remove()
                        continue
                    }

                    val entity = Bukkit.getEntity(targetId) as? LivingEntity
                    if (entity != null && entity.isValid && !entity.isDead) {
                        // 在怪物头顶产生金色与红色的魔法粒子，象征“被锁定”
                        val loc = entity.location.add(0.0, entity.height + 0.6, 0.0)
                        entity.world.spawnParticle(Particle.CRIT, loc, 3, 0.2, 0.1, 0.2, 0.0)
                        entity.world.spawnParticle(Particle.FLAME, loc, 1, 0.1, 0.1, 0.1, 0.0)
                    } else {
                        iter.remove()
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    // --- 1. 技能释放（打上触发标记） ---
    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null || projectile == null) return false

        if (projectile is AbstractArrow) {
            // 给这支箭打上“鹰眼触发器”标签
            projectile.setMetadata("hjh_yingyan_trigger", FixedMetadataValue(plugin, true))

            player.world.playSound(player.location, Sound.ENTITY_PHANTOM_SWOOP, 1f, 1.2f)
            player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 10, 0.2, 0.2, 0.2, 0.1)

            val message = config.getString("message", "&e&l武器技【鹰眼】发动！锁敌箭已射出！")
            if (!message.isNullOrEmpty()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
            }
            return true
        }
        return false
    }

    // --- 2. 射箭监听（给青铜弓射出的所有箭打上身份标记） ---
    // 为了实现“只有青铜弓能享受增伤”，我们需要在箭矢射出时给它发“身份证”
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        val meta = bow.itemMeta ?: return

        val weaponKey = NamespacedKey(plugin, "weapon_id")
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)

        // 如果射箭的武器是青铜弓
        if (weaponId == "qingtonggong") {
            // 给射出的所有箭（无论是否是技能箭）打上武器身份标签
            event.projectile.setMetadata("hjh_qingtonggong_arrow", FixedMetadataValue(plugin, true))
        }
    }

    // --- 3. 命中监听（处理标记叠加与距离增伤） ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        val victim = event.entity as? LivingEntity ?: return

        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        // A. 判定是否是技能触发箭
        if (arrow.hasMetadata("hjh_yingyan_trigger")) {
            // 施加标记 (持续 8 秒 = 8000 ms)
            markedTargets[victim.uniqueId] = System.currentTimeMillis() + 8000L

            victim.world.playSound(victim.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 2.0f)

            // 注意：这里不 return，因为触发标记的这支箭本身也能享受增伤
        }

        // B. 判定怪物身上是否有有效的鹰眼标记
        val expireTime = markedTargets[victim.uniqueId]
        if (expireTime != null && System.currentTimeMillis() < expireTime) {

            // C. 判定攻击的箭矢是否来自青铜弓
            if (arrow.hasMetadata("hjh_qingtonggong_arrow")) {
                val shooter = arrow.shooter as? Player ?: return

                // 确保在同一个世界
                if (shooter.world != victim.world) return

                // 计算距离
                val distance = shooter.location.distance(victim.location)

                // 距离计算公式：
                // 假设 30 格达到最大增伤 (240%)，即基础 100% + 额外 140%
                val maxDistance = 30.0
                val ratio = min(distance / maxDistance, 1.0) // 0.0 到 1.0 之间

                // multiplier 范围: 1.0(贴脸) ~ 2.4(30格开外)
                val multiplier = 1.0 + (ratio * 1.4)

                // 应用伤害放大
                event.damage *= multiplier

                // 视觉/听觉反馈：距离越远，打中越痛，音效越清脆
                if (multiplier > 2.0) {
                    victim.world.playSound(victim.location, Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1.5f)
                    victim.world.spawnParticle(Particle.CRIT, victim.location.add(0.0, 1.0, 0.0), (15 * multiplier).toInt(), 0.3, 0.3, 0.3, 0.1)
                }
            }
        }
    }
}
