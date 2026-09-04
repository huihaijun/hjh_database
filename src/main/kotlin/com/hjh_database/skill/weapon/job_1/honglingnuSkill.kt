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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class honglingnuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || projectile !is AbstractArrow) return false

        // 给射出的箭矢打上“炎海”标记
        projectile.setMetadata("hjh_yanhai_arrow", FixedMetadataValue(plugin, true))

        // 按要求：只保留这一句提示
        val message = "&a&l武器技【炎海】发动！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))

        // 射出时的音效
        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1f, 1.2f)

        return true
    }

    // 监听投射物命中事件 (命中方块或实体都会触发)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity
        if (arrow !is AbstractArrow) return
        if (!arrow.hasMetadata("hjh_yanhai_arrow")) return

        // 用完即删，防止箭矢由于穿透附魔等原因触发多次
        arrow.removeMetadata("hjh_yanhai_arrow", plugin)

        val shooter = arrow.shooter as? Player ?: return

        // 确定火海的中心点 (如果射中实体则在实体脚下，射中方块则在方块上方)
        val hitLoc = event.hitEntity?.location ?: event.hitBlock?.location?.add(0.5, 1.0, 0.5) ?: arrow.location

        // 爆发音效
        hitLoc.world?.playSound(hitLoc, Sound.ITEM_FIRECHARGE_USE, 1f, 1f)

        // 启动火海异步任务 (独立计时器，完美支持多人叠加重合)
        object : BukkitRunnable() {
            var count = 0
            val maxCount = 10 // 5秒持续时间，每0.5秒(10 ticks)执行一次，共10次

            override fun run() {
                // 如果次数用完，或者玩家离线，则结束火海
                if (count >= maxCount || !shooter.isOnline) {
                    this.cancel()
                    return
                }

                // 1. 渲染粒子特效 (对 TPS 非常友好)
                // 边长4格的火海 -> 半径为2。使用 Spigot 原生的粒子分布参数
                hitLoc.world?.spawnParticle(Particle.FLAME, hitLoc, 40, 1.5, 0.2, 1.5, 0.02)
                hitLoc.world?.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, hitLoc, 10, 1.5, 0.5, 1.5, 0.01)

                // 2. 获取玩家实时箭矢强度
                val pluginMain = plugin as com.hjh_database.Hjh_database
                val pData = pluginMain.playerManager.getData(shooter.uniqueId) ?: return
                val damageAmount = pData.archerDamage * 0.8 // 80% 箭矢强度

                // 3. 范围伤害判定 (2.0 的半径正好是 4x4 的区域)
                val nearbyEntities = hitLoc.world?.getNearbyEntities(hitLoc, 2.0, 2.0, 2.0) ?: emptyList()

                for (target in nearbyEntities) {
                    if (target is LivingEntity && target != shooter) {
                        if (target.scoreboardTags.contains("panling") && target.scoreboardTags.contains("monster")) {

                            // 打上物理技能标记，跳过武器检查，并能正确触发 CombatListener 的护甲计算
                            target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))

                            // ★★★ 取消无敌帧前置 ★★★
                            target.noDamageTicks = 0

                            // 炎海是固定区域持续伤害，不应沿用带攻击者伤害入口产生的原版击退。
                            // 记录怪物原有速度并在结算后还原，既保留事件链/护甲结算，也不会把怪物弹出火海。
                            val velocityBeforeDamage = target.velocity.clone()
                            try {
                                target.damage(damageAmount, shooter)
                            } finally {
                                if (target.hasMetadata("hjh_physical_skill")) {
                                    target.removeMetadata("hjh_physical_skill", plugin)
                                }
                                // ★★★ 取消无敌帧后置，防止火海吞掉玩家紧接着的平A伤害 ★★★
                                target.noDamageTicks = 0
                                if (target.isValid && !target.isDead) {
                                    target.velocity = velocityBeforeDamage
                                }
                            }
                        }
                    }
                }
                count++
            }
        }.runTaskTimer(plugin, 0L, 10L) // 0延迟启动，每 10 ticks (0.5秒) 执行一次
    }
}
