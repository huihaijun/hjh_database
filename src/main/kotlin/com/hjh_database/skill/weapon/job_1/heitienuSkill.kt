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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class heitienuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录玩家 Buff 的过期时间 (UUID -> 过期时间戳)
    private val activeBuffs = ConcurrentHashMap<UUID, Long>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 全局任务：监控 Buff 是否过期
        object : BukkitRunnable() {
            override fun run() {
                if (activeBuffs.isEmpty()) return
                val now = System.currentTimeMillis()
                val iter = activeBuffs.iterator()

                while (iter.hasNext()) {
                    val entry = iter.next()
                    val uuid = entry.key
                    val expireTime = entry.value

                    // 如果 Buff 过期了
                    if (now > expireTime) {
                        iter.remove()
                        val player = Bukkit.getPlayer(uuid)
                        if (player != null && player.isOnline) {
                            removeBuff(player)
                        }
                    } else {
                        // 还在 Buff 期间，给点脚底生风的粒子特效提示玩家
                        val player = Bukkit.getPlayer(uuid)
                        if (player != null && player.isOnline && Math.random() < 0.3) {
                            player.world.spawnParticle(Particle.CLOUD, player.location.add(0.0, 0.2, 0.0), 1, 0.1, 0.0, 0.1, 0.05)
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null || projectile == null) return false

        // 确保发射的是箭矢
        if (projectile is AbstractArrow) {

            // 1. 打上“游击”标记
            projectile.setMetadata("hjh_youji_arrow", FixedMetadataValue(plugin, true))

            // 2. 发射特效 (黑铁冷兵器风格)
            player.world.playSound(player.location, Sound.ITEM_CROSSBOW_SHOOT, 1f, 0.8f)
            player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 10, 0.2, 0.2, 0.2, 0.0)

            // 3. 提示
            val message = config.getString("message", "&a&l武器技【游击】发动！")
            if (!message.isNullOrEmpty()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
            }
            return true
        }

        return false
    }

    // 监听命中事件
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager
        if (arrow !is AbstractArrow) return
        if (!arrow.hasMetadata("hjh_youji_arrow")) return

        val victim = event.entity as? LivingEntity ?: return
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        val shooter = arrow.shooter as? Player ?: return

        // === 1. 击退效果 ===
        // 根据箭的飞行方向，生成一个向后的冲力。1.5的强度大概能击退2-3格。
        val kbVector = arrow.velocity.clone().normalize().multiply(1.5).setY(0.2)
        victim.velocity = kbVector

        // 击退音效与特效
        victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 1.2f)

        // === 2. 刷新玩家自身 Buff ===
        applyBuff(shooter)
    }

    // 施加/刷新 Buff
    private fun applyBuff(player: Player) {
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        // 持续时间 5 秒 (5000 毫秒)
        val isNewBuff = !activeBuffs.containsKey(player.uniqueId)
        activeBuffs[player.uniqueId] = System.currentTimeMillis() + 5000L

        // 写入临时属性 Map (固定提升 3 点箭矢强度)
        pData.tempBonuses["archer_damage"] = 3.0

        // 刷新属性
        pluginMain.playerManager.updateStats(player)

        // 如果是新获得 Buff，给个音效提示
        if (isNewBuff) {
            player.playSound(player.location, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1f, 1.5f)
        }
    }

    // 移除 Buff
    private fun removeBuff(player: Player) {
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        // 移除临时属性
        pData.tempBonuses.remove("archer_damage")

        // 刷新属性
        pluginMain.playerManager.updateStats(player)

        // 提示消失
        player.playSound(player.location, Sound.BLOCK_CHAIN_BREAK, 1f, 1.5f)
    }

    override fun deactivate(player: Player) {
        if (activeBuffs.remove(player.uniqueId) != null) removeBuff(player)
    }
}
