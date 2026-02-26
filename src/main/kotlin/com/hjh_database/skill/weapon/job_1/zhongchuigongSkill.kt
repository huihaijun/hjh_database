package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
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
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class zhongchuigongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录谁开启了主动技能（等待下一次攻击触发），UUID -> 技能等待的过期时间
    private val pendingActiveHits = ConcurrentHashMap<UUID, Long>()

    // 记录怪物的[重锤]标记，怪物UUID -> 标记过期时间戳
    private val markedTargets = ConcurrentHashMap<UUID, Long>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 视觉特效任务：被标记的怪物身上会有沉重的粒子特效
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
                        // 灰黑色的下沉粒子，代表重锤的减速与沉重感
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

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null) return false

        // 记录主动技能已开启，等待下一次攻击触发（给予10秒的等待时间打出这一击）
        pendingActiveHits[player.uniqueId] = System.currentTimeMillis() + 10000L

        val message = "&a&l武器技【重锤激荡】发动！下次攻击将附加击退与标记！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))

        // 沉闷的蓄力音效
        player.world.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 0.5f)
        player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 15, 0.5, 0.5, 0.5, 0.0)

        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        // 防止我们自己造成的额外伤害死循环触发
        if (victim.hasMetadata("hjh_zhongchui_extradamage")) return

        var attacker: Player? = null
        var isRanged = false

        // 判断攻击来源（近战 or 远程）
        if (event.damager is Player) {
            attacker = event.damager as Player
        } else if (event.damager is AbstractArrow) {
            val arrow = event.damager as AbstractArrow
            if (arrow.shooter is Player) {
                attacker = arrow.shooter as Player
                isRanged = true
            }
        }

        if (attacker == null) return

        val now = System.currentTimeMillis()
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(attacker.uniqueId) ?: return

        // ==========================================
        // 1. 被动逻辑：对有标记的怪物额外造成 100% 箭矢强度的伤害
        // ==========================================
        val expireTime = markedTargets[victim.uniqueId]
        if (expireTime != null && now <= expireTime) {
            val extraPassiveDamage = pData.archerDamage * 1.0

            if (extraPassiveDamage > 0) {
                applyExtraDamage(victim, attacker, extraPassiveDamage)
                victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5f, 1.5f)
            }
        }

        // ==========================================
        // 2. 主动逻辑：消耗 Buff，施加击退、标记、壁咚判定
        // ==========================================
        val activeExpireTime = pendingActiveHits[attacker.uniqueId]
        if (activeExpireTime != null && now <= activeExpireTime) {
            // 消耗掉这一发主动 Buff
            pendingActiveHits.remove(attacker.uniqueId)

            // A. 施加标记 (持续 5 秒)
            markedTargets[victim.uniqueId] = now + 5000L

            // B. 减速 50% (Slowness Amplifier 3 大概是 -60% 移速，非常接近)
            victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 3))

            // C. 计算水平击退向量
            val attackerLoc = if (isRanged) attacker.location else attacker.eyeLocation
            val knockbackDir = victim.location.toVector().subtract(attackerLoc.toVector())
            knockbackDir.setY(0.0) // 强制抹平 Y 轴，实现纯水平

            // 归一化并赋予力度 (1.8 的水平力度大概能滑行 3 格左右)
            // 必须给一点点 Y 轴 (0.1)，否则实体会因为与地面的巨大摩擦力而原地停下
            val kbVelocity = knockbackDir.normalize().multiply(1.8).setY(0.1)
            victim.velocity = kbVelocity

            victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.8f)

            // D. 壁咚判定 (检测击退方向是否有方块)
            // 从怪物胸口高度发射一条长度为 2.5 格的射线，忽略草丛等可穿透方块
            val rayTraceResult = victim.world.rayTraceBlocks(
                victim.location.add(0.0, 1.0, 0.0),
                knockbackDir,
                2.5,
                FluidCollisionMode.NEVER,
                true
            )

            if (rayTraceResult != null && rayTraceResult.hitBlock != null) {
                // 触发壁咚！
                val wallbangDamage = pData.archerDamage * 2.0

                // 稍微延迟 2 ticks 造成壁咚伤害，视觉上更像被“撞到墙上”后才受伤
                object : BukkitRunnable() {
                    override fun run() {
                        if (victim.isValid && !victim.isDead) {
                            // 造成 200% 箭矢强度的额外伤害
                            applyExtraDamage(victim, attacker, wallbangDamage)

                            // 晕眩 1 秒 (极高等级缓慢 + 失明)
                            victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 255))
                            victim.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20, 1))

                            // 碎石特效与重击音效
                            victim.world.spawnParticle(Particle.BLOCK, victim.location.add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, rayTraceResult.hitBlock!!.blockData)
                            victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f)
                        }
                    }
                }.runTaskLater(plugin, 2L)
            }
        }
    }

    // 独立造成物理伤害的方法，避免触发自身无限循环
    private fun applyExtraDamage(victim: LivingEntity, attacker: Player, amount: Double) {
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        victim.setMetadata("hjh_zhongchui_extradamage", FixedMetadataValue(plugin, true))
        victim.noDamageTicks = 0
        try {
            victim.damage(amount, attacker)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (victim.hasMetadata("hjh_physical_skill")) {
                victim.removeMetadata("hjh_physical_skill", plugin)
            }
            if (victim.hasMetadata("hjh_zhongchui_extradamage")) {
                victim.removeMetadata("hjh_zhongchui_extradamage", plugin)
            }
            victim.noDamageTicks = 0
        }
    }
}