package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
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

class tengmugongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        // 弓箭手技能必须有投射物 (projectile)
        if (player == null || config == null || projectile == null) return false

        // 确保投射物是箭矢 (AbstractArrow 包含普通箭、光灵箭等)
        if (projectile is AbstractArrow) {

            // 1. 给射出的这支箭打上“檀香”标记
            projectile.setMetadata("hjh_tanxiang_arrow", FixedMetadataValue(plugin, true))

            // 2. 射出时的特效与音效 (原木/自然风格)
            player.world.playSound(player.location, Sound.BLOCK_CHORUS_FLOWER_GROW, 1f, 1.5f)
            player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.0)

            // 3. 提示
            val message = config.getString("message", "&a&l武器技【檀香】发动！")
            if (!message.isNullOrEmpty()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
            }
            return true
        }

        return false
    }

    // 监听实体受伤事件 (当箭命中怪物时)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager

        // 1. 确认伤害来源是一支箭，并且带有檀香标记
        if (arrow !is AbstractArrow) return
        if (!arrow.hasMetadata("hjh_tanxiang_arrow")) return

        val victim = event.entity as? LivingEntity ?: return

        // 2. 标签检测 (panling 和 monster)
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        // 3. 减速效果：持续 5秒 (100 ticks)，缓慢 II (降低30%移速)
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 1))

        // 4. 亡灵生物判定 (使用 1.21+ 官方 Tag 判定是否属于亡灵/受亡灵杀手克制)
        val isUndead = org.bukkit.Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(victim.type)

        if (isUndead) {
            // 给与极高等级的缓慢和失明来模拟 0.6 秒的晕眩
            victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 12, 255))
            victim.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 12, 1))

            // 亡灵被净化/晕眩的特效
            victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 1.5f)
            victim.world.spawnParticle(Particle.WITCH, victim.location.add(0.0, 1.0, 0.0), 15, 0.3, 0.3, 0.3, 0.0)
        } else {
            // 普通怪物命中的木系/毒系特效 (已修复 SLIME -> ITEM_SLIME)
            victim.world.playSound(victim.location, Sound.BLOCK_LILY_PAD_PLACE, 1f, 1.0f)
            victim.world.spawnParticle(Particle.ITEM_SLIME, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.0)
        }
    }
}