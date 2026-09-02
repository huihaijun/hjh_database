package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.listener.CombatListener
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class beidoumieshengongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录玩家当前持有的【星】数量 (UUID -> 星数)
    private val activeStars = ConcurrentHashMap<UUID, Int>()

    // 记录存星的 tick 进度
    private val starTicks = ConcurrentHashMap<UUID, Int>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 被动任务：每 2.5 秒 (50 ticks) 获得一层星，并处理 buff
        object : BukkitRunnable() {
            override fun run() {
                val weaponKey = NamespacedKey(plugin, "weapon_id")
                val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return

                for (player in Bukkit.getOnlinePlayers()) {
                    if (!pluginMain.weaponSkillManager.isWeaponActivated(player, "beidoumieshengong")) {
                        if (activeStars.containsKey(player.uniqueId) || starTicks.containsKey(player.uniqueId)) {
                            deactivate(player)
                        }
                        continue
                    }
                    val item = player.inventory.itemInMainHand
                    if (!item.hasItemMeta()) continue

                    val weaponId = item.itemMeta?.persistentDataContainer?.get(weaponKey, PersistentDataType.STRING)

                    if (weaponId == "beidoumieshengong") {
                        val uuid = player.uniqueId
                        var ticks = starTicks.getOrDefault(uuid, 0)
                        ticks += 5 // 本任务每 5 ticks 执行一次

                        if (ticks >= STAR_GAIN_TICKS) {
                            val stars = activeStars.getOrDefault(uuid, 0)
                            if (stars < 7) {
                                val newStars = stars + 1
                                activeStars[uuid] = newStars
                                updateStarBuff(player, newStars)

                                // 严格按照要求的唯一提示
                                val msg = "&a&l获得一层[星]，当前持有${newStars}/7层"
                                player.spigot().sendMessage(
                                    ChatMessageType.ACTION_BAR,
                                    TextComponent(ChatColor.translateAlternateColorCodes('&', msg))
                                )
                            }
                            starTicks[uuid] = 0
                        } else {
                            starTicks[uuid] = ticks
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    // 独立管理星带来的箭矢强度 Buff
    private fun updateStarBuff(player: Player, stars: Int) {
        val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        if (stars > 0) {
            // 每层星提升 7% 箭矢强度，使用独立键避免与其他属性效果互相覆盖。
            pData.tempBonuses[STAR_DAMAGE_KEY] = stars * STAR_DAMAGE_BONUS_PER_STACK
        } else {
            pData.tempBonuses.remove(STAR_DAMAGE_KEY)
        }

        pluginMain.playerManager.updateStats(player)
    }

    // === 主动技能：七星灭 ===
    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || projectile !is AbstractArrow || data == null) return false
        val uuid = player.uniqueId
        val stars = activeStars.getOrDefault(uuid, 0)
        // 严格按照要求的发动提示
        val msg = "&a&l武器技【七星灭】发动！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', msg)))
        if (stars > 0) {
            // 先锁定【星】仍存在时的箭矢强度，供本次由【星】转化出的全部技能箭矢使用。
            // 随后可安全清层并刷新玩家属性，不会让技能箭错误读取到清层后的数值。
            val baseDamage = data.archerDamage
            // 清空星与对应的 Buff
            activeStars.remove(uuid)
            starTicks[uuid] = 0
            updateStarBuff(player, 0)
            // 连发机制：每 2 tick 射出一支虚拟星辰
            object : BukkitRunnable() {
                var firedCount = 0
                override fun run() {
                    if (firedCount >= stars || !player.isOnline) {
                        cancel()
                        return
                    }
                    // 第1支75%，随后每支递增50%，第7支达到375%。
                    val multiplier = (ACTIVE_INITIAL_MULTIPLIER +
                        firedCount * ACTIVE_INCREMENT_MULTIPLIER).coerceAtMost(ACTIVE_MAX_MULTIPLIER)
                    // 七支依次穿透25%/40%/55%/70%/85%/100%/100%护甲。
                    val armorPenetration = (ACTIVE_INITIAL_ARMOR_PENETRATION +
                        firedCount * ACTIVE_ARMOR_PENETRATION_INCREMENT)
                        .coerceAtMost(1.0)
                    val finalDamage = baseDamage * multiplier
                    launchVirtualStar(player, finalDamage, armorPenetration)
                    firedCount++
                }
            }.runTaskTimer(plugin, 0L, 2L)
        }
        return true
    }

    // === 虚拟投射物与逐支递增的部分穿甲伤害 ===
    private fun launchVirtualStar(shooter: Player, damage: Double, armorPenetration: Double) {
        val startLoc = shooter.eyeLocation
        val direction = startLoc.direction.normalize().multiply(1.5) // 每tick飞行 1.5格

        object : BukkitRunnable() {
            var ticks = 0
            val currentLoc = startLoc.clone()

            override fun run() {
                ticks++
                if (ticks > 40) { // 最远飞行 40 ticks
                    cancel()
                    return
                }

                currentLoc.add(direction)

                // 简单的星光粒子，不挡视野
                currentLoc.world?.spawnParticle(Particle.FIREWORK, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)

                // 撞墙检测
                if (currentLoc.block.type.isSolid) {
                    cancel()
                    return
                }

                // 命中判定
                val hitEntity = currentLoc.world?.getNearbyEntities(currentLoc, 0.8, 0.8, 0.8)
                    ?.firstOrNull {
                        it is LivingEntity &&
                                it != shooter &&
                                it.scoreboardTags.contains("panling") &&
                                it.scoreboardTags.contains("monster")
                    } as? LivingEntity

                if (hitEntity != null) {
                    // 物理技能保留自定义伤害，并通过统一护甲公式按本支箭的穿甲率结算。
                    hitEntity.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                    hitEntity.setMetadata(
                        CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA,
                        FixedMetadataValue(plugin, armorPenetration)
                    )

                    // 清除无敌帧，保证连发的每一段伤害都能真实判定
                    hitEntity.noDamageTicks = 0

                    try {
                        hitEntity.damage(damage, shooter)
                    } finally {
                        if (hitEntity.hasMetadata("hjh_physical_skill")) {
                            hitEntity.removeMetadata("hjh_physical_skill", plugin)
                        }
                        if (hitEntity.hasMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA)) {
                            hitEntity.removeMetadata(CombatListener.PHYSICAL_ARMOR_PENETRATION_METADATA, plugin)
                        }
                        hitEntity.noDamageTicks = 0
                    }

                    // 命中特效
                    currentLoc.world?.spawnParticle(Particle.CRIT, currentLoc, 5, 0.2, 0.2, 0.2, 0.1)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // === 武器失活处理 ===
    override fun deactivate(player: Player) {
        val uuid = player.uniqueId
        // 无条件清理，连同异常情况下可能残留的属性键一起移除。
        activeStars.remove(uuid)
        starTicks.remove(uuid)
        updateStarBuff(player, 0)
    }

    companion object {
        private const val STAR_DAMAGE_KEY = "beidoumieshengong::archer_damage_percent"
        private const val STAR_GAIN_TICKS = 50
        private const val STAR_DAMAGE_BONUS_PER_STACK = 0.07
        private const val ACTIVE_INITIAL_MULTIPLIER = 0.75
        private const val ACTIVE_INCREMENT_MULTIPLIER = 0.50
        private const val ACTIVE_MAX_MULTIPLIER = 3.75
        private const val ACTIVE_INITIAL_ARMOR_PENETRATION = 0.25
        private const val ACTIVE_ARMOR_PENETRATION_INCREMENT = 0.15
    }
}
