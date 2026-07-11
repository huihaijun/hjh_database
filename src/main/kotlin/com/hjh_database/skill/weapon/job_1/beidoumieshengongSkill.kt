package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
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

    // 第1~7颗星的独立伤害倍率
    private val damageMultipliers = arrayOf(0.5, 0.8, 1.1, 1.4, 1.7, 2.0, 2.3)

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 被动任务：每 3 秒 (60 ticks) 获得一层星，并处理 buff
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

                        if (ticks >= 60) { // 3秒到达
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
            // 每层星提升 20%
            pData.tempBonuses[STAR_DAMAGE_KEY] = stars * 0.20
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
            // 清空星与对应的 Buff
            activeStars.remove(uuid)
            starTicks[uuid] = 0
            updateStarBuff(player, 0)

            val baseDamage = data.archerDamage

            // 连发机制：每 2 tick 射出一支虚拟星辰
            object : BukkitRunnable() {
                var firedCount = 0
                override fun run() {
                    if (firedCount >= stars || !player.isOnline) {
                        cancel()
                        return
                    }

                    // 获取当前这颗星对应的伤害倍率 (0.5 到 3.5)
                    val multiplier = damageMultipliers[firedCount]
                    val finalDamage = baseDamage * multiplier

                    launchVirtualStar(player, finalDamage)

                    firedCount++
                }
            }.runTaskTimer(plugin, 0L, 2L)
        }

        return true
    }

    // === 虚拟投射物与穿甲伤害 ===
    private fun launchVirtualStar(shooter: Player, damage: Double) {
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
                    // ★★★ 核心修复：同时打上 穿甲标签 和 物理技能标签 ★★★
                    hitEntity.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
                    hitEntity.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))

                    // 清除无敌帧，保证连发的每一段伤害都能真实判定
                    hitEntity.noDamageTicks = 0

                    try {
                        hitEntity.damage(damage, shooter)
                    } finally {
                        // 结算后移除这两个标签
                        if (hitEntity.hasMetadata("hjh_magic_damage")) {
                            hitEntity.removeMetadata("hjh_magic_damage", plugin)
                        }
                        if (hitEntity.hasMetadata("hjh_physical_skill")) {
                            hitEntity.removeMetadata("hjh_physical_skill", plugin)
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
    }
}
