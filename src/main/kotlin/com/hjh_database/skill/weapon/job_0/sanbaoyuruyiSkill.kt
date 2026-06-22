package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
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

class sanbaoyuruyiSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录标记关系：玩家UUID -> (怪物UUID, 过期时间戳)
    private val markedTargets = ConcurrentHashMap<UUID, Pair<UUID, Long>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 特效任务：标记持续期间的视觉效果
        object : BukkitRunnable() {
            override fun run() {
                if (markedTargets.isEmpty()) return
                val now = System.currentTimeMillis()
                val iter = markedTargets.iterator()

                while (iter.hasNext()) {
                    val entry = iter.next()
                    val targetId = entry.value.first
                    val expireTime = entry.value.second

                    // 清理过期
                    if (now > expireTime) {
                        iter.remove()
                        continue
                    }

                    // 播放特效
                    val entity = Bukkit.getEntity(targetId)
                    if (entity != null && entity.isValid && !entity.isDead) {
                        val loc = entity.location.add(0.0, entity.height + 0.5, 0.0)
                        entity.world.spawnParticle(
                            Particle.DUST,
                            loc, 3, 0.3, 0.1, 0.3, 0.0,
                            Particle.DustOptions(Color.LIME, 1.5f) // 玉色
                        )
                        entity.world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 1, 0.2, 0.2, 0.2)
                    } else {
                        iter.remove()
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        // 1. 获取准心目标
        val target = getTargetEntity(player, 10.0)

        if (target == null) {
            player.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§c未瞄准有效目标！"))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 0.5f)
            return true
        }

        // 2. 施加标记
        val duration = config.getDouble("duration", 5.0)
        val expireTime = System.currentTimeMillis() + (duration * 1000).toLong()
        markedTargets[player.uniqueId] = Pair(target.uniqueId, expireTime)

        // 3. 提示与音效
        val message = config.getString("message", "&a&l武器技【驱邪】发动！")
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
        }

        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f)
        player.world.playSound(target.location, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 2.0f)

        // 连线特效
        spawnLineParticle(player, target)

        return true
    }

    // ★★★ 阶段 1：预处理 (Priority = LOW) ★★★
    // 在 CombatListener (HIGH) 之前运行。
    // 目的：打上 "hjh_magic_damage" (小写) 标记。
    // 效果：CombatListener 会正常计算你的攻击力，但在计算护甲时，发现这个标记会将 armor 设为 0。
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamagePre(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Player) return

        val markInfo = markedTargets[damager.uniqueId] ?: return

        // 检查过期
        if (System.currentTimeMillis() > markInfo.second) {
            markedTargets.remove(damager.uniqueId)
            return
        }

        // 检查目标
        if (event.entity.uniqueId != markInfo.first) return
        val victim = event.entity as? LivingEntity ?: return

        // 给怪物打上 CombatListener 识别的“无视护甲”标记 (注意是小写)
        victim.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
    }

    // ★★★ 阶段 2：后处理 (Priority = HIGHEST) ★★★
    // 在 CombatListener (HIGH) 之后运行。
    // 目的：此时 damage 已经是 (攻击力 - 0护甲) 的数值了。我们在这里乘倍率、处理斩杀，并移除标记。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamagePost(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Player) return
        val victim = event.entity as? LivingEntity ?: return

        // 再次校验标记（确保是同一次攻击逻辑）
        val markInfo = markedTargets[damager.uniqueId] ?: return
        if (event.entity.uniqueId != markInfo.first) return

        // 1. 【非常重要】立即清理标记，防止它永久留在怪物身上
        if (victim.hasMetadata("hjh_magic_damage")) {
            victim.removeMetadata("hjh_magic_damage", plugin)
        }

        // 2. 斩杀逻辑
        if (!victim.scoreboardTags.contains("instance_boss")) {
            val maxHealth = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            val currentHealth = victim.health
            val executeThreshold = maxHealth * 0.15

            // 如果当前血量低于 15%，或者 这一击伤害足以打到 15% 以下 (视需求而定，这里按血量低直接秒)
            if (currentHealth <= executeThreshold) {
                // 直接给予致死伤害
                event.damage = currentHealth + 1000.0

                // 特效
                victim.world.spawnParticle(Particle.FLASH, victim.location.add(0.0, 1.0, 0.0), 1)
                victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 1.5f)
                damager.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§a§l[驱邪] 斩杀！"))
                return
            }
        }

        // 3. 增伤逻辑 (提升 50%)
        // 此时 event.damage 已经是满额攻击力(无视护甲)了
        event.damage = event.damage * 1.5
    }

    // 辅助方法：光线追踪获取目标
    private fun getTargetEntity(player: Player, range: Double): LivingEntity? {
        val world = player.world
        val start = player.eyeLocation
        val direction = start.direction

        val result = world.rayTraceEntities(start, direction, range, 0.5) { entity ->
            entity is LivingEntity && entity != player &&
                    entity.scoreboardTags.contains("panling") &&
                    entity.scoreboardTags.contains("monster")
        }

        return result?.hitEntity as? LivingEntity
    }

    // 辅助方法：连线特效
    private fun spawnLineParticle(player: Player, target: LivingEntity) {
        val start = player.eyeLocation.add(0.0, -0.3, 0.0)
        val end = target.location.add(0.0, target.height / 2, 0.0)
        val distance = start.distance(end)
        val direction = end.subtract(start).toVector().normalize()

        for (i in 0 until (distance * 2).toInt()) {
            val point = start.clone().add(direction.clone().multiply(i * 0.5))
            player.world.spawnParticle(
                Particle.DUST,
                point, 1, 0.0, 0.0, 0.0, 0.0,
                Particle.DustOptions(Color.AQUA, 0.5f)
            )
        }
    }

    override fun deactivate(player: Player) {
        markedTargets.remove(player.uniqueId)
    }
}
