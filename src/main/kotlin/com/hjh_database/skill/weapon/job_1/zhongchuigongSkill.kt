package com.hjh_database.skill.weapon.job_1

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
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
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class zhongchuigongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java) as Hjh_database

    // 记录怪物的[重锤]标记，怪物UUID -> 标记过期时间戳
    private val markedTargets = ConcurrentHashMap<UUID, Long>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 视觉特效任务
        object : BukkitRunnable() {
            override fun run() {
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
                        val loc = entity.location.add(0.0, entity.height + 0.5, 0.0)
                        entity.world.spawnParticle(Particle.ASH, loc, 5, 0.3, 0.3, 0.3, 0.0)
                        entity.world.spawnParticle(Particle.FALLING_DUST, loc, 1, 0.2, 0.2, 0.2, 0.0, org.bukkit.Material.ANVIL.createBlockData())
                    } else {
                        iter.remove()
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    // === 主动技能触发验证区 ===
    // 这里如果返回 true，Manager 才会扣除 CD 并判定释放成功
    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || projectile == null) return false

        if (projectile is AbstractArrow) {
            // 1. 下蹲射击：传入的是箭矢。给箭打上主动标记。
            projectile.setMetadata("hjh_zhongchui_active_arrow", FixedMetadataValue(plugin, true))
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§a§l武器技【重锤】已附着于箭矢！"))
            return true

        } else if (projectile is LivingEntity) {
            // 2. 下蹲近战：Listener 传入的是受击怪物。
            // 检查是不是合法怪物（不符合条件返回 false 不进入冷却）
            if (!projectile.scoreboardTags.contains("panling") && !projectile.scoreboardTags.contains("monster")) {
                return false
            }
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§a§l武器技【重锤】发动！"))

            // 直接对该怪物执行击退/标记/壁咚逻辑
            applyActiveEffect(player, projectile, data)
            return true
        }
        return false
    }

    // === 核心主动逻辑 (击退+标记+壁咚) ===
    private fun applyActiveEffect(attacker: Player, victim: LivingEntity, pData: PlayerData?) {
        if (pData == null) return

        // 1. 施加标记 (持续 8 秒 = 8000L) - 【修改点】
        markedTargets[victim.uniqueId] = System.currentTimeMillis() + 8000L

        // 附带轻微减速
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 3))

        // 2. 计算击退向量 (抹平Y轴实现纯水平击退)
        val knockbackDir = victim.location.toVector().subtract(attacker.location.toVector()).setY(0.0)

        // 归一化并赋予力度 (1.8 的力度约等于 3 格，必须给 0.1 Y轴以克服地面摩擦力)
        val kbVelocity = knockbackDir.normalize().multiply(1.8).setY(0.1)
        victim.velocity = kbVelocity
        victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.8f)

        // 3. 壁咚判定 (检测击退方向 3 格内是否有方块)
        val rayTraceResult = victim.world.rayTraceBlocks(
            victim.location.add(0.0, 1.0, 0.0), // 从胸口高度发射射线
            knockbackDir,
            3.0, // 距离修正为3格
            FluidCollisionMode.NEVER,
            true
        )

        if (rayTraceResult != null && rayTraceResult.hitBlock != null) {
            // 触发壁咚！造成 300% 伤害
            val wallbangDamage = pData.archerDamage * 3.0 // 【修改点】300%

            // 稍微延迟 2 ticks 造成壁咚伤害，视觉上更像被“撞到墙上”后才受伤
            object : BukkitRunnable() {
                override fun run() {
                    if (victim.isValid && !victim.isDead) {
                        applyExtraDamage(victim, attacker, wallbangDamage)

                        // 【修改点】晕眩 0.5 秒 (10 ticks)
                        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 10, 255))
                        victim.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 10, 1))

                        // 碎石特效
                        victim.world.spawnParticle(Particle.BLOCK, victim.location.add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, rayTraceResult.hitBlock!!.blockData)
                        victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f)
                    }
                }
            }.runTaskLater(plugin, 2L)
        }
    }

    // === 伤害监听 (处理主动箭矢命中 & 被动额外伤害) ===
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (!victim.scoreboardTags.contains("panling") && !victim.scoreboardTags.contains("monster")) return

        // 防死循环检测
        if (victim.hasMetadata("hjh_zhongchui_extradamage")) return

        // 【严格判定】只有"箭矢"命中才能触发被动/主动射击逻辑
        val arrow = event.damager as? AbstractArrow ?: return
        val attacker = arrow.shooter as? Player ?: return

        val pluginMain = plugin
        val pData = pluginMain.playerManager.getData(attacker.uniqueId) ?: return
        val now = System.currentTimeMillis()

        // 1. 如果命中怪物的箭矢是刚才下蹲射出的"主动技能箭矢"
        if (arrow.hasMetadata("hjh_zhongchui_active_arrow")) {
            applyActiveEffect(attacker, victim, pData)
            return // 主动触发打出标记后，这次攻击不再触发额外被动伤害
        }

        // 2. 被动逻辑：普通箭矢命中带有[重锤]标记的怪物
        val expireTime = markedTargets[victim.uniqueId]
        if (expireTime != null && now <= expireTime) {
            // 消耗标记
//            markedTargets.remove(victim.uniqueId)

            // 【被动效果】额外造成 150% 箭矢强度的伤害
            val extraPassiveDamage = pData.archerDamage * 1.5

            if (extraPassiveDamage > 0) {
                applyExtraDamage(victim, attacker, extraPassiveDamage)
                victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5f, 1.5f)
                victim.world.spawnParticle(Particle.CRIT, victim.location.add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.1)
            }
        }
    }

    private fun applyExtraDamage(victim: LivingEntity, attacker: Player, amount: Double) {
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        victim.setMetadata("hjh_zhongchui_extradamage", FixedMetadataValue(plugin, true))
        victim.noDamageTicks = 0
        try {
            victim.damage(amount, attacker)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (victim.hasMetadata("hjh_physical_skill")) victim.removeMetadata("hjh_physical_skill", plugin)
            if (victim.hasMetadata("hjh_zhongchui_extradamage")) victim.removeMetadata("hjh_zhongchui_extradamage", plugin)
            victim.noDamageTicks = 0
        }
    }
}
