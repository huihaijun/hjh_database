package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class kunlunfeixianjianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录 Buff 过期时间: PlayerUUID -> 过期时间戳
    private val activeBuffs = ConcurrentHashMap<UUID, Long>()

    // 记录剑灵冲锋的内置冷却 (ICD): PlayerUUID -> 下次可释放时间戳
    private val chargeCooldowns = ConcurrentHashMap<UUID, Long>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 视觉特效任务：持续期间剑身缠绕粒子
        object : BukkitRunnable() {
            override fun run() {
                if (activeBuffs.isEmpty()) return
                val now = System.currentTimeMillis()
                val iter = activeBuffs.iterator()

                while (iter.hasNext()) {
                    val entry = iter.next()
                    val uuid = entry.key
                    val expireTime = entry.value

                    if (now > expireTime) {
                        iter.remove()
                        continue
                    }

                    val player = Bukkit.getPlayer(uuid) ?: continue
                    if (!player.isOnline) continue

                    // 简单的环绕特效 (剑灵庇佑)
                    spawnOrbitParticle(player)
                }
            }
        }.runTaskTimer(plugin, 2L, 2L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        // 1. 设置持续时间 10秒
        val duration = 10.0
        activeBuffs[player.uniqueId] = System.currentTimeMillis() + (duration * 1000).toLong()

        // 2. 提示与音效
        val message = config.getString("message", "&b&l武器技【剑灵庇佑】发动！")
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
        }
        player.world.playSound(player.location, Sound.ITEM_TOTEM_USE, 1f, 1.5f)

        // 爆发特效
        player.world.spawnParticle(Particle.CLOUD, player.location.add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.1)

        return true
    }

    // ★★★ 核心：监听挥剑动作 ★★★
    @EventHandler
    fun onSwing(event: PlayerAnimationEvent) {
        // 只监听手臂挥动 (左键/攻击动作)
        if (event.animationType != PlayerAnimationType.ARM_SWING) return

        val player = event.player
        val uuid = player.uniqueId

        // 1. 检查是否在 Buff 期间
        val expireTime = activeBuffs[uuid] ?: return
        if (System.currentTimeMillis() > expireTime) {
            activeBuffs.remove(uuid)
            return
        }

        // 2. 检查内置 CD (0.8秒)
        val nextCast = chargeCooldowns.getOrDefault(uuid, 0L)
        if (System.currentTimeMillis() < nextCast) return

        // 设置下次可用时间
        chargeCooldowns[uuid] = System.currentTimeMillis() + 500L // 0.5s

        // 3. 执行剑灵冲锋
        triggerSwordSpirit(player)
    }

    private fun triggerSwordSpirit(player: Player) {
        val startLoc = player.eyeLocation.subtract(0.0, 0.3, 0.0) // 视线稍微往下一点
        val direction = startLoc.direction.normalize()

        // 冲锋距离 6格
        val maxDistance = 6.0
        // 步长 0.5格 (步长越小检测越精准)
        val step = 0.5

        val enemiesHit = HashSet<UUID>() // 记录已命中的怪物，防止同一次冲锋重复判定
        val playersHealed = HashSet<UUID>() // 记录已治疗的玩家

        val pluginMain = plugin as com.hjh_database.Hjh_database
        val playerData = pluginMain.playerManager.getData(player.uniqueId)
        val damage = if (playerData != null) playerData.maxHealth * 0.6 else 12.0 // 60% 最大生命

        // 播放发射音效
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 2.0f)

        // 步进检测循环
        var currentDist = 0.0
        while (currentDist <= maxDistance) {
            val checkLoc = startLoc.clone().add(direction.clone().multiply(currentDist))

            // A. 粒子特效 (剑灵轨迹)
            player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, checkLoc, 1, 0.0, 0.0, 0.0, 0.0)
            player.world.spawnParticle(Particle.CRIT, checkLoc, 2, 0.2, 0.2, 0.2, 0.0)

            // B. 范围检测 (宽/高判定)
            // 在当前点检测周围 1.2 格内的实体 (形成一个粗圆柱体判定)
            val nearbyEntities = checkLoc.world.getNearbyEntities(checkLoc, 1.2, 1.2, 1.2)

            for (target in nearbyEntities) {
                if (target !is LivingEntity) continue

                // --- 敌对目标逻辑 ---
                if (target != player && enemiesHit.size < 3) { // 至多 3 只
                    if (target.scoreboardTags.contains("panling") && target.scoreboardTags.contains("monster")) {
                        if (!enemiesHit.contains(target.uniqueId)) {
                            // 命中！
                            enemiesHit.add(target.uniqueId)
                            applySpiritDamage(target, player, damage)
                            // 命中特效
                            target.world.spawnParticle(Particle.FLASH, target.location.add(0.0, 1.0, 0.0), 1)
                            target.world.playSound(target.location, Sound.ENTITY_PHANTOM_BITE, 1f, 1.5f)
                        }
                    }
                }

                // --- 友方玩家治疗逻辑 ---
                if (target is Player) {
                    if (!playersHealed.contains(target.uniqueId)) {
                        playersHealed.add(target.uniqueId)
                        // 恢复 2 颗心 = 4 点血
                        val maxHp = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                        val newHp = min(target.health + 4.0, maxHp)
                        target.health = newHp
                        // 添加伤害吸收 II
                        target.addPotionEffect(org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.ABSORPTION,
                            200,
                            1 // 0是I级, 1是II级
                        ))

                        target.world.spawnParticle(Particle.HEART, target.location.add(0.0, 2.0, 0.0), 3)
                        target.world.playSound(target.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.0f)
                    }
                }
            }

            currentDist += step
        }
    }

    private fun applySpiritDamage(victim: LivingEntity, attacker: Player, damage: Double) {
        // 使用物理技能标记：穿透武器判定，但计算护甲
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))

        // ★★★ 必须清除无敌帧，否则高频攻击或平A接技能会被吞 ★★★
        victim.noDamageTicks = 0

        try {
            victim.damage(damage, attacker)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (victim.hasMetadata("hjh_physical_skill")) {
                victim.removeMetadata("hjh_physical_skill", plugin)
            }
            // 造成伤害后再次清除产生的红身无敌，保证玩家后续平A能打中
            victim.noDamageTicks = 0
        }
    }

    // 简单的旋转粒子特效
    private fun spawnOrbitParticle(player: Player) {
        val loc = player.location.add(0.0, 1.0, 0.0)
        val time = System.currentTimeMillis() / 1000.0
        val radius = 0.8

        val x = radius * cos(time * 5) // 速度 5
        val z = radius * sin(time * 5)

        loc.add(x, 0.0, z)
        player.world.spawnParticle(Particle.SOUL, loc, 0, 0.0, 0.1, 0.0) // 幽灵粒子
    }

    override fun deactivate(player: Player) {
        activeBuffs.remove(player.uniqueId)
        chargeCooldowns.remove(player.uniqueId)
    }
}
